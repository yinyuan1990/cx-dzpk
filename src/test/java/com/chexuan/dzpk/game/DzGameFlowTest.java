package com.chexuan.dzpk.game;

import com.chexuan.dzpk.game.model.DzPlayer;
import com.chexuan.dzpk.game.model.DzRoom;
import com.chexuan.dzpk.game.model.GameStage;
import com.chexuan.dzpk.game.service.*;
import com.chexuan.dzpk.ws.GameMessage;
import com.chexuan.dzpk.ws.MsgType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 引擎集成测试 — 不起 WS,直接驱动 DzGameService 打完整牌局。
 * 引擎内部按房间串行异步执行,测试用轮询等待收敛。
 */
class DzGameFlowTest {

    static final long U1 = 1001, U2 = 1002, U3 = 1003;
    static final long INIT_BALANCE = 1_000_000L;

    DzRoomManager roomManager;
    RoomWorkerService roomWorker;
    WalletService wallet;
    RecordingBroadcaster bc;
    DzGameService game;

    @BeforeEach
    void setUp() {
        roomManager = new DzRoomManager();
        roomWorker = new RoomWorkerService();
        wallet = new WalletService();
        ReflectionTestUtils.setField(wallet, "initBalance", INIT_BALANCE);
        bc = new RecordingBroadcaster();
        game = new DzGameService(roomManager, roomWorker, wallet, bc);
        ReflectionTestUtils.setField(game, "actionTimeoutSecs", 15);
        ReflectionTestUtils.setField(game, "nextHandDelaySecs", 60); // 隔离:测试内不自动开下一手
        ReflectionTestUtils.setField(game, "awaitBuyinSecs", 60);
    }

    @AfterEach
    void tearDown() {
        roomWorker.shutdown();
    }

    // ================================================================

    @Test
    void 三人打到摊牌_筹码守恒_盈亏归零() {
        DzRoom room = createRoomAndSeat3(30);
        // 手动触发开局(绕过 nextHandDelay)
        startHandNow(room);
        waitUntil(() -> room.getStage() == GameStage.PREFLOP && room.getActingSeat() != -1, 3000);

        // 所有人跟注/过牌打到河牌摊牌
        playHandAllCall(room);
        waitUntil(() -> room.getStage() == GameStage.FINISHED || room.getStage() == GameStage.WAITING, 8000);

        // 摊牌结算广播
        GameMessage settle = bc.last(MsgType.SETTLE);
        assertNotNull(settle, "应有 SETTLE 广播");
        // 筹码守恒:三家桌面筹码总和不变(手内无抽水)
        long total = stackSum(room);
        assertEquals(30000, total, "桌面筹码总和应守恒");
        // 公共牌 5 张
        assertEquals(5, room.getBoard().size());
        // 每人手数+1
        for (long uid : new long[]{U1, U2, U3}) {
            assertEquals(1, room.playerByUserId(uid).getHandCount());
        }
    }

    @Test
    void 两人弃牌_剩者直接赢池() {
        DzRoom room = createRoomAndSeat3(30);
        startHandNow(room);
        waitUntil(() -> room.getStage() == GameStage.PREFLOP && room.getActingSeat() != -1, 3000);

        // 前两个行动的人弃牌 → 第三家直接赢
        for (int i = 0; i < 2; i++) {
            DzPlayer acting = actingPlayer(room);
            long uid = acting.getUserId();
            game.action(room.getRoomId(), uid, "fold", 0);
            int finalI = i;
            waitUntil(() -> room.getStage() != GameStage.PREFLOP
                    || actingPlayer(room) == null
                    || actingPlayer(room).getUserId() != uid, 3000);
            if (room.getStage() == GameStage.FINISHED || room.getStage() == GameStage.WAITING) break;
        }
        waitUntil(() -> room.getStage() == GameStage.FINISHED || room.getStage() == GameStage.WAITING, 5000);

        GameMessage settle = bc.last(MsgType.SETTLE);
        assertNotNull(settle);
        assertEquals(30000, stackSum(room), "弃牌结算后筹码守恒");
        // 赢家 = 盲注赢回来的那位,netWin 总和为 0
        long netSum = 0;
        for (long uid : new long[]{U1, U2, U3}) {
            netSum += room.playerByUserId(uid).getNetWin();
        }
        assertEquals(0, netSum);
    }

    @Test
    void 站起_盈利抽水_退回钱包() {
        DzRoom room = createRoomAndSeat3(30);
        // 不开局,直接站起(无盈利 → 不抽水,全额退回)
        game.standUp(room.getRoomId(), U1);
        waitUntil(() -> room.playerByUserId(U1) == null, 3000);
        assertEquals(INIT_BALANCE, wallet.balance(U1), "无盈利全额退回");

        // 模拟盈利后站起:直接给 U2 加 2000 桌面筹码(相当于赢来的)
        DzPlayer p2 = room.playerByUserId(U2);
        p2.setStack(p2.getStack() + 2000);
        game.standUp(room.getRoomId(), U2);
        waitUntil(() -> room.playerByUserId(U2) == null, 3000);
        // 带入 10000,退 12000-抽水(2000*5%=100) → 钱包 = 初始 - 10000 + 11900
        assertEquals(INIT_BALANCE - 10000 + 11900, wallet.balance(U2), "盈利部分按 5% 抽水");
    }

