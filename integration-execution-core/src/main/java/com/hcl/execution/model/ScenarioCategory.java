package com.hcl.execution.model;

import java.util.Locale;

public enum ScenarioCategory {
    DATAHUB,
    VRP,
    NORDICS,
    APIGEE;

    public static ScenarioCategory from(String value) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException("Scenario category is required");
        }
        return ScenarioCategory.valueOf(value.trim().toUpperCase(Locale.ROOT));
    }
}
