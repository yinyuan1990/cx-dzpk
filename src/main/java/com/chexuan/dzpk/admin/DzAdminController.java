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
    private final com.chexuan.dzpk.robot.DzRobotAdminService robotAdmin;
    private final com.chexuan.dzpk.robot.RobotParamService robotParams;
    private final com.chexuan.dzpk.robot.DzProfitControl profitControl;

    /** token → 过期时间戳 */
    private final Map<String, Long> tokens = new ConcurrentHashMap<>();

    @Value("${dzpk.admin-password:dz@admin2026}")
    private String adminPassword;

    public DzAdminController(DzConfigService configService, DzRoomManager roomManager,
                             DzGameService gameService, WsSessionRegistry registry,
                             com.chexuan.dzpk.gift.DzGiftService giftService,
                             com.chexuan.dzpk.db.DiamondService diamondService,
                             org.springframework.jdbc.core.JdbcTemplate jdbc,
                             com.chexuan.dzpk.robot.RobotService robotService,
                             com.chexuan.dzpk.robot.DzRobotAdminService robotAdmin,
                             com.chexuan.dzpk.robot.RobotParamService robotParams,
                             com.chexuan.dzpk.robot.DzProfitControl profitControl) {
        this.configService = configService;
        this.roomManager = roomManager;
        this.gameService = gameService;
        this.registry = registry;
        this.giftService = giftService;
        this.diamondService = diamondService;
        this.jdbc = jdbc;
        this.robotService = robotService;
        this.robotAdmin = robotAdmin;
        this.robotParams = robotParams;
        this.profitControl = profitControl;
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

    // ==================== 机器人(对齐扯旋:真实账号池,与牌局无关) ====================

    /** 一键生成俱乐部机器人:{count, initScore} → 建号(随机昵称/头像)+入会+初始上分 */
    @PostMapping("/clubs/{clubId}/robots/generate")
    public Map<String, Object> generateRobots(@RequestHeader(value = "X-Admin-Token", required = false) String token,
                                              @PathVariable long clubId, @RequestBody Map<String, Object> body) {
        if (!authed(token)) return deny();
        int count = (int) parseLongOr(String.valueOf(body.getOrDefault("count", 1)), 1);
        long initScore = parseLongOr(String.valueOf(body.getOrDefault("initScore", 0)), 0);
        return robotAdmin.generate(clubId, count, initScore);
    }

    /** 俱乐部机器人池(昵称/头像/积分/是否在桌) */
    @GetMapping("/clubs/{clubId}/robots")
    public Map<String, Object> clubRobots(@RequestHeader(value = "X-Admin-Token", required = false) String token,
                                          @PathVariable long clubId) {
        if (!authed(token)) return deny();
        return robotAdmin.list(clubId);
    }

    /** 俱乐部全部成员(?type=all|human|robot 分类,page/size 分页) */
    @GetMapping("/clubs/{clubId}/members")
    public Map<String, Object> clubMembers(@RequestHeader(value = "X-Admin-Token", required = false) String token,
                                           @PathVariable long clubId,
                                           @RequestParam(value = "type", defaultValue = "all") String type,
                                           @RequestParam(value = "page", defaultValue = "0") int page,
                                           @RequestParam(value = "size", defaultValue = "20") int size) {
        if (!authed(token)) return deny();
        return robotAdmin.members(clubId, type, page, size);
    }

    /** 机器人批量换头像:{urls:[...]}(一人一图,URL 去重后须 ≥ 机器人数) */
    @PostMapping("/clubs/{clubId}/robots/avatars")
    public Map<String, Object> assignRobotAvatars(@RequestHeader(value = "X-Admin-Token", required = false) String token,
                                                  @PathVariable long clubId, @RequestBody Map<String, Object> body) {
        if (!authed(token)) return deny();
        Object urls = body.get("urls");
        List<String> list = new java.util.ArrayList<>();
        if (urls instanceof List<?> l) {
            for (Object o : l) if (o != null && !String.valueOf(o).isBlank()) list.add(String.valueOf(o).trim());
        }
        return robotAdmin.assignAvatars(clubId, list);
    }

    /** 机器人一键随机改名(德州昵称词库) */
    @PostMapping("/clubs/{clubId}/robots/rename")
    public Map<String, Object> renameRobots(@RequestHeader(value = "X-Admin-Token", required = false) String token,
                                            @PathVariable long clubId) {
        if (!authed(token)) return deny();
        return robotAdmin.rename(clubId);
    }

    // ==================== 机器人参数(俱乐部默认 + 房间覆盖)/ 控盘 ====================

    /** 俱乐部机器人参数(含控盘,默认值兜底) */
    @GetMapping("/clubs/{clubId}/robot-config")
    public Map<String, Object> robotConfig(@RequestHeader(value = "X-Admin-Token", required = false) String token,
                                           @PathVariable long clubId) {
        if (!authed(token)) return deny();
        return Map.of("code", 0, "config", robotParams.clubConfig(clubId));
    }

    /** 保存俱乐部机器人参数 */
    @PutMapping("/clubs/{clubId}/robot-config")
    public Map<String, Object> saveRobotConfig(@RequestHeader(value = "X-Admin-Token", required = false) String token,
                                               @PathVariable long clubId, @RequestBody Map<String, Object> body) {
        if (!authed(token)) return deny();
        try {
            robotParams.saveClub(clubId, body);
            return Map.of("code", 0);
        } catch (Exception e) {
            log.error("机器人参数保存失败: clubId={}", clubId, e);
            return Map.of("code", 1, "msg", "保存失败: " + e.getMessage());
        }
    }

    /** 房间实际生效参数(值+来源) + 控盘状态 */
    @GetMapping("/rooms/{roomId}/robot-params")
    public Map<String, Object> roomRobotParams(@RequestHeader(value = "X-Admin-Token", required = false) String token,
                                               @PathVariable long roomId) {
        if (!authed(token)) return deny();
        DzRoom room = roomManager.get(roomId);
        if (room == null) return Map.of("code", 1, "msg", "房间不存在");
        return Map.of("code", 0, "params", robotParams.effective(room), "profit", profitControl.status(room));
    }

    /** 设置房间参数覆盖(空值=清除该键覆盖);resetLedger=true 顺带清控盘账本 */
    @PutMapping("/rooms/{roomId}/robot-params")
    public Map<String, Object> setRoomRobotParams(@RequestHeader(value = "X-Admin-Token", required = false) String token,
                                                  @PathVariable long roomId, @RequestBody Map<String, Object> body) {
        if (!authed(token)) return deny();
        DzRoom room = roomManager.get(roomId);
        if (room == null) return Map.of("code", 1, "msg", "房间不存在");
        robotParams.setRoomOverrides(roomId, body);
        if (Boolean.TRUE.equals(body.get("resetLedger")) || "true".equals(String.valueOf(body.get("resetLedger")))) {
            profitControl.resetLedger(roomId);
        }
        return Map.of("code", 0, "params", robotParams.effective(room));
    }

    /** 一键补分:{amount} → 该俱乐部全部机器人各加 amount 积分 */
    @PostMapping("/clubs/{clubId}/robots/topup")
    public Map<String, Object> topUpRobots(@RequestHeader(value = "X-Admin-Token", required = false) String token,
                                           @PathVariable long clubId, @RequestBody Map<String, Object> body) {
        if (!authed(token)) return deny();
        long amount = parseLongOr(String.valueOf(body.getOrDefault("amount", 0)), 0);
        return robotAdmin.topUp(clubId, amount);
    }

    /** 各房间机器人分布 */
    @GetMapping("/robots")
    public Map<String, Object> robots(@RequestHeader(value = "X-Admin-Token", required = false) String token) {
        if (!authed(token)) return deny();
        return robotService.listRobots();
    }

    /** 派机器人上桌:{roomId, count} → 从该房间所属俱乐部的机器人池取空闲机器人坐下带入 */
    @PostMapping("/robots/spawn")
    public Map<String, Object> spawnRobots(@RequestHeader(value = "X-Admin-Token", required = false) String token,
                                           @RequestBody Map<String, Object> body) {
        if (!authed(token)) return deny();
        long roomId = parseLongOr(String.valueOf(body.get("roomId")), 0);
        int count = (int) parseLongOr(String.valueOf(body.getOrDefault("count", 1)), 1);
        if (roomId <= 0) return Map.of("code", 1, "msg", "roomId 非法");
        Map<String, Object> res = robotAdmin.deploy(roomId, count);
        log.info("管理后台派机器人上桌: roomId={}, count={}, res={}", roomId, count, res);
        return res;
    }

    /** 撤回指定房间全部机器人:{roomId}(账号保留在池子里) */
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
