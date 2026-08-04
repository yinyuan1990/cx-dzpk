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
    /** 钻石服务(圈主周期扣钻);单测可为 null */
    private final com.chexuan.dzpk.db.DiamondService diamondService;
    /** 系统参数中心(管理后台在线调参);单测可为 null(退回 @Value 默认) */
    private final com.chexuan.dzpk.config.DzConfigService cfg;

    /** GPS 防火牌(gpsLimitOn 桌坐下校验);字段注入避免改全部构造器,单测为 null 跳过 */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private GpsService gpsService;

    @Value("${dzpk.action-timeout-secs:15}")
    private int actionTimeoutSecs;

    @Value("${dzpk.next-hand-delay-secs:4}")
    private int nextHandDelaySecs;

    /** 周期结算后补带入窗口(对齐扯旋 period_settle_bringin_seconds=10) */
    @Value("${dzpk.await-buyin-secs:10}")
    private int awaitBuyinSecs;

    /** 留座暂离(放假)倒计时(对齐扯旋 seat_reserve_grace_seconds=300) */
    @Value("${dzpk.seat-reserve-grace-secs:300}")
    private int seatReserveGraceSecs;

    /** 放假超时站起后物理锁座时长(对齐扯旋 seat_reserve_seconds=480) */
    @Value("${dzpk.seat-lock-secs:480}")
    private int seatLockSecs;

    /** 赢家早退过路费率%(对齐扯旋 winner_early_leave_rate=30) */
    @Value("${dzpk.winner-early-leave-rate:30}")
    private int winnerEarlyLeaveRate;

    /** 逃跑罚金总开关(对齐扯旋 run_away_enabled 默认关) */
    @Value("${dzpk.run-away-enabled:false}")
    private boolean runAwayEnabled;

    /** 逃跑罚金:本周期累计离线时长阈值(分钟) */
    @Value("${dzpk.run-away-time-mins:6}")
    private int runAwayTimeMins;

    /** 逃跑罚金率% */
    @Value("${dzpk.run-away-penalty-rate:30}")
    private int runAwayPenaltyRate;

    /** 圈主周期服务费(钻石,对齐扯旋 commission_settle_diamond_cost=5;0=不扣) */
    @Value("${dzpk.owner-period-diamond-cost:5}")
    private long ownerPeriodDiamondCost;

    @org.springframework.beans.factory.annotation.Autowired
    public DzGameService(DzRoomManager roomManager, RoomWorkerService roomWorker,
                         WalletService walletService, GameBroadcaster broadcaster,
                         DzRecordStore records, com.chexuan.dzpk.club.DzClubService clubService,
                         com.chexuan.dzpk.db.DiamondService diamondService,
                         com.chexuan.dzpk.config.DzConfigService cfg) {
        this.roomManager = roomManager;
        this.roomWorker = roomWorker;
        this.walletService = walletService;
        this.broadcaster = broadcaster;
        this.records = records;
        this.clubService = clubService;
        this.diamondService = diamondService;
        this.cfg = cfg;
    }

    /** 单测用:不落库、无俱乐部、不扣钻 */
    public DzGameService(DzRoomManager roomManager, RoomWorkerService roomWorker,
                         WalletService walletService, GameBroadcaster broadcaster) {
        this(roomManager, roomWorker, walletService, broadcaster, new DzRecordStore(null), null, null, null);
    }

    /** 单测用:带俱乐部(罚金/分成测试) */
    public DzGameService(DzRoomManager roomManager, RoomWorkerService roomWorker,
                         WalletService walletService, GameBroadcaster broadcaster,
                         com.chexuan.dzpk.club.DzClubService clubService) {
        this(roomManager, roomWorker, walletService, broadcaster, new DzRecordStore(null), clubService, null, null);
    }

    // ==================== 参数读取(管理后台可在线调,DB 值优先) ====================

    private int cfgInt(String key, int def) {
        return cfg != null ? cfg.getInt(key, def) : def;
    }

    private long cfgLong(String key, long def) {
        return cfg != null ? cfg.getLong(key, def) : def;
    }

    private boolean cfgBool(String key, boolean def) {
        return cfg != null ? cfg.getBool(key, def) : def;
    }

    private int nextHandDelay() {
        return cfgInt("next_hand_delay_secs", nextHandDelaySecs);
    }

    private int awaitBuyin() {
        return cfgInt("await_buyin_secs", awaitBuyinSecs);
    }

    private int graceSecs() {
        return cfgInt("seat_reserve_grace_secs", seatReserveGraceSecs);
    }

    private int lockSecs() {
        return cfgInt("seat_lock_secs", seatLockSecs);
    }

    private long ownerDiamondCost() {
        return cfgLong("owner_period_diamond_cost", ownerPeriodDiamondCost);
    }

    // ==================== 经济路由(积分按俱乐部独立) ====================
    // 俱乐部房:带入/退筹/罚金/分成走该俱乐部的成员积分(dz_club_member.score);
    // 公开房:走德州金币钱包(dz_user_wallet)。钻石全平台公用,与此无关。

    private boolean clubEconomy(DzRoom room) {
        return room.getClubId() > 0 && clubService != null;
    }

    /** 带入扣款,false=余额/积分不足 */
    private boolean debitBring(DzRoom room, long userId, long amount) {
        // 机器人不走经济体系(筹码是"空气",带入/退筹都不入账,不污染俱乐部积分/钱包)
        if (com.chexuan.dzpk.robot.RobotService.isRobotId(userId)) return true;
        if (clubEconomy(room)) {
            return clubService.debitScoreForGame(room.getClubId(), userId, amount, room.getRoomId());
        }
        return walletService.debit(userId, amount);
    }

    /** 退筹/罚金入账(scoreType: 31退筹 32罚金收) */
    private void creditBack(DzRoom room, long userId, long amount, int scoreType, String remark) {
        if (amount <= 0) return;
        if (com.chexuan.dzpk.robot.RobotService.isRobotId(userId)) return;
        if (clubEconomy(room)) {
            clubService.creditScoreForGame(room.getClubId(), userId, amount, scoreType, remark);
        } else {
            walletService.credit(userId, amount);
        }
    }

    /** 玩家在本房间语境下的余额(俱乐部积分或金币) */
    private long economyBalance(DzRoom room, long userId) {
        if (clubEconomy(room)) {
            return clubService.score(room.getClubId(), userId);
        }
        return walletService.balance(userId);
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
            // 重连:在座且离线 → 标回线(对齐扯旋207;不自动退出放假,须自己点回桌)
            DzPlayer seated = room.playerByUserId(userId);
            if (seated != null && seated.isOffline()) {
                seated.setOffline(false);
                if (seated.getOfflineSince() > 0) {
                    seated.setLeaveAccumMs(seated.getLeaveAccumMs()
                            + Math.max(0, System.currentTimeMillis() - seated.getOfflineSince()));
                    seated.setOfflineSince(0);
                }
                if (!seated.inGrace()) {
                    seated.setVacationPending(false);
                }
                broadcaster.toRoom(roomId, GameMessage.create(MsgType.PLAYER_ONLINE, roomId,
                        Map.of("userId", userId, "seat", seated.getSeat())));
            }
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
            // 大厅散房(clubId=0)没人就销毁;俱乐部房对齐扯旋 WAITING 长期存活,
            // 只走「最后一人站起条件自动解散」(maybeAutoDisbandClubRoom)或手动解散
            if (room.getClubId() == 0 && room.getMembers().isEmpty() && !room.inGame()) {
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
        sitDown(roomId, userId, seat, ip, "");
    }

    /** avatar 头像 URL(主服账号,坐下广播/快照带给全桌) */
    public void sitDown(long roomId, long userId, int seat, String ip, String avatar) {
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
            // 物理锁座(放假超时站起后保留给本人,对齐扯旋281)
            long lockedBy = room.seatLockedBy(seat);
            if (lockedBy != 0 && lockedBy != userId) {
                sendError(userId, roomId, "该座位为暂离玩家保留");
                return;
            }
            // 俱乐部房:仅成员可坐;群主/管理员不能玩(对齐扯旋 2026-07-25);群主钻石不足拒坐
            // 机器人豁免(管理台一键生成陪打,不占俱乐部成员/积分体系)
            boolean robotSit = com.chexuan.dzpk.robot.RobotService.isRobotId(userId);
            if (room.getClubId() > 0 && clubService != null && !robotSit) {
                if (!clubService.isMember(room.getClubId(), userId)) {
                    sendError(userId, roomId, "你不是该俱乐部成员,无法坐下");
                    return;
                }
                if (clubService.canCreateRoom(room.getClubId(), userId)) {
                    sendError(userId, roomId, "群主和管理员不能参与本俱乐部游戏");
                    return;
                }
                long sitCost = diamondService != null && room.getSettleTimeMins() > 0
                        ? ownerDiamondCostFor(room) : 0;
                if (sitCost > 0) {
                    long owner = clubService.ownerUserId(room.getClubId());
                    if (owner > 0 && diamondService.hasMainAccount(owner)
                            && diamondService.balance(owner) < sitCost) {
                        sendError(userId, roomId, "群主钻石不足,暂时无法坐下,请联系群主充值");
                        return;
                    }
                }
            }
            String deny = AccessRule.checkSit(room, ip);
            if (deny != null) {
                sendError(userId, roomId, deny);
                return;
            }
            // GPS 防火牌(对齐扯旋):开了 gpsLimitOn 的桌,与桌上每人距离须达标(机器人无 GPS,豁免)
            if (!robotSit && room.getRules() != null && room.getRules().isGpsLimitOn() && gpsService != null) {
                double minM = cfgLong("gps_min_distance_m", 100);
                long maxAgeMs = cfgLong("gps_max_age_secs", 90) * 1000L;
                for (DzPlayer sp : room.getSeats()) {
                    if (sp == null || sp.getUserId() == userId) continue;
                    if (com.chexuan.dzpk.robot.RobotService.isRobotId(sp.getUserId())) continue;
                    String gpsDeny = gpsService.checkPair(userId, sp.getUserId(), minM, maxAgeMs);
                    if (gpsDeny != null) {
                        sendError(userId, roomId, gpsDeny);
                        return;
                    }
                }
            }
            room.getSeatLocks().remove(seat);
            DzPlayer p = new DzPlayer();
            p.setUserId(userId);
            p.setNickname(room.getMembers().get(userId));
            p.setAvatar(avatar == null ? "" : avatar);
            p.setSeat(seat);
            p.setStack(0);
            p.setIp(ip);
            room.getSeats()[seat] = p;
            broadcaster.toRoom(roomId, GameMessage.create(MsgType.PLAYER_SIT, roomId,
                    Map.of("userId", userId, "nickname", p.getNickname(), "seat", seat,
                            "avatar", p.getAvatar(),
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
            if (!debitBring(room, userId, amount)) {
                sendError(userId, roomId, clubEconomy(room) ? "俱乐部积分不足,请联系群主上分" : "余额不足");
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
                            "stack", p.getStack(), "balance", economyBalance(room, userId))));
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
        standUp(roomId, userId, false);
    }

    /** confirmFine=true 表示前端已确认罚金(对齐扯旋 ack code=92 → confirmFine 重发) */
    public void standUp(long roomId, long userId, boolean confirmFine) {
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
            // 赢家离桌罚金:先报价,前端确认(confirmFine)后才真正站起
            long fineEst = estimateLeaveFines(room, p);
            if (fineEst > 0 && !confirmFine) {
                broadcaster.toUser(userId, GameMessage.create(MsgType.STAND_UP_RES, roomId, Map.of(
                        "status", 92, "pending", false, "fine", fineEst,
                        "msg", "盈利离桌将扣除罚金 " + fineEst + ",确认后继续")));
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

    // ================================================================
    // 留座暂离/放假(对齐扯旋 122/123/282)
    // ================================================================

    /** 留座暂离:空闲/已弃牌立即进放假;牌局中未弃牌先记 pending,弃牌或局末生效 */
    public void seatReserveLeave(long roomId, long userId) {
        DzRoom room = roomManager.get(roomId);
        if (room == null) return;
        roomWorker.submit(roomId, () -> {
            DzPlayer p = room.playerByUserId(userId);
            if (p == null) {
                sendError(userId, roomId, "不在座位上");
                return;
            }
            if (p.inGrace()) {
                sendError(userId, roomId, "已在暂离中");
                return;
            }
            if (p.isSeatReserveUsed()) {
                sendError(userId, roomId, "本结算周期只能暂离一次");
                return;
            }
            if (room.inGame() && p.isInHand() && !p.isFolded()) {
                p.setManualLeavePending(true);
                broadcaster.toUser(userId, GameMessage.create(MsgType.SEAT_RESERVE_GRACE, roomId, Map.of(
                        "userId", userId, "seat", p.getSeat(), "state", "PENDING",
                        "msg", "弃牌或本手结束后自动暂离")));
                return;
            }
            enterGrace(room, p, "MANUAL");
        });
    }

    /** 回到座位(重连不会自动回,必须主动发,对齐扯旋) */
    public void seatReserveResume(long roomId, long userId) {
        DzRoom room = roomManager.get(roomId);
        if (room == null) return;
        roomWorker.submit(roomId, () -> {
            DzPlayer p = room.playerByUserId(userId);
            if (p == null || !p.inGrace()) {
                if (p != null && p.isManualLeavePending()) {
                    p.setManualLeavePending(false);
                    broadcaster.toUser(userId, GameMessage.create(MsgType.SEAT_RESERVE_GRACE, roomId, Map.of(
                            "userId", userId, "seat", p.getSeat(), "state", "NONE", "reason", "RESUME")));
                    return;
                }
                sendError(userId, roomId, "未在暂离中");
                return;
            }
            cancelGraceTimer(p);
            p.setSittingOut(false);
            p.setSeatReserveDeadline(0);
            broadcaster.toRoom(roomId, GameMessage.create(MsgType.SEAT_RESERVE_GRACE, roomId, Map.of(
                    "userId", userId, "seat", p.getSeat(), "state", "NONE", "reason", "RESUME")));
            log.info("回到座位: roomId={}, userId={}", roomId, userId);
            tryStartHand(room);
        });
    }

    /** 进放假:每周期一次,不发牌、周期计时关闸,超时站起+锁座 */
    private void enterGrace(DzRoom room, DzPlayer p, String reason) {
        p.setSittingOut(true);
        p.setSeatReserveUsed(true);
        p.setManualLeavePending(false);
        p.setVacationPending(false);
        p.closeGate();  // 放假不计周期时间(对齐扯旋 closeGate)
        long deadline = System.currentTimeMillis() + seatReserveGraceSecs * 1000L;
        p.setSeatReserveDeadline(deadline);
        broadcaster.toRoom(room.getRoomId(), GameMessage.create(MsgType.SEAT_RESERVE_GRACE, room.getRoomId(), Map.of(
                "userId", p.getUserId(), "seat", p.getSeat(), "state", "ON_LEAVE",
                "reason", reason, "deadline", deadline, "graceSeconds", seatReserveGraceSecs)));
        log.info("进入暂离: roomId={}, userId={}, reason={}, {}s", room.getRoomId(), p.getUserId(), reason, seatReserveGraceSecs);

        long userId = p.getUserId();
        cancelGraceTimer(p);
        p.setGraceTimer(roomWorker.submitDelaySecs(room.getRoomId(), () -> {
            DzPlayer cur = room.playerByUserId(userId);
            if (cur == null || !cur.inGrace()) return;
            if (cur.getSeatReserveDeadline() > System.currentTimeMillis() + 1000) return;  // 已重新进过放假
            int seat = cur.getSeat();
            log.info("暂离超时站起: roomId={}, userId={}", room.getRoomId(), userId);
            doStandUp(room, cur, "grace_timeout");
            // 物理锁座:座位再留 seatLockSecs 只给本人(对齐扯旋281)
            room.lockSeat(seat, userId, seatLockSecs * 1000L);
            broadcaster.toRoom(room.getRoomId(), GameMessage.create(MsgType.SEAT_RESERVE_GRACE, room.getRoomId(), Map.of(
                    "userId", userId, "seat", seat, "state", "SEAT_LOCKED",
                    "deadline", System.currentTimeMillis() + seatLockSecs * 1000L)));
        }, seatReserveGraceSecs));
    }

    private void cancelGraceTimer(DzPlayer p) {
        if (p.getGraceTimer() != null) {
            p.getGraceTimer().cancel(false);
            p.setGraceTimer(null);
        }
    }

    /** 弃牌后处理暂离意愿:断线代弃/手动申请 → 进放假;本周期用过了 → 局末站起 */
    private void afterFoldLeaveIntent(DzRoom room, DzPlayer p) {
        if (p.isManualLeavePending()) {
            enterGrace(room, p, "MANUAL");
            return;
        }
        if (p.isVacationPending()) {
            if (!p.isSeatReserveUsed()) {
                enterGrace(room, p, "AUTO");
            } else {
                p.setVacationPending(false);
                p.setPendingStandUp(true);  // 本周期放假用完了 → 局末站起(对齐扯旋)
            }
        }
    }

    // ================================================================
    // 断线/回线(对齐扯旋 vacationPending 模型)
    // ================================================================

    /** WS 断开:标离线,不踢座;在手未弃 → 代弃后进放假;空闲 → 直接进放假 */
    public void onDisconnect(long userId) {
        for (DzRoom room : roomManager.list()) {
            DzPlayer p = room.playerByUserId(userId);
            if (p == null) continue;
            roomWorker.submit(room.getRoomId(), () -> {
                if (p.isOffline()) return;
                p.setOffline(true);
                p.setOfflineSince(System.currentTimeMillis());
                broadcaster.toRoom(room.getRoomId(), GameMessage.create(MsgType.PLAYER_OFFLINE, room.getRoomId(),
                        Map.of("userId", userId, "seat", p.getSeat())));
                if (p.inGrace() || p.isAwaitingBuyin()) {
                    return;  // 已在放假/补带入等待:倒计时照走
                }
                if (room.inGame() && p.isInHand() && !p.isFolded()) {
                    p.setVacationPending(true);  // 等超时代弃后自动进放假
                } else if (!p.isSeatReserveUsed()) {
                    enterGrace(room, p, "AUTO");
                } else {
                    p.setVacationPending(true);  // 下手代弃后局末站起
                }
            });
        }
    }

    // ================================================================
    // 实时战绩(对齐扯旋 109)
    // ================================================================

    public void realtimeStats(long roomId, long userId, Long sequence) {
        DzRoom room = roomManager.get(roomId);
        if (room == null) {
            sendError(userId, roomId, "房间不存在");
            return;
        }
        roomWorker.submit(roomId, () -> {
            List<Map<String, Object>> players = new ArrayList<>();
            long totalBringIn = 0, totalStack = 0;
            for (DzPlayer p : room.getSeats()) {
                if (p == null) continue;
                totalBringIn += p.getBringInThisPeriod();
                totalStack += p.getStack();
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("userId", p.getUserId());
                m.put("nickname", p.getNickname());
                m.put("seat", p.getSeat());
                m.put("bringIn", p.getBringInThisPeriod());
                m.put("stack", p.getStack());
                // 实时盈亏:未结手把桌面投入加回(对齐扯旋)
                m.put("profit", p.getStack() + p.getTotalBetThisHand() - p.getBringInThisPeriod());
                m.put("handCount", p.getHandCount());
                m.put("winCount", p.getWinCount());
                m.put("loseCount", p.getLoseCount());
                m.put("offline", p.isOffline());
                m.put("sittingOut", p.isSittingOut());
                m.put("awaitingBuyin", p.isAwaitingBuyin());
                if (room.getSettleTimeMins() > 0) {
                    m.put("remainingSecs", Math.max(0, room.getSettleTimeMins() * 60L - p.effectiveMs() / 1000));
                }
                players.add(m);
            }
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("players", players);
            data.put("room", Map.of(
                    "roomId", room.getRoomId(), "name", room.getName(),
                    "handNo", room.getHandNo(), "pot", room.displayPot(),
                    "totalBringIn", totalBringIn, "totalStack", totalStack,
                    "settleTimeMins", room.getSettleTimeMins()));
            data.put("history", records.roomRecords(roomId, 50));
            GameMessage res = GameMessage.create(MsgType.REALTIME_STATS_RES, roomId, data);
            res.setSequence(sequence);
            broadcaster.toUser(userId, res);
        });
    }

    // ================================================================
    // 解散牌局(对齐扯旋 130/285:创建者或群主/管理员)
    // ================================================================

    public void dismissRoom(long roomId, long userId) {
        dismissRoom(roomId, userId, false);
    }

    /** 管理后台强制解散 */
    public void dismissRoomByAdmin(long roomId) {
        dismissRoom(roomId, 0, true);
    }

    private void dismissRoom(long roomId, long userId, boolean force) {
        DzRoom room = roomManager.get(roomId);
        if (room == null) {
            if (!force) sendError(userId, roomId, "房间不存在");
            return;
        }
        roomWorker.submit(roomId, () -> {
            boolean allowed = force
                    || room.getCreatorUserId() == userId
                    || (room.getClubId() > 0 && clubService != null
                        && clubService.canCreateRoom(room.getClubId(), userId));
            if (!allowed) {
                sendError(userId, roomId, "只有创建者或群主/管理员可以解散牌局");
                return;
            }
            forceClearRoom(room, userId, "dismiss");
        });
    }

    /**
     * 强制清房(解散/停服维护共用,须在 roomWorker 内调):
     *   局中退回本手投入 → 全员站起结算(dismiss/maintenance 非主动离开,不收罚金) →
     *   广播 ROOM_DISMISSED → 移除房间。对齐扯旋 forceStandUpAllForMaintenance/285。
     */
    private void forceClearRoom(DzRoom room, long byUserId, String reason) {
        long roomId = room.getRoomId();
        cancelActionTimeout(room);
        cancelInsurance(room);
        // 局中清房:本手作废,桌面投入退回,不算战绩
        if (room.inGame()) {
            for (DzPlayer p : room.getSeats()) {
                if (p == null) continue;
                p.setStack(p.getStack() + p.getTotalBetThisHand());
                p.resetForHand();
            }
            room.setStage(GameStage.WAITING);
        }
        for (DzPlayer p : room.getSeats().clone()) {
            if (p != null) doStandUp(room, p, reason);
        }
        broadcaster.toRoom(roomId, GameMessage.create(MsgType.ROOM_DISMISSED, roomId,
                Map.of("byUserId", byUserId, "reason", reason)));
        roomManager.remove(roomId);
        roomWorker.removeRoom(roomId);
        records.markRoomClosed(roomId);
        log.info("强制清房: roomId={}, by={}, reason={}", roomId, byUserId, reason);
    }

    /**
     * 停服维护清扫(对齐扯旋 maintenance/toggle):开启维护瞬间调一次。
     *   没在打牌的桌立即清场请人离开;游戏中的桌打完当前这手在 finishHand 安全点清。
     */
    public void maintenanceSweep() {
        for (DzRoom room : roomManager.list()) {
            roomWorker.submit(room.getRoomId(), () -> {
                if (!room.inGame()) {
                    forceClearRoom(room, 0, "maintenance");
                }
                // 游戏中的桌:finishHand 检测 maintenance_mode 后清
            });
        }
        log.warn("停服维护清扫已触发(空闲桌立即清,游戏中的桌局末清)");
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

        // 圈主周期服务费:本周期第一手预付 / 到点即扣;扣不动 → 警告并停开新局(对齐扯旋 v46)
        if (!chargeOwnerDiamondIfDue(room)) {
            room.setStage(GameStage.WAITING);
            broadcaster.toRoom(room.getRoomId(), GameMessage.create(MsgType.ROOM_STATE, room.getRoomId(),
                    Map.of("stage", GameStage.WAITING.name(), "reason", "diamond")));
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

    /**
     * 圈主周期扣钻(对齐扯旋 prepay + 到点即扣):
     *   俱乐部房、cost>0、settleTime>0 才扣;群主无主服账号(开发/机器人)跳过;
     *   本周期第一手预付,之后房间墙钟到点续扣下一周期;失败广播 DIAMOND_WARNING。
     */
    private static final com.fasterxml.jackson.databind.ObjectMapper TIER_MAPPER =
            new com.fasterxml.jackson.databind.ObjectMapper();

    /**
     * 本房间的圈主周期服务费(对齐扯旋 v51 档位矩阵):
     *   owner_period_diamond_tiers JSON 数组按「结算分钟 + 大盲」精确匹配;
     *   没匹配到 → 回退单一兜底值 owner_period_diamond_cost。
     */
    private long ownerDiamondCostFor(DzRoom room) {
        String json = cfg != null ? cfg.getStr("owner_period_diamond_tiers", "") : "";
        if (json != null && !json.isBlank()) {
            try {
                com.fasterxml.jackson.databind.JsonNode arr = TIER_MAPPER.readTree(json);
                if (arr.isArray()) {
                    for (com.fasterxml.jackson.databind.JsonNode t : arr) {
                        if (t.path("minutes").asInt(-1) == room.getSettleTimeMins()
                                && t.path("baseScore").asLong(-1) == room.getBb()) {
                            return Math.max(0, t.path("cost").asLong(0));
                        }
                    }
                }
            } catch (Exception e) {
                log.warn("钻石档位矩阵解析失败,回退兜底值: {}", json);
            }
        }
        return ownerDiamondCost();
    }

    private boolean chargeOwnerDiamondIfDue(DzRoom room) {
        if (room.getClubId() <= 0 || clubService == null || diamondService == null
                || room.getSettleTimeMins() <= 0) {
            return true;
        }
        long cost = ownerDiamondCostFor(room);
        if (cost <= 0) return true;
        long owner = clubService.ownerUserId(room.getClubId());
        if (owner <= 0 || !diamondService.hasMainAccount(owner)) {
            return true;
        }
        long now = System.currentTimeMillis();
        long periodMs = room.getSettleTimeMins() * 60_000L;
        boolean needCharge = room.getOwnerDiamondDueAt() == 0 || now >= room.getOwnerDiamondDueAt();
        if (!needCharge) return true;
        if (!diamondService.debit(owner, cost, "room_period",
                "德州房间周期服务费 room=" + room.getRoomId())) {
            room.setDiamondBlocked(true);
            Map<String, Object> warn = Map.of(
                    "clubId", room.getClubId(), "needed", cost,
                    "msg", "群主钻石不足,牌局暂停,请联系群主充值");
            broadcaster.toRoom(room.getRoomId(), GameMessage.create(MsgType.DIAMOND_WARNING, room.getRoomId(), warn));
            broadcaster.toUser(owner, GameMessage.create(MsgType.DIAMOND_WARNING, room.getRoomId(), warn));
            log.warn("群主钻石不足: roomId={}, clubId={}, owner={}, need={}",
                    room.getRoomId(), room.getClubId(), owner, cost);
            return false;
        }
        room.setDiamondBlocked(false);
        room.setOwnerDiamondDueAt(room.getOwnerDiamondDueAt() == 0
                ? now + periodMs : room.getOwnerDiamondDueAt() + periodMs);
        log.info("圈主周期扣钻: roomId={}, owner={}, cost={}, nextDue={}",
                room.getRoomId(), owner, cost, room.getOwnerDiamondDueAt());
        return true;
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

        // 弃牌后处理暂离/断线意愿(对齐扯旋:代弃后自动进放假)
        if (act == ActionType.FOLD) {
            afterFoldLeaveIntent(room, p);
        }

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
                                "balance", economyBalance(room, p.getUserId()))));
                p.setPendingBuyin(0);
            }
            // 局间意愿:站起
            if (p.isPendingStandUp()) {
                doStandUp(room, p, "standup");
                continue;
            }
            // 暂离意愿(牌局中申请/断线,弃牌时没触发到的在这兜底)
            if (p.isManualLeavePending() || p.isVacationPending()) {
                afterFoldLeaveIntent(room, p);
                if (p.isPendingStandUp()) {  // 放假用完了转站起
                    doStandUp(room, p, "vacation");
                    continue;
                }
            }
            // 放假中的玩家不做周期结算(回桌后下一手再查,对齐扯旋"非grace才结")
            if (p.inGrace()) {
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

        // 停服维护(对齐扯旋):打完当前这手即全员站起清场请离房间,不开新局(不收罚金)
        if (cfgBool("maintenance_mode", false)) {
            forceClearRoom(room, 0, "maintenance");
            return;
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
        creditBack(room, p.getUserId(), refund, 2, "周期结算返还 room=" + room.getRoomId());
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
        panel.put("balance", economyBalance(room, p.getUserId()));

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

        cancelGraceTimer(p);
        p.closeGate();
        long playedSecs = p.getGameTimeAccumMs() / 1000;
        long stack = p.getStack();
        long bringIn = p.getBringInThisPeriod();
        long profit = stack - bringIn;
        long rake = applyRake(room, p, profit);
        // 赢家离桌罚金(仅主动站起/离开触发,超时/解散不罚,对齐扯旋)
        long fine = isUserInitiated(reason) ? applyLeaveFines(room, p, profit) : 0;
        long refund = Math.max(0, stack - rake - fine);
        if (clubEconomy(room)) {
            // 账本对齐扯旋:先全额"起立返还"(type2),罚金再单独一条"逃跑惩罚"(type19)
            creditBack(room, p.getUserId(), stack - rake, 2, "起立返还 room=" + room.getRoomId());
            if (fine > 0) {
                clubService.fineScoreForGame(room.getClubId(), p.getUserId(), fine, room.getRoomId());
            }
        } else {
            creditBack(room, p.getUserId(), refund, 2, "起立返还 room=" + room.getRoomId());
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
        data.put("fine", fine);
        data.put("refund", refund);
        data.put("handCount", p.getHandCount());
        data.put("winCount", p.getWinCount());
        data.put("loseCount", p.getLoseCount());
        data.put("playedSecs", playedSecs);
        data.put("balance", economyBalance(room, p.getUserId()));
        broadcaster.toRoom(room.getRoomId(), GameMessage.create(MsgType.PLAYER_STAND, room.getRoomId(), data));
        log.info("站起: roomId={}, userId={}, reason={}, bringIn={}, stack={}, profit={}, rake={}, fine={}, refund={}",
                room.getRoomId(), p.getUserId(), reason, bringIn, stack, profit, rake, fine, refund);
        maybeAutoDisbandClubRoom(room, reason);
    }

    /**
     * 对齐扯旋 EMPTY_AUTO_DISBAND:最后一名在座玩家站起后,俱乐部房自动解散;
     *   但同俱乐部同小盲还有其它桌才拆(保底留一桌入口),解散/维护等清房路径不触发(防递归)。
     * 追加到 roomWorker 队尾执行:等 finishHand 等当前流程收尾、stage 回 WAITING 后再判。
     */
    private void maybeAutoDisbandClubRoom(DzRoom room, String reason) {
        if (room.getClubId() <= 0) return;
        switch (reason) {
            case "standup", "leave", "grace_timeout", "vacation", "buyin_timeout" -> { }
            default -> { return; }
        }
        long roomId = room.getRoomId();
        roomWorker.submit(roomId, () -> {
            if (roomManager.get(roomId) == null || room.inGame()) return;
            for (DzPlayer q : room.getSeats()) {
                if (q != null) return;
            }
            boolean hasOtherSameBlind = false;
            for (DzRoom r : roomManager.list()) {
                if (r.getRoomId() != roomId && r.getClubId() == room.getClubId() && r.getSb() == room.getSb()) {
                    hasOtherSameBlind = true;
                    break;
                }
            }
            if (!hasOtherSameBlind) return;  // 同小盲最后一桌保留,俱乐部大厅始终有入口
            forceClearRoom(room, 0, "empty_auto");
        });
    }

    /** 主动离开的站起原因(罚金只对主动离开生效,对齐扯旋 StandUpReason.isUserInitiated) */
    private boolean isUserInitiated(String reason) {
        return "standup".equals(reason) || "leave".equals(reason);
    }

    /**
     * 预估赢家离桌罚金(不落地):
     *   ①赢家早退过路费:俱乐部房、盈利>0、非群主、桌上还有其他人 → 盈利×winnerEarlyLeaveRate%;
     *   ②逃跑罚金(默认关):本周期累计离线≥runAwayTimeMins 且盈利≥2BB → 再加盈利×runAwayPenaltyRate%。
     */
    private long estimateLeaveFines(DzRoom room, DzPlayer p) {
        if (room.getClubId() <= 0 || clubService == null) return 0;
        long profit = p.getStack() - p.getBringInThisPeriod();
        if (profit <= 0) return 0;
        if (clubService.isOwner(room.getClubId(), p.getUserId())) return 0;
        int others = 0;
        for (DzPlayer q : room.getSeats()) {
            if (q != null && q != p) others++;
        }
        if (others < 1) return 0;
        long fine = profit * winnerEarlyLeaveRate / 100;
        if (runAwayEnabled && p.getLeaveAccumMs() >= runAwayTimeMins * 60_000L
                && profit >= room.getBb() * 2) {
            fine += profit * runAwayPenaltyRate / 100;
        }
        return fine;
    }

    /** 落地罚金:归群主德州钱包(对齐扯旋归群主积分),广播 RUN_AWAY_FINE */
    private long applyLeaveFines(DzRoom room, DzPlayer p, long profit) {
        long fine = estimateLeaveFines(room, p);
        if (fine <= 0) return 0;
        long owner = clubService.ownerUserId(room.getClubId());
        if (owner > 0) {
            // 罚金进群主的俱乐部积分(type=20 逃跑惩罚,对齐扯旋)
            clubService.creditScoreForGame(room.getClubId(), owner, fine, 20,
                    "离桌罚金 from=" + p.getUserId() + " room=" + room.getRoomId());
        }
        boolean runAway = runAwayEnabled && p.getLeaveAccumMs() >= runAwayTimeMins * 60_000L;
        broadcaster.toRoom(room.getRoomId(), GameMessage.create(MsgType.RUN_AWAY_FINE, room.getRoomId(), Map.of(
                "userId", p.getUserId(), "seat", p.getSeat(),
                "kind", runAway ? "RUN_AWAY" : "EARLY_LEAVE",
                "amount", fine, "profit", profit, "toOwnerUserId", owner)));
        log.info("离桌罚金: roomId={}, userId={}, profit={}, fine={}, owner={}",
                room.getRoomId(), p.getUserId(), profit, fine, owner);
        return fine;
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
        m.put("creatorUserId", room.getCreatorUserId());
        // 本房间语境的我的余额:俱乐部房=该俱乐部积分,公开房=金币(前端带入面板用)
        m.put("myBalance", economyBalance(room, forUserId));
        m.put("diamondBlocked", room.isDiamondBlocked());
        // 物理锁座(放假超时后保留):前端画"保留中"
        List<Map<String, Object>> locks = new ArrayList<>();
        for (Map.Entry<Integer, long[]> e : room.getSeatLocks().entrySet()) {
            if (e.getValue()[1] > System.currentTimeMillis()) {
                locks.add(Map.of("seat", e.getKey(), "userId", e.getValue()[0], "deadline", e.getValue()[1]));
            }
        }
        if (!locks.isEmpty()) {
            m.put("seatLocks", locks);
        }
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
            pm.put("avatar", p.getAvatar() == null ? "" : p.getAvatar());
            pm.put("seat", p.getSeat());
            pm.put("stack", p.getStack());
            pm.put("inHand", p.isInHand());
            pm.put("folded", p.isFolded());
            pm.put("allIn", p.isAllIn());
            pm.put("betThisRound", p.getBetThisRound());
            pm.put("awaitingBuyin", p.isAwaitingBuyin());
            pm.put("offline", p.isOffline());
            pm.put("sittingOut", p.isSittingOut());
            if (p.inGrace()) {
                pm.put("graceDeadline", p.getSeatReserveDeadline());
            }
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
