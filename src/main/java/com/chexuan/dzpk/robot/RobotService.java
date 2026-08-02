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
            "老K", "菠萝", "石头", "阿豪", "肥猫", "夜风", "大米", "阿乐", "秃鹰", "小马"
    };

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

    public boolean isRobot(long userId) {
        return userId >= ROBOT_ID_BASE && userId < ROBOT_ID_BASE + 1_000_000L;
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
            case MsgType.TURN -> {
                long uid = lng(data, "userId");
                if (isRobot(uid) && isMyRobot(roomId, uid)) {
                    schedule(() -> act(roomId, uid, data), randDelay());
                }
            }
            case MsgType.PERIOD_SETTLE -> {
                long uid = lng(data, "userId");
                if (isRobot(uid) && isMyRobot(roomId, uid)) {
                    schedule(() -> rebuy(roomId, uid), 1000 + ThreadLocalRandom.current().nextLong(1500));
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
    // 补位 / 撤场
    // ================================================================

    /** 有真人在座就把机器人补到 fillCount 个(同步:防多条广播并发触发时抢同一座位) */
    private synchronized void fillCheck(long roomId) {
        DzRoom room = roomManager.get(roomId);
        if (room == null) return;

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
        gameService.sitDown(roomId, robotId, freeSeat);
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
            return;
        }
        boolean humanInRoom = room.getMembers().keySet().stream().anyMatch(uid -> !isRobot(uid));
        if (!humanInRoom && robots != null && !robots.isEmpty()) {
            log.info("真人全部离开,机器人撤场: roomId={}, robots={}", roomId, robots.size());
            for (long robotId : robots) {
                gameService.standUp(roomId, robotId);
                gameService.leaveRoom(roomId, robotId);
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

    private long randomBuyin(DzRoom room) {
        long lo = room.getMinBuyin();
        long hi = Math.min(room.getMaxBuyin(), lo * 3);
        return lo + ThreadLocalRandom.current().nextLong(Math.max(1, hi - lo + 1));
    }

    // ================================================================
    // 行动策略(启发式,不追求强度,只求打起来像样)
    // ================================================================

    private void act(long roomId, long robotId, Map<String, Object> turnData) {
        DzRoom room = roomManager.get(roomId);
        if (room == null) return;
        DzPlayer me = room.playerByUserId(robotId);
        if (me == null || room.getActingSeat() != me.getSeat()) return;  // 已换人/超时了

        long toCall = lng(turnData, "toCall");
        long minRaiseTo = lng(turnData, "minRaiseTo");
        long stack = me.getStack();
        long bb = room.getBb();

        double s = strength(robotId, room);
        s += ThreadLocalRandom.current().nextDouble(-0.08, 0.08);

        String act;
        long amount = 0;
        if (toCall <= 0) {
            if (s > 0.72 && stack > 0 && minRaiseTo > 0) {
                act = "raise";
                amount = Math.min(minRaiseTo + (long) (bb * ThreadLocalRandom.current().nextInt(0, 3)),
                        me.getBetThisRound() + stack);
            } else if (s > 0.55 && ThreadLocalRandom.current().nextDouble() < 0.25) {
                act = "raise";
                amount = Math.min(minRaiseTo, me.getBetThisRound() + stack);
            } else {
                act = "check";
            }
        } else if (toCall >= stack) {
            // 跟注就等于全下
            act = s > 0.72 ? "call" : "fold";
        } else {
            boolean cheap = toCall <= bb * 2;
            if (s > 0.82) {
                if (ThreadLocalRandom.current().nextDouble() < 0.6 && minRaiseTo - me.getBetThisRound() < stack) {
                    act = "raise";
                    amount = minRaiseTo;
                } else {
                    act = "call";
                }
            } else if (s > 0.58) {
                act = "call";
            } else if (s > 0.42 && cheap) {
                act = "call";
            } else if (cheap && ThreadLocalRandom.current().nextDouble() < 0.12) {
                act = "call";  // 偶尔松一手
            } else {
                act = "fold";
            }
        }
        log.debug("机器人行动: roomId={}, robotId={}, s={}, act={}, amount={}", roomId, robotId,
                String.format("%.2f", s), act, amount);
        gameService.action(roomId, robotId, act, amount);
    }

    /**
     * 粗略牌力 0~1:翻牌前看起手牌,翻牌后看与公共牌的成牌程度
     */
    private double strength(long robotId, DzRoom room) {
        List<Card> hole = holeCards.get(robotId);
        if (hole == null || hole.size() != 2) return 0.4;
        int r1 = hole.get(0).getRank();
        int r2 = hole.get(1).getRank();
        boolean pocketPair = r1 == r2;
        boolean suited = hole.get(0).getSuit() == hole.get(1).getSuit();
        int hi = Math.max(r1, r2);
        int lo = Math.min(r1, r2);

        // 起手牌基础分
        double base;
        if (pocketPair) {
            base = 0.55 + (r1 - 2) * 0.02;          // 22=0.55 ... AA=0.79
        } else if (hi == 14) {
            base = 0.45 + (lo - 2) * 0.015;          // A2=0.45 ... AK=0.61
        } else if (hi >= 12 && lo >= 10) {
            base = 0.52;                             // KQ/KJ/QJ/KT/QT 档
        } else {
            base = 0.28 + hi * 0.01 + (suited ? 0.04 : 0) + (hi - lo <= 2 ? 0.03 : 0);
        }

        List<Card> board = room.getBoard();
        if (board.isEmpty()) return base;

        // 翻牌后:数成牌
        int match1 = 0, match2 = 0;
        for (Card b : board) {
            if (b.getRank() == r1) match1++;
            if (b.getRank() == r2) match2++;
        }
        if (pocketPair && match1 >= 1) return 0.95;                 // 暗三条+
        if (match1 >= 2 || match2 >= 2) return 0.9;                 // 明三条
        if (match1 >= 1 && match2 >= 1) return 0.85;                // 两对
        if (pocketPair) {
            // 超对/中对:口袋对 vs 公共牌最大张
            int boardMax = board.stream().mapToInt(Card::getRank).max().orElse(0);
            return r1 > boardMax ? 0.8 : 0.55;
        }
        if (match1 >= 1 || match2 >= 1) {
            int pairRank = match1 >= 1 ? r1 : r2;
            int boardMax = board.stream().mapToInt(Card::getRank).max().orElse(0);
            return pairRank >= boardMax ? 0.68 : 0.52;              // 顶对/中对
        }
        // 没成牌:高牌打折
        return Math.max(0.15, base - 0.18);
    }

    // ================================================================

    private boolean isMyRobot(long roomId, long userId) {
        Set<Long> robots = roomRobots.get(roomId);
        return robots != null && robots.contains(userId);
    }

    private long randDelay() {
        return robotMinDelay() + ThreadLocalRandom.current().nextLong(Math.max(1, robotMaxDelay() - robotMinDelay()));
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
