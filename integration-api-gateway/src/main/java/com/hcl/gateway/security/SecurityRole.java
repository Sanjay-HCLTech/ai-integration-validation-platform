package com.hcl.gateway.security;

public enum SecurityRole {
    VIEWER,
    OPERATOR,
    ADMIN;

    public boolean includes(SecurityRole required) {
        return ordinal() >= required.ordinal();
    }

    public static SecurityRole from(String value, SecurityRole fallback) {
        if (value == null || value.trim().isEmpty()) {
            return fallback;
        }
        try {
            return SecurityRole.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }
}
