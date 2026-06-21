package com.hcl.execution.soap;

import com.hcl.execution.model.TestCase;
import com.hcl.execution.payload.PayloadResolution;
import com.hcl.execution.payload.PayloadResolver;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

@Service
public class SoapPayloadResolver {

    private final SoapFlowConfig flowConfig;
    private final PayloadResolver payloadResolver;
    private static final Set<String> SOAP_EXTENSIONS = new HashSet<>(Arrays.asList("xml", "txt"));

    public SoapPayloadResolver(
            SoapFlowConfig flowConfig,
            PayloadResolver payloadResolver) {
        this.flowConfig = flowConfig;
        this.payloadResolver = payloadResolver;
    }

    public ResolvedSoapPayload resolve(TestCase testCase) {
        String system = flowConfig.downstreamSystem(testCase);
        String payload = testCase == null ? null : testCase.getPayload();
        String bookingId = testCase == null ? null : testCase.getBookingId();

        if (isBlank(payload)) {
            PayloadResolution latest = payloadResolver.resolve(
                    null,
                    system,
                    bookingId,
                    SOAP_EXTENSIONS,
                    flowConfig.payloadRoot(),
                    flowConfig.systemPayloadFolder(system));
            if (latest != null) {
                return from(latest);
            }
            return new ResolvedSoapPayload(defaultPayload(bookingId), "DEFAULT", system);
        }

        String trimmed = payload.trim();
        if (looksLikeXml(trimmed)) {
            return new ResolvedSoapPayload(interpolate(trimmed, bookingId), "REQUEST_BODY", system);
        }

        PayloadResolution resolvedPayload = payloadResolver.resolve(
                trimmed,
                system,
                bookingId,
                SOAP_EXTENSIONS,
                flowConfig.payloadRoot(),
                flowConfig.systemPayloadFolder(system));
        if (resolvedPayload != null) {
            return from(resolvedPayload);
        }

        if (looksLikePath(trimmed)) {
            throw new IllegalArgumentException("SOAP payload file not found: " + trimmed);
        }
        return new ResolvedSoapPayload(interpolate(trimmed, bookingId), "INLINE", system);
    }

    private ResolvedSoapPayload from(PayloadResolution resolution) {
        return new ResolvedSoapPayload(resolution.getContent(), resolution.getSource(), resolution.getSystem());
    }

    private boolean looksLikeXml(String value) {
        return value != null && value.trim().startsWith("<");
    }

    private boolean looksLikePath(String value) {
        String lower = value == null ? "" : value.toLowerCase();
        return lower.endsWith(".xml") || lower.endsWith(".txt")
                || value.contains("/") || value.contains("\\");
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

    private String defaultPayload(String bookingId) {
        return "<soapenv:Envelope xmlns:soapenv=\"http://schemas.xmlsoap.org/soap/envelope/\">"
                + "<soapenv:Header/>"
                + "<soapenv:Body>"
                + "<bookingRequest>"
                + "<bookingId>" + value(bookingId) + "</bookingId>"
                + "</bookingRequest>"
                + "</soapenv:Body>"
                + "</soapenv:Envelope>";
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private String value(String value) {
        return isBlank(value) ? "NA" : value.trim();
    }

    public static class ResolvedSoapPayload {
        private final String content;
        private final String source;
        private final String system;

        private ResolvedSoapPayload(String content, String source, String system) {
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
