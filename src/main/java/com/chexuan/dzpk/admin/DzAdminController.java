package com.chexuan.dzpk.admin;

import com.chexuan.dzpk.config.DzConfigService;
import com.chexuan.dzpk.game.model.DzPlayer;
import com.chexuan.dzpk.game.model.DzRoom;
import com.chexuan.dzpk.game.service.DzGameService;
import com.chexuan.dzpk.game.service.DzRoomManager;
import com.chexuan.dzpk.ws.WsSessionRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 德州管理后台 API(独立 Vue3 后台 /admin/ 调用):
 *   登录换 token(密码配 dzpk.admin-password,生产用环境变量覆盖)→
 *   系统参数在线调整(dz_system_config,改了立即生效)+ 房间/在线监控 + 强制解散。
 */
@Slf4j
@RestController
@RequestMapping("/api/admin")
public class DzAdminController {

    private static final long TOKEN_TTL_MS = 12 * 3600_000L;

    private final DzConfigService configService;
    private final DzRoomManager roomManager;
    private final DzGameService gameService;
    private final WsSessionRegistry registry;
    private final com.chexuan.dzpk.gift.DzGiftService giftService;
    private final com.chexuan.dzpk.db.DiamondService diamondService;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;
    private final com.chexuan.dzpk.robot.RobotService robotService;

    /** token → 过期时间戳 */
    private final Map<String, Long> tokens = new ConcurrentHashMap<>();

    @Value("${dzpk.admin-password:dz@admin2026}")
    private String adminPassword;

    public DzAdminController(DzConfigService configService, DzRoomManager roomManager,
                             DzGameService gameService, WsSessionRegistry registry,
                             com.chexuan.dzpk.gift.DzGiftService giftService,
                             com.chexuan.dzpk.db.DiamondService diamondService,
                             org.springframework.jdbc.core.JdbcTemplate jdbc,
                             com.chexuan.dzpk.robot.RobotService robotService) {
        this.configService = configService;
        this.roomManager = roomManager;
        this.gameService = gameService;
        this.registry = registry;
        this.giftService = giftService;
        this.diamondService = diamondService;
        this.jdbc = jdbc;
        this.robotService = robotService;
    }

    // ==================== 登录 ====================

    @PostMapping("/login")
    public Map<String, Object> login(@RequestBody Map<String, Object> body) {
        String password = String.valueOf(body.getOrDefault("password", ""));
        if (!adminPassword.equals(password)) {
            log.warn("管理后台登录失败(密码错误)");
            return Map.of("code", 1, "msg", "密码错误");
        }
        String token = UUID.randomUUID().toString().replace("-", "");
        tokens.put(token, System.currentTimeMillis() + TOKEN_TTL_MS);
        // 顺手清一遍过期 token
        tokens.entrySet().removeIf(e -> e.getValue() < System.currentTimeMillis());
        log.info("管理后台登录成功");
        return Map.of("code", 0, "token", token);
    }

    private boolean authed(String token) {
        if (token == null) return false;
        Long exp = tokens.get(token);
        return exp != null && exp > System.currentTimeMillis();
    }

    private Map<String, Object> deny() {
        return Map.of("code", 401, "msg", "未登录或登录已过期");
    }

    // ==================== 系统参数 ====================

    @GetMapping("/configs")
    public Map<String, Object> configs(@RequestHeader(value = "X-Admin-Token", required = false) String token) {
        if (!authed(token)) return deny();
        return Map.of("code", 0, "configs", configService.all());
    }

    @PutMapping("/configs")
    public Map<String, Object> updateConfig(@RequestHeader(value = "X-Admin-Token", required = false) String token,
                                            @RequestBody Map<String, Object> body) {
        if (!authed(token)) return deny();
        String key = String.valueOf(body.get("key"));
        String value = String.valueOf(body.getOrDefault("value", ""));
        try {
            configService.update(key, value);
            // 停服维护开启瞬间清扫(对齐扯旋 maintenance/toggle):空闲桌立即清,游戏中的桌局末清
            if ("maintenance_mode".equals(key) && configService.getBool("maintenance_mode", false)) {
                gameService.maintenanceSweep();
            }
            return Map.of("code", 0, "key", key, "value", value);
        } catch (IllegalArgumentException e) {
            return Map.of("code", 1, "msg", e.getMessage());
        } catch (Exception e) {
            // 落库失败(超长/连接异常等)也回可读错误,不让前端吃 500
            log.error("参数保存失败: key={}", key, e);
            return Map.of("code", 1, "msg", "保存失败: " + e.getClass().getSimpleName()
                    + (e.getMessage() != null ? " " + e.getMessage() : ""));
        }
    }

