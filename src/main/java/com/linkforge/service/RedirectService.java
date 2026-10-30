package com.linkforge.service;

import com.linkforge.entity.Link;
import com.linkforge.exception.GoneException;
import com.linkforge.exception.NotFoundException;
import com.linkforge.repository.LinkRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Resolves a short code to its destination.
 *
 * <p>Phase 1 reads PostgreSQL directly. Phase 4 puts Redis in front of this lookup
 * with cache-aside, negative caching and jitter; the status rules below stay the same
 * and are the authority for what may be cached.
 */
@Service
public class RedirectService {

    private final LinkRepository links;

    public RedirectService(LinkRepository links) {
        this.links = links;
    }

    /**
     * @throws NotFoundException when the code was never issued
     * @throws GoneException when the link exists but is expired, disabled or deleted
     */
    @Transactional(readOnly = true)
    public String resolve(String code) {
        Link link = links.findByShortCode(code)
                .orElseThrow(() -> new NotFoundException("Short link not found"));

        if (!link.isRedirectableAt(Instant.now())) {
            throw new GoneException("Short link is no longer available");
        }

        return link.getOriginalUrl();
    }
}
