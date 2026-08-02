package com.chexuan.dzpk.game.service;

import com.chexuan.dzpk.db.DzRecordStore;
import com.chexuan.dzpk.game.card.BiPai;
import com.chexuan.dzpk.game.card.Card;
import com.chexuan.dzpk.game.card.Deck;
import com.chexuan.dzpk.game.card.HandResult;
import com.chexuan.dzpk.game.engine.Pot;
import com.chexuan.dzpk.game.engine.PotManager;
import com.chexuan.dzpk.game.model.ActionType;
import com.chexuan.dzpk.game.model.DzPlayer;
import com.chexuan.dzpk.game.model.DzRoom;
import com.chexuan.dzpk.game.model.GameStage;
import com.chexuan.dzpk.game.rules.AccessRule;
import com.chexuan.dzpk.game.rules.AnteRule;
import com.chexuan.dzpk.game.rules.InsuranceRule;
import com.chexuan.dzpk.game.rules.MuckRule;
import com.chexuan.dzpk.game.rules.SessionRule;
import com.chexuan.dzpk.game.rules.StraddleRule;
import com.chexuan.dzpk.ws.GameMessage;
import com.chexuan.dzpk.ws.MsgType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledFuture;

/**
 * 德州牌局引擎 — 循环玩法:
 *   比牌/边池: 移植老德州(BiPai)+ 标准边池(PotManager)
 *   结算(重写): 一手一结 + 周期结算 —
 *     创建房间选 settleTimeMins;玩家从参与发牌起开闸计时(墙钟,含局间);
 *     每手结束检查 effectiveMs >= settleTime → 抽水→退筹回钱包→桌面清零→
 *     等补带入(超时自动站起),房间继续循环开局。对齐扯旋 v46/v52.9r 模型。
 *
 * 所有对房间的修改都经 RoomWorkerService 串行化(同房串行,异房并行)。
 */
@Slf4j
@Service
public class DzGameService {

    private final DzRoomManager roomManager;
    private final RoomWorkerService roomWorker;
    private final WalletService walletService;
    private final GameBroadcaster broadcaster;
    private final DzRecordStore records;
    /** 俱乐部服务(合伙人分成/群主免抽);单测可为 null */
    private final com.chexuan.dzpk.club.DzClubService clubService;

    @Value("${dzpk.action-timeout-secs:15}")
    private int actionTimeoutSecs;

    @Value("${dzpk.next-hand-delay-secs:4}")
    private int nextHandDelaySecs;

    @Value("${dzpk.await-buyin-secs:30}")
    private int awaitBuyinSecs;

    @org.springframework.beans.factory.annotation.Autowired
    public DzGameService(DzRoomManager roomManager, RoomWorkerService roomWorker,
                         WalletService walletService, GameBroadcaster broadcaster,
                         DzRecordStore records, com.chexuan.dzpk.club.DzClubService clubService) {
        this.roomManager = roomManager;
        this.roomWorker = roomWorker;
        this.walletService = walletService;
        this.broadcaster = broadcaster;
        this.records = records;
        this.clubService = clubService;
    }

    /** 单测用:不落库、无俱乐部 */
    public DzGameService(DzRoomManager roomManager, RoomWorkerService roomWorker,
                         WalletService walletService, GameBroadcaster broadcaster) {
        this(roomManager, roomWorker, walletService, broadcaster, new DzRecordStore(null), null);
    }

    // ================================================================
    // 入口(外部调用,内部串行化)
    // ================================================================

    public void enterRoom(long roomId, long userId, String nickname) {
        DzRoom room = roomManager.get(roomId);
        if (room == null) {
            sendError(userId, roomId, "房间不存在");
            return;
        }
        roomWorker.submit(roomId, () -> {
            room.getMembers().put(userId, nickname);
            broadcaster.toUser(userId, GameMessage.create(MsgType.ENTER_ROOM_RES, roomId, snapshot(room, userId)));
            broadcaster.toRoom(roomId, GameMessage.create(MsgType.PLAYER_ENTER, roomId,
                    Map.of("userId", userId, "nickname", nickname)));
        });
    }

    public void leaveRoom(long roomId, long userId) {
        DzRoom room = roomManager.get(roomId);
        if (room == null) return;
        roomWorker.submit(roomId, () -> {
            DzPlayer p = room.playerByUserId(userId);
            if (p != null) {
                doStandUp(room, p, "leave");
            }
            room.getMembers().remove(userId);
            broadcaster.toRoom(roomId, GameMessage.create(MsgType.PLAYER_LEAVE, roomId, Map.of("userId", userId)));
            if (room.getMembers().isEmpty() && !room.inGame()) {
                roomManager.remove(roomId);
                roomWorker.removeRoom(roomId);
                records.markRoomClosed(roomId);
                log.info("房间清空销毁: roomId={}", roomId);
            }
        });
    }

    public void sitDown(long roomId, long userId, int seat) {
        sitDown(roomId, userId, seat, null);
    }

    /** ip 用于 AccessRule 同 IP 限制(机器人/单测传 null 跳过) */
    public void sitDown(long roomId, long userId, int seat, String ip) {
        DzRoom room = roomManager.get(roomId);
        if (room == null) {
            sendError(userId, roomId, "房间不存在");
            return;
        }
        roomWorker.submit(roomId, () -> {
            if (!room.getMembers().containsKey(userId)) {
                sendError(userId, roomId, "请先进入房间");
                return;
            }
            if (room.playerByUserId(userId) != null) {
                sendError(userId, roomId, "已在座位上");
                return;
            }
            if (seat < 0 || seat >= room.getMaxPlayers() || room.getSeats()[seat] != null) {
                sendError(userId, roomId, "座位不可用");
                return;
            }
            String deny = AccessRule.checkSit(room, ip);
            if (deny != null) {
                sendError(userId, roomId, deny);
                return;
            }
            DzPlayer p = new DzPlayer();
            p.setUserId(userId);
            p.setNickname(room.getMembers().get(userId));
            p.setSeat(seat);
            p.setStack(0);
            p.setIp(ip);
            room.getSeats()[seat] = p;
            broadcaster.toRoom(roomId, GameMessage.create(MsgType.PLAYER_SIT, roomId,
                    Map.of("userId", userId, "nickname", p.getNickname(), "seat", seat,
                            "stack", 0L, "minBuyin", room.getMinBuyin(), "maxBuyin", room.getMaxBuyin())));
        });
    }

