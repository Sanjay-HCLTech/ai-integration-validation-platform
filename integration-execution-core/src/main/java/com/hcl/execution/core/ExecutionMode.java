package com.hcl.execution.core;

import java.util.Locale;

public enum ExecutionMode {
    SYNC,
    ASYNC;

    public static ExecutionMode from(String value, ExecutionMode fallback) {
        if (value == null || value.trim().isEmpty()) {
            return fallback;
        }
        return ExecutionMode.valueOf(value.trim().toUpperCase(Locale.ROOT));
    }
}
