package com.linkforge.entity;

/**
 * Account role. Stored as a string in {@code users.role}, which has a matching
 * database check constraint.
 */
public enum Role {
    USER,
    ADMIN
}