    public void buyIn(long roomId, long userId, long amount) {
        DzRoom room = roomManager.get(roomId);
        if (room == null) {
            sendError(userId, roomId, "房间不存在");
            return;
        }
        roomWorker.submit(roomId, () -> {
            DzPlayer p = room.playerByUserId(userId);
            if (p == null) {
                sendError(userId, roomId, "请先坐下");
                return;
            }
            if (amount <= 0) {
                sendError(userId, roomId, "带入金额非法");
                return;
            }
            long after = p.getStack() + p.getPendingBuyin() + amount;
            if (after > room.getMaxBuyin()) {
                sendError(userId, roomId, "超过最大带入 " + room.getMaxBuyin());
                return;
            }
            if (p.getStack() + p.getPendingBuyin() + amount < room.getMinBuyin()) {
                sendError(userId, roomId, "低于最小带入 " + room.getMinBuyin());
                return;
            }
            if (!walletService.debit(userId, amount)) {
                sendError(userId, roomId, "余额不足");
                return;
            }
            p.setBringInThisPeriod(p.getBringInThisPeriod() + amount);
            boolean applied;
            if (room.inGame() && p.isInHand()) {
                // 牌局中追加 → 局间生效
                p.setPendingBuyin(p.getPendingBuyin() + amount);
                applied = false;
            } else {
                p.setStack(p.getStack() + amount);
                applied = true;
            }
            if (p.isAwaitingBuyin()) {
                p.setAwaitingBuyin(false);
                p.setAwaitBuyinDeadline(0);
            }
            broadcaster.toRoom(roomId, GameMessage.create(MsgType.BUY_IN_RES, roomId,
                    Map.of("userId", userId, "amount", amount, "applied", applied,
                            "stack", p.getStack(), "balance", walletService.balance(userId))));
            tryStartHand(room);
        });
    }

    /**
     * 站起(对齐扯旋语义):
     *   牌局中且未弃牌 → 只标记 pendingStandUp,这一手继续打完,局末真正站起;
     *   已弃牌 / 局间 → 立即站起结算。
     * 两种情况都给申请者即时回执 STAND_UP_RES {pending}。
     */
    public void standUp(long roomId, long userId) {
        DzRoom room = roomManager.get(roomId);
        if (room == null) return;
        roomWorker.submit(roomId, () -> {
            DzPlayer p = room.playerByUserId(userId);
            if (p == null) {
                sendError(userId, roomId, "不在座位上");
                return;
            }
            // 最短上桌时间(SessionRule):未满不能站起
            String deny = SessionRule.checkStandUp(room, p);
            if (deny != null) {
                sendError(userId, roomId, deny);
                return;
            }
            if (room.inGame() && p.isInHand() && !p.isFolded()) {
                boolean first = !p.isPendingStandUp();
                p.setPendingStandUp(true);
                broadcaster.toUser(userId, GameMessage.create(MsgType.STAND_UP_RES, roomId, Map.of(
                        "pending", true, "seat", p.getSeat(),
                        "msg", first ? "本手结束后自动站起" : "已申请,本手结束后自动站起")));
                log.info("申请站起(局末生效): roomId={}, userId={}", roomId, userId);
                return;
            }
            doStandUp(room, p, "standup");
            broadcaster.toUser(userId, GameMessage.create(MsgType.STAND_UP_RES, roomId, Map.of(
                    "pending", false)));
        });
    }

    public void action(long roomId, long userId, String act, long amount) {
        DzRoom room = roomManager.get(roomId);
        if (room == null) {
            sendError(userId, roomId, "房间不存在");
            return;
        }
        roomWorker.submit(roomId, () -> handleAction(room, userId, ActionType.of(act), amount, false));
    }

    public void snapshotTo(long roomId, long userId) {
        DzRoom room = roomManager.get(roomId);
        if (room == null) return;
        roomWorker.submit(roomId, () ->
                broadcaster.toUser(userId, GameMessage.create(MsgType.SNAPSHOT_RES, roomId, snapshot(room, userId))));
    }

    // ================================================================
    // 开局
    // ================================================================

    /** 人够且房间空闲就开下一手(坐下/带入/结算后都会来探一次) */
    private void tryStartHand(DzRoom room) {
        if (room.inGame() || room.isHandScheduled()) return;
        if (room.readyPlayers().size() >= startNeed(room)) {
            room.setHandScheduled(true);
            roomWorker.submitDelaySecs(room.getRoomId(), () -> startHand(room), nextHandDelaySecs);
        }
    }

    /** 开局所需人数:首局按 autoStartNum(SessionRule),开起来之后 ≥2 就续 */
    private int startNeed(DzRoom room) {
        if (room.getHandNo() > 0) return 2;
        return room.getRules() != null ? room.getRules().effectiveAutoStart() : 2;
    }

