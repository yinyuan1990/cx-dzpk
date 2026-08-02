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

    /** token → 过期时间戳 */
    private final Map<String, Long> tokens = new ConcurrentHashMap<>();

    @Value("${dzpk.admin-password:dz@admin2026}")
    private String adminPassword;

    public DzAdminController(DzConfigService configService, DzRoomManager roomManager,
                             DzGameService gameService, WsSessionRegistry registry) {
        this.configService = configService;
        this.roomManager = roomManager;
        this.gameService = gameService;
        this.registry = registry;
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
            return Map.of("code", 0, "key", key, "value", value);
        } catch (IllegalArgumentException e) {
            return Map.of("code", 1, "msg", e.getMessage());
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
