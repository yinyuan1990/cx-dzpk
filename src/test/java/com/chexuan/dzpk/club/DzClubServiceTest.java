package com.chexuan.dzpk.club;

import com.chexuan.dzpk.db.DiamondService;
import com.chexuan.dzpk.game.service.WalletService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 俱乐部体系测试(H2 内存库,规则对齐扯旋):
 * 创建/申请/审批/角色权限/踢人挂树/合伙人多层分成。
 */
class DzClubServiceTest {

    private static final AtomicInteger DB_SEQ = new AtomicInteger();

    private JdbcTemplate jdbc;
    private WalletService wallet;
    private DzClubService clubs;

    private static final long OWNER = 1001, ADMIN = 1002, PARTNER_A = 1003, PARTNER_B = 1004, MEMBER = 1005, OUTSIDER = 1006;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource ds = new DriverManagerDataSource(
                "jdbc:h2:mem:club" + DB_SEQ.incrementAndGet() + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;" +
                        "CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
        ResourceDatabasePopulator populator = new ResourceDatabasePopulator(new ClassPathResource("schema.sql"));
        populator.setContinueOnError(true);
        populator.execute(ds);
        jdbc = new JdbcTemplate(ds);
        wallet = new WalletService(jdbc);
        clubs = new DzClubService(jdbc, new DiamondService(null), wallet);
        ReflectionTestUtils.setField(clubs, "maxClubPerUser", 10);
        ReflectionTestUtils.setField(clubs, "createClubDiamondCost", 0L);
    }

    /** 建一个俱乐部:群主 + 管理员 + 合伙人A(rate30,挂群主) + 合伙人B(rate50,挂A) + 成员(挂B) */
    private long buildClub() {
        long clubId = (long) clubs.createClub(OWNER, "群主", "测试俱乐部", "").get("clubId");
        joinVia(clubId, ADMIN, "管理员", clubNo(clubId));
        clubs.setRole(clubId, OWNER, ADMIN, DzClubService.ROLE_ADMIN, 0);
        joinVia(clubId, PARTNER_A, "合伙人A", inviteCodeOf(clubId, OWNER));
        clubs.setRole(clubId, OWNER, PARTNER_A, DzClubService.ROLE_PARTNER, 30);
        joinVia(clubId, PARTNER_B, "合伙人B", inviteCodeOf(clubId, PARTNER_A));
        clubs.setRole(clubId, PARTNER_A, PARTNER_B, DzClubService.ROLE_PARTNER, 50);
        joinVia(clubId, MEMBER, "成员", inviteCodeOf(clubId, PARTNER_B));
        return clubId;
    }

    private void joinVia(long clubId, long userId, String nick, long code) {
        clubs.apply(userId, nick, code);
        long reqId = jdbc.queryForObject(
                "SELECT id FROM dz_club_join_request WHERE club_id=? AND user_id=? AND status=0",
                Long.class, clubId, userId);
        clubs.review(clubId, OWNER, reqId, true);
    }

    private long clubNo(long clubId) {
        return jdbc.queryForObject("SELECT club_no FROM dz_club WHERE id=?", Long.class, clubId);
    }

    private long inviteCodeOf(long clubId, long userId) {
        return (long) clubs.member(clubId, userId).get("inviteCode");
    }

    @Test
    void 创建俱乐部_六位编号_群主入座() {
        Map<String, Object> res = clubs.createClub(OWNER, "群主", "我的俱乐部", "公告");
        long no = (long) res.get("clubNo");
        assertTrue(no >= 100000 && no <= 999999, "6位编号");
        Map<String, Object> me = clubs.member((long) res.get("clubId"), OWNER);
        assertEquals(DzClubService.ROLE_OWNER, me.get("role"));
        long invite = (long) me.get("inviteCode");
        assertTrue(invite >= 100000 && invite <= 999999, "6位邀请码");
    }

    @Test
    void 创建数量上限() {
        ReflectionTestUtils.setField(clubs, "maxClubPerUser", 2);
        clubs.createClub(OWNER, "群主", "一号", "");
        clubs.createClub(OWNER, "群主", "二号", "");
        assertThrows(DzClubService.ClubException.class,
                () -> clubs.createClub(OWNER, "群主", "三号", ""));
    }

    @Test
    void 申请_俱乐部号挂群主_邀请码挂邀请人() {
        long clubId = buildClub();
        // 俱乐部号加入 → parent=群主, level=1
        Map<String, Object> admin = clubs.member(clubId, ADMIN);
        assertEquals(OWNER, admin.get("parentUserId"));
        assertEquals(1, admin.get("level"));
        // 邀请码加入 → parent=邀请人, level=上级+1
        Map<String, Object> b = clubs.member(clubId, PARTNER_B);
        assertEquals(PARTNER_A, b.get("parentUserId"));
        assertEquals(2, b.get("level"));
        Map<String, Object> m = clubs.member(clubId, MEMBER);
        assertEquals(PARTNER_B, m.get("parentUserId"));
        assertEquals(3, m.get("level"));
    }