    private void startHand(DzRoom room) {
        room.setHandScheduled(false);
        if (room.inGame()) return;
        List<DzPlayer> ready = room.readyPlayers();
        if (ready.size() < startNeed(room)) {
            room.setStage(GameStage.WAITING);
            broadcaster.toRoom(room.getRoomId(), GameMessage.create(MsgType.ROOM_STATE, room.getRoomId(),
                    Map.of("stage", GameStage.WAITING.name())));
            return;
        }

        room.setHandNo(room.getHandNo() + 1);
        room.getBoard().clear();
        room.setPots(new ArrayList<>());
        room.setCollectedPot(0);
        room.getDeadContributions().clear();
        cancelInsurance(room);
        room.setInsurance(null);
        room.setDeck(new Deck());

        for (DzPlayer p : room.getSeats()) {
            if (p == null) continue;
            p.resetForHand();
        }
        for (DzPlayer p : ready) {
            p.setInHand(true);
            p.openGate();  // 周期计时:参与发牌即开闸
        }

        // 庄位轮转
        int button = room.nextSeat(room.getButton(), DzPlayer::isInHand);
        room.setButton(button);
        boolean headsUp = ready.size() == 2;
        int sbSeat = headsUp ? button : room.nextSeat(button, DzPlayer::isInHand);
        int bbSeat = room.nextSeat(sbSeat, DzPlayer::isInHand);
        room.setSbSeat(sbSeat);
        room.setBbSeat(bbSeat);

        // 前注(AnteRule):发盲注前每人强投,直接进池
        long anteTotal = AnteRule.post(room, ready);

        // 盲注
        pay(room.playerAtSeat(sbSeat), room.getSb());
        pay(room.playerAtSeat(bbSeat), room.getBb());
        room.setCurrentBet(room.getBb());
        room.setMinRaise(room.getBb());

        // 抓头(StraddleRule):BB 下家强制 2BB,翻前行动从其下家开始
        int straddleSeat = StraddleRule.post(room, this::pay);

        // 发手牌
        for (DzPlayer p : ready) {
            p.setHoleCards(new Card[]{room.getDeck().deal(), room.getDeck().deal()});
        }

        room.setStage(GameStage.PREFLOP);
        Map<String, Object> startData = new LinkedHashMap<>();
        startData.put("handNo", room.getHandNo());
        startData.put("button", button);
        startData.put("sbSeat", sbSeat);
        startData.put("bbSeat", bbSeat);
        startData.put("sb", room.getSb());
        startData.put("bb", room.getBb());
        startData.put("ante", anteTotal > 0 ? (room.getRules() != null ? room.getRules().getAnte() : 0) : 0);
        startData.put("straddleSeat", straddleSeat);
        startData.put("pot", room.displayPot());
        startData.put("players", seatBrief(room));
        broadcaster.toRoom(room.getRoomId(), GameMessage.create(MsgType.HAND_START, room.getRoomId(), startData));
        for (DzPlayer p : ready) {
            broadcaster.toUser(p.getUserId(), GameMessage.create(MsgType.HOLE_CARDS, room.getRoomId(), Map.of(
                    "handNo", room.getHandNo(),
                    "cards", cardStrs(p.getHoleCards()))));
        }

        int lastBlindSeat = straddleSeat != -1 ? straddleSeat : bbSeat;
        int first = room.nextSeat(lastBlindSeat, DzPlayer::canAct);
        if (first == -1) {
            // 盲注就全下光了 → 直接跑马
            advanceStreet(room);
        } else {
            setActing(room, first);
        }
        log.info("开局: roomId={}, handNo={}, players={}, button={}", room.getRoomId(), room.getHandNo(), ready.size(), button);
    }

    // ================================================================
    // 行动
    // ================================================================

    private void handleAction(DzRoom room, long userId, ActionType act, long amount, boolean isAuto) {
        if (!isBettingStage(room.getStage())) {
            if (!isAuto) sendError(userId, room.getRoomId(), "当前不可行动");
            return;
        }
        DzPlayer p = room.playerAtSeat(room.getActingSeat());
        if (p == null || p.getUserId() != userId) {
            if (!isAuto) sendError(userId, room.getRoomId(), "还没轮到你");
            return;
        }
        if (act == null) {
            if (!isAuto) sendError(userId, room.getRoomId(), "非法操作");
            return;
        }

        long toCall = room.getCurrentBet() - p.getBetThisRound();
        long paid = 0;
        switch (act) {
            case FOLD -> p.setFolded(true);
            case CHECK -> {
                if (toCall > 0) {
                    if (!isAuto) sendError(userId, room.getRoomId(), "有注要跟,不能过牌");
                    return;
                }
            }
            case CALL -> {
                if (toCall <= 0) {
                    if (!isAuto) sendError(userId, room.getRoomId(), "无注可跟");
                    return;
                }
                paid = pay(p, toCall);
            }
            case RAISE, ALLIN -> {
                long raiseTo = (act == ActionType.ALLIN) ? p.getBetThisRound() + p.getStack() : amount;
                if (raiseTo <= room.getCurrentBet()) {
                    // 全下额不够加注 → 按跟注处理(allin for less)
                    if (act == ActionType.ALLIN) {
                        paid = pay(p, p.getStack());
                        break;
                    }
                    sendError(userId, room.getRoomId(), "加注额必须大于当前注 " + room.getCurrentBet());
                    return;
                }
                long need = raiseTo - p.getBetThisRound();
                if (need > p.getStack()) {
                    sendError(userId, room.getRoomId(), "筹码不足");
                    return;
                }
                long increment = raiseTo - room.getCurrentBet();
                boolean isAllin = need == p.getStack();
                if (increment < room.getMinRaise() && !isAllin) {
                    sendError(userId, room.getRoomId(), "最小加注到 " + (room.getCurrentBet() + room.getMinRaise()));
                    return;
                }
                paid = pay(p, need);
                room.setCurrentBet(raiseTo);
                if (increment >= room.getMinRaise()) {
                    room.setMinRaise(increment);
                    // 完整加注 → 重开其他人的行动权
                    for (DzPlayer other : room.getSeats()) {
                        if (other != null && other != p && other.canAct()) {
                            other.setActed(false);
                        }
                    }
                }
                // 不足额全下:不重开行动权(标准规则),其他人只需补跟
            }
        }
        p.setActed(true);
        // 入池率统计(vpOn):翻前主动投入(跟注/加注/全下)记一次入池
        if (room.getStage() == GameStage.PREFLOP && !p.isVpipThisHand()
                && (act == ActionType.CALL || act == ActionType.RAISE || act == ActionType.ALLIN)) {
            p.setVpipThisHand(true);
            p.setVpipCount(p.getVpipCount() + 1);
        }
        cancelActionTimeout(room);

        broadcaster.toRoom(room.getRoomId(), GameMessage.create(MsgType.ACTION_BC, room.getRoomId(), Map.of(
                "userId", userId, "seat", p.getSeat(),
                "act", (p.isAllIn() && act != ActionType.FOLD && act != ActionType.CHECK) ? "allin" : act.name().toLowerCase(),
                "paid", paid, "betThisRound", p.getBetThisRound(),
                "stack", p.getStack(), "pot", room.displayPot(),
                "currentBet", room.getCurrentBet(), "auto", isAuto)));

        progress(room);
    }

