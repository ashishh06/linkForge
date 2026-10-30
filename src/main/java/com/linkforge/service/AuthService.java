package com.linkforge.service;

import com.linkforge.dto.auth.AuthResponse;
import com.linkforge.dto.auth.LoginRequest;
import com.linkforge.dto.auth.RegisterRequest;
import com.linkforge.dto.auth.UserResponse;
import com.linkforge.entity.Role;
import com.linkforge.entity.User;
import com.linkforge.exception.BadRequestException;
import com.linkforge.exception.ConflictException;
import com.linkforge.exception.UnauthorizedException;
import com.linkforge.repository.UserRepository;
import com.linkforge.security.IssuedToken;
import com.linkforge.security.JwtService;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Registration and login.
 *
 * <p>Phase 1 returns an access token only. Refresh-token rotation and reuse
 * detection are Phase 2; the token issuance point is kept in one place so that
 * change is additive.
 */
@Service
public class AuthService {

    /** BCrypt is defined over the first 72 bytes of the input. */
    private static final int MAX_PASSWORD_BYTES = 72;

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    /**
     * A valid hash verified when the email does not exist. It keeps the failed-login
     * path's work roughly constant, so response time does not reveal which emails are
     * registered.
     */
    private final String dummyPasswordHash;

    public AuthService(UserRepository users, PasswordEncoder passwordEncoder, JwtService jwtService) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.dummyPasswordHash = passwordEncoder.encode("linkforge-dummy-password");
    }

    @Transactional
    public AuthResponse register(RegisterRequest request) {
        String email = normaliseEmail(request.email());
        validatePasswordBytes(request.password());

        if (users.existsByEmail(email)) {
            throw new ConflictException("Email is already registered");
        }

        String passwordHash = passwordEncoder.encode(request.password());
        User user = new User(email, passwordHash, Role.USER);

        try {
            // Flush now rather than at commit so a concurrent registration that wins
            // the unique constraint is reported as 409 instead of a failed commit.
            User saved = users.saveAndFlush(user);
            return authResponse(saved);
        } catch (DataIntegrityViolationException ex) {
            throw new ConflictException("Email is already registered");
        }
    }

    @Transactional(readOnly = true)
    public AuthResponse login(LoginRequest request) {
        String email = normaliseEmail(request.email());

        User user = users.findByEmail(email).orElse(null);
        String hash = user != null ? user.getPasswordHash() : dummyPasswordHash;
        boolean passwordMatches = passwordFitsBcrypt(request.password())
                && passwordEncoder.matches(request.password(), hash);

        if (user == null || !passwordMatches) {
            throw new UnauthorizedException("Invalid email or password");
        }

        return authResponse(user);
    }

    private AuthResponse authResponse(User user) {
        IssuedToken token = jwtService.issue(user.getId(), user.getEmail(), user.getRole());
        return new AuthResponse(
                token.value(),
                "Bearer",
                token.expiresInSeconds(),
                new UserResponse(user.getId(), user.getEmail(), user.getRole().name()));
    }

    private static String normaliseEmail(String email) {
        return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
    }

    private static boolean passwordFitsBcrypt(String password) {
        return password != null
                && password.getBytes(StandardCharsets.UTF_8).length <= MAX_PASSWORD_BYTES;
    }

    private static void validatePasswordBytes(String password) {
        if (!passwordFitsBcrypt(password)) {
            throw new BadRequestException("password must be at most 72 bytes");
        }
    }
}
