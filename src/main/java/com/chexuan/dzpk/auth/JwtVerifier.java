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
 * 德州独立 JWT 签发+验签(账号体系已与扯旋主服脱钩,token 本服自签自验)。
 * claims: userId(Long) / phone(String)。
 */
@Slf4j
@Component
public class JwtVerifier {

    private final SecretKey key;

    /** token 有效期(天) */
    @Value("${dzpk.jwt-expire-days:7}")
    private long expireDays;

    public JwtVerifier(@Value("${dzpk.jwt-secret}") String secret) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    /** 本地签发(注册/登录成功后调) */
    public String sign(long userId, String phone) {
        long now = System.currentTimeMillis();
        return Jwts.builder()
                .claim("userId", userId)
                .claim("phone", phone)
                .issuedAt(new Date(now))
                .expiration(new Date(now + expireDays * 24 * 3600_000L))
                .signWith(key)
                .compact();
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
