package com.chexuan.dzpk.auth;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

/**
 * 主服 token 本地验签 — 与 chexuan-springboot JwtUtil 共享密钥,
 * 子游戏不回主服校验,零耦合。claims: userId(Long) / phone(String)。
 */
@Slf4j
@Component
public class JwtVerifier {

    private final SecretKey key;

    public JwtVerifier(@Value("${dzpk.jwt-secret}") String secret) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    /** 验签+校期,返回 userId;无效返回 null */
    public Long verify(String token) {
        try {
            Claims claims = Jwts.parser().verifyWith(key).build()
                    .parseSignedClaims(token).getPayload();
            if (claims.getExpiration() != null && claims.getExpiration().before(new Date())) {
                return null;
            }
            return claims.get("userId", Long.class);
        } catch (Exception e) {
            log.warn("token 验签失败: {}", e.getMessage());
            return null;
        }
    }
}
