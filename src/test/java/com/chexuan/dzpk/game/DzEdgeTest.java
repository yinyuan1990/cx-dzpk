package com.chexuan.dzpk.game;

import com.chexuan.dzpk.club.DzClubService;
import com.chexuan.dzpk.db.DiamondService;
import com.chexuan.dzpk.game.model.DzPlayer;
import com.chexuan.dzpk.game.model.DzRoom;
import com.chexuan.dzpk.game.model.GameStage;
import com.chexuan.dzpk.game.service.*;
import com.chexuan.dzpk.ws.GameMessage;
import com.chexuan.dzpk.ws.MsgType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 边缘功能测试(对齐扯旋):留座暂离/放假、断线 vacation、赢家离桌罚金、
 * 解散牌局、实时战绩、物理锁座、俱乐部坐下限制。
 */
class DzEdgeTest {

    static final long U1 = 1001, U2 = 1002, U3 = 1003;
    static final long INIT_BALANCE = 1_000_000L;
    private static final AtomicInteger DB_SEQ = new AtomicInteger();

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
        config(game);
    }

    private void config(DzGameService g) {
        ReflectionTestUtils.setField(g, "actionTimeoutSecs", 15);
        ReflectionTestUtils.setField(g, "nextHandDelaySecs", 60);
        ReflectionTestUtils.setField(g, "awaitBuyinSecs", 60);
        ReflectionTestUtils.setField(g, "seatReserveGraceSecs", 300);
        ReflectionTestUtils.setField(g, "seatLockSecs", 480);
        ReflectionTestUtils.setField(g, "winnerEarlyLeaveRate", 30);
        ReflectionTestUtils.setField(g, "runAwayEnabled", false);
        ReflectionTestUtils.setField(g, "runAwayTimeMins", 6);
        ReflectionTestUtils.setField(g, "runAwayPenaltyRate", 30);
        ReflectionTestUtils.setField(g, "ownerPeriodDiamondCost", 0L);
    }

    @AfterEach
    void tearDown() {
        roomWorker.shutdown();
    }

    // ================================================================
    // 留座暂离/放假
    // ================================================================

    @Test
    void 暂离_进放假_不发牌_回桌恢复() {
        DzRoom room = createRoomAndSeat3(30);
        game.seatReserveLeave(room.getRoomId(), U1);
        waitUntil(() -> room.playerByUserId(U1).inGrace(), 3000);
        DzPlayer p1 = room.playerByUserId(U1);
        assertTrue(p1.isSittingOut());
        assertTrue(p1.isSeatReserveUsed(), "本周期已用一次");
        GameMessage grace = bc.last(MsgType.SEAT_RESERVE_GRACE);
        assertNotNull(grace);
        assertEquals("ON_LEAVE", dataOf(grace).get("state"));

        // 开局:放假玩家不发牌
        startHandNow(room);
        waitUntil(() -> room.getStage() == GameStage.PREFLOP, 3000);
        assertFalse(room.playerByUserId(U1).isInHand(), "放假中不参与发牌");

        // 回桌
        game.seatReserveResume(room.getRoomId(), U1);
        waitUntil(() -> !room.playerByUserId(U1).inGrace(), 3000);
        assertFalse(room.playerByUserId(U1).isSittingOut());
    }

    @Test
    void 暂离_每周期只能一次() {
        DzRoom room = createRoomAndSeat3(30);
        game.seatReserveLeave(room.getRoomId(), U1);
        waitUntil(() -> room.playerByUserId(U1).inGrace(), 3000);
        game.seatReserveResume(room.getRoomId(), U1);
        waitUntil(() -> !room.playerByUserId(U1).inGrace(), 3000);

        // 再申请 → 拒绝
        bc.userMsgs.clear();
        game.seatReserveLeave(room.getRoomId(), U1);
        waitUntil(() -> lastUserMsg(MsgType.ERROR) != null, 3000);
        assertTrue(dataOf(lastUserMsg(MsgType.ERROR)).get("msg").toString().contains("一次"));
        assertFalse(room.playerByUserId(U1).inGrace());
    }

    @Test
    void 暂离超时_自动站起_物理锁座() {
        DzRoom room = createRoomAndSeat3(30);
        ReflectionTestUtils.setField(game, "seatReserveGraceSecs", 1);
        game.seatReserveLeave(room.getRoomId(), U1);
        waitUntil(() -> room.playerByUserId(U1) != null && room.playerByUserId(U1).inGrace(), 3000);

        // 1 秒后超时:站起 + 座位锁给本人
        waitUntil(() -> room.playerByUserId(U1) == null, 5000);
        assertEquals(U1, room.seatLockedBy(0), "座位0应物理锁给U1");
        GameMessage locked = bc.last(MsgType.SEAT_RESERVE_GRACE);
        assertEquals("SEAT_LOCKED", dataOf(locked).get("state"));
        assertEquals(INIT_BALANCE, wallet.balance(U1), "无盈利全额退回");

        // 别人坐不进锁定座位
        game.enterRoom(room.getRoomId(), 9001L, "路人");
        game.sitDown(room.getRoomId(), 9001L, 0);
        waitUntil(() -> lastUserMsg(MsgType.ERROR) != null
                && dataOf(lastUserMsg(MsgType.ERROR)).get("msg").toString().contains("保留"), 3000);
        assertNull(room.playerAtSeat(0));

        // 本人可以坐回去
        game.sitDown(room.getRoomId(), U1, 0);
        waitUntil(() -> room.playerAtSeat(0) != null && room.playerAtSeat(0).getUserId() == U1, 3000);
    }

    @Test
    void 牌局中申请暂离_弃牌后生效() {
        DzRoom room = createRoomAndSeat3(30);
        startHandNow(room);
        waitUntil(() -> room.getStage() == GameStage.PREFLOP && room.getActingSeat() != -1, 3000);

        DzPlayer acting = actingPlayer(room);
        long uid = acting.getUserId();
        game.seatReserveLeave(room.getRoomId(), uid);
        waitUntil(() -> room.playerByUserId(uid).isManualLeavePending(), 3000);
        assertFalse(room.playerByUserId(uid).inGrace(), "未弃牌前不进放假");

        // 弃牌 → 立即进放假
        game.action(room.getRoomId(), uid, "fold", 0);
        waitUntil(() -> room.playerByUserId(uid).inGrace(), 3000);
        assertFalse(room.playerByUserId(uid).isManualLeavePending());
    }

    // ================================================================
    // 断线(vacationPending 模型)
    // ================================================================

    @Test
    void 空闲断线_直接进放假_重连标回线不自动回桌() {
        DzRoom room = createRoomAndSeat3(30);
        game.onDisconnect(U1);
        waitUntil(() -> room.playerByUserId(U1).isOffline(), 3000);
        assertTrue(room.playerByUserId(U1).inGrace(), "空闲断线直接进放假");
        assertNotNull(bc.last(MsgType.PLAYER_OFFLINE));

        // 重连(再进房):标回线,但不自动退出放假(对齐扯旋须发 RESUME)
        game.enterRoom(room.getRoomId(), U1, "P1");
        waitUntil(() -> !room.playerByUserId(U1).isOffline(), 3000);
        assertNotNull(bc.last(MsgType.PLAYER_ONLINE));
        assertTrue(room.playerByUserId(U1).inGrace(), "重连不自动回桌");
    }

    @Test
    void 牌局中断线_代弃后自动进放假() {
        DzRoom room = createRoomAndSeat3(30);
        startHandNow(room);
        waitUntil(() -> room.getStage() == GameStage.PREFLOP && room.getActingSeat() != -1, 3000);

        DzPlayer acting = actingPlayer(room);
        long uid = acting.getUserId();
        game.onDisconnect(uid);
        waitUntil(() -> room.playerByUserId(uid).isOffline(), 3000);
        assertTrue(room.playerByUserId(uid).isVacationPending(), "在手未弃 → 挂 vacationPending");
        assertFalse(room.playerByUserId(uid).inGrace(), "弃牌前不进放假");

        // 弃牌(相当于超时代弃)→ 自动进放假
        game.action(room.getRoomId(), uid, "fold", 0);
        waitUntil(() -> room.playerByUserId(uid).inGrace(), 3000);
        assertFalse(room.playerByUserId(uid).isVacationPending());
    }

    // ================================================================
    // 解散牌局 / 实时战绩
    // ================================================================

    @Test
    void 解散牌局_本手作废退筹_房间销毁() {
        DzRoom room = createRoomAndSeat3(30);
        startHandNow(room);
        waitUntil(() -> room.getStage() == GameStage.PREFLOP && room.getActingSeat() != -1, 3000);

        // 非创建者不能解散
        game.dismissRoom(room.getRoomId(), U2);
        waitUntil(() -> lastUserMsg(MsgType.ERROR) != null, 3000);
        assertNotNull(roomManager.get(room.getRoomId()));

        // 创建者解散:本手投入退回,全员按带入结算(无盈利全退),房间移除
        game.dismissRoom(room.getRoomId(), U1);
        waitUntil(() -> roomManager.get(room.getRoomId()) == null, 5000);
        assertNotNull(bc.last(MsgType.ROOM_DISMISSED));
        for (long uid : new long[]{U1, U2, U3}) {
            assertEquals(INIT_BALANCE, wallet.balance(uid), "解散后全额退回 uid=" + uid);
        }
    }

    @Test
    void 实时战绩_在座盈亏与房间汇总() {
        DzRoom room = createRoomAndSeat3(30);
        startHandNow(room);
        waitUntil(() -> room.getStage() == GameStage.PREFLOP && room.getActingSeat() != -1, 3000);

        game.realtimeStats(room.getRoomId(), U1, 7L);
        waitUntil(() -> lastUserMsg(MsgType.REALTIME_STATS_RES) != null, 3000);
        GameMessage res = lastUserMsg(MsgType.REALTIME_STATS_RES);
        assertEquals(7L, res.getSequence());
        Map<String, Object> data = dataOf(res);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> players = (List<Map<String, Object>>) data.get("players");
        assertEquals(3, players.size());
        long profitSum = 0;
        for (Map<String, Object> p : players) {
            assertEquals(10000L, p.get("bringIn"));
            profitSum += (long) p.get("profit");
        }
        assertEquals(0, profitSum, "牌局中实时盈亏总和为0(桌面投入加回)");
        @SuppressWarnings("unchecked")
        Map<String, Object> roomInfo = (Map<String, Object>) data.get("room");
        assertEquals(30000L, roomInfo.get("totalBringIn"));
    }

    // ================================================================
    // 俱乐部房:罚金 / 坐下限制(H2 库)
    // ================================================================

    @Test
    void 俱乐部房_赢家早退罚金_确认后扣给群主() {
        ClubEnv env = clubEnv();
        DzRoom room = clubRoom(env);
        seatMember(env, room, env.member1, 0);
        seatMember(env, room, env.member2, 1);

        // member1 盈利 2000(直接改桌面筹码模拟)
        DzPlayer p = room.playerByUserId(env.member1);
        p.setStack(p.getStack() + 2000);

        // 不带确认 → 回执 status=92 报罚金,不站起
        env.game.standUp(room.getRoomId(), env.member1);
        waitUntil(() -> {
            GameMessage m = lastUserMsgOf(env.bc, MsgType.STAND_UP_RES);
            return m != null && Integer.valueOf(92).equals(dataOf(m).get("status"));
        }, 3000);
        assertNotNull(room.playerByUserId(env.member1), "确认前不站起");
        long fineQuoted = (long) dataOf(lastUserMsgOf(env.bc, MsgType.STAND_UP_RES)).get("fine");
        assertEquals(2000 * 30 / 100, fineQuoted, "罚金=盈利30%");

        // 确认 → 站起,罚金给群主,盈利部分还有 5% 抽水走合伙人链;全部走俱乐部积分账
        long ownerBefore = env.clubs.score(env.clubId, env.owner);
        env.game.standUp(room.getRoomId(), env.member1, true);
        waitUntil(() -> room.playerByUserId(env.member1) == null, 3000);
        GameMessage fine = env.bc.last(MsgType.RUN_AWAY_FINE);
        assertNotNull(fine);
        assertEquals("EARLY_LEAVE", dataOf(fine).get("kind"));
        assertTrue(env.clubs.score(env.clubId, env.owner) >= ownerBefore + fineQuoted, "罚金进群主俱乐部积分");
        // 玩家积分 = 上分30000 - 带入10000 + 退回(12000 - 抽水100 - 罚金600)
        assertEquals(30000 - 10000 + 12000 - 100 - 600, env.clubs.score(env.clubId, env.member1));
        // 全局钱包不参与俱乐部房经济
        assertEquals(INIT_BALANCE, env.wallet.balance(env.member1), "俱乐部房不动金币钱包");
    }

    @Test
    void 俱乐部房_群主管理员不能坐下_非成员不能坐下() {
        ClubEnv env = clubEnv();
        DzRoom room = clubRoom(env);

        // 群主坐下被拒
        env.game.enterRoom(room.getRoomId(), env.owner, "群主");
        env.game.sitDown(room.getRoomId(), env.owner, 0);
        waitUntil(() -> {
            GameMessage m = lastUserMsgOf(env.bc, MsgType.ERROR);
            return m != null && dataOf(m).get("msg").toString().contains("群主和管理员");
        }, 3000);
        assertNull(room.playerAtSeat(0));

        // 非成员坐下被拒(观战进房是允许的)
        env.game.enterRoom(room.getRoomId(), 8888L, "路人");
        env.game.sitDown(room.getRoomId(), 8888L, 0);
        waitUntil(() -> {
            GameMessage m = lastUserMsgOf(env.bc, MsgType.ERROR);
            return m != null && dataOf(m).get("msg").toString().contains("成员");
        }, 3000);
        assertNull(room.playerAtSeat(0));

        // 普通成员可以坐
        seatMember(env, room, env.member1, 0);
        assertNotNull(room.playerAtSeat(0));
    }

    // ================================================================
    // 工具
    // ================================================================

    /** 俱乐部环境:H2 + 群主/两个成员,游戏服务带 clubService */
    private ClubEnv clubEnv() {
        DriverManagerDataSource ds = new DriverManagerDataSource(
                "jdbc:h2:mem:edge" + DB_SEQ.incrementAndGet() + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;" +
                        "CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
        ResourceDatabasePopulator populator = new ResourceDatabasePopulator(new ClassPathResource("schema.sql"));
        populator.setContinueOnError(true);
        populator.execute(ds);
        JdbcTemplate jdbc = new JdbcTemplate(ds);

        ClubEnv env = new ClubEnv();
        env.owner = 2001;
        env.member1 = 2002;
        env.member2 = 2003;
        env.wallet = new WalletService(jdbc);
        ReflectionTestUtils.setField(env.wallet, "initBalance", INIT_BALANCE);
        env.clubs = new DzClubService(jdbc, new DiamondService(null), env.wallet);
        ReflectionTestUtils.setField(env.clubs, "maxClubPerUser", 10);
        ReflectionTestUtils.setField(env.clubs, "createClubDiamondCost", 0L);
        env.bc = new RecordingBroadcaster();
        env.game = new DzGameService(roomManager, roomWorker, env.wallet, env.bc, env.clubs);
        config(env.game);

        env.clubId = (long) env.clubs.createClub(env.owner, "群主", "边缘部", "简介", "club.png").get("clubId");
        long clubNo = jdbc.queryForObject("SELECT club_no FROM dz_club WHERE id=?", Long.class, env.clubId);
        for (long uid : new long[]{env.member1, env.member2}) {
            env.clubs.apply(uid, "成员" + uid, clubNo);
            long reqId = jdbc.queryForObject(
                    "SELECT id FROM dz_club_join_request WHERE club_id=? AND user_id=? AND status=0",
                    Long.class, env.clubId, uid);
            env.clubs.review(env.clubId, env.owner, reqId, true);
        }
        // 积分按俱乐部独立:群主增发 → 上分给成员(带入货币)
        env.clubs.ownerAddScore(env.clubId, env.owner, 100000);
        env.clubs.distributeScore(env.clubId, env.owner, env.member1, 30000);
        env.clubs.distributeScore(env.clubId, env.owner, env.member2, 30000);
        return env;
    }

    private DzRoom clubRoom(ClubEnv env) {
        return roomManager.create("俱乐部局", env.owner, 50, 100, 9, 30, 5, env.clubId);
    }

    private void seatMember(ClubEnv env, DzRoom room, long uid, int seat) {
        env.game.enterRoom(room.getRoomId(), uid, "成员" + uid);
        env.game.sitDown(room.getRoomId(), uid, seat);
        env.game.buyIn(room.getRoomId(), uid, 10000);
        waitUntil(() -> room.playerByUserId(uid) != null && room.playerByUserId(uid).getStack() == 10000, 3000);
    }

    static class ClubEnv {
        long owner, member1, member2, clubId;
        WalletService wallet;
        DzClubService clubs;
        RecordingBroadcaster bc;
        DzGameService game;
    }

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

    private void startHandNow(DzRoom room) {
        roomWorker.submit(room.getRoomId(), () ->
                ReflectionTestUtils.invokeMethod(game, "startHand", room));
    }

    private DzPlayer actingPlayer(DzRoom room) {
        int seat = room.getActingSeat();
        return seat >= 0 ? room.playerAtSeat(seat) : null;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> dataOf(GameMessage m) {
        return (Map<String, Object>) m.getData();
    }

    private GameMessage lastUserMsg(int type) {
        return lastUserMsgOf(bc, type);
    }

    private static GameMessage lastUserMsgOf(RecordingBroadcaster b, int type) {
        for (int i = b.userMsgs.size() - 1; i >= 0; i--) {
            if (b.userMsgs.get(i).getType() == type) return b.userMsgs.get(i);
        }
        return null;
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
