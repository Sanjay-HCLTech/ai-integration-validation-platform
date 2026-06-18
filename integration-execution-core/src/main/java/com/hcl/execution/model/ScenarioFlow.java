package com.hcl.execution.model;

import java.util.Locale;

public enum ScenarioFlow {
    DMS_BOOKING,
    GIP_BOOKING,
    SAP_BOOKING;

    public static ScenarioFlow from(String value) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException("Scenario flow is required");
        }
        return ScenarioFlow.valueOf(value.trim().toUpperCase(Locale.ROOT));
    }
}