    private void progress(DzRoom room) {
        List<DzPlayer> contesting = room.contestingPlayers();
        if (contesting.size() <= 1) {
            winByFold(room, contesting.isEmpty() ? null : contesting.get(0));
            return;
        }
        int next = room.nextSeat(room.getActingSeat(),
                q -> q.canAct() && (!q.isActed() || q.getBetThisRound() < room.getCurrentBet()));
        if (next != -1) {
            setActing(room, next);
        } else {
            advanceStreet(room);
        }
    }

    /** 行动思考时间:建房参数 opTimeSec 优先,没配走全局默认 */
    private int actionSecs(DzRoom room) {
        if (room.getRules() != null && room.getRules().getOpTimeSec() > 0) {
            return room.getRules().getOpTimeSec();
        }
        return actionTimeoutSecs;
    }

    private void setActing(DzRoom room, int seat) {
        room.setActingSeat(seat);
        DzPlayer p = room.playerAtSeat(seat);
        int secs = actionSecs(room);
        long deadline = System.currentTimeMillis() + secs * 1000L;
        room.setActionDeadline(deadline);
        broadcaster.toRoom(room.getRoomId(), GameMessage.create(MsgType.TURN, room.getRoomId(), Map.of(
                "seat", seat, "userId", p.getUserId(),
                "toCall", Math.max(0, room.getCurrentBet() - p.getBetThisRound()),
                "minRaiseTo", room.getCurrentBet() + room.getMinRaise(),
                "deadline", deadline, "timeoutSecs", secs)));
        scheduleActionTimeout(room, seat);
    }

    private void scheduleActionTimeout(DzRoom room, int seat) {
        cancelActionTimeout(room);
        long handNo = room.getHandNo();
        GameStage stage = room.getStage();
        ScheduledFuture<?> f = roomWorker.submitDelaySecs(room.getRoomId(), () -> {
            if (room.getHandNo() != handNo || room.getStage() != stage || room.getActingSeat() != seat) {
                return; // 已行动过/换街了
            }
            DzPlayer p = room.playerAtSeat(seat);
            if (p == null) return;
            // 超时自动:能过就过,不能过就弃
            boolean canCheck = room.getCurrentBet() == p.getBetThisRound();
            log.info("行动超时自动{}: roomId={}, userId={}", canCheck ? "过牌" : "弃牌", room.getRoomId(), p.getUserId());
            handleAction(room, p.getUserId(), canCheck ? ActionType.CHECK : ActionType.FOLD, 0, true);
        }, actionSecs(room));
        room.setActionTimeout(f);
    }

    private void cancelActionTimeout(DzRoom room) {
        if (room.getActionTimeout() != null) {
            room.getActionTimeout().cancel(false);
            room.setActionTimeout(null);
        }
    }

    // ================================================================
    // 换街 / 摊牌 / 结算
    // ================================================================

    private void collectBets(DzRoom room) {
        long sum = 0;
        for (DzPlayer p : room.getSeats()) {
            if (p == null) continue;
            sum += p.getBetThisRound();
            p.resetForStreet();
        }
        room.setCollectedPot(room.getCollectedPot() + sum);
        room.setActingSeat(-1);
    }

    private void advanceStreet(DzRoom room) {
        collectBets(room);
        GameStage cur = room.getStage();
        if (cur == GameStage.RIVER) {
            showdown(room);
            return;
        }
        // 保险(InsuranceRule):发河牌前,两人全下跑马 → 给领先方报价,拿到决定再发
        if (cur == GameStage.TURN && tryOfferInsurance(room)) {
            return;
        }
        int dealCount = (cur == GameStage.PREFLOP) ? 3 : 1;
        for (int i = 0; i < dealCount; i++) {
            room.getBoard().add(room.getDeck().deal());
        }
        GameStage next = switch (cur) {
            case PREFLOP -> GameStage.FLOP;
            case FLOP -> GameStage.TURN;
            default -> GameStage.RIVER;
        };
        room.setStage(next);
        room.setCurrentBet(0);
        room.setMinRaise(room.getBb());
        broadcaster.toRoom(room.getRoomId(), GameMessage.create(MsgType.DEAL, room.getRoomId(), Map.of(
                "stage", next.name(), "board", cardStrs(room.getBoard().toArray(new Card[0])),
                "pot", room.getCollectedPot())));

        long canAct = room.contestingPlayers().stream().filter(DzPlayer::canAct).count();
        if (canAct >= 2) {
            int first = room.nextSeat(room.getButton(), DzPlayer::canAct);
            setActing(room, first);
        } else {
            // 都全下了 → 跑马,给前端留出翻牌动画时间
            roomWorker.submitDelayMs(room.getRoomId(), () -> advanceStreet(room), 1200);
        }
    }

    // ================================================================
    // 保险(河牌保险,规则细节在 InsuranceRule)
    // ================================================================

    @Value("${dzpk.insurance-timeout-secs:12}")
    private int insuranceTimeoutSecs;

