package com.chexuan.dzpk.robot;

import com.chexuan.dzpk.game.card.Card;
import com.chexuan.dzpk.game.model.DzPlayer;
import com.chexuan.dzpk.game.model.DzRoom;
import com.chexuan.dzpk.game.service.DzGameService;
import com.chexuan.dzpk.game.service.DzRoomManager;
import com.chexuan.dzpk.ws.GameMessage;
import com.chexuan.dzpk.ws.MsgType;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 机器人 — 陪打测试用:
 *   真人坐下带入后自动进房补位(补到 fill-count 个),按牌力启发式行动(带随机延迟拟真),
 *   周期结算/打光自动补带入,房间里没有真人后自动撤场。
 *
 * 挂接方式:CompositeBroadcaster 把引擎广播同时路由到这里,机器人"收到消息→调引擎入口",
 * 与真人玩家走完全相同的 DzGameService API,不开后门。
 */
@Slf4j
@Service
public class RobotService {

    /** 机器人 id 段(与真人/游客区分) */
    public static final long ROBOT_ID_BASE = 800_000_001L;

    private static final String[] NICKNAMES = {
            "老K", "菠萝", "石头", "阿豪", "肥猫", "夜风", "大米", "阿乐", "秃鹰", "小马",
            "阿肥", "老猫", "大炮", "小辣椒", "阿坤", "东哥", "海王", "六指", "阿灿", "胖虎",
            "老狼", "阿飞", "皮蛋", "大圣", "铁头", "阿宝", "老酒", "斧头", "阿杰", "闪电"
    };

    /** 机器人头像池(前端同域静态资源,坐下广播带给全桌) */
    private static final int AVATAR_POOL = 16;

    private static String randomRobotAvatar() {
        return "/assets/table/heads/head_" + (1 + ThreadLocalRandom.current().nextInt(AVATAR_POOL)) + ".png";
    }

    private final DzRoomManager roomManager;
    private final DzGameService gameService;

    @Value("${dzpk.robot.enabled:true}")
    private boolean enabled;

    /** 每个房间补足的机器人数量 */
    @Value("${dzpk.robot.fill-count:2}")
    private int fillCount;

    @Value("${dzpk.robot.min-delay-ms:800}")
    private long minDelayMs;

    @Value("${dzpk.robot.max-delay-ms:2500}")
    private long maxDelayMs;

    private final AtomicLong idGen = new AtomicLong(ROBOT_ID_BASE);
    private final ScheduledExecutorService scheduler;

    /** roomId → 该房间的机器人 */
    private final Map<Long, Set<Long>> roomRobots = new ConcurrentHashMap<>();
    /** robotUserId → 手牌(HOLE_CARDS 私发时存) */
    private final Map<Long, List<Card>> holeCards = new ConcurrentHashMap<>();
    /** roomId → 已被机器人预定但坐下还没落地的座位(防并发抢同一座位) */
    private final Map<Long, Set<Integer>> reservedSeats = new ConcurrentHashMap<>();

    /** 系统参数中心(可为 null,退回 @Value 默认) */
    private final com.chexuan.dzpk.config.DzConfigService cfg;

    /** 机器人账号注册表(dz_user.is_robot=1 真实账号池;单测可为 null) */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private RobotRegistry registry;

    /** 机器人参数中心(俱乐部默认+房间覆盖;单测可为 null 退回 @Value) */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private RobotParamService params;

    /** 盈利控盘(单测可为 null=不控) */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private DzProfitControl profit;

    @org.springframework.beans.factory.annotation.Autowired
    public RobotService(DzRoomManager roomManager, @Lazy DzGameService gameService,
                        com.chexuan.dzpk.config.DzConfigService cfg) {
        this.roomManager = roomManager;
        this.gameService = gameService;
        this.cfg = cfg;
        this.scheduler = Executors.newScheduledThreadPool(2, r -> {
            Thread t = new Thread(r, "dzpk-robot");
            t.setDaemon(true);
            return t;
        });
    }

