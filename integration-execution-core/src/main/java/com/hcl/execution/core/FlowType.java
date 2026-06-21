package com.hcl.execution.core;

import java.util.Locale;

public enum FlowType {
    SOAP,
    REST,
    JMS,
    RABBIT,
    KAFKA;

    public static FlowType from(String value) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException("FlowType is required");
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        if ("RABBITMQ".equals(normalized) || "AMQP".equals(normalized)) {
            return RABBIT;
        }
        if ("EVENTHUB".equals(normalized) || "EVENT-HUB".equals(normalized)) {
            return KAFKA;
        }
        return FlowType.valueOf(normalized);
    }
}