    /** 尝试报价。true=已挂起等领先方决定(advanceStreet 暂停),false=不满足条件继续发牌 */
    private boolean tryOfferInsurance(DzRoom room) {
        InsuranceRule.State st = room.getInsurance();
        if (st != null && st.handNo == room.getHandNo()) {
            return st.pending();  // 已决定过(买/放弃/超时)→ 不再报价
        }
        InsuranceRule.Offer offer = InsuranceRule.tryOffer(room);
        if (offer == null) return false;

        st = new InsuranceRule.State();
        st.handNo = room.getHandNo();
        st.offer = offer;
        st.deadline = System.currentTimeMillis() + insuranceTimeoutSecs * 1000L;
        room.setInsurance(st);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("leaderUserId", offer.leaderUserId);
        data.put("outs", offer.outs);
        data.put("outCards", offer.outCards);
        data.put("oddsX100", offer.oddsX100);
        data.put("maxInsure", offer.maxInsure);
        data.put("deadline", st.deadline);
        data.put("timeoutSecs", insuranceTimeoutSecs);
        // 全房广播(前端:领先方弹购买面板,其他人显示"保险决策中")
        broadcaster.toRoom(room.getRoomId(), GameMessage.create(MsgType.INSURANCE_OFFER, room.getRoomId(), data));
        log.info("保险报价: roomId={}, handNo={}, leader={}, outs={}, odds={}, maxInsure={}",
                room.getRoomId(), st.handNo, offer.leaderUserId, offer.outs, offer.oddsX100, offer.maxInsure);

        long handNo = st.handNo;
        ScheduledFuture<?> f = roomWorker.submitDelaySecs(room.getRoomId(), () -> {
            InsuranceRule.State cur = room.getInsurance();
            if (cur == null || cur.handNo != handNo || cur.decided) return;
            decideInsurance(room, cur, 0);  // 超时=放弃
        }, insuranceTimeoutSecs);
        room.setInsuranceTimeout(f);
        return true;
    }

    /** C→S 买保险(amount=0 放弃) */
    public void insuranceBuy(long roomId, long userId, long amount) {
        DzRoom room = roomManager.get(roomId);
        if (room == null) return;
        roomWorker.submit(roomId, () -> {
            InsuranceRule.State st = room.getInsurance();
            if (st == null || !st.pending() || st.handNo != room.getHandNo()) {
                sendError(userId, roomId, "当前没有可购买的保险");
                return;
            }
            if (st.offer.leaderUserId != userId) {
                sendError(userId, roomId, "只有领先方可以购买保险");
                return;
            }
            long insured = Math.max(0, Math.min(amount, st.offer.maxInsure));
            decideInsurance(room, st, insured);
        });
    }

    /** 落定保险决定并继续发河牌 */
    private void decideInsurance(DzRoom room, InsuranceRule.State st, long insured) {
        st.decided = true;
        st.insured = insured;
        st.premium = insured > 0 ? InsuranceRule.premium(insured, st.offer.outs) : 0;
        cancelInsurance(room);
        broadcaster.toRoom(room.getRoomId(), GameMessage.create(MsgType.INSURANCE_RESULT, room.getRoomId(), Map.of(
                "phase", "decided",
                "leaderUserId", st.offer.leaderUserId,
                "insured", st.insured, "premium", st.premium, "outs", st.offer.outs)));
        log.info("保险决定: roomId={}, leader={}, insured={}, premium={}",
                room.getRoomId(), st.offer.leaderUserId, st.insured, st.premium);
        advanceStreet(room);
    }

    /** 取消保险超时任务(不动 State) */
    private void cancelInsurance(DzRoom room) {
        if (room.getInsuranceTimeout() != null) {
            room.getInsuranceTimeout().cancel(false);
            room.setInsuranceTimeout(null);
        }
    }

    /**
     * 摊牌后套用保险(平台承保,不动底池):
     *   河牌是 out(被反超)→ 赔付投保额;守住 → 扣保费(不超过桌上筹码)。
     */
    private void applyInsurance(DzRoom room) {
        InsuranceRule.State st = room.getInsurance();
        if (st == null || st.handNo != room.getHandNo() || st.insured <= 0) return;
        DzPlayer leader = room.playerByUserId(st.offer.leaderUserId);
        if (leader == null) return;
        String river = room.getBoard().size() == 5 ? room.getBoard().get(4).toString() : "";
        boolean outHit = st.offer.outCards.contains(river);
        long delta;
        if (outHit) {
            delta = st.insured;
        } else {
            delta = -Math.min(st.premium, leader.getStack());
        }
        leader.setStack(leader.getStack() + delta);
        leader.setNetWin(leader.getNetWin() + delta);
        broadcaster.toRoom(room.getRoomId(), GameMessage.create(MsgType.INSURANCE_RESULT, room.getRoomId(), Map.of(
                "phase", "settled",
                "leaderUserId", leader.getUserId(),
                "outHit", outHit, "river", river,
                "insured", st.insured, "premium", st.premium, "delta", delta,
                "stack", leader.getStack())));
        log.info("保险结算: roomId={}, leader={}, outHit={}, delta={}",
                room.getRoomId(), leader.getUserId(), outHit, delta);
    }

    /** 只剩一家(其他全弃) — 不摊牌直接拿走 */
    private void winByFold(DzRoom room, DzPlayer winner) {
        cancelActionTimeout(room);
        cancelInsurance(room);
        collectBets(room);
        room.setStage(GameStage.SETTLING);
        long total = room.getCollectedPot();
        List<Map<String, Object>> results = new ArrayList<>();
        for (DzPlayer p : room.getSeats()) {
            if (p == null || !p.isInHand()) continue;
            long net = (p == winner) ? total - p.getTotalBetThisHand() : -p.getTotalBetThisHand();
            p.setNetWin(net);
            if (p == winner) p.setStack(p.getStack() + total);
            results.add(playerResult(p, false));
        }
        broadcaster.toRoom(room.getRoomId(), GameMessage.create(MsgType.SETTLE, room.getRoomId(), Map.of(
                "handNo", room.getHandNo(), "reason", "fold",
                "winnerUserId", winner != null ? winner.getUserId() : 0,
                "pot", total, "results", results)));
        finishHand(room);
    }

