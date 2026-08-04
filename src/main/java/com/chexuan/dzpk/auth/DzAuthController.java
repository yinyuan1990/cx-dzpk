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