    /** 单测用 */
    public RobotService(DzRoomManager roomManager, DzGameService gameService) {
        this(roomManager, gameService, null);
    }

    private boolean robotEnabled() {
        return cfg != null ? cfg.getBool("robot_enabled", enabled) : enabled;
    }

    private int robotFillCount() {
        return cfg != null ? cfg.getInt("robot_fill_count", fillCount) : fillCount;
    }

    private long robotMinDelay() {
        return cfg != null ? cfg.getLong("robot_min_delay_ms", minDelayMs) : minDelayMs;
    }

    private long robotMaxDelay() {
        return cfg != null ? cfg.getLong("robot_max_delay_ms", maxDelayMs) : maxDelayMs;
    }

    /** 是否机器人:大厅临时机器人(ID段) 或 俱乐部机器人账号(dz_user.is_robot=1) */
    public boolean isRobot(long userId) {
        return isRobotId(userId) || (registry != null && registry.isRobot(userId));
    }

    /** 静态判断【大厅临时机器人】(ID 段;引擎豁免其经济/俱乐部限制用,避免 bean 循环依赖) */
    public static boolean isRobotId(long userId) {
        return userId >= ROBOT_ID_BASE && userId < ROBOT_ID_BASE + 1_000_000L;
    }

    /** 把机器人挂到房间驱动表(收到 TURN 才会行动);俱乐部账号机器人上桌前由 DzRobotAdminService 调 */
    public void registerRobot(long roomId, long userId) {
        roomRobots.computeIfAbsent(roomId, k -> ConcurrentHashMap.newKeySet()).add(userId);
    }

    // ================================================================
    // 消息入口(CompositeBroadcaster 调)
    // ================================================================

    /** 房间广播 → 机器人观察 */
    public void onRoomMessage(long roomId, GameMessage msg) {
        if (!robotEnabled() || msg.getType() == null) return;
        Map<String, Object> data = dataMap(msg);
        switch (msg.getType()) {
            case MsgType.PLAYER_SIT, MsgType.BUY_IN_RES, MsgType.PLAYER_ENTER, MsgType.ROOM_STATE ->
                    schedule(() -> fillCheck(roomId), 600);
            // 开手:控盘定方向(广播在 roomWorker 线程同步到达,此刻手牌已发、状态一致)
            case MsgType.HAND_START -> {
                DzRoom rm = roomManager.get(roomId);
                if (rm != null && profit != null) profit.planHand(rm, this::isRobot);
            }
            // 一手结算:控盘记账 + 筹码/亏损封顶站起检查
            case MsgType.SETTLE -> {
                DzRoom rm = roomManager.get(roomId);
                if (rm != null && profit != null) profit.onSettle(rm, this::isRobot);
                schedule(() -> capStandUpCheck(roomId), 1500);
            }
            case MsgType.TURN -> {
                long uid = lng(data, "userId");
                if (isRobot(uid) && isMyRobot(roomId, uid)) {
                    schedule(() -> act(roomId, uid, data), randDelay(roomId));
                }
            }
            case MsgType.PERIOD_SETTLE -> {
                long uid = lng(data, "userId");
                if (isRobot(uid) && isMyRobot(roomId, uid)) {
                    long periodProfit = lng(data, "profit");
                    schedule(() -> periodDecision(roomId, uid, periodProfit),
                            1000 + ThreadLocalRandom.current().nextLong(1500));
                }
            }
            case MsgType.PLAYER_STAND, MsgType.PLAYER_LEAVE ->
                    schedule(() -> retreatCheckAndFill(roomId), 800);
            default -> {
            }
        }
    }

