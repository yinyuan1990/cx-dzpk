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
    private final com.chexuan.dzpk.db.DiamondService diamondService;

    public DzRobotAdminService(JdbcTemplate jdbc, RobotRegistry registry, DzClubService clubService,
                               DzRoomManager roomManager, @Lazy DzGameService gameService,
                               RobotService robotService, com.chexuan.dzpk.db.DiamondService diamondService) {
        this.jdbc = jdbc;
        this.registry = registry;
        this.clubService = clubService;
        this.roomManager = roomManager;
        this.gameService = gameService;
        this.robotService = robotService;
        this.diamondService = diamondService;
    }

    // ==================== 德州昵称词库(纯中文德州术语风,对齐扯旋"按语料分桶"思路) ====================
    // 组合空间:完整词 78 + 前缀×动作 16×22=352 + 场景×称号 12×14=168 + 叠字小名 ~5000 → 同俱乐部不重名

    /** 完整昵称:德州黑话/牌型/打法风 */
    private static final String[] NICK_WHOLE = {
            "河牌奇迹", "坚果在手", "翻前梭哈", "慢玩大师", "底池怪兽", "常胜鲨鱼", "跟注站",
            "诈唬艺术家", "大盲守卫", "抢盲专家", "读牌大师", "数学玩家", "位置玩家", "紧凶打法",
            "松到没边", "石头一块", "鱼塘塘主", "翻牌击中", "转牌反超", "河牌绝杀", "口袋对王",
            "暗三条", "两头顺子", "卡顺听牌", "同花听牌", "葫芦满天", "四条带走", "天顺到手",
            "隐形坚果", "干燥牌面", "湿润牌面", "薄价值下注", "超池全下", "极化范围", "平衡大师",
            "剥削打法", "期望值信徒", "全下之王", "弃牌冠军", "过牌加注", "三条街价值", "免费看牌",
            "买保险专业户", "从不买保险", "牌运正旺", "手气爆棚", "稳如老狗", "输完就走",
            "赢了加鸡腿", "通宵战神", "牌桌常客", "从不虚张", "一手好牌", "锦鲤本人", "气运之子",
            "倒霉蛋", "老江湖", "拼命三郎", "熬夜冠军", "常胜军", "钉子户", "守夜人", "老油条",
            "冷面杀手", "面无表情", "墨镜大哥", "帽檐压低", "筹码城堡", "码农上桌", "深夜鲨鱼",
            "凌晨三点半", "最后一个筹码", "翻倍或回家", "短码专家", "深码怪", "单挑王", "多人底池混子"
    };

    /** 前缀 × 动作(如"专逮全下""河牌就偷鸡") */
    private static final String[] NICK_PREFIX = {
            "专逮", "就爱", "天天", "半夜", "从不", "最爱", "一直", "专门",
            "开局就", "上桌就", "见牌就", "翻前", "翻牌圈", "转牌就", "河牌只", "醒来就"
    };
    private static final String[] NICK_CORE = {
            "全下", "偷鸡", "诈唬", "跟注", "弃牌", "加注", "看牌", "梭哈", "慢玩", "快打",
            "守盲", "抢盲", "补牌", "听花", "卡顺", "平跟", "过牌", "埋牌", "亮牌", "打光",
            "追花", "偷底池"
    };

    /** 场景 × 称号(如"深夜牌神""鱼塘之王") */
    private static final String[] NICK_PLACE = {
            "深夜", "凌晨", "周末", "鱼塘", "牌桌", "河边", "转角", "长牌桌", "短牌桌", "线上", "地下室", "阳台"
    };
    private static final String[] NICK_TITLE = {
            "牌神", "鲨鱼", "之王", "职业哥", "老炮", "常客", "小鱼", "大神", "扛把子", "钉子户", "守卫", "猎人", "渔夫", "刺客"
    };

    /** 叠字小名(真人感稀释,组合空间最大) */
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
        if (bucket < 25) return NICK_WHOLE[r.nextInt(NICK_WHOLE.length)];
        if (bucket < 50) return NICK_PREFIX[r.nextInt(NICK_PREFIX.length)] + NICK_CORE[r.nextInt(NICK_CORE.length)];
        if (bucket < 65) return NICK_PLACE[r.nextInt(NICK_PLACE.length)] + NICK_TITLE[r.nextInt(NICK_TITLE.length)];
        // 叠字小名:勇勇 / 小霞 / 军哥
        char c = NICK_DOUBLE_CHARS.charAt(r.nextInt(NICK_DOUBLE_CHARS.length()));
        String[] pre = {"", "", "小", "老", "阿"};
        String[] suf = {"", "", "哥", "姐", "爷", "儿"};
        String core = r.nextInt(100) < 50 ? ("" + c + c) : String.valueOf(c);
        String name = pre[r.nextInt(pre.length)] + core + suf[r.nextInt(suf.length)];
        return name.length() < 2 ? name + c : name;
    }

    /** 本地头像池(16 个);生成时优先分配该俱乐部机器人还没用过的,用尽才随机(对齐扯旋一人一图思路) */
    private String pickAvatar(long clubId) {
        Set<String> usedAv = new HashSet<>(jdbc.queryForList(
                "SELECT u.avatar FROM dz_user u JOIN dz_club_member m ON m.user_id = u.id " +
                        "WHERE m.club_id = ? AND u.is_robot = 1", String.class, clubId));
        List<String> free = new ArrayList<>();
        for (int i = 1; i <= 16; i++) {
            String av = "/assets/table/heads/head_" + i + ".png";
            if (!usedAv.contains(av)) free.add(av);
        }
        if (!free.isEmpty()) return free.get(ThreadLocalRandom.current().nextInt(free.size()));
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
                long uid = insertRobotUser(nick, pickAvatar(clubId));
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

    // ==================== 成员列表(分类) / 一键补分 / 批量头像 / 一键改名 ====================

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

    /**
     * 俱乐部全部成员(分类:all=全部 / human=真人 / robot=机器人;分页):
     * 昵称/头像/角色/积分/机器人标记/是否在桌。
     */
    public Map<String, Object> members(long clubId, String type, int page, int size) {
        String cond = "robot".equalsIgnoreCase(type) ? " AND u.is_robot = 1"
                : "human".equalsIgnoreCase(type) ? " AND u.is_robot = 0" : "";
        int p = Math.max(0, page);
        int s = Math.max(1, Math.min(100, size));
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT u.id AS userId, u.nickname, u.avatar, u.number_id AS numberId, u.is_robot AS isRobot, " +
                        "m.role, m.score " +
                        "FROM dz_user u JOIN dz_club_member m ON m.user_id = u.id AND m.club_id = ? AND m.status = 1" +
                        cond + " ORDER BY m.role DESC, u.id LIMIT " + s + " OFFSET " + (p * s), clubId);
        Set<Long> seated = seatedUserIds();
        for (Map<String, Object> r : rows) {
            long uid = ((Number) r.get("userId")).longValue();
            r.put("inRoom", seated.contains(uid));
        }
        // filteredTotal=当前分类的总数(分页用);total/robotCount=全体统计(顶部展示)
        Integer filtered = jdbc.queryForObject(
                "SELECT COUNT(*) FROM dz_user u JOIN dz_club_member m ON m.user_id = u.id " +
                        "WHERE m.club_id = ? AND m.status = 1" + cond, Integer.class, clubId);
        Integer total = jdbc.queryForObject(
                "SELECT COUNT(*) FROM dz_club_member WHERE club_id = ? AND status = 1", Integer.class, clubId);
        Integer robots = jdbc.queryForObject(
                "SELECT COUNT(*) FROM dz_user u JOIN dz_club_member m ON m.user_id = u.id " +
                        "WHERE m.club_id = ? AND m.status = 1 AND u.is_robot = 1", Integer.class, clubId);
        return Map.of("code", 0, "members", rows,
                "filteredTotal", filtered == null ? rows.size() : filtered,
                "page", p, "size", s,
                "total", total == null ? rows.size() : total,
                "robotCount", robots == null ? 0 : robots);
    }

    /**
     * 批量随机换头像(对齐扯旋 randomAssignAvatars,一人一图):
     * urls 去重后必须 ≥ 该俱乐部机器人数,洗牌后一一分配,绝不重复。
     */
    public Map<String, Object> assignAvatars(long clubId, List<String> urls) {
        if (urls == null || urls.isEmpty()) return Map.of("code", 1, "msg", "没有可用的头像图片");
        List<Long> ids = jdbc.queryForList(
                "SELECT u.id FROM dz_user u JOIN dz_club_member m ON m.user_id = u.id AND m.club_id = ? AND m.status = 1 " +
                        "WHERE u.is_robot = 1 ORDER BY u.id", Long.class, clubId);
        if (ids.isEmpty()) return Map.of("code", 1, "msg", "该俱乐部没有机器人");
        List<String> pool = new ArrayList<>(new java.util.LinkedHashSet<>(urls)); // URL 去重
        if (pool.size() < ids.size()) {
            return Map.of("code", 1, "msg", "头像图片不足:去重后 " + pool.size() + " 张,机器人 "
                    + ids.size() + " 个。一人一图,请补足后再分配");
        }
        java.util.Collections.shuffle(pool, ThreadLocalRandom.current());
        int changed = 0;
        for (int i = 0; i < ids.size(); i++) {
            jdbc.update("UPDATE dz_user SET avatar = ? WHERE id = ?", pool.get(i), ids.get(i));
            changed++;
        }
        log.info("机器人批量换头像: clubId={}, changed={}", clubId, changed);
        return Map.of("code", 0, "changed", changed);
    }

    /** 一键随机改名(德州昵称词库,同俱乐部机器人不重名) */
    public Map<String, Object> rename(long clubId) {
        List<Long> ids = jdbc.queryForList(
                "SELECT u.id FROM dz_user u JOIN dz_club_member m ON m.user_id = u.id AND m.club_id = ? AND m.status = 1 " +
                        "WHERE u.is_robot = 1 ORDER BY u.id", Long.class, clubId);
        if (ids.isEmpty()) return Map.of("code", 1, "msg", "该俱乐部没有机器人");
        Set<String> used = new HashSet<>();
        int changed = 0;
        for (long uid : ids) {
            String nick = randomNickname(used);
            jdbc.update("UPDATE dz_user SET nickname = ? WHERE id = ?", nick, uid);
            jdbc.update("UPDATE dz_club_member SET nickname = ? WHERE club_id = ? AND user_id = ?", nick, clubId, uid);
            changed++;
        }
        log.info("机器人一键改名: clubId={}, changed={}", clubId, changed);
        return Map.of("code", 0, "changed", changed);
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

        // ★ 预检群主钻石(坐下是异步的,失败只会私发给机器人、接口感知不到——
        //   这里提前用与 sitDown 完全相同的规则拦下来,给管理台一个明确的错误提示)
        if (room.getSettleTimeMins() > 0) {
            long sitCost = gameService.ownerDiamondCostFor(room);
            if (sitCost > 0) {
                long owner = clubService.ownerUserId(clubId);
                if (owner > 0 && diamondService.hasMainAccount(owner)
                        && diamondService.balance(owner) < sitCost) {
                    return Map.of("code", 1, "msg", "群主钻石不足(需 " + sitCost + " 钻,当前 "
                            + diamondService.balance(owner) + " 钻),机器人和真人都无法坐下。"
                            + "请到「用户管理」给群主(ID " + owner + ")充钻石");
                }
            }
        }

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
        if (freeSeats.isEmpty()) return Map.of("code", 1, "msg", "桌上没有空位了");

        // 实际派数 = min(请求数, 空闲机器人数, 空座位数):6人桌已坐1人再派8个 → 只上5个
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
        String note = sent < count
                ? (freeSeats.size() < pool.size() ? "空位只有 " + freeSeats.size() + " 个" : "空闲机器人只有 " + pool.size() + " 个")
                : "";
        return Map.of("code", 0, "deployed", sent, "requested", count,
                "poolIdle", pool.size() - sent, "note", note);
    }

    private long randomBuyin(DzRoom room) {
        long lo = room.getMinBuyin();
        long hi = Math.min(room.getMaxBuyin(), lo * 3);
        return lo + ThreadLocalRandom.current().nextLong(Math.max(1, hi - lo + 1));
    }
}
