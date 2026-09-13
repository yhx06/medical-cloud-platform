package com.medcloud.platform.security;

import com.medcloud.platform.identity.domain.UserRole;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.UUID;

@Service
public class JwtService {
    private final SecretKey key;

    public JwtService(@Value("${app.security.jwt-secret}") String secret) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    public String issue(CurrentUser user) {
        Instant now = Instant.now();
        var builder = Jwts.builder()
                .subject(user.userId().toString())
                .claim("username", user.username())
                .claim("role", user.role().name())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(30, ChronoUnit.MINUTES)));
        if (user.tenantId() != null) {
            builder.claim("tenantId", user.tenantId().toString());
        }
        return builder.signWith(key).compact();
    }

    public CurrentUser parse(String token) {
        Claims claims = Jwts.parser().verifyWith(key).build()
                .parseSignedClaims(token).getPayload();
        String tenantValue = claims.get("tenantId", String.class);
        return new CurrentUser(
                UUID.fromString(claims.getSubject()),
                claims.get("username", String.class),
                UserRole.valueOf(claims.get("role", String.class)),
                tenantValue == null ? null : UUID.fromString(tenantValue)
        );
    }
}