    /** 私发给机器人的消息 */
    public void onUserMessage(long userId, GameMessage msg) {
        if (msg.getType() == null) return;
        if (msg.getType() == MsgType.HOLE_CARDS) {
            Map<String, Object> data = dataMap(msg);
            Object cards = data.get("cards");
            if (cards instanceof List<?> list) {
                holeCards.put(userId, list.stream().map(c -> Card.of(c.toString())).toList());
            }
        } else if (msg.getType() == MsgType.ERROR) {
            log.debug("机器人收到错误: userId={}, {}", userId, dataMap(msg).get("msg"));
        }
    }

    // ================================================================
    // 管理台操作(俱乐部账号机器人的上桌由 DzRobotAdminService.deploy 走真人流程)
    // ================================================================

    /** 清掉指定房间的全部机器人(牌局中的先标记站起,局末落地) */
    public synchronized Map<String, Object> clearRobots(long roomId) {
        Set<Long> robots = roomRobots.remove(roomId);
        reservedSeats.remove(roomId);
        int n = robots == null ? 0 : robots.size();
        if (robots != null) {
            for (long robotId : robots) {
                gameService.standUp(roomId, robotId);
                gameService.leaveRoom(roomId, robotId, true);
                holeCards.remove(robotId);
            }
        }
        log.info("管理台清场机器人: roomId={}, count={}", roomId, n);
        return Map.of("code", 0, "cleared", n);
    }

    /** 各房间机器人分布(管理台展示) */
    public Map<String, Object> listRobots() {
        Map<String, Object> byRoom = new java.util.LinkedHashMap<>();
        roomRobots.forEach((roomId, ids) -> {
            if (!ids.isEmpty()) byRoom.put(String.valueOf(roomId), ids.size());
        });
        return Map.of("code", 0, "rooms", byRoom);
    }

    // ================================================================
    // 补位 / 撤场
    // ================================================================

    /** 有真人在座就把机器人补到 fillCount 个(同步:防多条广播并发触发时抢同一座位) */
    private synchronized void fillCheck(long roomId) {
        DzRoom room = roomManager.get(roomId);
        if (room == null) return;
        // 俱乐部房不进机器人:仅成员可坐(对齐扯旋,机器人陪打只服务大厅散房)
        if (room.getClubId() > 0) return;

        Set<Long> robots = roomRobots.computeIfAbsent(roomId, k -> ConcurrentHashMap.newKeySet());
        Set<Integer> reserved = reservedSeats.computeIfAbsent(roomId, k -> ConcurrentHashMap.newKeySet());
        boolean humanSeated = false;
        int freeSeat = -1;
        for (int i = 0; i < room.getMaxPlayers(); i++) {
            DzPlayer p = room.getSeats()[i];
            if (p != null) {
                reserved.remove(i);  // 坐下已落地,解除预留
                if (!isRobot(p.getUserId())) humanSeated = true;
                continue;
            }
            if (freeSeat == -1 && !reserved.contains(i)) freeSeat = i;
        }
        if (!humanSeated || robots.size() >= robotFillCount() || freeSeat == -1) return;
        reserved.add(freeSeat);

        long robotId = idGen.getAndIncrement();
        String nick = NICKNAMES[(int) (robotId % NICKNAMES.length)] + "·AI";
        robots.add(robotId);

        long buyin = randomBuyin(room);
        log.info("机器人进场: roomId={}, robotId={}, nick={}, seat={}, buyin={}", roomId, robotId, nick, freeSeat, buyin);
        gameService.enterRoom(roomId, robotId, nick);
        gameService.sitDown(roomId, robotId, freeSeat, null, randomRobotAvatar());
        gameService.buyIn(roomId, robotId, buyin);

        // 还没补够 → 下一轮继续(错峰进场更拟真)
        if (robots.size() < robotFillCount()) {
            schedule(() -> fillCheck(roomId), 1200 + ThreadLocalRandom.current().nextLong(1500));
        }
    }

