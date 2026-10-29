package com.fyntune.monitor.auth;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;

@Service
public class JwtService {
    /** The development secret committed in application-dev.yml; anyone can sign tokens with it. */
    static final String DEV_SECRET = "dev-only-monitor-secret-change-me-dev-only-monitor-secret";

    private final SecretKey key;
    private final long expiryMinutes;

    public JwtService(@Value("${app.jwt.secret}") String secret, @Value("${app.jwt.expiry-minutes}") long expiryMinutes, Environment env) {
        if (secret == null || secret.isBlank())
            throw new IllegalStateException("APP_JWT_SECRET is not set. Use a random value of at least 32 bytes, e.g. `openssl rand -base64 48`.");
        if (secret.getBytes(StandardCharsets.UTF_8).length < 32)
            throw new IllegalStateException("APP_JWT_SECRET must be at least 32 bytes.");
        if (DEV_SECRET.equals(secret) && !env.acceptsProfiles(Profiles.of("dev")))
            throw new IllegalStateException("The development JWT secret is public. Set APP_JWT_SECRET outside the dev profile.");
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.expiryMinutes = expiryMinutes;
    }

    public String issue(AuthUser u) {
        Instant now = Instant.now();
        return Jwts.builder().subject(u.username()).claim("uid", u.id())
                .issuedAt(Date.from(now)).expiration(Date.from(now.plus(expiryMinutes, ChronoUnit.MINUTES)))
                .signWith(key).compact();
    }

    /** The username in a valid token; throws for a forged or expired one. */
    public String username(String token) {
        Claims c = Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
        return c.getSubject();
    }
}
