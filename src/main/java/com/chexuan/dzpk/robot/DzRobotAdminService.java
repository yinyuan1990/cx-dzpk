package com.chexuan.dzpk.robot;

import com.chexuan.dzpk.club.DzClubService;
import com.chexuan.dzpk.game.model.DzPlayer;
import com.chexuan.dzpk.game.model.DzRoom;
import com.chexuan.dzpk.game.service.DzGameService;
import com.chexuan.dzpk.game.service.DzRoomManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.security.MessageDigest;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 机器人后台管理(对齐扯旋 RobotAdminService,简化版):
 * 机器人 = dz_user 里 is_robot=1 的真实账号,生成时即加入俱乐部成为真实成员并上积分——
 * 跟有没有牌局无关。之后从池子里"派上桌",带入扣它自己的俱乐部积分,全程走真人流程。
 */
@Slf4j
@Service
public class DzRobotAdminService {

    private final JdbcTemplate jdbc;
    private final RobotRegistry registry;
    private final DzClubService clubService;
    private final DzRoomManager roomManager;
    private final DzGameService gameService;
    private final RobotService robotService;

    public DzRobotAdminService(JdbcTemplate jdbc, RobotRegistry registry, DzClubService clubService,
                               DzRoomManager roomManager, @Lazy DzGameService gameService,
                               RobotService robotService) {
        this.jdbc = jdbc;
        this.registry = registry;
        this.clubService = clubService;
        this.roomManager = roomManager;
        this.gameService = gameService;
        this.robotService = robotService;
    }

    // ==================== 昵称词库(扯旋风格,简化版:组合空间约 3000 种) ====================

    private static final String[] NICK_PREFIX = {
            "专逮", "就爱", "天天", "半夜", "从不", "最爱", "一直", "专门", "开局就", "上桌就",
            "见牌就", "逢人就", "隔壁", "楼下", "巷子头", "三天两头", "一摸牌就", "老", "小", "阿"
    };
    private static final String[] NICK_CORE = {
            "全下", "偷鸡", "诈唬", "跟注", "弃牌", "加注", "看牌", "梭哈", "翻牌", "河杀",
            "顶对", "暗三", "两头顺", "同花", "口袋对", "慢打", "快攻", "守盲", "抢盲", "补牌"
    };
    private static final String[] NICK_WHOLE = {
            "牌运正旺", "手气爆棚", "稳如老狗", "输完就走", "赢了加鸡腿", "通宵战神", "牌桌常客",
            "从不虚张", "一手好牌", "锦鲤本人", "气运之子", "倒霉蛋", "老江湖", "拼命三郎",
            "熬夜冠军", "常胜军", "钉子户", "守夜人", "扛把子", "老油条"
    };
    private static final String NICK_DOUBLE_CHARS =
            "勇强娟花妹龙虎娃军林伟敏芳英霞燕梅兰菊蓉丽刚建波涛超磊静娜杰鹏飞洪贵富明亮华国平安康宁雪冬夏秋春月星云雨风山海";

    private String randomNickname(Set<String> used) {
        for (int i = 0; i < 60; i++) {
            String name = pickNickname();
            if (used.add(name)) return name;
        }
        // 组合空间将尽:双词拼接兜底
        String name = pickNickname() + pickNickname();
        used.add(name);
        return name;
    }

    private String pickNickname() {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        int bucket = r.nextInt(100);
        if (bucket < 30) return NICK_WHOLE[r.nextInt(NICK_WHOLE.length)];
        if (bucket < 65) return NICK_PREFIX[r.nextInt(NICK_PREFIX.length)] + NICK_CORE[r.nextInt(NICK_CORE.length)];
        // 叠字小名:勇勇 / 小霞 / 军哥
        char c = NICK_DOUBLE_CHARS.charAt(r.nextInt(NICK_DOUBLE_CHARS.length()));
        String[] pre = {"", "", "小", "老", "阿"};
        String[] suf = {"", "", "哥", "姐", "爷", "儿"};
        String core = r.nextInt(100) < 50 ? ("" + c + c) : String.valueOf(c);
        String name = pre[r.nextInt(pre.length)] + core + suf[r.nextInt(suf.length)];
        return name.length() < 2 ? name + c : name;
    }

    private static String randomAvatar() {
        return "/assets/table/heads/head_" + (1 + ThreadLocalRandom.current().nextInt(16)) + ".png";
    }

    // ==================== 一键生成(与牌局无关,生成即入会+上分) ====================