    /** 真人全走了 → 机器人撤场;否则继续检查补位 */
    private void retreatCheckAndFill(long roomId) {
        DzRoom room = roomManager.get(roomId);
        Set<Long> robots = roomRobots.get(roomId);
        if (room == null) {
            if (robots != null) {
                robots.forEach(holeCards::remove);
                roomRobots.remove(roomId);
            }
            reservedSeats.remove(roomId);
            if (profit != null) profit.clearRoom(roomId);
            if (params != null) params.clearRoom(roomId);
            return;
        }
        boolean humanInRoom = room.getMembers().keySet().stream().anyMatch(uid -> !isRobot(uid));
        // 俱乐部房的机器人是管理台手动派的,真人离开也继续打(手动撤回/房间解散才走);
        // 只有大厅陪打房(clubId=0)才"真人全走自动撤场"。
        if (!humanInRoom && robots != null && !robots.isEmpty() && room.getClubId() <= 0) {
            log.info("真人全部离开,机器人撤场: roomId={}, robots={}", roomId, robots.size());
            for (long robotId : robots) {
                gameService.standUp(roomId, robotId);
                gameService.leaveRoom(roomId, robotId, true);
                holeCards.remove(robotId);
            }
            roomRobots.remove(roomId);
            reservedSeats.remove(roomId);
        } else if (humanInRoom) {
            fillCheck(roomId);
        }
    }

    /** 周期结算/打光 → 补带入继续打 */
    private void rebuy(long roomId, long robotId) {
        DzRoom room = roomManager.get(roomId);
        if (room == null) return;
        DzPlayer p = room.playerByUserId(robotId);
        if (p == null || !p.isAwaitingBuyin()) return;
        long buyin = randomBuyin(room);
        log.info("机器人补带入: roomId={}, robotId={}, buyin={}", roomId, robotId, buyin);
        gameService.buyIn(roomId, robotId, buyin);
    }

    /**
     * 周期结算决策(对齐扯旋 periodWin/LoseStandUpProb):
     * 净赢/净输按各自概率站起离桌,否则补带入继续打。
     */
    private void periodDecision(long roomId, long robotId, long periodProfit) {
        DzRoom room = roomManager.get(roomId);
        if (room == null) return;
        long prob = params == null ? 0
                : params.getLong(room, periodProfit >= 0 ? "period_win_standup_prob" : "period_lose_standup_prob");
        if (ThreadLocalRandom.current().nextInt(100) < prob) {
            log.info("机器人周期站起: roomId={}, robotId={}, profit={}, prob={}", roomId, robotId, periodProfit, prob);
            removeRobotFromTable(roomId, robotId);
        } else {
            rebuy(roomId, robotId);
        }
    }

    /**
     * 筹码/亏损封顶站起(对齐扯旋 chipCap/lossCapMultiplier,大盲×倍数;0=不启用):
     * 一手结算后检查,命中的机器人站起离桌。
     */
    private void capStandUpCheck(long roomId) {
        DzRoom room = roomManager.get(roomId);
        Set<Long> robots = roomRobots.get(roomId);
        if (room == null || robots == null || robots.isEmpty() || params == null) return;
        long chipCap = params.getLong(room, "chip_cap_multiplier");
        long lossCap = params.getLong(room, "loss_cap_multiplier");
        if (chipCap <= 0 && lossCap <= 0) return;
        for (long robotId : List.copyOf(robots)) {
            DzPlayer p = room.playerByUserId(robotId);
            if (p == null) continue;
            long net = p.getStack() - p.getBringInThisPeriod();
            if (chipCap > 0 && p.getStack() >= room.getBb() * chipCap) {
                log.info("机器人筹码封顶站起: roomId={}, robotId={}, stack={}", roomId, robotId, p.getStack());
                removeRobotFromTable(roomId, robotId);
            } else if (lossCap > 0 && -net >= room.getBb() * lossCap) {
                log.info("机器人亏损封顶站起: roomId={}, robotId={}, net={}", roomId, robotId, net);
                removeRobotFromTable(roomId, robotId);
            }
        }
    }

