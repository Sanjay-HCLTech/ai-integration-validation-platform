package com.hcl.execution.kafka;

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
public class KafkaPayloadResolver {

    private static final Set<String> KAFKA_EXTENSIONS = new HashSet<>(Arrays.asList("json", "xml", "txt"));

    private final KafkaFlowConfig flowConfig;
    private final PayloadResolver payloadResolver;

    public KafkaPayloadResolver(KafkaFlowConfig flowConfig, PayloadResolver payloadResolver) {
        this.flowConfig = flowConfig;
        this.payloadResolver = payloadResolver;
    }

    public ResolvedKafkaPayload resolve(TestCase testCase) {
        String system = flowConfig.system(testCase);
        String payload = testCase == null ? null : testCase.getPayload();
        String bookingId = testCase == null ? null : testCase.getBookingId();

        if (isBlank(payload)) {
            String configuredPayload = flowConfig.payloadFile(testCase);
            if (!isBlank(configuredPayload)) {
                PayloadResolution configured = resolvePayloadReference(
                        configuredPayload,
                        system,
                        bookingId);
                if (configured != null) {
                    return from(configured);
                }
                throw new IllegalArgumentException("Configured Kafka payload file not found: " + configuredPayload);
            }
            PayloadResolution latest = payloadResolver.resolve(
                    null,
                    system,
                    bookingId,
                    KAFKA_EXTENSIONS,
                    flowConfig.payloadRoot(),
                    flowConfig.systemPayloadFolder(system));
            if (latest != null) {
                return from(latest);
            }
            return new ResolvedKafkaPayload(defaultPayload(testCase, system), "DEFAULT", system);
        }

        String trimmed = payload.trim();
        if (looksLikeInlinePayload(trimmed)) {
            return new ResolvedKafkaPayload(interpolate(trimmed, bookingId), "REQUEST_BODY", system);
        }

        PayloadResolution resolvedPayload = resolvePayloadReference(
                trimmed,
                system,
                bookingId);
        if (resolvedPayload != null) {
            return from(resolvedPayload);
        }

        if (looksLikePath(trimmed)) {
            throw new IllegalArgumentException("Kafka payload file not found: " + trimmed);
        }
        return new ResolvedKafkaPayload(interpolate(trimmed, bookingId), "INLINE", system);
    }

    private PayloadResolution resolvePayloadReference(String payloadReference, String system, String bookingId) {
        return payloadResolver.resolve(
                payloadReference,
                system,
                bookingId,
                KAFKA_EXTENSIONS,
                flowConfig.payloadRoot(),
                flowConfig.systemPayloadFolder(system));
    }

    private ResolvedKafkaPayload from(PayloadResolution resolution) {
        return new ResolvedKafkaPayload(resolution.getContent(), resolution.getSource(), resolution.getSystem());
    }

    private String defaultPayload(TestCase testCase, String system) {
        return "{"
                + "\"bookingId\":\"" + value(testCase == null ? null : testCase.getBookingId()) + "\","
                + "\"system\":\"" + value(system) + "\","
                + "\"eventType\":\"DATAHUB_EVENT\","
                + "\"timestamp\":\"${timestamp}\""
                + "}";
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

    private boolean looksLikeInlinePayload(String value) {
        return value != null && (value.startsWith("{") || value.startsWith("[") || value.startsWith("<"));
    }

    private boolean looksLikePath(String value) {
        String lower = value == null ? "" : value.toLowerCase(Locale.ROOT);
        return lower.endsWith(".json") || lower.endsWith(".xml") || lower.endsWith(".txt")
                || value.contains("/") || value.contains("\\");
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private String value(String value) {
        return isBlank(value) ? "NA" : value.trim();
    }

    public static class ResolvedKafkaPayload {
        private final String content;
        private final String source;
        private final String system;

        private ResolvedKafkaPayload(String content, String source, String system) {
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