    // ==================== 礼物配置(对齐扯旋 GiftController admin CRUD) ====================

    @GetMapping("/gifts")
    public Map<String, Object> gifts(@RequestHeader(value = "X-Admin-Token", required = false) String token) {
        if (!authed(token)) return deny();
        return Map.of("code", 0, "gifts", giftService.listAll());
    }

    @PutMapping("/gifts")
    public Map<String, Object> saveGift(@RequestHeader(value = "X-Admin-Token", required = false) String token,
                                        @RequestBody Map<String, Object> body) {
        if (!authed(token)) return deny();
        try {
            giftService.save(body);
            return Map.of("code", 0);
        } catch (Exception e) {
            return Map.of("code", 1, "msg", e.getMessage() == null ? "保存失败" : e.getMessage());
        }
    }

    @DeleteMapping("/gifts/{id}")
    public Map<String, Object> deleteGift(@RequestHeader(value = "X-Admin-Token", required = false) String token,
                                          @PathVariable long id) {
        if (!authed(token)) return deny();
        giftService.delete(id);
        return Map.of("code", 0);
    }

    // ==================== 用户/钻石(独立账号体系,钻石来源=后台充值) ====================

    /** 用户查询:q 为手机号(精确)或 userId */
    @GetMapping("/users")
    public Map<String, Object> users(@RequestHeader(value = "X-Admin-Token", required = false) String token,
                                     @RequestParam(value = "q", required = false) String q) {
        if (!authed(token)) return deny();
        String sql = "SELECT id, phone, nickname, avatar, diamond, state, created_at, last_login_at FROM dz_user ";
        List<Map<String, Object>> rows;
        if (q != null && !q.isBlank()) {
            rows = jdbc.queryForList(sql + "WHERE phone = ? OR id = ? ORDER BY id DESC LIMIT 50",
                    q.trim(), parseLongOr(q.trim(), -1));
        } else {
            rows = jdbc.queryForList(sql + "ORDER BY id DESC LIMIT 50");
        }
        return Map.of("code", 0, "users", rows);
    }

    /** 充/扣钻石:{userId, amount(正充负扣), remark?} */
    @PostMapping("/users/diamond")
    public Map<String, Object> adjustDiamond(@RequestHeader(value = "X-Admin-Token", required = false) String token,
                                             @RequestBody Map<String, Object> body) {
        if (!authed(token)) return deny();
        long userId = parseLongOr(String.valueOf(body.get("userId")), 0);
        long amount = parseLongOr(String.valueOf(body.get("amount")), 0);
        String remark = String.valueOf(body.getOrDefault("remark", "后台调整"));
        if (userId <= 0 || amount == 0) return Map.of("code", 1, "msg", "userId/amount 非法");
        boolean ok = amount > 0
                ? diamondService.credit(userId, amount, "admin_adjust", remark)
                : diamondService.debit(userId, -amount, "admin_adjust", remark);
        if (!ok) return Map.of("code", 1, "msg", "调整失败(账号不存在或余额不足)");
        log.info("后台钻石调整: userId={}, amount={}, remark={}", userId, amount, remark);
        return Map.of("code", 0, "userId", userId, "diamond", diamondService.balance(userId));
    }

    private static long parseLongOr(String s, long def) {
        try {
            return Long.parseLong(s);
        } catch (Exception e) {
            return def;
        }
    }

    // ==================== 监控 ====================

