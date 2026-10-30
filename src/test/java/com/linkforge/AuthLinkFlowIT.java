package com.linkforge;

import com.linkforge.entity.Link;
import com.linkforge.entity.Role;
import com.linkforge.entity.User;
import com.linkforge.repository.LinkRepository;
import com.linkforge.repository.UserRepository;
import com.linkforge.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class AuthLinkFlowIT extends IntegrationTestBase {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository users;

    @Autowired
    private LinkRepository links;

    @Test
    void registerLoginCreateAndRedirect() throws Exception {
        String email = uniqueEmail("flow");
        String password = "password123";

        MvcResult registered = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("email", email, "password", password))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.user.email").value(email))
                .andReturn();

        MvcResult loggedIn = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("email", email, "password", password))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andReturn();

        String token = read(loggedIn, "accessToken").asString();
        long userId = read(loggedIn, "user").get("id").asLong();
        String originalUrl = "https://example.com/products/electronics/phones?id=84729&utm_source=newsletter";

        MvcResult created = mockMvc.perform(post("/api/v1/links")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("url", originalUrl))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").isString())
                .andExpect(jsonPath("$.shortUrl").value(startsWith("http://localhost:8080/")))
                .andExpect(jsonPath("$.originalUrl").value(originalUrl))
                .andReturn();

        String code = read(created, "code").asString();
        assertThat(code).hasSize(7);

        Link stored = links.findByShortCode(code).orElseThrow();
        assertThat(stored.getUserId()).isEqualTo(userId);
        assertThat(stored.getOriginalUrl()).isEqualTo(originalUrl);

        mockMvc.perform(get("/" + code))
                .andExpect(status().isFound())
                .andExpect(header().string(HttpHeaders.LOCATION, originalUrl));
    }

    @Test
    void unknownCodeReturns404InTheDocumentedShape() throws Exception {
        mockMvc.perform(get("/" + "z".repeat(20)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.error").value("Not Found"))
                .andExpect(jsonPath("$.path").value("/" + "z".repeat(20)));
    }

    @Test
    void createWithoutTokenReturns401() throws Exception {
        mockMvc.perform(post("/api/v1/links")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("url", "https://example.com"))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.message").value("Authentication required"));
    }

    @Test
    void invalidAccessTokenIsTreatedAsAnonymous() throws Exception {
        mockMvc.perform(post("/api/v1/links")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer not-a-real-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("url", "https://example.com"))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void loginWithWrongPasswordReturns401() throws Exception {
        String email = uniqueEmail("wrong-password");
        register(email, "password123");

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("email", email, "password", "not-the-password"))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.message").value("Invalid email or password"));
    }

    @Test
    void duplicateRegistrationReturns409() throws Exception {
        String email = uniqueEmail("duplicate");
        register(email, "password123");

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("email", email, "password", "password123"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409));
    }

    @Test
    void invalidDestinationReturns400() throws Exception {
        String token = register(uniqueEmail("invalid-url"), "password123");

        mockMvc.perform(post("/api/v1/links")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("url", "javascript:alert(1)"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").value("url must use http or https"));
    }

    @Test
    void expiredDisabledAndDeletedLinksReturn410() throws Exception {
        User owner = users.save(new User(uniqueEmail("status"), "unused-password-hash", Role.USER));
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 10);

        Link expired = new Link("e" + suffix, "https://example.com/expired", owner.getId());
        expired.setCreatedAt(Instant.now().minus(2, ChronoUnit.DAYS));
        expired.setExpiresAt(Instant.now().minus(1, ChronoUnit.DAYS));
        links.save(expired);

        Link disabled = new Link("d" + suffix, "https://example.com/disabled", owner.getId());
        disabled.setActive(false);
        links.save(disabled);

        Link deleted = new Link("x" + suffix, "https://example.com/deleted", owner.getId());
        deleted.setDeletedAt(Instant.now());
        links.save(deleted);

        for (String code : new String[] {expired.getShortCode(), disabled.getShortCode(), deleted.getShortCode()}) {
            mockMvc.perform(get("/" + code))
                    .andExpect(status().isGone())
                    .andExpect(jsonPath("$.status").value(410));
        }
    }

    private String register(String email, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("email", email, "password", password))))
                .andExpect(status().isCreated())
                .andReturn();
        return read(result, "accessToken").asString();
    }

    private JsonNode read(MvcResult result, String field) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8)).get(field);
    }

    private String json(Object value) throws Exception {
        return objectMapper.writeValueAsString(value);
    }

    private static String uniqueEmail(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8) + "@example.com";
    }
}
