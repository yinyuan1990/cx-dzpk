package com.chexuan.dzpk.club;

import com.chexuan.dzpk.db.DiamondService;
import com.chexuan.dzpk.game.service.WalletService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 德州独立俱乐部(规则对齐扯旋):
 *   创建(6位编号,扣公用钻石) / 申请加入(码先当俱乐部号再当邀请码) / 群主管理员审批 /
 *   角色 1成员 2管理员 3创建者 4合伙人 / 推荐树(parent_user_id) /
 *   合伙人分成:抽水沿链自上而下按 partner_rate 多层让利。
 * 业务异常统一抛 ClubException(msg 直接回给前端)。
 */
@Slf4j
@Service
public class DzClubService {

    public static final int ROLE_MEMBER = 1;
    public static final int ROLE_ADMIN = 2;
    public static final int ROLE_OWNER = 3;
    public static final int ROLE_PARTNER = 4;

    public static class ClubException extends RuntimeException {
        public ClubException(String msg) {
            super(msg);
        }
    }

    private final JdbcTemplate jdbc;
    private final DiamondService diamondService;
    private final WalletService walletService;
    /** 系统参数中心(可为 null,退回 @Value 默认) */
    private final com.chexuan.dzpk.config.DzConfigService cfg;

    /** 创建俱乐部扣钻石(公用钻石,主库 user.diamond);0=不扣。扯旋普通俱乐部 200 钻 */
    @Value("${dzpk.create-club-diamond-cost:0}")
    private long createClubDiamondCost;

    /** 每人最多创建俱乐部数(对齐扯旋默认 10) */
    @Value("${dzpk.max-club-per-user:10}")
    private int maxClubPerUser;

    @org.springframework.beans.factory.annotation.Autowired
    public DzClubService(JdbcTemplate jdbc, DiamondService diamondService, WalletService walletService,
                         com.chexuan.dzpk.config.DzConfigService cfg) {
        this.jdbc = jdbc;
        this.diamondService = diamondService;
        this.walletService = walletService;
        this.cfg = cfg;
    }

    /** 单测用 */
    public DzClubService(JdbcTemplate jdbc, DiamondService diamondService, WalletService walletService) {
        this(jdbc, diamondService, walletService, null);
    }

    private long clubDiamondCost() {
        return cfg != null ? cfg.getLong("create_club_diamond_cost", createClubDiamondCost) : createClubDiamondCost;
    }

    private int clubLimit() {
        return cfg != null ? cfg.getInt("max_club_per_user", maxClubPerUser) : maxClubPerUser;
    }

    private void requireDb() {
        if (jdbc == null) throw new ClubException("俱乐部服务未启用");
    }

    private static Timestamp now() {
        return new Timestamp(System.currentTimeMillis());
    }

    // ================================================================
    // 创建
    // ================================================================

    /**
     * 创建俱乐部(参数对齐扯旋 CreateClubRequest:名称+简介+头像,公告创建时强制空串):
     *   名称:显示宽度 ≤ club_name_max_length×2(汉字宽2/半角宽1,默认4汉字宽),禁纯数字;
     *   简介:必填 ≤100 字;头像:必填(URL/标识)。
     * 返回 {clubId, clubNo, name, myInviteCode, diamondCost, diamond}
     */
    public Map<String, Object> createClub(long userId, String nickname, String name, String remark, String avatar) {
        requireDb();
        if (name == null || name.isBlank()) throw new ClubException("俱乐部名称不能为空");
        name = name.trim();
        if (name.chars().allMatch(Character::isDigit)) throw new ClubException("俱乐部名称不能是纯数字");
        int maxLen = cfg != null ? cfg.getInt("club_name_max_length", 4) : 4;
        if (displayWidth(name) > maxLen * 2) throw new ClubException("俱乐部名称最长 " + maxLen + " 个汉字(或等宽字符)");
        if (remark == null || remark.isBlank()) throw new ClubException("俱乐部简介不能为空");
        remark = remark.trim();
        if (remark.length() > 100) throw new ClubException("俱乐部简介不能超过100个字符");
        if (avatar == null || avatar.isBlank()) throw new ClubException("请选择俱乐部头像");
        if (avatar.length() > 255) throw new ClubException("头像地址过长");
        Integer cnt = jdbc.queryForObject(
                "SELECT COUNT(*) FROM dz_club WHERE creator_user_id = ? AND state <> 2", Integer.class, userId);
        if (cnt != null && cnt >= clubLimit()) throw new ClubException("最多创建 " + clubLimit() + " 个俱乐部");

        long cost = 0;
        long needDiamond = clubDiamondCost();
        if (needDiamond > 0 && diamondService.hasMainAccount(userId)) {
            if (!diamondService.debit(userId, needDiamond, "create_club", "德州创建俱乐部")) {
                throw new ClubException("钻石不足,创建俱乐部需要 " + needDiamond + " 钻石");
            }
            cost = needDiamond;
        }

        long clubNo = uniqueNo("SELECT COUNT(*) FROM dz_club WHERE club_no = ? AND state <> 2");
        // 公告创建时强制空串(对齐扯旋:公告只能建后走更新)
        jdbc.update("INSERT INTO dz_club (club_no, name, remark, avatar, notice, creator_user_id, state, diamond_cost, created_at) " +
                        "VALUES (?,?,?,?,'',?,1,?,?)",
                clubNo, name, remark, avatar, userId, cost, now());
        Long clubId = jdbc.queryForObject("SELECT id FROM dz_club WHERE club_no = ? AND creator_user_id = ? " +
                "ORDER BY id DESC LIMIT 1", Long.class, clubNo, userId);

        long inviteCode = uniqueInviteCode(clubId);
        jdbc.update("INSERT INTO dz_club_member (club_id, user_id, nickname, role, parent_user_id, level, " +
                        "invite_code, partner_rate, status, created_at) VALUES (?,?,?,?,0,0,?,0,1,?)",
                clubId, userId, nickname, ROLE_OWNER, inviteCode, now());
        log.info("创建俱乐部: clubId={}, clubNo={}, name={}, creator={}, 钻石={}", clubId, clubNo, name, userId, cost);

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("clubId", clubId);
        m.put("clubNo", clubNo);
        m.put("name", name);
        m.put("remark", remark);
        m.put("avatar", avatar);
        m.put("myInviteCode", inviteCode);
        m.put("diamondCost", cost);
        m.put("diamond", diamondService.balance(userId));
        return m;
    }