    /**
     * 一键生成俱乐部机器人:建 dz_user(is_robot=1,随机昵称/头像) → 加入俱乐部 → 初始上分。
     */
    public synchronized Map<String, Object> generate(long clubId, int count, long initScore) {
        Long owner = jdbc.queryForObject(
                "SELECT creator_user_id FROM dz_club WHERE id = ? AND state = 1", Long.class, clubId);
        if (owner == null) return Map.of("code", 1, "msg", "俱乐部不存在");

        // 同俱乐部现有机器人昵称去重
        Set<String> used = new HashSet<>(jdbc.queryForList(
                "SELECT u.nickname FROM dz_user u JOIN dz_club_member m ON m.user_id = u.id " +
                        "WHERE m.club_id = ? AND u.is_robot = 1", String.class, clubId));

        int created = 0;
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < Math.max(1, Math.min(50, count)); i++) {
            try {
                String nick = randomNickname(used);
                long uid = insertRobotUser(nick, randomAvatar());
                joinClub(clubId, uid, nick);
                if (initScore > 0) {
                    clubService.creditScoreForGame(clubId, uid, initScore, 14, "机器人初始上分");
                }
                registry.add(uid);
                ids.add(uid);
                created++;
            } catch (Exception e) {
                log.warn("生成机器人失败(跳过): clubId={}", clubId, e);
            }
        }
        log.info("一键生成机器人: clubId={}, created={}, initScore={}", clubId, created, initScore);
        return Map.of("code", 0, "created", created, "userIds", ids);
    }

    private long insertRobotUser(String nick, String avatar) throws Exception {
        String phone = robotPhone();
        String salt = HexFormat.of().formatHex(randomBytes(8));
        jdbc.update("INSERT INTO dz_user (phone, number_id, password_hash, salt, nickname, avatar, " +
                        "invite_code, register_device, diamond, state, is_robot, created_at) " +
                        "VALUES (?,?,?,?,?,?,'',3,0,1,1,?)",
                phone, numberId(), sha256(salt + "Robot#" + phone), salt, nick, avatar,
                new Timestamp(System.currentTimeMillis()));
        Long id = jdbc.queryForObject("SELECT id FROM dz_user WHERE phone = ?", Long.class, phone);
        if (id == null) throw new IllegalStateException("插入机器人账号失败");
        return id;
    }

    private void joinClub(long clubId, long userId, String nick) {
        long inviteCode = 100000 + ThreadLocalRandom.current().nextLong(900000);
        jdbc.update("INSERT INTO dz_club_member (club_id, user_id, nickname, role, parent_user_id, level, " +
                        "invite_code, partner_rate, score, status, created_at) VALUES (?,?,?,1,0,0,?,0,0,1,?)",
                clubId, userId, nick, inviteCode, new Timestamp(System.currentTimeMillis()));
    }

    private String robotPhone() {
        for (int i = 0; i < 20; i++) {
            String phone = "19" + String.format("%09d", ThreadLocalRandom.current().nextInt(1_000_000_000));
            Integer c = jdbc.queryForObject("SELECT COUNT(*) FROM dz_user WHERE phone = ?", Integer.class, phone);
            if (c == null || c == 0) return phone;
        }
        return "rb" + (System.nanoTime() % 1_000_000_000_000L);
    }

    private String numberId() {
        for (int i = 0; i < 10; i++) {
            String no = String.valueOf(100000 + ThreadLocalRandom.current().nextInt(900000));
            Integer c = jdbc.queryForObject("SELECT COUNT(*) FROM dz_user WHERE number_id = ?", Integer.class, no);
            if (c == null || c == 0) return no;
        }
        return "";
    }

    private static byte[] randomBytes(int n) {
        byte[] b = new byte[n];
        ThreadLocalRandom.current().nextBytes(b);
        return b;
    }

    private static String sha256(String s) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes()));
    }

    // ==================== 池子列表 / 一键补分 ====================

    /** 俱乐部机器人池:昵称/头像/积分/是否在桌 */
    public Map<String, Object> list(long clubId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT u.id AS userId, u.nickname, u.avatar, m.score " +
                        "FROM dz_user u JOIN dz_club_member m ON m.user_id = u.id AND m.club_id = ? AND m.status = 1 " +
                        "WHERE u.is_robot = 1 ORDER BY u.id", clubId);
        Set<Long> seated = seatedUserIds();
        for (Map<String, Object> r : rows) {
            long uid = ((Number) r.get("userId")).longValue();
            r.put("inRoom", seated.contains(uid));
        }
        return Map.of("code", 0, "robots", rows);
    }

    /** 一键补分:给该俱乐部全部机器人各加 amount 积分 */
    public Map<String, Object> topUp(long clubId, long amount) {
        if (amount <= 0) return Map.of("code", 1, "msg", "金额必须大于 0");
        List<Long> ids = jdbc.queryForList(
                "SELECT u.id FROM dz_user u JOIN dz_club_member m ON m.user_id = u.id AND m.club_id = ? AND m.status = 1 " +
                        "WHERE u.is_robot = 1", Long.class, clubId);
        int n = 0;
        for (long uid : ids) {
            try {
                clubService.creditScoreForGame(clubId, uid, amount, 14, "机器人一键上分");
                n++;
            } catch (Exception e) {
                log.warn("机器人上分失败: clubId={}, userId={}", clubId, uid, e);
            }
        }
        return Map.of("code", 0, "affected", n, "amount", amount);
    }

    /** 全部房间当前已坐下的 userId(判断机器人空闲/在桌) */
    private Set<Long> seatedUserIds() {
        Set<Long> seated = new HashSet<>();
        for (DzRoom r : roomManager.list()) {
            for (DzPlayer p : r.getSeats()) {
                if (p != null) seated.add(p.getUserId());
            }
        }
        return seated;
    }

    // ==================== 派上桌 / 撤回(走真人流程,带入扣真实俱乐部积分) ====================

    /**
     * 从该房间所属俱乐部的机器人池取 count 个空闲机器人上桌:
     * 进房 → 坐空位 → 带入(积分不足自动补足)。牌局内的行动由 RobotService 按广播驱动。
     */
    public synchronized Map<String, Object> deploy(long roomId, int count) {
        DzRoom room = roomManager.get(roomId);
        if (room == null) return Map.of("code", 1, "msg", "房间不存在");
        long clubId = room.getClubId();
        if (clubId <= 0) return Map.of("code", 1, "msg", "只支持俱乐部房间(大厅房有自动陪打)");

        // 空闲机器人 = 该俱乐部机器人 - 已在任何桌上的
        List<Map<String, Object>> pool = jdbc.queryForList(
                "SELECT u.id AS userId, u.nickname, u.avatar FROM dz_user u " +
                        "JOIN dz_club_member m ON m.user_id = u.id AND m.club_id = ? AND m.status = 1 " +
                        "WHERE u.is_robot = 1 ORDER BY u.id", clubId);
        Set<Long> seated = seatedUserIds();
        pool.removeIf(r -> seated.contains(((Number) r.get("userId")).longValue()));
        if (pool.isEmpty()) return Map.of("code", 1, "msg", "该俱乐部没有空闲机器人,先一键生成");

        // 空座位
        List<Integer> freeSeats = new ArrayList<>();
        for (int i = 0; i < room.getMaxPlayers(); i++) {
            if (room.getSeats()[i] == null) freeSeats.add(i);
        }

        int n = Math.min(Math.max(1, count), Math.min(pool.size(), freeSeats.size()));
        int sent = 0;
        for (int i = 0; i < n; i++) {
            Map<String, Object> bot = pool.get(i);
            long uid = ((Number) bot.get("userId")).longValue();
            String nick = String.valueOf(bot.get("nickname"));
            String avatar = String.valueOf(bot.get("avatar"));
            int seat = freeSeats.get(i);
            long buyin = randomBuyin(room);
            // 积分不足自动补足(测试永远能带入)
            long score = clubService.score(clubId, uid);
            if (score < buyin) {
                clubService.creditScoreForGame(clubId, uid, buyin - score, 14, "机器人带入自动补分");
            }
            robotService.registerRobot(roomId, uid); // 先注册再坐下,不漏 TURN
            gameService.enterRoom(roomId, uid, nick);
            gameService.sitDown(roomId, uid, seat, null, avatar);
            gameService.buyIn(roomId, uid, buyin);
            sent++;
            log.info("机器人上桌: roomId={}, userId={}, nick={}, seat={}, buyin={}", roomId, uid, nick, seat, buyin);
        }
        return Map.of("code", 0, "deployed", sent, "poolIdle", pool.size() - sent);
    }

    private long randomBuyin(DzRoom room) {
        long lo = room.getMinBuyin();
        long hi = Math.min(room.getMaxBuyin(), lo * 3);
        return lo + ThreadLocalRandom.current().nextLong(Math.max(1, hi - lo + 1));
    }
}
