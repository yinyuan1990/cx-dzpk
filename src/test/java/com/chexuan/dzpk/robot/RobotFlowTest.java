package com.chexuan.dzpk.robot;

import com.chexuan.dzpk.game.model.DzPlayer;
import com.chexuan.dzpk.game.model.DzRoom;
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
 * 机器人全流程:真人坐下带入 → 机器人自动进场补位 → 自动打牌循环多手 → 真人离开机器人撤场。
 * 真人策略 = 轮到就弃牌,牌局由机器人打起来。
 */
class RobotFlowTest {

    static final long HUMAN = 2001;

    DzRoomManager roomManager;
    RoomWorkerService roomWorker;
    WalletService wallet;
    DzGameService game;
    RobotService robots;
    Recorder recorder;

    /** 后绑定的广播出口,解决手动装配的循环依赖 */
    static class LateBroadcaster implements GameBroadcaster {
        GameBroadcaster delegate;

        @Override
        public void toRoom(long roomId, GameMessage msg) {
            if (delegate != null) delegate.toRoom(roomId, msg);
        }

        @Override
        public void toUser(long userId, GameMessage msg) {
            if (delegate != null) delegate.toUser(userId, msg);
        }
    }

    static class Recorder {
        final List<GameMessage> msgs = new CopyOnWriteArrayList<>();

        long count(int type) {
            return msgs.stream().filter(m -> m.getType() != null && m.getType() == type).count();
        }
    }

    @BeforeEach
    void setUp() {
        roomManager = new DzRoomManager();
        roomWorker = new RoomWorkerService();
        wallet = new WalletService();
        ReflectionTestUtils.setField(wallet, "initBalance", 1_000_000L);

        LateBroadcaster late = new LateBroadcaster();
        game = new DzGameService(roomManager, roomWorker, wallet, late);
        ReflectionTestUtils.setField(game, "actionTimeoutSecs", 5);
        ReflectionTestUtils.setField(game, "nextHandDelaySecs", 1);
        ReflectionTestUtils.setField(game, "awaitBuyinSecs", 20);

        robots = new RobotService(roomManager, game);
        ReflectionTestUtils.setField(robots, "enabled", true);
        ReflectionTestUtils.setField(robots, "fillCount", 2);
        ReflectionTestUtils.setField(robots, "minDelayMs", 100L);
        ReflectionTestUtils.setField(robots, "maxDelayMs", 300L);

        recorder = new Recorder();
        RobotService r = robots;
        Recorder rec = recorder;
        late.delegate = new GameBroadcaster() {
            @Override
            public void toRoom(long roomId, GameMessage msg) {
                rec.msgs.add(msg);
                r.onRoomMessage(roomId, msg);
            }

            @Override
            public void toUser(long userId, GameMessage msg) {
                if (r.isRobot(userId)) r.onUserMessage(userId, msg);
                else rec.msgs.add(msg);
            }
        };
    }

    @AfterEach
    void tearDown() {
        robots.shutdown();
        roomWorker.shutdown();
    }

    @Test
    void 机器人进场打循环局_真人走后撤场() {
        DzRoom room = roomManager.create("机器人测试局", HUMAN, 50, 100, 9, 30, 5);
        long roomId = room.getRoomId();

        game.enterRoom(roomId, HUMAN, "真人");
        game.sitDown(roomId, HUMAN, 0);
        game.buyIn(roomId, HUMAN, 10000);

        // 机器人自动进场补到 2 个
        waitUntil(() -> countRobotsSeated(room) >= 2, 15_000, "机器人未进场");

        // 牌局自动转起来:真人轮到就弃牌,机器人互打;等打完 2 手
        long deadline = System.currentTimeMillis() + 60_000;
        while (System.currentTimeMillis() < deadline && recorder.count(MsgType.SETTLE) < 2) {
            DzPlayer human = room.playerByUserId(HUMAN);
            if (human != null && room.getActingSeat() == human.getSeat() && human.canAct()) {
                game.action(roomId, HUMAN, "fold", 0);
            }
            sleep(100);
        }
        assertTrue(recorder.count(MsgType.SETTLE) >= 2, "机器人应打完至少 2 手(循环开局)");
        assertTrue(room.getHandNo() >= 2, "手数应递增");

        // 真人离开 → 机器人撤场 → 房间销毁
        game.standUp(roomId, HUMAN);
        game.leaveRoom(roomId, HUMAN);
        waitUntil(() -> roomManager.get(roomId) == null || countRobotsSeated(roomManager.get(roomId)) == 0,
                20_000, "真人走后机器人未撤场");
    }

    private int countRobotsSeated(DzRoom room) {
        if (room == null) return 0;
        int n = 0;
        for (DzPlayer p : room.getSeats()) {
            if (p != null && robots.isRobot(p.getUserId()) && p.getStack() > 0) n++;
        }
        return n;
    }

    private static void waitUntil(BooleanSupplier cond, long timeoutMs, String failMsg) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (cond.getAsBoolean()) return;
            sleep(50);
        }
        fail(failMsg + "(超时 " + timeoutMs + "ms)");
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
