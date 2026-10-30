package com.linkforge;

import com.linkforge.dto.link.LinkResponse;
import com.linkforge.entity.Role;
import com.linkforge.entity.User;
import com.linkforge.repository.LinkRepository;
import com.linkforge.repository.UserRepository;
import com.linkforge.service.LinkService;
import com.linkforge.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 1 exit criterion: 1,000 creates across 50 threads must produce zero duplicate
 * codes and zero unhandled errors.
 *
 * <p>This exercises the real insert path against Postgres, including the unique
 * constraint and the retry logic, rather than mocking the database.
 */
class LinkCreationConcurrencyIT extends IntegrationTestBase {

    private static final int CREATES = 1_000;
    private static final int THREADS = 50;

    @Autowired
    private LinkService linkService;

    @Autowired
    private UserRepository users;

    @Autowired
    private LinkRepository links;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Test
    void createsOneThousandUniqueLinksAcrossFiftyThreads() throws Exception {
        User owner = users.save(new User(
                "concurrency-" + UUID.randomUUID().toString().substring(0, 8) + "@example.com",
                passwordEncoder.encode("unused-password"),
                Role.USER));

        long linksBefore = links.count();
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        Set<String> codes = new HashSet<>();

        try {
            List<Future<LinkResponse>> futures = new ArrayList<>(CREATES);
            for (int i = 0; i < CREATES; i++) {
                int index = i;
                futures.add(pool.submit(() -> linkService.create(
                        owner.getId(),
                        "https://example.com/items/" + index)));
            }

            for (Future<LinkResponse> future : futures) {
                LinkResponse response = future.get(60, TimeUnit.SECONDS);
                assertThat(response.code()).hasSize(7);
                assertThat(codes.add(response.code()))
                        .as("duplicate short code: %s", response.code())
                        .isTrue();
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(codes).hasSize(CREATES);
        assertThat(links.count()).isEqualTo(linksBefore + CREATES);
    }
}
