package com.linkforge.repository;

import com.linkforge.entity.BlockedDomain;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface BlockedDomainRepository extends JpaRepository<BlockedDomain, Long> {

    /**
     * Only the domain strings are needed on the create path; selecting the whole
     * entity would pull back columns the validator does not use.
     */
    @Query("select b.domain from BlockedDomain b")
    List<String> findAllDomains();

    boolean existsByDomain(String domain);
}