    @Test
    void 重复申请_已是成员_均拦截() {
        long clubId = buildClub();
        long no = clubNo(clubId);
        assertThrows(DzClubService.ClubException.class, () -> clubs.apply(MEMBER, "成员", no),
                "已是成员不能再申请");
        clubs.apply(OUTSIDER, "路人", no);
        assertThrows(DzClubService.ClubException.class, () -> clubs.apply(OUTSIDER, "路人", no),
                "待审中不能重复申请");
    }

    @Test
    void 审批权限_成员和合伙人不能审() {
        long clubId = buildClub();
        clubs.apply(OUTSIDER, "路人", clubNo(clubId));
        long reqId = jdbc.queryForObject(
                "SELECT id FROM dz_club_join_request WHERE club_id=? AND user_id=? AND status=0",
                Long.class, clubId, OUTSIDER);
        assertThrows(DzClubService.ClubException.class, () -> clubs.review(clubId, MEMBER, reqId, true));
        assertThrows(DzClubService.ClubException.class, () -> clubs.review(clubId, PARTNER_A, reqId, true));
        // 管理员可以审;拒绝后可重新申请
        clubs.review(clubId, ADMIN, reqId, false);
        assertFalse(clubs.isMember(clubId, OUTSIDER));
        clubs.apply(OUTSIDER, "路人", clubNo(clubId));
    }

    @Test
    void 设管理员仅群主_设合伙人限直推_比例只升不降() {
        long clubId = buildClub();
        // 管理员不能设管理员
        assertThrows(DzClubService.ClubException.class,
                () -> clubs.setRole(clubId, ADMIN, MEMBER, DzClubService.ROLE_ADMIN, 0));
        // 合伙人只能设自己直推:A 设 MEMBER(直推是 B)不行
        assertThrows(DzClubService.ClubException.class,
                () -> clubs.setRole(clubId, PARTNER_A, MEMBER, DzClubService.ROLE_PARTNER, 10));
        // B 设自己直推的 MEMBER 可以
        clubs.setRole(clubId, PARTNER_B, MEMBER, DzClubService.ROLE_PARTNER, 20);
        assertEquals(DzClubService.ROLE_PARTNER, clubs.member(clubId, MEMBER).get("role"));
        // 比例只能升不能降
        assertThrows(DzClubService.ClubException.class,
                () -> clubs.setRole(clubId, PARTNER_B, MEMBER, DzClubService.ROLE_PARTNER, 10));
        clubs.setRole(clubId, PARTNER_B, MEMBER, DzClubService.ROLE_PARTNER, 40);
        assertEquals(40, clubs.member(clubId, MEMBER).get("partnerRate"));
        // 撤合伙人仅群主
        assertThrows(DzClubService.ClubException.class,
                () -> clubs.setRole(clubId, PARTNER_B, MEMBER, DzClubService.ROLE_MEMBER, 0));
        clubs.setRole(clubId, OWNER, MEMBER, DzClubService.ROLE_MEMBER, 0);
        assertEquals(DzClubService.ROLE_MEMBER, clubs.member(clubId, MEMBER).get("role"));
    }

    @Test
    void 踢人_下级上挂_合伙人只能踢直推() {
        long clubId = buildClub();
        // 合伙人A踢 MEMBER(不是直推)不行
        assertThrows(DzClubService.ClubException.class, () -> clubs.kick(clubId, PARTNER_A, MEMBER));
        // 管理员踢 B → MEMBER 上挂到 B 的上级 A
        clubs.kick(clubId, ADMIN, PARTNER_B);
        assertFalse(clubs.isMember(clubId, PARTNER_B));
        assertEquals(PARTNER_A, clubs.member(clubId, MEMBER).get("parentUserId"));
        // 谁都不能踢群主
        assertThrows(DzClubService.ClubException.class, () -> clubs.kick(clubId, ADMIN, OWNER));
        // 被踢后可重新申请
        clubs.apply(PARTNER_B, "合伙人B", clubNo(clubId));
    }

    @Test
    void 退出_群主不可退_解散仅群主() {
        long clubId = buildClub();
        assertThrows(DzClubService.ClubException.class, () -> clubs.quit(clubId, OWNER));
        clubs.quit(clubId, MEMBER);
        assertFalse(clubs.isMember(clubId, MEMBER));
        assertThrows(DzClubService.ClubException.class, () -> clubs.dissolve(clubId, ADMIN));
        clubs.dissolve(clubId, OWNER);
        assertTrue(clubs.myClubs(OWNER).isEmpty(), "解散后列表不再出现");
    }

    @Test
    void 建房权限_群主管理员可建_成员合伙人不可() {
        long clubId = buildClub();
        assertTrue(clubs.canCreateRoom(clubId, OWNER));
        assertTrue(clubs.canCreateRoom(clubId, ADMIN));
        assertFalse(clubs.canCreateRoom(clubId, PARTNER_A));
        assertFalse(clubs.canCreateRoom(clubId, MEMBER));
        assertFalse(clubs.canCreateRoom(clubId, OUTSIDER));
    }