    /**
     * 修改俱乐部资料(对齐扯旋 /user/updateClub:头像/名称/简介/公告,群主/管理员)。
     * 校验口径与创建一致;公告可空(清空公告)≤200 字。
     */
    public Map<String, Object> updateClub(long clubId, long operatorId,
                                          String name, String remark, String avatar, String notice) {
        requireDb();
        requireOwnerOrAdmin(clubId, operatorId);
        if (name == null || name.isBlank()) throw new ClubException("俱乐部名称不能为空");
        name = name.trim();
        if (name.chars().allMatch(Character::isDigit)) throw new ClubException("俱乐部名称不能是纯数字");
        int maxLen = cfg != null ? cfg.getInt("club_name_max_length", 4) : 4;
        if (displayWidth(name) > maxLen * 2) throw new ClubException("俱乐部名称最长 " + maxLen + " 个汉字(或等宽字符)");
        if (remark == null || remark.isBlank()) throw new ClubException("俱乐部简介不能为空");
        remark = remark.trim();
        if (remark.length() > 100) throw new ClubException("俱乐部简介不能超过100个字符");
        if (avatar == null || avatar.isBlank()) throw new ClubException("请选择俱乐部头像");
        if (avatar.length() > 255) throw new ClubException("头像地址过长");
        notice = notice == null ? "" : notice.trim();
        if (notice.length() > 200) throw new ClubException("公告不能超过200个字符");

        jdbc.update("UPDATE dz_club SET name = ?, remark = ?, avatar = ?, notice = ? WHERE id = ? AND state = 1",
                name, remark, avatar, notice, clubId);
        log.info("修改俱乐部资料: clubId={}, operator={}, name={}", clubId, operatorId, name);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("clubId", clubId);
        m.put("name", name);
        m.put("remark", remark);
        m.put("avatar", avatar);
        m.put("notice", notice);
        return m;
    }

