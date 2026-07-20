package com.adaptivelearning.shared.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Set;

@Service
public class JwtService {
    private final SecretKey key;
    private final Duration accessTtl;

    public JwtService(@Value("${app.security.jwt-secret}") String secret,
                      @Value("${app.security.access-token-minutes:15}") long minutes) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.accessTtl = Duration.ofMinutes(minutes);
    }

    public String issue(CurrentUser user) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(user.id().toString())
                .claim("pid", user.publicId())
                .claim("username", user.username())
                .claim("roles", user.roles())
                .claim("permissions", user.permissions())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(accessTtl)))
                .signWith(key)
                .compact();
    }

    @SuppressWarnings("unchecked")
    public CurrentUser parse(String token) {
        Claims claims = Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
        Set<String> roles = Set.copyOf((List<String>) claims.getOrDefault("roles", List.of()));
        Set<String> permissions = Set.copyOf((List<String>) claims.getOrDefault("permissions", List.of()));
        return new CurrentUser(Long.valueOf(claims.getSubject()), claims.get("pid", String.class),
                claims.get("username", String.class), "", roles, permissions);
    }

    public long expiresInSeconds() {
        return accessTtl.toSeconds();
    }
}