    @Test
    void 分成_沿链多层让利_进俱乐部积分() {
        long clubId = buildClub();
        long o0 = clubs.score(clubId, OWNER), a0 = clubs.score(clubId, PARTNER_A), b0 = clubs.score(clubId, PARTNER_B);
        // MEMBER 被抽 1000:链 群主→A(30%)→B(50%)
        // 群主留 1000-300=700;A 留 300-150=150;B 末端拿 150
        clubs.distributeRake(clubId, 888888, MEMBER, 20000, 1000);
        assertEquals(700, clubs.score(clubId, OWNER) - o0);
        assertEquals(150, clubs.score(clubId, PARTNER_A) - a0);
        assertEquals(150, clubs.score(clubId, PARTNER_B) - b0);
        Integer logs = jdbc.queryForObject(
                "SELECT COUNT(*) FROM dz_commission_log WHERE club_id=? AND from_user_id=?",
                Integer.class, clubId, MEMBER);
        assertEquals(3, logs);
        Long total = jdbc.queryForObject(
                "SELECT SUM(share_amount) FROM dz_commission_log WHERE club_id=?", Long.class, clubId);
        assertEquals(1000L, total, "份额加总等于抽水");
    }

    @Test
    void 分成_普通成员在链上不参与() {
        long clubId = buildClub();
        // ADMIN 直接挂群主且不是合伙人 → ADMIN 被抽时全归群主
        long o0 = clubs.score(clubId, OWNER);
        clubs.distributeRake(clubId, 888888, ADMIN, 5000, 500);
        assertEquals(500, clubs.score(clubId, OWNER) - o0);
    }

    // ================================================================
    // 俱乐部积分(每俱乐部独立一本账)
    // ================================================================

    @Test
    void 积分_群主增发核销_只有群主能用() {
        long clubId = buildClub();
        clubs.ownerAddScore(clubId, OWNER, 100000);
        assertEquals(100000, clubs.score(clubId, OWNER));
        assertThrows(DzClubService.ClubException.class, () -> clubs.ownerAddScore(clubId, ADMIN, 1000),
                "管理员不能增发");
        clubs.ownerBurnScore(clubId, OWNER, 30000);
        assertEquals(70000, clubs.score(clubId, OWNER));
        assertThrows(DzClubService.ClubException.class, () -> clubs.ownerBurnScore(clubId, OWNER, 999999),
                "核销超余额拒绝");
    }

    @Test
    void 积分_上分下分_转移操作者自己的分() {
        long clubId = buildClub();
        clubs.ownerAddScore(clubId, OWNER, 50000);
        // 上分:群主 → 成员(扣群主自己的)
        clubs.distributeScore(clubId, OWNER, MEMBER, 20000);
        assertEquals(30000, clubs.score(clubId, OWNER));
        assertEquals(20000, clubs.score(clubId, MEMBER));
        // 管理员没分,上分失败
        assertThrows(DzClubService.ClubException.class,
                () -> clubs.distributeScore(clubId, ADMIN, MEMBER, 1000), "管理员余额不足");
        // 成员不能上分
        assertThrows(DzClubService.ClubException.class,
                () -> clubs.distributeScore(clubId, MEMBER, PARTNER_A, 1000));
        // 下分:收回成员的分
        clubs.collectScore(clubId, OWNER, MEMBER, 5000);
        assertEquals(35000, clubs.score(clubId, OWNER));
        assertEquals(15000, clubs.score(clubId, MEMBER));
    }

    @Test
    void 积分_成员赠送_游戏带入退筹() {
        long clubId = buildClub();
        clubs.ownerAddScore(clubId, OWNER, 50000);
        clubs.distributeScore(clubId, OWNER, MEMBER, 20000);
        // 赠送:成员 → 合伙人B
        clubs.transferScore(clubId, MEMBER, PARTNER_B, 3000);
        assertEquals(17000, clubs.score(clubId, MEMBER));
        assertEquals(3000, clubs.score(clubId, PARTNER_B));
        // 游戏带入(type=1)与起立返还(type=2)
        assertTrue(clubs.debitScoreForGame(clubId, MEMBER, 10000, 777));
        assertEquals(7000, clubs.score(clubId, MEMBER));
        assertFalse(clubs.debitScoreForGame(clubId, MEMBER, 99999, 777), "积分不足带入失败");
        clubs.creditScoreForGame(clubId, MEMBER, 12000, 2, "起立返还");
        assertEquals(19000, clubs.score(clubId, MEMBER));
        // 流水可查
        List<Map<String, Object>> logs = clubs.scoreLogs(clubId, MEMBER, 0, 50);
        assertFalse(logs.isEmpty());
    }

    @Test
    void 我的俱乐部列表_角色与待审数() {
        long clubId = buildClub();
        clubs.apply(OUTSIDER, "路人", clubNo(clubId));
        List<Map<String, Object>> mine = clubs.myClubs(OWNER);
        assertEquals(1, mine.size());
        assertEquals(DzClubService.ROLE_OWNER, mine.get(0).get("myRole"));
        assertEquals(5L, mine.get(0).get("memberCount"));
        assertEquals(1L, mine.get(0).get("pendingCount"));
        // 普通成员看不到待审数
        assertEquals(0L, clubs.myClubs(MEMBER).get(0).get("pendingCount"));
    }
}