    private void showdown(DzRoom room) {
        cancelActionTimeout(room);
        room.setStage(GameStage.SHOWDOWN);

        List<DzPlayer> contesting = room.contestingPlayers();
        Card[] board = room.getBoard().toArray(new Card[0]);
        for (DzPlayer p : contesting) {
            Card[] seven = new Card[7];
            seven[0] = p.getHoleCards()[0];
            seven[1] = p.getHoleCards()[1];
            System.arraycopy(board, 0, seven, 2, 5);
            p.setHandResult(BiPai.evaluate(seven));
        }

        // 切池(死钱:局中弃牌先走的玩家投入,只进池不参与分池)
        List<PotManager.Contribution> contributions = new ArrayList<>(room.getDeadContributions());
        for (DzPlayer p : room.getSeats()) {
            if (p == null || p.getTotalBetThisHand() <= 0) continue;
            contributions.add(new PotManager.Contribution(p.getUserId(), p.getTotalBetThisHand(), !p.contesting()));
        }
        List<Pot> pots = PotManager.buildPots(contributions);
        room.setPots(pots);

        // 逐池找最大牌型平分
        List<Map<String, Object>> potResults = new ArrayList<>();
        for (Pot pot : pots) {
            List<DzPlayer> eligible = new ArrayList<>();
            for (long uid : pot.getEligibleUserIds()) {
                DzPlayer p = room.playerByUserId(uid);
                if (p != null && p.getHandResult() != null) eligible.add(p);
            }
            if (eligible.isEmpty()) continue;
            List<DzPlayer> winners = new ArrayList<>();
            winners.add(eligible.get(0));
            for (int i = 1; i < eligible.size(); i++) {
                int c = BiPai.compare(eligible.get(i).getHandResult(), winners.get(0).getHandResult());
                if (c == 0) {          // 新的更大
                    winners.clear();
                    winners.add(eligible.get(i));
                } else if (c == -1) {  // 一样大
                    winners.add(eligible.get(i));
                }
            }
            long share = pot.getAmount() / winners.size();
            long remainder = pot.getAmount() - share * winners.size();
            List<Long> winnerIds = new ArrayList<>();
            for (DzPlayer w : winners) {
                long got = share + (remainder > 0 ? 1 : 0);
                if (remainder > 0) remainder--;
                w.setStack(w.getStack() + got);
                w.setNetWin(w.getNetWin() + got);
                winnerIds.add(w.getUserId());
            }
            pot.getWinnerUserIds().addAll(winnerIds);
            potResults.add(Map.of("amount", pot.getAmount(), "winners", winnerIds));
        }
        // 净输赢 = 赢得 - 投入
        for (DzPlayer p : room.getSeats()) {
            if (p == null || !p.isInHand()) continue;
            p.setNetWin(p.getNetWin() - p.getTotalBetThisHand());
        }

        // 保险结算(InsuranceRule):被反超赔付 / 守住扣保费
        applyInsurance(room);

        room.setStage(GameStage.SETTLING);
        List<Map<String, Object>> results = new ArrayList<>();
        for (DzPlayer p : room.getSeats()) {
            if (p == null || !p.isInHand()) continue;
            // 埋牌(MuckRule):开着只亮赢家,关着摊牌都亮
            results.add(playerResult(p, MuckRule.shouldShow(room, p, pots)));
        }
        broadcaster.toRoom(room.getRoomId(), GameMessage.create(MsgType.SETTLE, room.getRoomId(), Map.of(
                "handNo", room.getHandNo(), "reason", "showdown",
                "board", cardStrs(board), "pots", potResults, "results", results)));
        finishHand(room);
    }

