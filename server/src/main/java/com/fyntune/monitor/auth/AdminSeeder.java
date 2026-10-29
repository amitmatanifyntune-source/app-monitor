package com.fyntune.monitor.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/** Creates the first administrator when there are no users yet. Does nothing afterwards. */
@Component
public class AdminSeeder implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(AdminSeeder.class);
    private final Users users;
    private final PasswordEncoder encoder;
    private final String username, password;

    public AdminSeeder(Users users, PasswordEncoder encoder, @Value("${app.admin.username}") String username, @Value("${app.admin.password}") String password) {
        this.users = users;
        this.encoder = encoder;
        this.username = username;
        this.password = password;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (users.count() > 0) return;
        if (password == null || password.length() < 10) {
            log.warn("No users yet. Set MONITOR_ADMIN_PASSWORD (at least 10 characters) and restart to create the first administrator.");
            return;
        }
        users.create(username, "Administrator", encoder.encode(password), "ADMIN");
        log.info("Created the first administrator '{}'", username);
    }
}
