package com.linkforge.security;

import com.linkforge.entity.Role;

/**
 * Principal placed in the security context after a JWT is verified. It carries only
 * the claims the request needs; the database is not hit on every authenticated call.
 */
public record AuthenticatedUser(
        Long id,
        String email,
        Role role
) {
}
