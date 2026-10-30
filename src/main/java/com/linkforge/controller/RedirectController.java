package com.linkforge.controller;

import com.linkforge.service.RedirectService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

/**
 * The public redirect. This is the only route outside {@code /api/v1}; the security
 * configuration permits exactly a single-segment GET here.
 */
@RestController
@Tag(name = "Redirect", description = "Public short-link redirect")
public class RedirectController {

    private final RedirectService redirectService;

    public RedirectController(RedirectService redirectService) {
        this.redirectService = redirectService;
    }

    @GetMapping("/{code}")
    @Operation(summary = "Redirect to the original URL (302)")
    public ResponseEntity<Void> redirect(@PathVariable String code) {
        String destination = redirectService.resolve(code);
        return ResponseEntity.status(HttpStatus.FOUND)
                .location(URI.create(destination))
                .build();
    }
}