    /** 机器人站起并离桌(牌局中先 pending 局末落地),从驱动表移除 */
    private void removeRobotFromTable(long roomId, long robotId) {
        gameService.standUp(roomId, robotId);
        gameService.leaveRoom(roomId, robotId, true);
        Set<Long> robots = roomRobots.get(roomId);
        if (robots != null) robots.remove(robotId);
        holeCards.remove(robotId);
    }

    private long randomBuyin(DzRoom room) {
        long lo = room.getMinBuyin();
        long hi = Math.min(room.getMaxBuyin(), lo * 3);
        return lo + ThreadLocalRandom.current().nextLong(Math.max(1, hi - lo + 1));
    }

    // ================================================================
    // 行动策略 — 复用老德州模型(RobotBrain:上帝视角比牌 + 性格概率表 + 控盘介入)
    // ================================================================

    private void act(long roomId, long robotId, Map<String, Object> turnData) {
        DzRoom room = roomManager.get(roomId);
        if (room == null) return;
        DzPlayer me = room.playerByUserId(robotId);
        if (me == null || room.getActingSeat() != me.getSeat()) return;  // 已换人/超时了

        long toCall = lng(turnData, "toCall");
        long minRaiseTo = lng(turnData, "minRaiseTo");

        int persona = RobotBrain.personaOf(robotId,
                params != null ? params.getInt(room, "aggressive_prob") : 30,
                params != null ? params.getInt(room, "conservative_prob") : 30);
        int bias = profit != null ? profit.biasFor(roomId) : 0;
        long budget = profit != null ? profit.budgetLeft(roomId) : 0;

        RobotBrain.Decision d = RobotBrain.decide(room, me, toCall, minRaiseTo, persona, bias, budget);

        // 放水手喂池消耗预算(新增投入:call≈toCall;raise≈raiseTo-本轮已投)
        if (profit != null && bias == -1) {
            long spend = switch (d.act()) {
                case "call" -> toCall;
                case "raise" -> Math.max(0, d.amount() - me.getBetThisRound());
                default -> 0;
            };
            if (spend > 0) profit.consume(roomId, spend);
        }
        log.debug("机器人行动: roomId={}, robotId={}, persona={}, bias={}, act={}, amount={}",
                roomId, robotId, persona, bias, d.act(), d.amount());
        gameService.action(roomId, robotId, d.act(), d.amount());
    }

    // ================================================================

    private boolean isMyRobot(long roomId, long userId) {
        Set<Long> robots = roomRobots.get(roomId);
        return robots != null && robots.contains(userId);
    }

    /** 行动延迟:优先房间/俱乐部参数(RobotParamService 两层),无参数中心退回 @Value */
    private long randDelay(long roomId) {
        long lo = robotMinDelay();
        long hi = robotMaxDelay();
        if (params != null) {
            DzRoom room = roomManager.get(roomId);
            if (room != null) {
                lo = params.getLong(room, "min_action_delay_ms");
                hi = params.getLong(room, "max_action_delay_ms");
            }
        }
        if (hi < lo) hi = lo;
        return lo + ThreadLocalRandom.current().nextLong(Math.max(1, hi - lo));
    }

    private void schedule(Runnable task, long delayMs) {
        scheduler.schedule(() -> {
            try {
                task.run();
            } catch (Exception e) {
                log.error("机器人任务异常", e);
            }
        }, delayMs, TimeUnit.MILLISECONDS);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> dataMap(GameMessage msg) {
        return (msg.getData() instanceof Map) ? (Map<String, Object>) msg.getData() : Map.of();
    }

    private long lng(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return (v instanceof Number n) ? n.longValue() : 0;
    }

    @PreDestroy
    public void shutdown() {
        scheduler.shutdownNow();
    }
}
