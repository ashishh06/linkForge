package com.linkforge.service;

import com.linkforge.dto.auth.AuthResponse;
import com.linkforge.dto.auth.LoginRequest;
import com.linkforge.dto.auth.RegisterRequest;
import com.linkforge.entity.Role;
import com.linkforge.entity.User;
import com.linkforge.exception.BadRequestException;
import com.linkforge.exception.ConflictException;
import com.linkforge.exception.UnauthorizedException;
import com.linkforge.repository.UserRepository;
import com.linkforge.security.IssuedToken;
import com.linkforge.security.JwtService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock
    private UserRepository users;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private JwtService jwtService;

    private AuthService service;

    @BeforeEach
    void setUp() {
        // Called once by the constructor to build the constant-time dummy hash.
        when(passwordEncoder.encode("linkforge-dummy-password")).thenReturn("$dummy$");
        service = new AuthService(users, passwordEncoder, jwtService);
    }

    @Test
    void registersWithNormalisedEmailAndReturnsAnAccessToken() throws Exception {
        when(users.existsByEmail("new@example.com")).thenReturn(false);
        when(passwordEncoder.encode("password123")).thenReturn("$2a$10$hash");
        when(users.saveAndFlush(any(User.class))).thenAnswer(invocation -> {
            User user = invocation.getArgument(0);
            setUserId(user, 5L);
            return user;
        });
        when(jwtService.issue(5L, "new@example.com", Role.USER))
                .thenReturn(new IssuedToken("access-token", 900, Instant.now().plusSeconds(900)));

        AuthResponse response = service.register(new RegisterRequest("  New@Example.com ", "password123"));

        assertThat(response.accessToken()).isEqualTo("access-token");
        assertThat(response.tokenType()).isEqualTo("Bearer");
        assertThat(response.expiresIn()).isEqualTo(900);
        assertThat(response.user().id()).isEqualTo(5L);
        assertThat(response.user().email()).isEqualTo("new@example.com");

        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(users).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getEmail()).isEqualTo("new@example.com");
        assertThat(saved.getValue().getPasswordHash()).isEqualTo("$2a$10$hash");
        assertThat(saved.getValue().getRole()).isEqualTo(Role.USER);
    }

    @Test
    void rejectsDuplicateRegistrationWithoutInserting() {
        when(users.existsByEmail("taken@example.com")).thenReturn(true);

        assertThatThrownBy(() -> service.register(new RegisterRequest("taken@example.com", "password123")))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("already registered");

        verify(users, never()).saveAndFlush(any(User.class));
    }

    @Test
    void rejectsPasswordLongerThanBcryptCanHash() {
        String longPassword = "x".repeat(73);

        assertThatThrownBy(() -> service.register(new RegisterRequest("user@example.com", longPassword)))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("72 bytes");

        verify(users, never()).saveAndFlush(any(User.class));
    }

    @Test
    void logsInWithValidCredentials() throws Exception {
        User user = user(7L, "user@example.com", "$2a$10$hash");
        when(users.findByEmail("user@example.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("password123", "$2a$10$hash")).thenReturn(true);
        when(jwtService.issue(7L, "user@example.com", Role.USER))
                .thenReturn(new IssuedToken("access-token", 900, Instant.now().plusSeconds(900)));

        AuthResponse response = service.login(new LoginRequest("USER@example.com", "password123"));

        assertThat(response.accessToken()).isEqualTo("access-token");
        assertThat(response.user().email()).isEqualTo("user@example.com");
    }

    @Test
    void unknownEmailGetsTheSameGenericErrorAsAWrongPassword() {
        when(users.findByEmail("missing@example.com")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.login(new LoginRequest("missing@example.com", "password123")))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessage("Invalid email or password");
    }

    @Test
    void wrongPasswordIsRejected() throws Exception {
        User user = user(7L, "user@example.com", "$2a$10$hash");
        when(users.findByEmail("user@example.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("wrong-password", "$2a$10$hash")).thenReturn(false);

        assertThatThrownBy(() -> service.login(new LoginRequest("user@example.com", "wrong-password")))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessage("Invalid email or password");
    }

    private static User user(Long id, String email, String hash) throws Exception {
        User user = new User(email, hash, Role.USER);
        setUserId(user, id);
        return user;
    }

    private static void setUserId(User user, Long id) throws Exception {
        Field field = User.class.getDeclaredField("id");
        field.setAccessible(true);
        field.set(user, id);
    }
}
