package com.linkforge.service;

import com.linkforge.entity.Link;
import com.linkforge.repository.LinkRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * One insert attempt in its own transaction.
 *
 * <p>This exists specifically so that collision retries work: when Postgres rejects a
 * duplicate {@code short_code}, the surrounding transaction is already marked
 * rollback-only and cannot be reused. Each attempt therefore gets a fresh
 * transaction, and only successful attempts commit.
 */
@Component
public class LinkInserter {

    private final LinkRepository links;

    public LinkInserter(LinkRepository links) {
        this.links = links;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Link insert(Link link) {
        return links.saveAndFlush(link);
    }
}
