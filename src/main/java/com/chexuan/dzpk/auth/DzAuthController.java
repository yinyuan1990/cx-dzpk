package com.chexuan.dzpk.auth;

import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 独立账号注册/登录(dz_user 本地库,与扯旋主服完全脱钩;钻石同样独立)。
 * 成功返回 {code:0, token, userId, nickname, avatar, diamond},token 走 WS LOGIN(401) 验签登录。
 * 前端同源调用(生产由本服静态托管;开发 vite 代理),@CrossOrigin 仅为开发期 5173 跨域。
 */
@Slf4j
@RestController
@RequestMapping("/api/auth")
@org.springframework.web.bind.annotation.CrossOrigin
public class DzAuthController {

    private final DzUserService userService;
    private final JwtVerifier jwt;

    public DzAuthController(DzUserService userService, JwtVerifier jwt) {
        this.userService = userService;
        this.jwt = jwt;
    }

    /** 登录 {phone, password} */
    @PostMapping("/login")
    public Map<String, Object> login(@RequestBody Map<String, Object> body) {
        try {
            return ok(userService.login(str(body, "phone"), str(body, "password")));
        } catch (DzUserService.AuthException e) {
            return fail(e.getMessage());
        } catch (Exception e) {
            log.error("登录异常", e);
            return fail("登录失败,请稍后再试");
        }
    }

    /**
     * 注册 — 字段对标扯旋 RegisterRequest:
     * {phone, username, avatar, password, confirmPassword, inviteCode?, registerDevice?}
     * 成功即自动登录,返回同 login。
     */
    @PostMapping("/register")
    public Map<String, Object> register(@RequestBody Map<String, Object> body) {
        try {
            String username = str(body, "username");
            if (username == null || username.isBlank()) username = str(body, "nickname"); // 旧字段名兼容
            int device = 3; // 默认 Web
            Object dv = body.get("registerDevice");
            if (dv instanceof Number n) device = n.intValue();
            return ok(userService.register(str(body, "phone"), username, str(body, "avatar"),
                    str(body, "password"), str(body, "confirmPassword"),
                    str(body, "inviteCode"), device));
        } catch (DzUserService.AuthException e) {
            return fail(e.getMessage());
        } catch (Exception e) {
            log.error("注册异常", e);
            return fail("注册失败,请稍后再试");
        }
    }

    /**
     * 修改资料(昵称/头像) — 需 token(Authorization: Bearer xxx 或 body.token)。
     * {username, avatar} → 返回最新 {userId,numberId,nickname,avatar,diamond}
     */
    @PostMapping("/update-profile")
    public Map<String, Object> updateProfile(
            @org.springframework.web.bind.annotation.RequestHeader(value = "Authorization", required = false) String auth,
            @RequestBody Map<String, Object> body) {
        Long userId = authedUserId(auth, body);
        if (userId == null) return fail("登录已过期,请重新登录");
        try {
            String username = str(body, "username");
            if (username == null || username.isBlank()) username = str(body, "nickname");
            Map<String, Object> profile = userService.updateProfile(userId, username, str(body, "avatar"));
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("code", 0);
            out.put("userId", profile.get("userId"));
            out.put("numberId", profile.get("numberId"));
            out.put("nickname", profile.get("nickname"));
            out.put("avatar", profile.get("avatar"));
            out.put("diamond", profile.get("diamond"));
            return out;
        } catch (DzUserService.AuthException e) {
            return fail(e.getMessage());
        } catch (Exception e) {
            log.error("修改资料异常: userId={}", userId, e);
            return fail("修改失败,请稍后再试");
        }
    }

    /** 修改登录密码 — 需 token。{oldPassword, newPassword, confirmPassword} */
    @PostMapping("/change-password")
    public Map<String, Object> changePassword(
            @org.springframework.web.bind.annotation.RequestHeader(value = "Authorization", required = false) String auth,
            @RequestBody Map<String, Object> body) {
        Long userId = authedUserId(auth, body);
        if (userId == null) return fail("登录已过期,请重新登录");
        try {
            userService.changePassword(userId, str(body, "oldPassword"),
                    str(body, "newPassword"), str(body, "confirmPassword"));
            return Map.of("code", 0);
        } catch (DzUserService.AuthException e) {
            return fail(e.getMessage());
        } catch (Exception e) {
            log.error("修改密码异常: userId={}", userId, e);
            return fail("修改失败,请稍后再试");
        }
    }

    /** 从 Authorization: Bearer xxx(或 body.token 兜底)验签取 userId */
    private Long authedUserId(String auth, Map<String, Object> body) {
        String token = null;
        if (auth != null && auth.startsWith("Bearer ")) token = auth.substring(7).trim();
        if ((token == null || token.isBlank()) && body != null) token = str(body, "token");
        if (token == null || token.isBlank()) return null;
        return jwt.verify(token);
    }

    private Map<String, Object> ok(Map<String, Object> profile) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("code", 0);
        out.put("token", jwt.sign((long) profile.get("userId"), (String) profile.get("phone")));
        out.put("userId", profile.get("userId"));
        out.put("numberId", profile.get("numberId"));
        out.put("nickname", profile.get("nickname"));
        out.put("avatar", profile.get("avatar"));
        out.put("diamond", profile.get("diamond"));
        return out;
    }

    private static Map<String, Object> fail(String msg) {
        return Map.of("code", 1, "msg", msg);
    }

    private static String str(Map<String, Object> m, String k) {
        Object v = m.get(k);
        return v != null ? v.toString() : null;
    }
}
