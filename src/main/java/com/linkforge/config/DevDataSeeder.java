package com.linkforge.config;

import com.linkforge.entity.Role;
import com.linkforge.entity.User;
import com.linkforge.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Creates a predictable development account when the {@code local} profile is active.
 *
 * <p>This is a convenience for manual testing, not a production feature: the bean only
 * exists under {@code local}, so a deployed instance never gains a known password.
 */
@Component
@Profile("local")
public class DevDataSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DevDataSeeder.class);

    static final String DEV_EMAIL = "dev@linkforge.local";
    static final String DEV_PASSWORD = "devpassword";

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;

    public DevDataSeeder(UserRepository users, PasswordEncoder passwordEncoder) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (users.existsByEmail(DEV_EMAIL)) {
            return;
        }

        users.save(new User(DEV_EMAIL, passwordEncoder.encode(DEV_PASSWORD), Role.USER));
        log.info("Seeded local development user: {} / {}", DEV_EMAIL, DEV_PASSWORD);
    }
}
