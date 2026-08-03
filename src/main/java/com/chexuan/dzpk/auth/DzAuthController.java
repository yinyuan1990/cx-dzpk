package com.chexuan.dzpk.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 账号密码登录/注册 — 代理到扯旋主服(/api/user/login、/api/user/register)。
 * 主服签发的 JWT 与本服共享密钥(dzpk.jwt-secret),拿到 token 后走 WS LOGIN(401)验签登录;
 * userId 即主服 user.id,钻石(公用货币)天然互通。
 * 前端同源调用(生产由本服静态托管;开发 vite 代理 /api → 9100),无跨域问题。
 */
@Slf4j
@RestController
@RequestMapping("/api/auth")
@org.springframework.web.bind.annotation.CrossOrigin // 开发期前端 5173 跨域调用;生产同源无影响
public class DzAuthController {

    private final ObjectMapper mapper;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).build();

    /** 主服地址:生产 compose 同网络容器名;本地开发需自起主服或改配置 */
    @Value("${dzpk.main-server-url:http://chexuan-app:9000}")
    private String mainServerUrl;

    public DzAuthController(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /** 登录 {phone, password} → {code:0, token, userId, nickname, diamond} / {code:1, msg} */
    @PostMapping("/login")
    public Map<String, Object> login(@RequestBody Map<String, Object> body) {
        String phone = str(body, "phone");
        String password = str(body, "password");
        if (phone == null || phone.isBlank() || password == null || password.isBlank()) {
            return fail("请输入账号和密码");
        }
        return forward("/api/user/login", Map.of("phone", phone.trim(), "password", password));
    }

    /** 注册 {phone, password, nickname?} → 主服注册成功即自动登录,返回同 login */
    @PostMapping("/register")
    public Map<String, Object> register(@RequestBody Map<String, Object> body) {
        String phone = str(body, "phone");
        String password = str(body, "password");
        if (phone == null || phone.isBlank() || password == null || password.isBlank()) {
            return fail("请输入账号和密码");
        }
        String nickname = str(body, "nickname");
        if (nickname == null || nickname.isBlank()) {
            nickname = "玩家" + phone.trim().substring(Math.max(0, phone.trim().length() - 4));
        }
        Map<String, Object> req = new LinkedHashMap<>();
        req.put("phone", phone.trim());
        req.put("username", nickname.trim());
        req.put("avatar", "default");
        req.put("password", password);
        req.put("confirmPassword", password);
        req.put("registerDevice", 3);
        return forward("/api/user/register", req);
    }

    // ==================== 主服转发 ====================

    @SuppressWarnings("unchecked")
    private Map<String, Object> forward(String path, Map<String, Object> body) {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(mainServerUrl + path))
                    .timeout(Duration.ofSeconds(8))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                    .build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            Map<String, Object> main = mapper.readValue(res.body(), Map.class);
            Object code = main.get("code");
            if (!(code instanceof Number n) || n.intValue() != 200) {
                Object message = main.get("message");
                return fail(message != null ? message.toString() : "登录失败");
            }
            Map<String, Object> data = main.get("data") instanceof Map
                    ? (Map<String, Object>) main.get("data") : Map.of();
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("code", 0);
            out.put("token", data.get("token"));
            out.put("userId", data.get("userId"));
            out.put("nickname", data.get("username"));
            out.put("diamond", data.get("diamond"));
            return out;
        } catch (Exception e) {
            log.warn("主服转发失败: {}{} - {}", mainServerUrl, path, e.toString());
            return fail("登录服务暂不可用,请稍后再试");
        }
    }

    private static Map<String, Object> fail(String msg) {
        return Map.of("code", 1, "msg", msg);
    }

    private static String str(Map<String, Object> m, String k) {
        Object v = m.get(k);
        return v != null ? v.toString() : null;
    }
}