    /** 显示宽度:CJK 等全角算 2,半角算 1(对齐扯旋 NicknameValidator 口径) */
    private static int displayWidth(String s) {
        int w = 0;
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            w += cp > 0xFF ? 2 : 1;
            i += Character.charCount(cp);
        }
        return w;
    }

    /** 6 位唯一编号(100000-999999) */
    private long uniqueNo(String checkSql) {
        for (int i = 0; i < 20; i++) {
            long no = ThreadLocalRandom.current().nextLong(100000, 1000000);
            Integer c = jdbc.queryForObject(checkSql, Integer.class, no);
            if (c == null || c == 0) return no;
        }
        throw new ClubException("编号分配失败,请重试");
    }

    private long uniqueInviteCode(long clubId) {
        for (int i = 0; i < 20; i++) {
            long code = ThreadLocalRandom.current().nextLong(100000, 1000000);
            Integer c = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM dz_club_member WHERE club_id = ? AND invite_code = ?",
                    Integer.class, clubId, code);
            if (c == null || c == 0) return code;
        }
        throw new ClubException("邀请码分配失败,请重试");
    }

    // ================================================================
    // 查询
    // ================================================================

    /** 我加入的俱乐部列表 */
    public List<Map<String, Object>> myClubs(long userId) {
        requireDb();
        return jdbc.query("SELECT c.id, c.club_no, c.name, c.remark, c.avatar, c.notice, c.creator_user_id, " +
                        "m.role, m.invite_code, m.partner_rate, m.score, " +
                        "(SELECT COUNT(*) FROM dz_club_member x WHERE x.club_id = c.id AND x.status = 1) AS members, " +
                        "(SELECT COUNT(*) FROM dz_club_join_request r WHERE r.club_id = c.id AND r.status = 0) AS pendings " +
                        "FROM dz_club_member m JOIN dz_club c ON c.id = m.club_id " +
                        "WHERE m.user_id = ? AND m.status = 1 AND c.state = 1 ORDER BY c.id",
                (rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("clubId", rs.getLong("id"));
                    m.put("clubNo", rs.getLong("club_no"));
                    m.put("name", rs.getString("name"));
                    m.put("remark", rs.getString("remark"));
                    m.put("avatar", rs.getString("avatar"));
                    m.put("notice", rs.getString("notice"));
                    m.put("ownerUserId", rs.getLong("creator_user_id"));
                    m.put("myRole", rs.getInt("role"));
                    m.put("myInviteCode", rs.getLong("invite_code"));
                    m.put("myPartnerRate", rs.getInt("partner_rate"));
                    m.put("myScore", rs.getLong("score")); // 我的俱乐部积分(对齐扯旋 memberScore)
                    m.put("memberCount", rs.getLong("members"));
                    // 待审数只透给能审批的人
                    m.put("pendingCount", rs.getInt("role") >= ROLE_ADMIN && rs.getInt("role") != ROLE_PARTNER
                            ? rs.getLong("pendings") : 0);
                    return m;
                }, userId);
    }

    /** 成员信息(不在或非活跃返回 null) */
    public Map<String, Object> member(long clubId, long userId) {
        requireDb();
        List<Map<String, Object>> list = jdbc.query(
                "SELECT * FROM dz_club_member WHERE club_id = ? AND user_id = ? AND status = 1",
                (rs, i) -> memberRow(rs), clubId, userId);
        return list.isEmpty() ? null : list.get(0);
    }

    public boolean isMember(long clubId, long userId) {
        if (jdbc == null) return false;
        Integer c = jdbc.queryForObject(
                "SELECT COUNT(*) FROM dz_club_member WHERE club_id = ? AND user_id = ? AND status = 1",
                Integer.class, clubId, userId);
        return c != null && c > 0;
    }

    /** 建房权限:群主/管理员(对齐扯旋 canCreateGame) */
    public boolean canCreateRoom(long clubId, long userId) {
        Map<String, Object> m = member(clubId, userId);
        if (m == null) return false;
        int role = (int) m.get("role");
        return role == ROLE_OWNER || role == ROLE_ADMIN;
    }

    /** 成员列表 */
    public List<Map<String, Object>> members(long clubId, long userId) {
        requireDb();
        if (!isMember(clubId, userId)) throw new ClubException("不是俱乐部成员");
        return jdbc.query("SELECT * FROM dz_club_member WHERE club_id = ? AND status = 1 " +
                "ORDER BY role DESC, id", (rs, i) -> memberRow(rs), clubId);
    }

    private Map<String, Object> memberRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("userId", rs.getLong("user_id"));
        m.put("nickname", rs.getString("nickname"));
        m.put("role", rs.getInt("role"));
        m.put("parentUserId", rs.getLong("parent_user_id"));
        m.put("level", rs.getInt("level"));
        m.put("inviteCode", rs.getLong("invite_code"));
        m.put("partnerRate", rs.getInt("partner_rate"));
        m.put("score", rs.getLong("score"));
        return m;
    }

    // ================================================================
    // 俱乐部积分(对齐扯旋 v3.18:每俱乐部独立一本账,带入货币就是它)
    //   积分流向只有三条路:
    //   1. 增发/核销 — 只有群主,凭空给自己造分/销分(type 14/21)
    //   2. 上/下分   — 群主·管理员 ↔ 成员,转移操作者自己的分(16/10、17/11)
    //   3. 赠送      — 任何成员 → 同俱乐部成员,扣自己的分(12/13)
    //   游戏侧(type 对齐扯旋 game_score_log):带入1 / 起立返还2 / 提取红利15 / 逃跑惩罚19·20
    // ================================================================

    /** 积分流水类型名(对齐扯旋 GameScoreLogService.typeName) */
    public static String scoreTypeName(int type) {
        return switch (type) {
            case 1 -> "坐下带入";
            case 2 -> "起立返还";
            case 3 -> "每局结算";
            case 4 -> "系统调整";
            case 5 -> "其他";
            case 10, 16 -> "玩家上分";
            case 11, 17 -> "玩家下分";
            case 12, 13 -> "赠送积分";
            case 14 -> "增发积分";
            case 15 -> "提取红利";
            case 18 -> "礼物赠送";
            case 19, 20 -> "逃跑惩罚";
            case 21 -> "核销积分";
            default -> "未知";
        };
    }

    /** 俱乐部内积分余额(非成员返回 0) */
    public long score(long clubId, long userId) {
        if (jdbc == null) return 0;
        try {
            Long v = jdbc.queryForObject(
                    "SELECT score FROM dz_club_member WHERE club_id = ? AND user_id = ? AND status = 1",
                    Long.class, clubId, userId);
            return v != null ? v : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    /** 原子加/扣分(扣分余额不足抛 ClubException),返回变动后余额 */
    private long changeScore(long clubId, long userId, long delta, int type, long otherUserId, String remark) {
        requireDb();
        int n;
        if (delta < 0) {
            n = jdbc.update("UPDATE dz_club_member SET score = score + ? " +
                            "WHERE club_id = ? AND user_id = ? AND status = 1 AND score >= ?",
                    delta, clubId, userId, -delta);
            if (n <= 0) throw new ClubException("俱乐部积分不足");
        } else {
            n = jdbc.update("UPDATE dz_club_member SET score = score + ? " +
                            "WHERE club_id = ? AND user_id = ? AND status = 1",
                    delta, clubId, userId);
            if (n <= 0) throw new ClubException("不是俱乐部成员");
        }
        long after = score(clubId, userId);
        try {
            jdbc.update("INSERT INTO dz_score_log (club_id, user_id, other_user_id, type, amount, " +
                            "before_score, after_score, remark, created_at) VALUES (?,?,?,?,?,?,?,?,?)",
                    clubId, userId, otherUserId, type, delta, after - delta, after,
                    remark == null ? "" : remark, now());
        } catch (Exception e) {
            log.error("积分流水写入失败: clubId={}, userId={}", clubId, userId, e);
        }
        return after;
    }

    /** 群主增发(全系统唯一"印钞机",凭空给自己造分,type=14) */
    public Map<String, Object> ownerAddScore(long clubId, long operatorId, long amount) {
        requireDb();
        if (amount <= 0) throw new ClubException("金额必须大于 0");
        if (!isOwner(clubId, operatorId)) throw new ClubException("只有群主可以增发积分");
        long after = changeScore(clubId, operatorId, amount, 14, 0, "群主增发");
        return Map.of("op", "ownerAdd", "amount", amount, "score", after);
    }

    /** 群主核销(销毁自己账上的分,type=21) */
    public Map<String, Object> ownerBurnScore(long clubId, long operatorId, long amount) {
        requireDb();
        if (amount <= 0) throw new ClubException("金额必须大于 0");
        if (!isOwner(clubId, operatorId)) throw new ClubException("只有群主可以核销积分");
        long after = changeScore(clubId, operatorId, -amount, 21, 0, "群主核销");
        return Map.of("op", "ownerBurn", "amount", amount, "score", after);
    }

    /** 上分:群主/管理员把自己的分转给成员(操作者16-,成员10+) */
    public Map<String, Object> distributeScore(long clubId, long operatorId, long targetUserId, long amount) {
        requireDb();
        if (amount <= 0) throw new ClubException("金额必须大于 0");
        Map<String, Object> op = member(clubId, operatorId);
        if (op == null || ((int) op.get("role") != ROLE_OWNER && (int) op.get("role") != ROLE_ADMIN)) {
            throw new ClubException("只有群主/管理员可以上分");
        }
        if (member(clubId, targetUserId) == null) throw new ClubException("对方不是俱乐部成员");
        changeScore(clubId, operatorId, -amount, 16, targetUserId, "上分给 " + targetUserId);
        long after = changeScore(clubId, targetUserId, amount, 10, operatorId, "群主/管理员上分");
        return Map.of("op", "distribute", "userId", targetUserId, "amount", amount, "targetScore", after,
                "myScore", score(clubId, operatorId));
    }

    /** 下分:群主/管理员收回成员的分(成员11-,操作者17+) */
    public Map<String, Object> collectScore(long clubId, long operatorId, long targetUserId, long amount) {
        requireDb();
        if (amount <= 0) throw new ClubException("金额必须大于 0");
        Map<String, Object> op = member(clubId, operatorId);
        if (op == null || ((int) op.get("role") != ROLE_OWNER && (int) op.get("role") != ROLE_ADMIN)) {
            throw new ClubException("只有群主/管理员可以下分");
        }
        changeScore(clubId, targetUserId, -amount, 11, operatorId, "群主/管理员下分");
        long after = changeScore(clubId, operatorId, amount, 17, targetUserId, "从 " + targetUserId + " 下分");
        return Map.of("op", "collect", "userId", targetUserId, "amount", amount,
                "targetScore", score(clubId, targetUserId), "myScore", after);
    }

    /** 赠送:任何成员把自己的分送给同俱乐部成员(转出12-,转入13+) */
    public Map<String, Object> transferScore(long clubId, long operatorId, long targetUserId, long amount) {
        requireDb();
        if (amount <= 0) throw new ClubException("金额必须大于 0");
        if (operatorId == targetUserId) throw new ClubException("不能赠送给自己");
        if (member(clubId, operatorId) == null) throw new ClubException("不是俱乐部成员");
        if (member(clubId, targetUserId) == null) throw new ClubException("对方不是俱乐部成员");
        changeScore(clubId, operatorId, -amount, 12, targetUserId, "赠送给 " + targetUserId);
        changeScore(clubId, targetUserId, amount, 13, operatorId, "来自 " + operatorId + " 的赠送");
        return Map.of("op", "transfer", "userId", targetUserId, "amount", amount,
                "myScore", score(clubId, operatorId));
    }

    /** 积分流水(自己的;群主/管理员可查任何人) */
    public List<Map<String, Object>> scoreLogs(long clubId, long operatorId, long targetUserId, int limit) {
        requireDb();
        long queryUser = targetUserId > 0 ? targetUserId : operatorId;
        if (queryUser != operatorId) {
            Map<String, Object> op = member(clubId, operatorId);
            if (op == null || ((int) op.get("role") != ROLE_OWNER && (int) op.get("role") != ROLE_ADMIN)) {
                throw new ClubException("无权查看他人流水");
            }
        }
        return jdbc.query("SELECT type, amount, before_score, after_score, other_user_id, remark, created_at " +
                        "FROM dz_score_log WHERE club_id = ? AND user_id = ? ORDER BY id DESC LIMIT " +
                        Math.min(Math.max(limit, 1), 500),
                (rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    int type = rs.getInt("type");
                    m.put("type", type);
                    m.put("typeName", scoreTypeName(type));
                    m.put("amount", rs.getLong("amount"));
                    m.put("before", rs.getLong("before_score"));
                    m.put("after", rs.getLong("after_score"));
                    m.put("otherUserId", rs.getLong("other_user_id"));
                    m.put("remark", rs.getString("remark"));
                    m.put("time", rs.getTimestamp("created_at").getTime());
                    return m;
                }, clubId, queryUser);
    }

    // ---- 游戏侧(引擎调用,失败不抛只返回 false / 打日志) ----

    /** 带入扣分(type=1 坐下带入),积分不足返回 false */
    public boolean debitScoreForGame(long clubId, long userId, long amount, long roomId) {
        try {
            changeScore(clubId, userId, -amount, 1, roomId, "牌局带入 room=" + roomId);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** 游戏回分:起立返还2 / 提取红利15 / 逃跑惩罚(群主收)20 */
    public void creditScoreForGame(long clubId, long userId, long amount, int type, String remark) {
        if (amount <= 0) return;
        try {
            changeScore(clubId, userId, amount, type, 0, remark);
        } catch (Exception e) {
            log.error("游戏回分失败: clubId={}, userId={}, amount={}, type={}", clubId, userId, amount, type, e);
        }
    }

    /** 送礼扣分(type=18 礼物赠送,对齐扯旋 GiftService CLUB_SCORE 分支),积分不足抛异常;返回扣后余额 */
    public long debitScoreForGift(long clubId, long userId, long cost, String remark) {
        return changeScore(clubId, userId, -cost, 18, 0, remark);
    }

    /** 罚金扣分(type=19 逃跑惩罚,玩家侧;退筹已入账后再扣,余额必够) */
    public void fineScoreForGame(long clubId, long userId, long fine, long roomId) {
        if (fine <= 0) return;
        try {
            changeScore(clubId, userId, -fine, 19, roomId, "离桌罚金 room=" + roomId);
        } catch (Exception e) {
            log.error("罚金扣分失败: clubId={}, userId={}, fine={}", clubId, userId, fine, e);
        }
    }

    // ================================================================
    // 申请 / 审批
    // ================================================================

    /** 申请加入:code 先当俱乐部号,再当邀请码(对齐扯旋)。返回 {clubId, clubName, codeType} */
    public Map<String, Object> apply(long userId, String nickname, long code) {
        requireDb();
        Long clubId = null;
        int codeType = 1;
        long inviter = 0;
        List<Map<String, Object>> byNo = jdbc.query(
                "SELECT id FROM dz_club WHERE club_no = ? AND state = 1",
                (rs, i) -> Map.of("id", rs.getLong("id")), code);
        if (!byNo.isEmpty()) {
            clubId = (Long) byNo.get(0).get("id");
        } else {
            List<Map<String, Object>> byInvite = jdbc.query(
                    "SELECT m.club_id, m.user_id FROM dz_club_member m JOIN dz_club c ON c.id = m.club_id " +
                            "WHERE m.invite_code = ? AND m.status = 1 AND c.state = 1",
                    (rs, i) -> Map.of("clubId", rs.getLong("club_id"), "userId", rs.getLong("user_id")), code);
            if (!byInvite.isEmpty()) {
                clubId = (Long) byInvite.get(0).get("clubId");
                inviter = (Long) byInvite.get(0).get("userId");
                codeType = 2;
            }
        }
        if (clubId == null) throw new ClubException("俱乐部号/邀请码不存在");
        if (isMember(clubId, userId)) throw new ClubException("您已经是该俱乐部成员");

        Integer pending = jdbc.queryForObject(
                "SELECT COUNT(*) FROM dz_club_join_request WHERE club_id = ? AND user_id = ? AND status = 0",
                Integer.class, clubId, userId);
        if (pending != null && pending > 0) throw new ClubException("已申请,等待审批");
        // 历史已处理的申请删掉重新排队(被踢/被拒后可重申)
        jdbc.update("DELETE FROM dz_club_join_request WHERE club_id = ? AND user_id = ? AND status <> 0",
                clubId, userId);
        jdbc.update("INSERT INTO dz_club_join_request (club_id, user_id, nickname, code_used, code_type, " +
                        "inviter_user_id, status, created_at) VALUES (?,?,?,?,?,?,0,?)",
                clubId, userId, nickname, code, codeType, inviter, now());

        String clubName = jdbc.queryForObject("SELECT name FROM dz_club WHERE id = ?", String.class, clubId);
        log.info("申请入会: clubId={}, userId={}, code={}, type={}", clubId, userId, code, codeType);
        return Map.of("clubId", clubId, "clubName", clubName == null ? "" : clubName, "codeType", codeType);
    }

    /** 待审批列表(群主/管理员) */
    public List<Map<String, Object>> applyList(long clubId, long userId) {
        requireDb();
        requireOwnerOrAdmin(clubId, userId);
        return jdbc.query("SELECT id, user_id, nickname, code_used, code_type, inviter_user_id, created_at " +
                        "FROM dz_club_join_request WHERE club_id = ? AND status = 0 ORDER BY id",
                (rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("requestId", rs.getLong("id"));
                    m.put("userId", rs.getLong("user_id"));
                    m.put("nickname", rs.getString("nickname"));
                    m.put("codeType", rs.getInt("code_type"));
                    m.put("inviterUserId", rs.getLong("inviter_user_id"));
                    m.put("time", rs.getTimestamp("created_at").getTime());
                    return m;
                }, clubId);
    }

    /**
     * 全部俱乐部待审聚合(对齐扯旋 /user/getAllMyClubsJoinRequests):
     * 我是群主/管理员的所有俱乐部的待审申请,项带 clubId/clubName。顶栏消息弹框用。
     */
    public List<Map<String, Object>> applyListAll(long userId) {
        requireDb();
        return jdbc.query("SELECT r.id, r.club_id, c.name AS club_name, r.user_id, r.nickname, " +
                        "r.code_type, r.inviter_user_id, r.created_at " +
                        "FROM dz_club_join_request r " +
                        "JOIN dz_club c ON c.id = r.club_id AND c.state = 1 " +
                        "JOIN dz_club_member m ON m.club_id = r.club_id AND m.user_id = ? " +
                        "  AND m.status = 1 AND m.role IN (" + ROLE_OWNER + "," + ROLE_ADMIN + ") " +
                        "WHERE r.status = 0 ORDER BY r.id DESC",
                (rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("requestId", rs.getLong("id"));
                    m.put("clubId", rs.getLong("club_id"));
                    m.put("clubName", rs.getString("club_name"));
                    m.put("userId", rs.getLong("user_id"));
                    m.put("nickname", rs.getString("nickname"));
                    m.put("codeType", rs.getInt("code_type"));
                    m.put("inviterUserId", rs.getLong("inviter_user_id"));
                    m.put("time", rs.getTimestamp("created_at").getTime());
                    return m;
                }, userId);
    }

    /** 审批(approve=true 同意)。返回申请人 userId(便于上层推送通知) */
    public long review(long clubId, long operatorId, long requestId, boolean approve) {
        requireDb();
        requireOwnerOrAdmin(clubId, operatorId);
        List<Map<String, Object>> reqs = jdbc.query(
                "SELECT user_id, nickname, code_type, inviter_user_id, status FROM dz_club_join_request " +
                        "WHERE id = ? AND club_id = ?",
                (rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("userId", rs.getLong("user_id"));
                    m.put("nickname", rs.getString("nickname"));
                    m.put("codeType", rs.getInt("code_type"));
                    m.put("inviter", rs.getLong("inviter_user_id"));
                    m.put("status", rs.getInt("status"));
                    return m;
                }, requestId, clubId);
        if (reqs.isEmpty()) throw new ClubException("申请不存在");
        Map<String, Object> req = reqs.get(0);
        if ((int) req.get("status") != 0) throw new ClubException("该申请已处理");
        long applicant = (long) req.get("userId");

        if (approve) {
            if (isMember(clubId, applicant)) throw new ClubException("该用户已经是俱乐部成员");
            long parent;
            int level;
            if ((int) req.get("codeType") == 2 && (long) req.get("inviter") > 0) {
                parent = (long) req.get("inviter");
                Map<String, Object> pm = member(clubId, parent);
                level = pm != null ? (int) pm.get("level") + 1 : 1;
            } else {
                parent = ownerOf(clubId);
                level = 1;
            }
            long inviteCode = uniqueInviteCode(clubId);
            jdbc.update("INSERT INTO dz_club_member (club_id, user_id, nickname, role, parent_user_id, level, " +
                            "invite_code, partner_rate, status, created_at) VALUES (?,?,?,?,?,?,?,0,1,?)",
                    clubId, applicant, req.get("nickname"), ROLE_MEMBER, parent, level, inviteCode, now());
        }
        jdbc.update("UPDATE dz_club_join_request SET status = ?, reviewer_user_id = ?, reviewed_at = ? WHERE id = ?",
                approve ? 1 : 2, operatorId, now(), requestId);
        log.info("审批入会: clubId={}, requestId={}, applicant={}, approve={}, by={}",
                clubId, requestId, applicant, approve, operatorId);
        return applicant;
    }

    // ================================================================
    // 角色管理 / 踢人 / 退出 / 解散
    // ================================================================

    /**
     * 设置角色(对齐扯旋):
     *   设/撤管理员(role 1↔2):仅群主;
     *   设合伙人(role→4,带 partnerRate):群主/管理员/合伙人,且目标必须是自己直推(群主不限);
     *   合伙人比例只升不降(扯旋规则)。
     */
    public void setRole(long clubId, long operatorId, long targetUserId, int role, int partnerRate) {
        requireDb();
        Map<String, Object> op = member(clubId, operatorId);
        Map<String, Object> target = member(clubId, targetUserId);
        if (op == null) throw new ClubException("不是俱乐部成员");
        if (target == null) throw new ClubException("目标不是俱乐部成员");
        int opRole = (int) op.get("role");
        int targetRole = (int) target.get("role");
        if (targetRole == ROLE_OWNER) throw new ClubException("不能修改群主角色");

        if (role == ROLE_ADMIN || (role == ROLE_MEMBER && targetRole == ROLE_ADMIN)) {
            // 设/撤管理员:仅群主
            if (opRole != ROLE_OWNER) throw new ClubException("只有群主能设置管理员");
            jdbc.update("UPDATE dz_club_member SET role = ? WHERE club_id = ? AND user_id = ?",
                    role, clubId, targetUserId);
        } else if (role == ROLE_PARTNER) {
            if (opRole != ROLE_OWNER && opRole != ROLE_ADMIN && opRole != ROLE_PARTNER) {
                throw new ClubException("无权设置合伙人");
            }
            if (opRole != ROLE_OWNER && (long) target.get("parentUserId") != operatorId) {
                throw new ClubException("只能设置自己直推的成员为合伙人");
            }
            if (partnerRate < 0 || partnerRate > 100) throw new ClubException("比例须在 0-100");
            int oldRate = (int) target.get("partnerRate");
            if (targetRole == ROLE_PARTNER && partnerRate < oldRate) {
                throw new ClubException("合伙人比例只能上调");
            }
            jdbc.update("UPDATE dz_club_member SET role = ?, partner_rate = ? WHERE club_id = ? AND user_id = ?",
                    ROLE_PARTNER, partnerRate, clubId, targetUserId);
        } else if (role == ROLE_MEMBER) {
            // 撤合伙人 → 成员:仅群主
            if (opRole != ROLE_OWNER) throw new ClubException("只有群主能取消合伙人");
            jdbc.update("UPDATE dz_club_member SET role = 1, partner_rate = 0 WHERE club_id = ? AND user_id = ?",
                    clubId, targetUserId);
        } else {
            throw new ClubException("非法角色");
        }
        log.info("设置角色: clubId={}, target={}, role={}, rate={}, by={}",
                clubId, targetUserId, role, partnerRate, operatorId);
    }

    /** 踢人:群主/管理员可踢任意(除群主);合伙人只能踢自己直推。下级整体上挂到被踢者的上级 */
    public void kick(long clubId, long operatorId, long targetUserId) {
        requireDb();
        Map<String, Object> op = member(clubId, operatorId);
        Map<String, Object> target = member(clubId, targetUserId);
        if (op == null || target == null) throw new ClubException("成员不存在");
        int opRole = (int) op.get("role");
        if ((int) target.get("role") == ROLE_OWNER) throw new ClubException("不能踢群主");
        if (opRole == ROLE_PARTNER) {
            if ((long) target.get("parentUserId") != operatorId) throw new ClubException("只能移除自己直推的成员");
        } else if (opRole != ROLE_OWNER && opRole != ROLE_ADMIN) {
            throw new ClubException("无权移除成员");
        }
        removeMember(clubId, targetUserId, (long) target.get("parentUserId"));
        log.info("踢出成员: clubId={}, target={}, by={}", clubId, targetUserId, operatorId);
    }

    /** 退出俱乐部(群主不可退) */
    public void quit(long clubId, long userId) {
        requireDb();
        Map<String, Object> me = member(clubId, userId);
        if (me == null) throw new ClubException("不是俱乐部成员");
        if ((int) me.get("role") == ROLE_OWNER) throw new ClubException("群主不能退出,可解散俱乐部");
        removeMember(clubId, userId, (long) me.get("parentUserId"));
        log.info("退出俱乐部: clubId={}, userId={}", clubId, userId);
    }

    /** 解散(仅群主):state=2,成员全删 */
    public void dissolve(long clubId, long userId) {
        requireDb();
        Long owner = ownerOf(clubId);
        if (owner == null || owner != userId) throw new ClubException("只有群主能解散俱乐部");
        jdbc.update("UPDATE dz_club SET state = 2, dissolved_at = ? WHERE id = ?", now(), clubId);
        jdbc.update("DELETE FROM dz_club_member WHERE club_id = ?", clubId);
        jdbc.update("DELETE FROM dz_club_join_request WHERE club_id = ?", clubId);
        log.info("解散俱乐部: clubId={}, by={}", clubId, userId);
    }

    /** 删成员 + 下级 reparent + 清申请记录 */
    private void removeMember(long clubId, long userId, long reparentTo) {
        jdbc.update("UPDATE dz_club_member SET parent_user_id = ? WHERE club_id = ? AND parent_user_id = ?",
                reparentTo, clubId, userId);
        jdbc.update("DELETE FROM dz_club_member WHERE club_id = ? AND user_id = ?", clubId, userId);
        jdbc.update("DELETE FROM dz_club_join_request WHERE club_id = ? AND user_id = ?", clubId, userId);
    }

    private Long ownerOf(long clubId) {
        return jdbc.queryForObject("SELECT creator_user_id FROM dz_club WHERE id = ?", Long.class, clubId);
    }

    public String clubName(long clubId) {
        if (jdbc == null) return "";
        try {
            String name = jdbc.queryForObject("SELECT name FROM dz_club WHERE id = ?", String.class, clubId);
            return name == null ? "" : name;
        } catch (Exception e) {
            return "";
        }
    }

    private void requireOwnerOrAdmin(long clubId, long userId) {
        Map<String, Object> m = member(clubId, userId);
        if (m == null) throw new ClubException("不是俱乐部成员");
        int role = (int) m.get("role");
        if (role != ROLE_OWNER && role != ROLE_ADMIN) throw new ClubException("需要群主/管理员权限");
    }

    // ================================================================
    // 合伙人分成(对齐扯旋 distributeCommission)
    // ================================================================

    /** 玩家是否该俱乐部群主(群主免抽) */
    public boolean isOwner(long clubId, long userId) {
        if (jdbc == null) return false;
        Long owner = ownerOf(clubId);
        return owner != null && owner == userId;
    }

    /** 群主 userId(查不到返回 0) — 周期扣钻/罚金归属用 */
    public long ownerUserId(long clubId) {
        if (jdbc == null || clubId <= 0) return 0;
        try {
            Long owner = ownerOf(clubId);
            return owner != null ? owner : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * 抽水分成:沿被抽玩家的 parent 链上溯到群主,反转为「群主→…→玩家」,
     * 只有群主(3)/合伙人(4)参与;每级把「剩余 × 下游 partnerRate%」让给下一个有资格的下游,
     * 末端拿剩余。份额直接进各自德州钱包 + dz_commission_log 流水。
     * 任何异常只打日志,不影响牌局结算。
     */
    public void distributeRake(long clubId, long roomId, long fromUserId, long totalProfit, long rake) {
        if (jdbc == null || rake <= 0 || clubId <= 0) return;
        try {
            // 上溯链(玩家→群主),最多 20 层防环
            List<Map<String, Object>> chain = new ArrayList<>();
            long cur = fromUserId;
            for (int i = 0; i < 20; i++) {
                Map<String, Object> m = member(clubId, cur);
                if (m == null) break;
                chain.add(m);
                long parent = (long) m.get("parentUserId");
                if (parent <= 0) break;
                cur = parent;
            }
            // 反转成 群主→…→玩家,只留 群主/合伙人,且排除被抽玩家本人
            List<Map<String, Object>> eligible = new ArrayList<>();
            for (int i = chain.size() - 1; i >= 0; i--) {
                Map<String, Object> m = chain.get(i);
                int role = (int) m.get("role");
                long uid = (long) m.get("userId");
                if (uid == fromUserId) continue;
                if (role == ROLE_OWNER || role == ROLE_PARTNER) eligible.add(m);
            }
            if (eligible.isEmpty()) {
                // 链上没人(理论上至少有群主):全给群主兜底
                Long owner = ownerOf(clubId);
                if (owner == null) return;
                credit(clubId, roomId, fromUserId, owner, "OWNER", totalProfit, rake, rake, 100);
                return;
            }
            long remaining = rake;
            for (int i = 0; i < eligible.size() && remaining > 0; i++) {
                Map<String, Object> holder = eligible.get(i);
                Map<String, Object> next = (i + 1 < eligible.size()) ? eligible.get(i + 1) : null;
                long share;
                int rate;
                if (next == null) {
                    share = remaining;
                    rate = 100;
                } else {
                    int nextRate = (int) next.get("partnerRate");
                    long give = remaining * nextRate / 100;
                    share = remaining - give;
                    rate = 100 - nextRate;
                    remaining = give;
                }
                if (share > 0) {
                    long uid = (long) holder.get("userId");
                    String toRole = (int) holder.get("role") == ROLE_OWNER ? "OWNER" : "PARTNER";
                    credit(clubId, roomId, fromUserId, uid, toRole, totalProfit, rake, share, rate);
                }
                if (next == null) remaining = 0;
            }
        } catch (Exception e) {
            log.error("抽水分成失败: clubId={}, roomId={}, from={}, rake={}", clubId, roomId, fromUserId, rake, e);
        }
    }

    private void credit(long clubId, long roomId, long fromUserId, long toUserId, String toRole,
                        long totalProfit, long commission, long share, int rate) {
        // 分成进各自的俱乐部积分(type=15 提取红利,对齐扯旋)
        creditScoreForGame(clubId, toUserId, share, 15, "抽水分成 room=" + roomId);
        jdbc.update("INSERT INTO dz_commission_log (club_id, room_id, from_user_id, to_user_id, to_role, " +
                        "total_profit, commission, share_amount, share_rate, created_at) VALUES (?,?,?,?,?,?,?,?,?,?)",
                clubId, roomId, fromUserId, toUserId, toRole, totalProfit, commission, share, rate, now());
        log.info("抽水分成: clubId={}, room={}, {} ← {} 分得 {}({}%),抽水共 {}",
                clubId, roomId, toRole, fromUserId, share, rate, commission);
    }
}