    @Test
    void 周期结算_到期抽水退筹_等补带入() {
        DzRoom room = createRoomAndSeat3(1);  // 结算时间 1 分钟
        startHandNow(room);
        waitUntil(() -> room.getStage() == GameStage.PREFLOP && room.getActingSeat() != -1, 3000);

        // 伪造 U1 已累计打满 61 秒 → 本手结束触发周期结算
        DzPlayer p1 = room.playerByUserId(U1);
        p1.setGameTimeAccumMs(61_000);

        playHandAllCall(room);
        waitUntil(() -> room.getStage() == GameStage.FINISHED || room.getStage() == GameStage.WAITING, 8000);
        waitUntil(() -> room.playerByUserId(U1) == null || room.playerByUserId(U1).isAwaitingBuyin(), 3000);

        GameMessage ps = bc.last(MsgType.PERIOD_SETTLE);
        assertNotNull(ps, "应有周期结算广播");
        DzPlayer after = room.playerByUserId(U1);
        assertNotNull(after, "周期结算不离座");
        assertTrue(after.isAwaitingBuyin(), "应进入补带入等待");
        assertEquals(0, after.getStack(), "桌面清零");
        assertEquals(0, after.getGameTimeAccumMs(), "周期计时清零");
        assertEquals(1, after.getSettlePeriodSeq(), "结算周期 seq+1");
        assertTrue(wallet.balance(U1) > INIT_BALANCE - 10000, "退筹应回到钱包");

        // 补带入 → 清除等待标记,可继续下一周期
        game.buyIn(room.getRoomId(), U1, 10000);
        waitUntil(() -> !room.playerByUserId(U1).isAwaitingBuyin(), 3000);
        assertEquals(10000, room.playerByUserId(U1).getStack());
    }

    // ================================================================
    // 工具
    // ================================================================

    private DzRoom createRoomAndSeat3(int settleTimeMins) {
        DzRoom room = roomManager.create("测试局", U1, 50, 100, 9, settleTimeMins, 5);
        long roomId = room.getRoomId();
        long[] uids = {U1, U2, U3};
        for (int i = 0; i < 3; i++) {
            game.enterRoom(roomId, uids[i], "P" + (i + 1));
            game.sitDown(roomId, uids[i], i);
            game.buyIn(roomId, uids[i], 10000);
        }
        waitUntil(() -> {
            for (long uid : uids) {
                DzPlayer p = room.playerByUserId(uid);
                if (p == null || p.getStack() != 10000) return false;
            }
            return true;
        }, 3000);
        return room;
    }

    /** 绕过 nextHandDelay 直接开局(反射调 private startHand) */
    private void startHandNow(DzRoom room) {
        roomWorker.submit(room.getRoomId(), () ->
                ReflectionTestUtils.invokeMethod(game, "startHand", room));
    }

    /** 所有人跟注/过牌直到本手结束 */
    private void playHandAllCall(DzRoom room) {
        long deadline = System.currentTimeMillis() + 10_000;
        long lastActedFingerprint = -1;
        while (System.currentTimeMillis() < deadline) {
            if (room.getStage() == GameStage.FINISHED || room.getStage() == GameStage.WAITING) return;
            DzPlayer acting = actingPlayer(room);
            if (acting == null) {
                sleep(30);
                continue;
            }
            long fingerprint = acting.getUserId() * 1000 + room.getStage().ordinal() * 10 + room.getBoard().size();
            if (fingerprint == lastActedFingerprint) {
                sleep(30);
                continue;
            }
            lastActedFingerprint = fingerprint;
            long toCall = room.getCurrentBet() - acting.getBetThisRound();
            game.action(room.getRoomId(), acting.getUserId(), toCall > 0 ? "call" : "check", 0);
            sleep(50);
        }
    }

    private DzPlayer actingPlayer(DzRoom room) {
        int seat = room.getActingSeat();
        return seat >= 0 ? room.playerAtSeat(seat) : null;
    }

    private long stackSum(DzRoom room) {
        long sum = 0;
        for (long uid : new long[]{U1, U2, U3}) {
            DzPlayer p = room.playerByUserId(uid);
            if (p != null) sum += p.getStack();
        }
        return sum;
    }

    private static void waitUntil(BooleanSupplier cond, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (cond.getAsBoolean()) return;
            sleep(20);
        }
        fail("等待超时(" + timeoutMs + "ms)");
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 记录广播的假会话层 */
    static class RecordingBroadcaster implements GameBroadcaster {
        final List<GameMessage> roomMsgs = new CopyOnWriteArrayList<>();
        final List<GameMessage> userMsgs = new CopyOnWriteArrayList<>();

        @Override
        public void toRoom(long roomId, GameMessage msg) {
            roomMsgs.add(msg);
        }

        @Override
        public void toUser(long userId, GameMessage msg) {
            userMsgs.add(msg);
        }

        GameMessage last(int type) {
            for (int i = roomMsgs.size() - 1; i >= 0; i--) {
                if (roomMsgs.get(i).getType() == type) return roomMsgs.get(i);
            }
            return null;
        }
    }
}
