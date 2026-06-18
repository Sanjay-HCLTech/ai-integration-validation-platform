package com.hcl.execution.model;

import java.util.Locale;

public enum ScenarioMode {
    SYNC,
    ASYNC;

    public static ScenarioMode from(String value) {
        if (value == null || value.trim().isEmpty()) {
            return SYNC;
        }
        return ScenarioMode.valueOf(value.trim().toUpperCase(Locale.ROOT));
    }
}