    private Map<String, Object> playerResult(DzPlayer p, boolean showCards) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("userId", p.getUserId());
        m.put("seat", p.getSeat());
        m.put("netWin", p.getNetWin());
        m.put("stack", p.getStack());
        m.put("folded", p.isFolded());
        if (showCards && p.getHoleCards() != null) {
            m.put("cards", cardStrs(p.getHoleCards()));
            if (p.getHandResult() != null) {
                m.put("handType", p.getHandResult().getType());
                m.put("handName", p.getHandResult().typeName());
                m.put("best5", cardStrs(p.getHandResult().getBest5()));
            }
        }
        return m;
    }

    // ================================================================
    // 一手结束:计数 → 局间意愿 → 周期结算 → 下一手
    // ================================================================

    private void finishHand(DzRoom room) {
        room.setStage(GameStage.FINISHED);
        cancelInsurance(room);
        long roomId = room.getRoomId();

        // 每手战绩落库(参与者每人一行;局中先走的已由 doStandUp 单独补行)
        records.saveHandRecords(room);

        for (DzPlayer p : room.getSeats().clone()) {
            if (p == null) continue;
            if (p.isInHand()) {
                p.setHandCount(p.getHandCount() + 1);
                if (p.getNetWin() > 0) p.setWinCount(p.getWinCount() + 1);
                else if (p.getNetWin() < 0) p.setLoseCount(p.getLoseCount() + 1);
            }
            // 局间意愿:追加带入
            if (p.getPendingBuyin() > 0) {
                p.setStack(p.getStack() + p.getPendingBuyin());
                broadcaster.toRoom(roomId, GameMessage.create(MsgType.BUY_IN_RES, roomId,
                        Map.of("userId", p.getUserId(), "amount", p.getPendingBuyin(),
                                "applied", true, "stack", p.getStack(),
                                "balance", walletService.balance(p.getUserId()))));
                p.setPendingBuyin(0);
            }
            // 局间意愿:站起
            if (p.isPendingStandUp()) {
                doStandUp(room, p, "standup");
                continue;
            }
            // ⭐ 周期结算(循环玩法核心):累计游戏时间到期 → 结算不离座
            if (room.getSettleTimeMins() > 0 && p.effectiveMs() >= room.getSettleTimeMins() * 60_000L) {
                periodSettle(room, p);
                continue;
            }
            // 打光了 → 进补带入等待(不带就自动站起)
            if (p.getStack() <= 0 && !p.isAwaitingBuyin()) {
                enterAwaitBuyin(room, p, Map.of("reason", "busted"));
            }
        }

        broadcaster.toRoom(roomId, GameMessage.create(MsgType.ROOM_STATE, roomId, Map.of(
                "stage", GameStage.FINISHED.name(), "nextHandDelaySecs", nextHandDelaySecs)));

        if (room.readyPlayers().size() >= 2 && !room.isHandScheduled()) {
            room.setHandScheduled(true);
            roomWorker.submitDelaySecs(roomId, () -> startHand(room), nextHandDelaySecs);
        } else if (room.readyPlayers().size() < 2) {
            room.setStage(GameStage.WAITING);
            broadcaster.toRoom(roomId, GameMessage.create(MsgType.ROOM_STATE, roomId,
                    Map.of("stage", GameStage.WAITING.name())));
        }
    }

    /**
     * 周期结算(结算重写核心) — 玩家不离座,但本周期真结算:
     *   关闸 → 盈利抽水 → 剩余筹码全额退回钱包、桌面清零 → 周期计数清零(seq+1)
     *   → 进补带入等待:重新带入开新周期,超时自动站起。
     */
    private void periodSettle(DzRoom room, DzPlayer p) {
        p.closeGate();
        long playedMs = p.getGameTimeAccumMs();
        long bringIn = p.getBringInThisPeriod();
        long stack = p.getStack();
        long profit = stack - bringIn;
        long rake = applyRake(room, p, profit);
        long refund = stack - rake;
        if (refund > 0) {
            walletService.credit(p.getUserId(), refund);
        }
        p.setStack(0);
        records.saveSettleRecord(room, p, "period", bringIn, stack, profit, rake, refund, playedMs / 1000);
        log.info("周期结算: roomId={}, userId={}, played={}s, bringIn={}, stack={}, profit={}, rake={}, refund={}",
                room.getRoomId(), p.getUserId(), playedMs / 1000, bringIn, stack, profit, rake, refund);

        Map<String, Object> panel = new LinkedHashMap<>();
        panel.put("reason", "period");
        panel.put("periodSeq", p.getSettlePeriodSeq());
        panel.put("playedSecs", playedMs / 1000);
        panel.put("bringIn", bringIn);
        panel.put("finalStack", stack);
        panel.put("profit", profit);
        panel.put("rake", rake);
        panel.put("refund", refund);
        panel.put("handCount", p.getHandCount());
        panel.put("winCount", p.getWinCount());
        panel.put("loseCount", p.getLoseCount());
        panel.put("balance", walletService.balance(p.getUserId()));

        p.resetPeriod();  // 新周期:计时/带入/计数清零,seq+1
        enterAwaitBuyin(room, p, panel);
    }

    /** 进补带入等待:awaitBuyinSecs 内重新带入继续打,否则自动站起 */
    private void enterAwaitBuyin(DzRoom room, DzPlayer p, Map<String, Object> panelData) {
        p.setAwaitingBuyin(true);
        long deadline = System.currentTimeMillis() + awaitBuyinSecs * 1000L;
        p.setAwaitBuyinDeadline(deadline);

        Map<String, Object> data = new LinkedHashMap<>(panelData);
        data.put("userId", p.getUserId());
        data.put("seat", p.getSeat());
        data.put("awaitBuyinSecs", awaitBuyinSecs);
        data.put("deadline", deadline);
        broadcaster.toRoom(room.getRoomId(), GameMessage.create(MsgType.PERIOD_SETTLE, room.getRoomId(), data));

        long userId = p.getUserId();
        roomWorker.submitDelaySecs(room.getRoomId(), () -> {
            DzPlayer cur = room.playerByUserId(userId);
            if (cur != null && cur.isAwaitingBuyin() && cur.getStack() <= 0) {
                log.info("补带入超时自动站起: roomId={}, userId={}", room.getRoomId(), userId);
                doStandUp(room, cur, "buyin_timeout");
            }
        }, awaitBuyinSecs);
    }

    /**
     * 真正站起 — 结清本周期(盈利抽水→退钱包→写 dz_settle_record)后离座。
     * 牌局中未弃牌不允许直接到这(standUp 入口拦成 pending);
     * 只有 leaveRoom(离房)会强制走:先弃牌,若这手还没完则转 pending 由局末处理。
     */
    private void doStandUp(DzRoom room, DzPlayer p, String reason) {
        // 离房强制路径:牌局中未弃牌 → 先弃牌;正轮到他行动则推进牌局
        if (room.inGame() && p.isInHand() && !p.isFolded()) {
            if (room.getActingSeat() == p.getSeat()) {
                handleAction(room, p.getUserId(), ActionType.FOLD, 0, true);
            } else {
                p.setFolded(true);
            }
            // 本手投入的筹码留在池里,手打完由 finishHand 的 pendingStandUp 真正站起
            if (room.inGame()) {
                p.setPendingStandUp(true);
                return;
            }
        }

        // 局中已弃牌立即站起:座位要清,但投入是死钱必须留在池里,战绩也补上这一手
        if (room.inGame() && p.isInHand()) {
            room.setCollectedPot(room.getCollectedPot() + p.getBetThisRound());
            if (p.getTotalBetThisHand() > 0) {
                room.getDeadContributions().add(
                        new PotManager.Contribution(p.getUserId(), p.getTotalBetThisHand(), true));
            }
            p.setNetWin(-p.getTotalBetThisHand());
            p.setHandCount(p.getHandCount() + 1);
            if (p.getNetWin() < 0) p.setLoseCount(p.getLoseCount() + 1);
            records.saveHandRecordForLeaver(room, p);
            p.setBetThisRound(0);
        }

        p.closeGate();
        long playedSecs = p.getGameTimeAccumMs() / 1000;
        long stack = p.getStack();
        long bringIn = p.getBringInThisPeriod();
        long profit = stack - bringIn;
        long rake = applyRake(room, p, profit);
        long refund = stack - rake;
        if (refund > 0) {
            walletService.credit(p.getUserId(), refund);
        }
        // 空周期(周期结算后没再带入就走)不写记录
        if (bringIn > 0 || stack > 0 || p.getHandCount() > 0) {
            records.saveSettleRecord(room, p, reason, bringIn, stack, profit, rake, refund, playedSecs);
        }
        room.getSeats()[p.getSeat()] = null;

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("userId", p.getUserId());
        data.put("seat", p.getSeat());
        data.put("reason", reason);
        data.put("bringIn", bringIn);
        data.put("finalStack", stack);
        data.put("profit", profit);
        data.put("rake", rake);
        data.put("refund", refund);
        data.put("handCount", p.getHandCount());
        data.put("winCount", p.getWinCount());
        data.put("loseCount", p.getLoseCount());
        data.put("playedSecs", playedSecs);
        data.put("balance", walletService.balance(p.getUserId()));
        broadcaster.toRoom(room.getRoomId(), GameMessage.create(MsgType.PLAYER_STAND, room.getRoomId(), data));
        log.info("站起: roomId={}, userId={}, reason={}, bringIn={}, stack={}, profit={}, rake={}, refund={}",
                room.getRoomId(), p.getUserId(), reason, bringIn, stack, profit, rake, refund);
    }

    /**
     * 计算并落地抽水(对齐扯旋 applyCommissionOnStandUp):
     *   盈利 ≤ 0 不抽;俱乐部房群主免抽;
     *   俱乐部房的抽水沿推荐链分给 群主/合伙人(多层按 partner_rate 让利);
     *   公开房抽水归平台(不分配)。
     */
    private long applyRake(DzRoom room, DzPlayer p, long profit) {
        if (profit <= 0 || room.getRakePercent() <= 0) return 0;
        boolean clubRoom = room.getClubId() > 0 && clubService != null;
        if (clubRoom && clubService.isOwner(room.getClubId(), p.getUserId())) {
            return 0;
        }
        long rake = profit * room.getRakePercent() / 100;
        if (rake > 0 && clubRoom) {
            clubService.distributeRake(room.getClubId(), room.getRoomId(), p.getUserId(), profit, rake);
        }
        return rake;
    }

    // ================================================================
    // 工具
    // ================================================================

    private long pay(DzPlayer p, long amount) {
        long actual = Math.min(amount, p.getStack());
        p.setStack(p.getStack() - actual);
        p.setBetThisRound(p.getBetThisRound() + actual);
        p.setTotalBetThisHand(p.getTotalBetThisHand() + actual);
        if (p.getStack() == 0) {
            p.setAllIn(true);
        }
        return actual;
    }

    private boolean isBettingStage(GameStage s) {
        return s == GameStage.PREFLOP || s == GameStage.FLOP || s == GameStage.TURN || s == GameStage.RIVER;
    }

    private void sendError(long userId, long roomId, String msg) {
        broadcaster.toUser(userId, GameMessage.create(MsgType.ERROR, roomId, Map.of("msg", msg)));
    }

    private List<String> cardStrs(Card[] cards) {
        List<String> list = new ArrayList<>(cards.length);
        for (Card c : cards) {
            list.add(c.toString());
        }
        return list;
    }

    private List<Map<String, Object>> seatBrief(DzRoom room) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (DzPlayer p : room.getSeats()) {
            if (p == null) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("userId", p.getUserId());
            m.put("nickname", p.getNickname());
            m.put("seat", p.getSeat());
            m.put("stack", p.getStack());
            m.put("inHand", p.isInHand());
            m.put("betThisRound", p.getBetThisRound());
            list.add(m);
        }
        return list;
    }

    /** 房间全量快照(重连/进房用),只带自己的手牌 */
    public Map<String, Object> snapshot(DzRoom room, long forUserId) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("roomId", room.getRoomId());
        m.put("name", room.getName());
        m.put("clubId", room.getClubId());
        m.put("sb", room.getSb());
        m.put("bb", room.getBb());
        m.put("maxPlayers", room.getMaxPlayers());
        m.put("minBuyin", room.getMinBuyin());
        m.put("maxBuyin", room.getMaxBuyin());
        m.put("settleTimeMins", room.getSettleTimeMins());
        m.put("rakePercent", room.getRakePercent());
        if (room.getRules() != null) {
            m.put("rules", room.getRules().toMap());
        }
        InsuranceRule.State ins = room.getInsurance();
        if (ins != null && ins.pending()) {
            m.put("insurance", Map.of(
                    "leaderUserId", ins.offer.leaderUserId,
                    "outs", ins.offer.outs, "oddsX100", ins.offer.oddsX100,
                    "maxInsure", ins.offer.maxInsure, "deadline", ins.deadline));
        }
        m.put("stage", room.getStage().name());
        m.put("handNo", room.getHandNo());
        m.put("button", room.getButton());
        m.put("board", cardStrs(room.getBoard().toArray(new Card[0])));
        m.put("pot", room.displayPot());
        m.put("currentBet", room.getCurrentBet());
        m.put("actingSeat", room.getActingSeat());
        m.put("actionDeadline", room.getActionDeadline());

        List<Map<String, Object>> seats = new ArrayList<>();
        for (DzPlayer p : room.getSeats()) {
            if (p == null) continue;
            Map<String, Object> pm = new LinkedHashMap<>();
            pm.put("userId", p.getUserId());
            pm.put("nickname", p.getNickname());
            pm.put("seat", p.getSeat());
            pm.put("stack", p.getStack());
            pm.put("inHand", p.isInHand());
            pm.put("folded", p.isFolded());
            pm.put("allIn", p.isAllIn());
            pm.put("betThisRound", p.getBetThisRound());
            pm.put("awaitingBuyin", p.isAwaitingBuyin());
            pm.put("offline", p.isOffline());
            pm.put("effectiveSecs", p.effectiveMs() / 1000);
            if (room.getRules() != null && room.getRules().isVpOn()) {
                pm.put("vpip", p.getVpipCount());
                pm.put("handCount", p.getHandCount());
            }
            if (p.getUserId() == forUserId && p.getHoleCards() != null) {
                pm.put("cards", cardStrs(p.getHoleCards()));
            }
            seats.add(pm);
        }
        m.put("seats", seats);
        return m;
    }
}
