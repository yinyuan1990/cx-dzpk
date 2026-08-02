package com.chexuan.dzpk.game.service;

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

    @Value("${dzpk.action-timeout-secs:15}")
    private int actionTimeoutSecs;

    @Value("${dzpk.next-hand-delay-secs:4}")
    private int nextHandDelaySecs;

    @Value("${dzpk.await-buyin-secs:30}")
    private int awaitBuyinSecs;

    public DzGameService(DzRoomManager roomManager, RoomWorkerService roomWorker,
                         WalletService walletService, GameBroadcaster broadcaster) {
        this.roomManager = roomManager;
        this.roomWorker = roomWorker;
        this.walletService = walletService;
        this.broadcaster = broadcaster;
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
                log.info("房间清空销毁: roomId={}", roomId);
            }
        });
    }

    public void sitDown(long roomId, long userId, int seat) {
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
            DzPlayer p = new DzPlayer();
            p.setUserId(userId);
            p.setNickname(room.getMembers().get(userId));
            p.setSeat(seat);
            p.setStack(0);
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

    public void standUp(long roomId, long userId) {
        DzRoom room = roomManager.get(roomId);
        if (room == null) return;
        roomWorker.submit(roomId, () -> {
            DzPlayer p = room.playerByUserId(userId);
            if (p == null) return;
            doStandUp(room, p, "standup");
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
        if (room.readyPlayers().size() >= 2) {
            room.setHandScheduled(true);
            roomWorker.submitDelaySecs(room.getRoomId(), () -> startHand(room), nextHandDelaySecs);
        }
    }

    private void startHand(DzRoom room) {
        room.setHandScheduled(false);
        if (room.inGame()) return;
        List<DzPlayer> ready = room.readyPlayers();
        if (ready.size() < 2) {
            room.setStage(GameStage.WAITING);
            broadcaster.toRoom(room.getRoomId(), GameMessage.create(MsgType.ROOM_STATE, room.getRoomId(),
                    Map.of("stage", GameStage.WAITING.name())));
            return;
        }

        room.setHandNo(room.getHandNo() + 1);
        room.getBoard().clear();
        room.setPots(new ArrayList<>());
        room.setCollectedPot(0);
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

        // 盲注
        pay(room.playerAtSeat(sbSeat), room.getSb());
        pay(room.playerAtSeat(bbSeat), room.getBb());
        room.setCurrentBet(room.getBb());
        room.setMinRaise(room.getBb());

        // 发手牌
        for (DzPlayer p : ready) {
            p.setHoleCards(new Card[]{room.getDeck().deal(), room.getDeck().deal()});
        }

        room.setStage(GameStage.PREFLOP);
        broadcaster.toRoom(room.getRoomId(), GameMessage.create(MsgType.HAND_START, room.getRoomId(), Map.of(
                "handNo", room.getHandNo(),
                "button", button, "sbSeat", sbSeat, "bbSeat", bbSeat,
                "sb", room.getSb(), "bb", room.getBb(),
                "players", seatBrief(room))));
        for (DzPlayer p : ready) {
            broadcaster.toUser(p.getUserId(), GameMessage.create(MsgType.HOLE_CARDS, room.getRoomId(), Map.of(
                    "handNo", room.getHandNo(),
                    "cards", cardStrs(p.getHoleCards()))));
        }

        int first = room.nextSeat(bbSeat, DzPlayer::canAct);
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

    private void setActing(DzRoom room, int seat) {
        room.setActingSeat(seat);
        DzPlayer p = room.playerAtSeat(seat);
        long deadline = System.currentTimeMillis() + actionTimeoutSecs * 1000L;
        room.setActionDeadline(deadline);
        broadcaster.toRoom(room.getRoomId(), GameMessage.create(MsgType.TURN, room.getRoomId(), Map.of(
                "seat", seat, "userId", p.getUserId(),
                "toCall", Math.max(0, room.getCurrentBet() - p.getBetThisRound()),
                "minRaiseTo", room.getCurrentBet() + room.getMinRaise(),
                "deadline", deadline, "timeoutSecs", actionTimeoutSecs)));
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
        }, actionTimeoutSecs);
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

    /** 只剩一家(其他全弃) — 不摊牌直接拿走 */
    private void winByFold(DzRoom room, DzPlayer winner) {
        cancelActionTimeout(room);
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

        // 切池
        List<PotManager.Contribution> contributions = new ArrayList<>();
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
            potResults.add(Map.of("amount", pot.getAmount(), "winners", winnerIds));
        }
        // 净输赢 = 赢得 - 投入
        for (DzPlayer p : room.getSeats()) {
            if (p == null || !p.isInHand()) continue;
            p.setNetWin(p.getNetWin() - p.getTotalBetThisHand());
        }

        room.setStage(GameStage.SETTLING);
        List<Map<String, Object>> results = new ArrayList<>();
        for (DzPlayer p : room.getSeats()) {
            if (p == null || !p.isInHand()) continue;
            results.add(playerResult(p, p.contesting()));
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
        long roomId = room.getRoomId();

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
        long rake = (profit > 0 && room.getRakePercent() > 0) ? profit * room.getRakePercent() / 100 : 0;
        long refund = stack - rake;
        if (refund > 0) {
            walletService.credit(p.getUserId(), refund);
        }
        p.setStack(0);
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
     * 站起 — 结清本周期(盈利抽水)后离座。牌局中站起先弃牌。
     */
    private void doStandUp(DzRoom room, DzPlayer p, String reason) {
        // 牌局中 → 先弃牌;正轮到他行动则推进牌局
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

        p.closeGate();
        long stack = p.getStack();
        long profit = stack - p.getBringInThisPeriod();
        long rake = (profit > 0 && room.getRakePercent() > 0) ? profit * room.getRakePercent() / 100 : 0;
        long refund = stack - rake;
        if (refund > 0) {
            walletService.credit(p.getUserId(), refund);
        }
        room.getSeats()[p.getSeat()] = null;
        broadcaster.toRoom(room.getRoomId(), GameMessage.create(MsgType.PLAYER_STAND, room.getRoomId(), Map.of(
                "userId", p.getUserId(), "seat", p.getSeat(), "reason", reason,
                "refund", refund, "rake", rake,
                "balance", walletService.balance(p.getUserId()))));
        log.info("站起: roomId={}, userId={}, reason={}, refund={}, rake={}",
                room.getRoomId(), p.getUserId(), reason, refund, rake);
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
        m.put("sb", room.getSb());
        m.put("bb", room.getBb());
        m.put("maxPlayers", room.getMaxPlayers());
        m.put("minBuyin", room.getMinBuyin());
        m.put("maxBuyin", room.getMaxBuyin());
        m.put("settleTimeMins", room.getSettleTimeMins());
        m.put("rakePercent", room.getRakePercent());
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
            if (p.getUserId() == forUserId && p.getHoleCards() != null) {
                pm.put("cards", cardStrs(p.getHoleCards()));
            }
            seats.add(pm);
        }
        m.put("seats", seats);
        return m;
    }
}
