package com.linkforge.service;

import com.linkforge.config.properties.LinkforgeProperties;
import com.linkforge.dto.link.LinkResponse;
import com.linkforge.entity.Link;
import com.linkforge.exception.ShortCodeAllocationException;
import com.linkforge.util.ShortCodeGenerator;
import com.linkforge.util.UrlValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Creates short links.
 *
 * <p>Collision handling (decision D1): generate a random 7-character Base62 code,
 * insert, and retry a bounded number of times when the unique constraint rejects it.
 * The constraint is the source of truth, not a prior existence check, so concurrent
 * creates across instances remain correct.
 */
@Service
public class LinkService {

    private static final Logger log = LoggerFactory.getLogger(LinkService.class);

    private final LinkInserter inserter;
    private final ShortCodeGenerator codeGenerator;
    private final UrlValidator urlValidator;
    private final LinkforgeProperties properties;
    private final Set<String> reservedAliases;

    public LinkService(LinkInserter inserter,
                       ShortCodeGenerator codeGenerator,
                       UrlValidator urlValidator,
                       LinkforgeProperties properties) {
        this.inserter = inserter;
        this.codeGenerator = codeGenerator;
        this.urlValidator = urlValidator;
        this.properties = properties;
        this.reservedAliases = new HashSet<>();
        for (String alias : properties.link().reservedAliases()) {
            reservedAliases.add(alias.toLowerCase(Locale.ROOT));
        }
    }

    public LinkResponse create(Long userId, String rawUrl) {
        String url = urlValidator.validate(rawUrl);
        int maxAttempts = properties.link().maxCollisionRetries();

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            String code = codeGenerator.generate();
            if (reservedAliases.contains(code.toLowerCase(Locale.ROOT))) {
                // A generated code must not shadow a route that is reserved for the
                // application (FR-2). This is cheap to check and avoids a nasty edge case.
                continue;
            }

            Link link = new Link(code, url, userId);
            try {
                Link saved = inserter.insert(link);
                return toResponse(saved);
            } catch (DataIntegrityViolationException ex) {
                if (!isShortCodeCollision(ex)) {
                    throw ex;
                }
                log.debug("Short code collision on attempt {}/{}", attempt, maxAttempts);
            }
        }

        throw new ShortCodeAllocationException(maxAttempts);
    }

    private LinkResponse toResponse(Link link) {
        return new LinkResponse(
                link.getId(),
                link.getShortCode(),
                shortUrl(link.getShortCode()),
                link.getOriginalUrl(),
                link.isCustomAlias(),
                link.getCreatedAt(),
                link.getExpiresAt());
    }

    private String shortUrl(String code) {
        String baseUrl = properties.baseUrl();
        if (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }
        return baseUrl + "/" + code;
    }

    private static boolean isShortCodeCollision(DataIntegrityViolationException ex) {
        Throwable cause = ex.getMostSpecificCause();
        String message = cause.getMessage();
        if (message == null) {
            return false;
        }
        String lower = message.toLowerCase(Locale.ROOT);
        return lower.contains("uq_links_short_code") || lower.contains("short_code");
    }
}