    @GetMapping("/overview")
    public Map<String, Object> overview(@RequestHeader(value = "X-Admin-Token", required = false) String token) {
        if (!authed(token)) return deny();
        List<Map<String, Object>> rooms = new ArrayList<>();
        for (DzRoom r : roomManager.list()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("roomId", r.getRoomId());
            m.put("name", r.getName());
            m.put("clubId", r.getClubId());
            m.put("sb", r.getSb());
            m.put("bb", r.getBb());
            m.put("maxPlayers", r.getMaxPlayers());
            m.put("settleTimeMins", r.getSettleTimeMins());
            m.put("stage", r.getStage().name());
            m.put("handNo", r.getHandNo());
            m.put("memberCount", r.getMembers().size());
            List<Map<String, Object>> seats = new ArrayList<>();
            for (DzPlayer p : r.getSeats()) {
                if (p == null) continue;
                seats.add(Map.of("userId", p.getUserId(), "nickname", p.getNickname(),
                        "seat", p.getSeat(), "stack", p.getStack(),
                        "offline", p.isOffline(), "sittingOut", p.isSittingOut()));
            }
            m.put("players", seats);
            rooms.add(m);
        }
        return Map.of("code", 0,
                "onlineCount", registry.onlineCount(),
                "roomCount", rooms.size(),
                "rooms", rooms);
    }

    // ==================== 俱乐部管理 ====================

    /** 俱乐部列表(正常状态):群主昵称、成员数,配合 overview 的房间 clubId 组装俱乐部详情 */
    @GetMapping("/clubs")
    public Map<String, Object> clubs(@RequestHeader(value = "X-Admin-Token", required = false) String token) {
        if (!authed(token)) return deny();
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT c.id AS clubId, c.club_no AS clubNo, c.name, c.remark, c.creator_user_id AS ownerId, " +
                        "u.nickname AS ownerNick, c.created_at AS createdAt, " +
                        "(SELECT COUNT(*) FROM dz_club_member m WHERE m.club_id = c.id AND m.status = 1) AS memberCount " +
                        "FROM dz_club c LEFT JOIN dz_user u ON u.id = c.creator_user_id " +
                        "WHERE c.state = 1 ORDER BY c.id DESC LIMIT 200");
        return Map.of("code", 0, "clubs", rows);
    }

    // ==================== 机器人(一键生成,俱乐部房测试用) ====================

    /** 各房间机器人分布 */
    @GetMapping("/robots")
    public Map<String, Object> robots(@RequestHeader(value = "X-Admin-Token", required = false) String token) {
        if (!authed(token)) return deny();
        return robotService.listRobots();
    }

    /** 一键生成:{roomId, count} → 随机昵称/头像坐空位并带入(俱乐部房豁免成员/积分限制) */
    @PostMapping("/robots/spawn")
    public Map<String, Object> spawnRobots(@RequestHeader(value = "X-Admin-Token", required = false) String token,
                                           @RequestBody Map<String, Object> body) {
        if (!authed(token)) return deny();
        long roomId = parseLongOr(String.valueOf(body.get("roomId")), 0);
        int count = (int) parseLongOr(String.valueOf(body.getOrDefault("count", 1)), 1);
        if (roomId <= 0) return Map.of("code", 1, "msg", "roomId 非法");
        Map<String, Object> res = robotService.spawnRobots(roomId, count);
        log.info("管理后台生成机器人: roomId={}, count={}, res={}", roomId, count, res);
        return res;
    }

    /** 清掉指定房间全部机器人:{roomId} */
    @PostMapping("/robots/clear")
    public Map<String, Object> clearRobots(@RequestHeader(value = "X-Admin-Token", required = false) String token,
                                           @RequestBody Map<String, Object> body) {
        if (!authed(token)) return deny();
        long roomId = parseLongOr(String.valueOf(body.get("roomId")), 0);
        if (roomId <= 0) return Map.of("code", 1, "msg", "roomId 非法");
        return robotService.clearRobots(roomId);
    }

    @PostMapping("/rooms/{roomId}/dismiss")
    public Map<String, Object> dismiss(@RequestHeader(value = "X-Admin-Token", required = false) String token,
                                       @PathVariable long roomId) {
        if (!authed(token)) return deny();
        gameService.dismissRoomByAdmin(roomId);
        log.info("管理后台强制解散房间: roomId={}", roomId);
        return Map.of("code", 0, "roomId", roomId);
    }

    /** /admin 与 /admin/ 目录访问转到静态 index(Spring 静态目录不做子目录索引) */
    @org.springframework.stereotype.Controller
    static class AdminIndexForward {
        @GetMapping({"/admin", "/admin/"})
        public String index() {
            return "forward:/admin/index.html";
        }
    }
}
