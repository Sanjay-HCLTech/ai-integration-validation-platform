package com.hcl.execution.jms;

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
public class JmsPayloadResolver {

    private static final Set<String> JMS_EXTENSIONS = new HashSet<>(Arrays.asList("", "xml", "json", "txt"));

    private final JmsFlowConfig flowConfig;
    private final PayloadResolver payloadResolver;

    public JmsPayloadResolver(JmsFlowConfig flowConfig, PayloadResolver payloadResolver) {
        this.flowConfig = flowConfig;
        this.payloadResolver = payloadResolver;
    }

    public ResolvedJmsPayload resolve(TestCase testCase) {
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
                throw new IllegalArgumentException("Configured JMS payload file not found: " + configuredPayload);
            }
            PayloadResolution latest = payloadResolver.resolve(
                    null,
                    system,
                    bookingId,
                    JMS_EXTENSIONS,
                    flowConfig.payloadRoot(),
                    flowConfig.systemPayloadFolder(system));
            if (latest != null) {
                return from(latest);
            }
            return new ResolvedJmsPayload(defaultPayload(bookingId, system), "DEFAULT", system);
        }

        String trimmed = payload.trim();
        if (looksLikeInlinePayload(trimmed)) {
            return new ResolvedJmsPayload(interpolate(trimmed, bookingId), "REQUEST_BODY", system);
        }

        PayloadResolution resolvedPayload = resolvePayloadReference(
                trimmed,
                system,
                bookingId);
        if (resolvedPayload != null) {
            return from(resolvedPayload);
        }

        if (looksLikePath(trimmed)) {
            throw new IllegalArgumentException("JMS payload file not found: " + trimmed);
        }
        return new ResolvedJmsPayload(interpolate(trimmed, bookingId), "INLINE", system);
    }

    private PayloadResolution resolvePayloadReference(String payloadReference, String system, String bookingId) {
        PayloadResolution resolved = payloadResolver.resolve(
                payloadReference,
                system,
                bookingId,
                JMS_EXTENSIONS,
                flowConfig.payloadRoot(),
                flowConfig.systemPayloadFolder(system));
        if (resolved != null) {
            return resolved;
        }
        String correctedReference = correctCommonPayloadTypos(payloadReference);
        if (correctedReference.equals(payloadReference)) {
            return null;
        }
        return payloadResolver.resolve(
                correctedReference,
                system,
                bookingId,
                JMS_EXTENSIONS,
                flowConfig.payloadRoot(),
                flowConfig.systemPayloadFolder(system));
    }

    private String correctCommonPayloadTypos(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("Quueu", "Queue")
                .replace("quueu", "queue")
                .replace("QUEEU", "QUEUE")
                .replace("Queeu", "Queue")
                .replace("queeu", "queue");
    }

    private ResolvedJmsPayload from(PayloadResolution resolution) {
        return new ResolvedJmsPayload(resolution.getContent(), resolution.getSource(), resolution.getSystem());
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
        return "<datahubEvent>"
                + "<bookingId>" + value(bookingId) + "</bookingId>"
                + "<system>" + value(system) + "</system>"
                + "<eventType>BOOKING_UPDATE</eventType>"
                + "<timestamp>${timestamp}</timestamp>"
                + "</datahubEvent>";
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

    public static class ResolvedJmsPayload {
        private final String content;
        private final String source;
        private final String system;

        private ResolvedJmsPayload(String content, String source, String system) {
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
