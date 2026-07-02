package com.hcl.execution.rabbit;

import com.hcl.execution.model.TestCase;
import com.hcl.execution.payload.PayloadResolution;
import com.hcl.execution.payload.PayloadResolver;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

@Service
public class RabbitPayloadResolver {

    private static final Set<String> RABBIT_EXTENSIONS = new HashSet<>(Arrays.asList("xml", "json", "txt"));

    private final RabbitFlowConfig flowConfig;
    private final PayloadResolver payloadResolver;

    public RabbitPayloadResolver(RabbitFlowConfig flowConfig, PayloadResolver payloadResolver) {
        this.flowConfig = flowConfig;
        this.payloadResolver = payloadResolver;
    }

    public ResolvedRabbitPayload resolve(TestCase testCase) {
        String system = flowConfig.system(testCase);
        String payload = testCase == null ? null : testCase.getPayload();
        String bookingId = testCase == null ? null : testCase.getBookingId();

        if (isBlank(payload)) {
            String configuredPayload = flowConfig.payloadFile(testCase);
            if (!isBlank(configuredPayload)) {
                PayloadResolution configured = payloadResolver.resolve(
                        configuredPayload,
                        system,
                        bookingId,
                        RABBIT_EXTENSIONS,
                        flowConfig.payloadRoot(),
                        flowConfig.systemPayloadFolder(system));
                if (configured != null) {
                    return from(configured);
                }
                throw new IllegalArgumentException("Configured Rabbit payload file not found: " + configuredPayload);
            }
            PayloadResolution latest = payloadResolver.resolve(
                    null,
                    system,
                    bookingId,
                    RABBIT_EXTENSIONS,
                    flowConfig.payloadRoot(),
                    flowConfig.systemPayloadFolder(system));
            if (latest != null) {
                return from(latest);
            }
            return new ResolvedRabbitPayload(defaultPayload(bookingId, system), "DEFAULT", system);
        }

        String trimmed = payload.trim();
        if (looksLikeInlinePayload(trimmed)) {
            return new ResolvedRabbitPayload(interpolate(trimmed, bookingId), "REQUEST_BODY", system);
        }

        PayloadResolution resolvedPayload = payloadResolver.resolve(
                trimmed,
                system,
                bookingId,
                RABBIT_EXTENSIONS,
                flowConfig.payloadRoot(),
                flowConfig.systemPayloadFolder(system));
        if (resolvedPayload != null) {
            return from(resolvedPayload);
        }

        if (looksLikePath(trimmed)) {
            throw new IllegalArgumentException("Rabbit payload file not found: " + trimmed);
        }
        return new ResolvedRabbitPayload(interpolate(trimmed, bookingId), "INLINE", system);
    }

    private ResolvedRabbitPayload from(PayloadResolution resolution) {
        return new ResolvedRabbitPayload(resolution.getContent(), resolution.getSource(), resolution.getSystem());
    }

    private boolean looksLikeInlinePayload(String value) {
        return value != null && (value.startsWith("<") || value.startsWith("{") || value.startsWith("["));
    }

    private boolean looksLikePath(String value) {
        String lower = value == null ? "" : value.toLowerCase(Locale.ROOT);
        return lower.endsWith(".xml") || lower.endsWith(".json") || lower.endsWith(".txt")
                || value.contains("/") || value.contains("\\");
    }

    private String defaultPayload(String bookingId, String system) {
        return "<nordicsEvent>"
                + "<bookingId>" + value(bookingId) + "</bookingId>"
                + "<system>" + value(system) + "</system>"
                + "<eventType>DATAHUB_EVENT</eventType>"
                + "<timestamp>${timestamp}</timestamp>"
                + "</nordicsEvent>";
    }

    private String interpolate(String payload, String bookingId) {
        if (payload == null) {
            return "";
        }
        return payload
                .replace("${bookingId}", value(bookingId))
                .replace("${jobId}", "")
                .replace("${corrId}", "")
                .replace("${timestamp}", OffsetDateTime.now().toString());
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private String value(String value) {
        return isBlank(value) ? "NA" : value.trim();
    }

    public static class ResolvedRabbitPayload {
        private final String content;
        private final String source;
        private final String system;

        private ResolvedRabbitPayload(String content, String source, String system) {
            this.content = content;
            this.source = source;
            this.system = system;
        }

        public String getContent() {
            return content;
        }

        public String getSource() {
            return source;
        }

        public String getSystem() {
            return system;
        }
    }
}
