package com.chexuan.dzpk.game;

import com.chexuan.dzpk.game.card.Card;
import com.chexuan.dzpk.game.card.Deck;
import com.chexuan.dzpk.game.model.DzPlayer;
import com.chexuan.dzpk.game.model.DzRoom;
import com.chexuan.dzpk.game.model.GameStage;
import com.chexuan.dzpk.game.rules.AccessRule;
import com.chexuan.dzpk.game.rules.InsuranceRule;
import com.chexuan.dzpk.game.rules.RoomRules;
import com.chexuan.dzpk.game.rules.SessionRule;
import com.chexuan.dzpk.game.service.*;
import com.chexuan.dzpk.ws.GameMessage;
import com.chexuan.dzpk.ws.MsgType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 建房规则模块测试 — RoomRules 解析校验 + 各独立规则
 * (前注/抓头/埋牌/保险/最短上桌/同IP/自动开局人数)。
 */
class DzRulesTest {

    static final long U1 = 2001, U2 = 2002, U3 = 2003;
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
        ReflectionTestUtils.setField(game, "nextHandDelaySecs", 60);
        ReflectionTestUtils.setField(game, "awaitBuyinSecs", 60);
        ReflectionTestUtils.setField(game, "insuranceTimeoutSecs", 12);
    }

    @AfterEach
    void tearDown() {
        roomWorker.shutdown();
    }

    // ================================================================
    // RoomRules 解析
    // ================================================================

    @Test
    void 建房参数_默认值() {
        RoomRules r = RoomRules.parse(new HashMap<>(), "默认名");
        assertEquals("默认名", r.getName());
        assertEquals(50, r.getSb());
        assertEquals(100, r.bb());
        assertEquals(9, r.getMaxPlayers());
        assertEquals(30, r.getSettleTimeMins());
        assertEquals(100 * 100, r.getInChip());
        assertEquals(10000, r.minBuyin());
        assertEquals(40000, r.maxBuyin());
        assertEquals(15, r.getOpTimeSec());
        assertEquals(0, r.getAnte());
        assertFalse(r.isStraddleOn());
        assertFalse(r.isInsuranceOn());
        assertFalse(r.isMuckOn());
        assertTrue(r.isAheadLeaveOn());
        assertEquals(2, r.effectiveAutoStart());
    }

    @Test
    void 建房参数_非法值被拒() {
        assertThrows(IllegalArgumentException.class,
                () -> RoomRules.parse(Map.of("sb", 0), "n"));
        assertThrows(IllegalArgumentException.class,
                () -> RoomRules.parse(Map.of("maxPlayers", 10), "n"));
        assertThrows(IllegalArgumentException.class,
                () -> RoomRules.parse(Map.of("maxPlayers", 1), "n"));
        assertThrows(IllegalArgumentException.class,
                () -> RoomRules.parse(Map.of("opTimeSec", 3), "n"));
        assertThrows(IllegalArgumentException.class,
                () -> RoomRules.parse(Map.of("sb", 50, "inChip", 100), "n"));
        assertThrows(IllegalArgumentException.class,
                () -> RoomRules.parse(Map.of("inMinRate", 2, "inMaxRate", 1), "n"));
        assertThrows(IllegalArgumentException.class,
                () -> RoomRules.parse(Map.of("sb", 50, "ante", 500), "n"));
        assertThrows(IllegalArgumentException.class,
                () -> RoomRules.parse(Map.of("autoStartNum", 1), "n"));
        assertThrows(IllegalArgumentException.class,
                () -> RoomRules.parse(Map.of("maxPlayers", 6, "autoStartNum", 7), "n"));
    }

    @Test
    void 建房参数_2人桌与6人桌() {
        RoomRules r2 = RoomRules.parse(Map.of("maxPlayers", 2), "n");
        assertEquals(2, r2.getMaxPlayers());
        RoomRules r6 = RoomRules.parse(Map.of("maxPlayers", 6, "autoStartNum", 4), "n");
        assertEquals(6, r6.getMaxPlayers());
        assertEquals(4, r6.effectiveAutoStart());
    }

    // ================================================================
    // 前注 / 抓头(流程)
    // ================================================================

    @Test
    void 前注_开局直接进池() {
        DzRoom room = createRoomAndSeat3(Map.of("sb", 50, "ante", 20));
        startHandNow(room);
        waitUntil(() -> room.getStage() == GameStage.PREFLOP && room.getActingSeat() != -1, 3000);

        assertEquals(60, room.getCollectedPot(), "3 人前注 20 直接进池");
        assertEquals(60 + 150, room.displayPot(), "总池 = 前注 + 大小盲");
        for (long uid : new long[]{U1, U2, U3}) {
            assertTrue(room.playerByUserId(uid).getTotalBetThisHand() >= 20, "每人都交了前注");
        }
        // 前注不影响跟注线
        assertEquals(100, room.getCurrentBet());
    }

    @Test
    void 抓头_BB下家强制2BB_行动从其下家开始() {
        DzRoom room = createRoomAndSeat3(Map.of("sb", 50, "straddleOn", 1));
        startHandNow(room);
        waitUntil(() -> room.getStage() == GameStage.PREFLOP && room.getActingSeat() != -1, 3000);

        // 3 人局:BB 下家 = 庄位抓头
        int straddleSeat = room.nextSeat(room.getBbSeat(), DzPlayer::isInHand);
        DzPlayer straddler = room.playerAtSeat(straddleSeat);
        assertEquals(200, straddler.getBetThisRound(), "抓头 2BB");
        assertEquals(200, room.getCurrentBet(), "跟注线变 2BB");
        assertEquals(200, room.getMinRaise(), "最小加注增量 2BB");
        assertEquals(room.getSbSeat(), room.getActingSeat(), "行动从抓头位下家(SB)开始");
        assertFalse(straddler.isActed(), "抓头者同盲注待遇,翻前有最后行动权");
    }

    // ================================================================
    // 埋牌(摊牌只亮赢家)
    // ================================================================

    @Test
    @SuppressWarnings("unchecked")
    void 埋牌_摊牌只亮赢家() {
        DzRoom room = createRoomAndSeat3(Map.of("sb", 50, "muckOn", 1));
        startHandNow(room);
        waitUntil(() -> room.getStage() == GameStage.PREFLOP && room.getActingSeat() != -1, 3000);
        playHandAllCall(room);
        waitUntil(() -> room.getStage() == GameStage.FINISHED || room.getStage() == GameStage.WAITING, 8000);

        GameMessage settle = bc.last(MsgType.SETTLE);
        assertNotNull(settle);
        Map<String, Object> data = (Map<String, Object>) settle.getData();
        assertEquals("showdown", data.get("reason"));
        // 赢家集合 = 各池 winners 并集
        java.util.Set<Object> winners = new java.util.HashSet<>();
        for (Map<String, Object> pot : (List<Map<String, Object>>) data.get("pots")) {
            winners.addAll((List<Object>) pot.get("winners"));
        }
        assertFalse(winners.isEmpty());
        for (Map<String, Object> r : (List<Map<String, Object>>) data.get("results")) {
            boolean isWinner = winners.contains(r.get("userId"));
            if (isWinner) {
                assertNotNull(r.get("cards"), "赢家必须亮牌");
            } else {
                assertNull(r.get("cards"), "muck 开启:输家不亮牌");
            }
        }
    }

    // ================================================================
    // 保险
    // ================================================================

    @Test
    void 保险_outs与赔率计算() {
        // KK(领先,板上有 K)vs AA(落后,outs = 剩下两张 A)
        DzRoom room = insuranceRoom();
        InsuranceRule.Offer offer = InsuranceRule.tryOffer(room);
        assertNotNull(offer, "两人全下+转牌已发 → 应有报价");
        assertEquals(U1, offer.leaderUserId, "KK 中set 领先");
        assertEquals(2, offer.outs, "AA 的 outs = AC/AS 两张");
        assertTrue(offer.outCards.contains("AC") && offer.outCards.contains("AS"));
        assertEquals(1600, offer.oddsX100, "2 个 outs 赔率 16 倍");
        assertEquals(2000, offer.maxInsure, "投保上限 = 底池");
        // 保费向上取整:500/16 = 31.25 → 32
        assertEquals(32, InsuranceRule.premium(500, 2));
    }

    @Test
    void 保险_未开启或非跑马_不报价() {
        DzRoom room = insuranceRoom();
        room.getRules().setInsuranceOn(false);
        assertNull(InsuranceRule.tryOffer(room), "保险关闭不报价");

        room.getRules().setInsuranceOn(true);
        room.playerByUserId(U1).setAllIn(false);
        room.playerByUserId(U2).setAllIn(false);
        assertNull(InsuranceRule.tryOffer(room), "双方都能行动(河牌还有下注轮)不报价");
    }

    @Test
    void 保险_买保后_守住扣保费_被反超获赔() {
        // 场景 1:河牌 3D(非 out)→ 领先方拿池,扣保费
        DzRoom room = insuranceRoom();
        rigNextCard(room.getDeck(), Card.of("3D"));
        long roomId = room.getRoomId();
        roomWorker.submit(roomId, () ->
                ReflectionTestUtils.invokeMethod(game, "advanceStreet", room));
        waitUntil(() -> bc.last(MsgType.INSURANCE_OFFER) != null, 3000);
        assertEquals(4, room.getBoard().size(), "报价挂起,河牌未发");

        game.insuranceBuy(roomId, U1, 500);
        waitUntil(() -> room.getStage() == GameStage.FINISHED || room.getStage() == GameStage.WAITING, 8000);
        DzPlayer leader = room.playerByUserId(U1);
        // 赢池 2000,扣保费 32 → 1968
        assertEquals(2000 - 32, leader.getStack(), "守住:拿池扣保费");
        GameMessage result = bc.last(MsgType.INSURANCE_RESULT);
        assertNotNull(result);
        assertEquals(Boolean.FALSE, ((Map<?, ?>) result.getData()).get("outHit"));
    }

    @Test
    void 保险_被反超_按投保额赔付() {
        // 场景 2:河牌 AC(out)→ AA 三条反超,领先方输池但获赔投保额
        DzRoom room = insuranceRoom();
        rigNextCard(room.getDeck(), Card.of("AC"));
        long roomId = room.getRoomId();
        roomWorker.submit(roomId, () ->
                ReflectionTestUtils.invokeMethod(game, "advanceStreet", room));
        waitUntil(() -> bc.last(MsgType.INSURANCE_OFFER) != null, 3000);

        game.insuranceBuy(roomId, U1, 500);
        waitUntil(() -> room.getStage() == GameStage.FINISHED || room.getStage() == GameStage.WAITING, 8000);
        DzPlayer leader = room.playerByUserId(U1);
        // 输池(stack 0)但获赔 500
        assertEquals(500, leader.getStack(), "被反超:获赔投保额");
        assertEquals(2000, room.playerByUserId(U2).getStack(), "反超方拿池");
        GameMessage result = bc.last(MsgType.INSURANCE_RESULT);
        assertEquals(Boolean.TRUE, ((Map<?, ?>) result.getData()).get("outHit"));
    }

    // ================================================================
    // 最短上桌时间 / 同 IP / 自动开局人数
    // ================================================================

    @Test
    void 最短上桌时间_未满不能站起() {
        DzRoom room = new DzRoom(9);
        RoomRules rules = RoomRules.parse(Map.of("gameMinTime", 10, "aheadLeaveOn", 0), "n");
        room.setRules(rules);
        DzPlayer p = new DzPlayer();
        p.setBringInThisPeriod(10000);
        p.setGameTimeAccumMs(60_000); // 只玩了 1 分钟
        assertNotNull(SessionRule.checkStandUp(room, p), "未满 10 分钟不能站起");

        p.setGameTimeAccumMs(10 * 60_000L);
        assertNull(SessionRule.checkStandUp(room, p), "满时长可以站起");

        rules.setAheadLeaveOn(true);
        p.setGameTimeAccumMs(0);
        assertNull(SessionRule.checkStandUp(room, p), "允许提前离桌则不限");

        rules.setAheadLeaveOn(false);
        DzPlayer fresh = new DzPlayer();
        assertNull(SessionRule.checkStandUp(room, fresh), "没玩过随时可走");
    }

    @Test
    void 同IP限制_不能同桌() {
        DzRoom room = new DzRoom(9);
        RoomRules rules = RoomRules.parse(Map.of("ipLimitOn", 1), "n");
        room.setRules(rules);
        DzPlayer seated = new DzPlayer();
        seated.setIp("1.2.3.4");
        room.getSeats()[0] = seated;

        assertNotNull(AccessRule.checkSit(room, "1.2.3.4"), "同 IP 拒绝");
        assertNull(AccessRule.checkSit(room, "5.6.7.8"), "不同 IP 放行");
        assertNull(AccessRule.checkSit(room, null), "无 IP(机器人)放行");

        rules.setIpLimitOn(false);
        assertNull(AccessRule.checkSit(room, "1.2.3.4"), "开关关闭放行");
    }

    @Test
    void 自动开局人数_坐满才开() {
        RoomRules rules = RoomRules.parse(Map.of("sb", 50, "autoStartNum", 3), "n");
        DzRoom room = roomManager.create(rules, U1);
        long roomId = room.getRoomId();
        for (int i = 0; i < 2; i++) {
            long uid = new long[]{U1, U2}[i];
            game.enterRoom(roomId, uid, "P" + (i + 1));
            game.sitDown(roomId, uid, i);
            game.buyIn(roomId, uid, 10000);
        }
        waitUntil(() -> room.playerByUserId(U2) != null && room.playerByUserId(U2).getStack() == 10000, 3000);

        startHandNow(room);
        sleep(300);
        assertEquals(GameStage.WAITING, room.getStage(), "2 人 < autoStartNum(3) 不开局");

        game.enterRoom(roomId, U3, "P3");
        game.sitDown(roomId, U3, 2);
        game.buyIn(roomId, U3, 10000);
        waitUntil(() -> room.playerByUserId(U3) != null && room.playerByUserId(U3).getStack() == 10000, 3000);
        startHandNow(room);
        waitUntil(() -> room.getStage() == GameStage.PREFLOP, 3000);
    }

    // ================================================================
    // 工具
    // ================================================================

    /**
     * 手搭"两人全下、转牌已发"的保险局:
     *   U1 = KH KS(板上 KD 中三条,领先)
     *   U2 = AH AD(一对 A,outs = AC/AS)
     *   板 = 2H 7D 9C KD,底池 2000(每人 1000)
     */
    private DzRoom insuranceRoom() {
        RoomRules rules = RoomRules.parse(Map.of("sb", 50, "insuranceOn", 1), "保险局");
        DzRoom room = roomManager.create(rules, U1);
        room.setHandNo(1);
        room.setStage(GameStage.TURN);
        room.setButton(0);
        room.setDeck(new Deck());
        room.getBoard().add(Card.of("2H"));
        room.getBoard().add(Card.of("7D"));
        room.getBoard().add(Card.of("9C"));
        room.getBoard().add(Card.of("KD"));
        room.setCollectedPot(2000);

        long[] uids = {U1, U2};
        Card[][] holes = {{Card.of("KH"), Card.of("KS")}, {Card.of("AH"), Card.of("AD")}};
        for (int i = 0; i < 2; i++) {
            DzPlayer p = new DzPlayer();
            p.setUserId(uids[i]);
            p.setNickname("P" + (i + 1));
            p.setSeat(i);
            p.setStack(0);
            p.setInHand(true);
            p.setAllIn(true);
            p.setTotalBetThisHand(1000);
            p.setHoleCards(holes[i]);
            room.getSeats()[i] = p;
            room.getMembers().put(uids[i], p.getNickname());
        }
        return room;
    }

    /** 把牌堆的下一张换成指定牌(与后面某位置交换) */
    @SuppressWarnings("unchecked")
    private void rigNextCard(Deck deck, Card want) {
        List<Card> cards = (List<Card>) ReflectionTestUtils.getField(deck, "cards");
        int cursor = (int) ReflectionTestUtils.getField(deck, "cursor");
        for (int i = 0; i < cards.size(); i++) {
            if (cards.get(i).getId() == want.getId()) {
                Card tmp = cards.get(cursor);
                cards.set(cursor, cards.get(i));
                cards.set(i, tmp);
                return;
            }
        }
        fail("牌堆里找不到 " + want);
    }

    private DzRoom createRoomAndSeat3(Map<String, Object> ruleParams) {
        Map<String, Object> params = new HashMap<>(ruleParams);
        RoomRules rules = RoomRules.parse(params, "规则测试局");
        DzRoom room = roomManager.create(rules, U1);
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

    private void playHandAllCall(DzRoom room) {
        long deadline = System.currentTimeMillis() + 10_000;
        long lastFingerprint = -1;
        while (System.currentTimeMillis() < deadline) {
            if (room.getStage() == GameStage.FINISHED || room.getStage() == GameStage.WAITING) return;
            DzPlayer acting = actingPlayer(room);
            if (acting == null) {
                sleep(30);
                continue;
            }
            long fingerprint = acting.getUserId() * 1000 + room.getStage().ordinal() * 10 + room.getBoard().size();
            if (fingerprint == lastFingerprint) {
                sleep(30);
                continue;
            }
            lastFingerprint = fingerprint;
            long toCall = room.getCurrentBet() - acting.getBetThisRound();
            game.action(room.getRoomId(), acting.getUserId(), toCall > 0 ? "call" : "check", 0);
            sleep(50);
        }
    }

    private DzPlayer actingPlayer(DzRoom room) {
        int seat = room.getActingSeat();
        return seat >= 0 ? room.playerAtSeat(seat) : null;
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
