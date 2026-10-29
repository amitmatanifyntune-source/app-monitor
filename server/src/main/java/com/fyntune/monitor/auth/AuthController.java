package com.fyntune.monitor.auth;

import com.fyntune.monitor.common.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    /** Five failed attempts per username and address lock that pair out for 15 minutes. */
    private static final int MAX_FAILS = 5;
    private static final long LOCK_SECONDS = 15 * 60;

    private final Users users;
    private final PasswordEncoder encoder;
    private final JwtService jwt;
    private final Audit audit;
    private final Map<String, int[]> fails = new ConcurrentHashMap<>();
    private final Map<String, Instant> lockedUntil = new ConcurrentHashMap<>();

    public AuthController(Users users, PasswordEncoder encoder, JwtService jwt, Audit audit) {
        this.users = users;
        this.encoder = encoder;
        this.jwt = jwt;
        this.audit = audit;
    }

    public record Login(@NotBlank String username, @NotBlank String password) {}

    @PostMapping("/login")
    public Map<String, Object> login(@jakarta.validation.Valid @RequestBody Login in, HttpServletRequest req) {
        String key = in.username().toLowerCase() + "|" + req.getRemoteAddr();
        Instant until = lockedUntil.get(key);
        if (until != null && until.isAfter(Instant.now()))
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "Too many failed attempts. Try again in 15 minutes.");
        var hash = users.passwordHash(in.username());
        if (hash.isEmpty() || !encoder.matches(in.password(), hash.get())) {
            int n = fails.computeIfAbsent(key, k -> new int[1])[0] += 1;
            if (n >= MAX_FAILS) { lockedUntil.put(key, Instant.now().plusSeconds(LOCK_SECONDS)); fails.remove(key); }
            audit.log(in.username(), "LOGIN_FAILED", null, "from " + req.getRemoteAddr());
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Wrong username or password");
        }
        fails.remove(key);
        lockedUntil.remove(key);
        AuthUser u = users.active(in.username()).orElseThrow();
        audit.log(u.username(), "LOGIN", null, "from " + req.getRemoteAddr());
        return Map.of("token", jwt.issue(u), "user", u);
    }

    @GetMapping("/me")
    public AuthUser me() {
        return CurrentUser.get();
    }
}
