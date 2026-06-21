package com.hcl.execution.jms;

import org.springframework.stereotype.Service;

import java.util.Locale;

@Service
public class JmsValidationService {

    public String validate(JmsMessage message) {
        if (message == null) {
            return "JMS message is missing";
        }
        if (isBlank(message.getBookingId())) {
            return "BookingID is missing";
        }
        if (isBlank(message.getCorrId())) {
            return "CorrID is missing";
        }
        if (isBlank(message.getJobId())) {
            return "JobID is missing";
        }
        if (!isSupportedSource(message.getSourceSystem())) {
            return "Unsupported sourceSystem: " + message.getSourceSystem();
        }
        if (!hasPayloadIntegrity(message.getPayload())) {
            return "Payload integrity validation failed";
        }
        return null;
    }

    private boolean isSupportedSource(String sourceSystem) {
        if (isBlank(sourceSystem)) {
            return false;
        }

        String normalized = sourceSystem.trim().toUpperCase(Locale.ROOT);
        return "DATAHUB".equals(normalized)
                || "GIP".equals(normalized)
                || "SAP".equals(normalized)
                || "DMS".equals(normalized)
                || "AO".equals(normalized)
                || "TDA".equals(normalized)
                || "C4C".equals(normalized);
    }

    private boolean hasPayloadIntegrity(String payload) {
        if (isBlank(payload)) {
            return false;
        }

        String trimmed = payload.trim();
        return trimmed.length() > 2 && !"{}".equals(trimmed);
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
