package com.hcl.execution.trigger;

import com.hcl.execution.model.TestCase;
import com.hcl.execution.payload.PayloadResolution;
import com.hcl.execution.payload.PayloadResolver;
import com.hcl.execution.rest.RestAssertionResult;
import com.hcl.execution.rest.RestFlowConfig;
import com.hcl.execution.rest.RestResponseValidator;
import com.hcl.execution.rest.RestTriggerOutcome;
import com.hcl.execution.rest.RestValidationResult;
import com.hcl.observability.trace.NormalizedTraceEvent;
import com.hcl.observability.trace.TracePhase;
import com.hcl.observability.trace.TraceProtocol;
import com.hcl.observability.trace.TraceStatus;
import com.hcl.observability.trace.TraceSystem;
import com.hcl.observability.trace.UnifiedTraceContextHolder;
import com.hcl.observability.trace.UnifiedTimelineBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.zip.GZIPInputStream;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service("restTriggerService")
public class RestTriggerService implements TriggerService {

    private final UnifiedTimelineBuilder timelineBuilder;
    private final UnifiedTraceContextHolder traceContextHolder;
    private final RestFlowConfig flowConfig;
    private final PayloadResolver payloadResolver;
    private final RestResponseValidator responseValidator;
    private static final Set<String> REST_EXTENSIONS = new HashSet<>(Arrays.asList("json", "xml", "txt"));
    private static final List<String> TRACKING_HEADERS = Arrays.asList(
            "TrackingID",
            "Tracking-Id",
            "Tracking_Id",
            "trackingId",
            "X-Tracking-ID",
            "X-TrackingId",
            "X-Correlation-ID",
            "X-Request-ID");
    private static final Pattern TRACKING_JSON_PATTERN = Pattern.compile(
            "\"(?:trackingId|trackingID|tracking_id|TrackingID|Tracking_Id|traceId|requestId)\"\\s*:\\s*\"([^\"]+)\"",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern TRACKING_XML_PATTERN = Pattern.compile(
            "<(?:trackingId|trackingID|tracking_id|TrackingID|Tracking_Id|traceId|requestId)>([^<]+)</",
            Pattern.CASE_INSENSITIVE);

    public RestTriggerService(
            UnifiedTimelineBuilder timelineBuilder,
            UnifiedTraceContextHolder traceContextHolder,
            RestFlowConfig flowConfig,
            PayloadResolver payloadResolver,
            RestResponseValidator responseValidator) {
        this.timelineBuilder = timelineBuilder;
        this.traceContextHolder = traceContextHolder;
        this.flowConfig = flowConfig;
        this.payloadResolver = payloadResolver;
        this.responseValidator = responseValidator;
    }

    @Value("${rest.endpoint.default}")
    private String endpoint;

    @Value("${rest.api.key.default}")
    private String apiKey;

    @Value("${rest.method.default}")
    private String method;

    @Value("${rest.accept.value}")
    private String acceptHeader;

    @Value("${rest.content.type.default}")
    private String contentType;

    @Value("${rest.connect.timeout.ms}")
    private int connectTimeoutMs;

    @Value("${rest.read.timeout.ms}")
    private int readTimeoutMs;

    @Value("${rest.get.query.param}")
    private String getQueryParam;

    @Value("${system.rest.enabled}")
    private boolean enabled;

    @Value("${unified.trace.report.enabled}")
    private boolean unifiedTraceReportEnabled;

    @Override
    public void trigger(TestCase testCase) throws Exception {
        execute(testCase);
    }

    public RestTriggerOutcome execute(TestCase testCase) throws Exception {
        if (!enabled) {
            throw new RuntimeException("REST trigger is disabled by system.rest.enabled=false");
        }

        String env = flowConfig.env(testCase);
        String collection = flowConfig.collection(testCase);
        String brand = flowConfig.brand(testCase);
        String resolvedEndpoint = flowConfig.endpoint(env, collection, brand);
        String resolvedApiKey = flowConfig.apiKey(env, collection, brand);
        String resolvedCombinedAcceptHeader = flowConfig.combinedAcceptHeader(collection);
        String resolvedAcceptHeader = flowConfig.acceptHeader(collection, acceptHeader);
        String resolvedDefaultAcceptHeader = flowConfig.defaultAcceptHeader(collection);
        String resolvedConnectionHeader = flowConfig.connectionHeader(collection);
        String resolvedAcceptEncodingHeader = flowConfig.acceptEncodingHeader(collection);
        String resolvedContentType = flowConfig.contentType(collection, contentType);
        String successMarkers = flowConfig.successMarkers(collection);
        String httpMethod = flowConfig.method(collection, method == null ? "POST" : method)
                .trim().toUpperCase(Locale.ROOT);
        ResolvedRestPayload resolvedPayload = resolvePayload(testCase, collection);
        String payload = resolvedPayload.content;

        if (resolvedEndpoint == null || resolvedEndpoint.isEmpty()) {
            throw new RuntimeException("REST endpoint not configured");
        }

        if (resolvedApiKey == null || resolvedApiKey.isEmpty()) {
            throw new RuntimeException(missingApiKeyMessage(env, collection, brand));
        }

        String urlStr = resolvedEndpoint;
        if ("GET".equals(httpMethod)) {
            String queryParam = flowConfig.queryParam(collection, getQueryParam);
            String queryPayload = flowConfig.compactJsonQuery() ? compactJson(payload) : payload;
            String encodedPayload = encodeQueryPayload(queryPayload, flowConfig.queryEncoding(collection));
            urlStr = resolvedEndpoint + "?" + valueOrDefault(queryParam, "request") + "=" + encodedPayload;
        }

        if (!unifiedTraceReportEnabled) {
            System.out.println();
            System.out.println("[API]");
            System.out.println("Endpoint=" + resolvedEndpoint);
        }

        long requestTimeMs = System.currentTimeMillis();
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(urlStr).openConnection();
            conn.setRequestMethod(httpMethod);
            conn.setConnectTimeout(Math.max(0, connectTimeoutMs));
            conn.setReadTimeout(Math.max(0, readTimeoutMs));
            setAcceptHeaders(conn, resolvedCombinedAcceptHeader, resolvedDefaultAcceptHeader, resolvedAcceptHeader);
            if (!"GET".equals(httpMethod)) {
                conn.setRequestProperty("Content-Type", valueOrDefault(resolvedContentType, "application/json"));
            }
            conn.setRequestProperty("User-Agent", "PostmanRuntime/7.54.0");
            conn.setRequestProperty("Connection", valueOrDefault(resolvedConnectionHeader, "close"));
            if (trimToNull(resolvedAcceptEncodingHeader) != null) {
                conn.setRequestProperty("Accept-Encoding", resolvedAcceptEncodingHeader);
            }
            conn.setRequestProperty("x-api-key", resolvedApiKey);
            if (!"GET".equals(httpMethod)) {
                conn.setDoOutput(true);
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(payload.getBytes(StandardCharsets.UTF_8));
                }
            }

            int responseCode = conn.getResponseCode();
            String responseBody = readResponse(conn, responseCode);
            String trackingId = trackingId(conn, responseBody);
            RestValidationResult validationResult = responseValidator.validate(responseCode, responseBody, successMarkers);
            long responseTimeMs = System.currentTimeMillis();
            if (!unifiedTraceReportEnabled) {
                System.out.println("Status=" + responseCode);
                printRestFlow(testCase, env, collection, brand, httpMethod, resolvedEndpoint, responseCode, resolvedPayload.source);
                printAssertions(validationResult);
            }
            List<NormalizedTraceEvent> restTrace = buildRestTrace(
                    testCase, collection, resolvedEndpoint, requestTimeMs, responseTimeMs, responseCode);
            traceContextHolder.currentOrCreate().setApiEndpoint(resolvedEndpoint);
            traceContextHolder.currentOrCreate().setApiStatus(String.valueOf(responseCode));
            traceContextHolder.addEvents(restTrace);
            addUnifiedTraceLines(
                    testCase,
                    env,
                    collection,
                    brand,
                    httpMethod,
                    resolvedEndpoint,
                    responseCode,
                    resolvedPayload.source,
                    validationResult,
                    responseTimeMs - requestTimeMs);
            addRequestDiagnosticLines(collection, resolvedDefaultAcceptHeader, resolvedAcceptHeader, resolvedCombinedAcceptHeader);

            RestTriggerOutcome outcome = new RestTriggerOutcome();
            outcome.setEndpoint(resolvedEndpoint);
            outcome.setMethod(httpMethod);
            outcome.setCollection(collection);
            outcome.setBrand(brand);
            outcome.setPayloadSource(resolvedPayload.source);
            outcome.setHttpStatus(responseCode);
            outcome.setTrackingId(trackingId);
            outcome.setResponseBody(responseBody);
            outcome.setValidationResult(validationResult);
            outcome.setMessage(validationResult.summary());

            return outcome;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private void setAcceptHeaders(
            HttpURLConnection conn,
            String combinedAccept,
            String defaultAccept,
            String configuredAccept) {
        String normalizedCombined = trimToNull(combinedAccept);
        if (normalizedCombined != null) {
            conn.setRequestProperty("Accept", normalizedCombined);
            return;
        }
        String normalizedDefault = trimToNull(defaultAccept);
        String normalizedConfigured = trimToNull(configuredAccept);
        if (normalizedDefault != null) {
            conn.setRequestProperty("Accept", normalizedDefault);
            if (normalizedConfigured != null && !normalizedConfigured.equalsIgnoreCase(normalizedDefault)) {
                conn.addRequestProperty("Accept", normalizedConfigured);
            }
            return;
        }
        conn.setRequestProperty("Accept", valueOrDefault(normalizedConfigured, "application/json"));
    }

    private String encodeQueryPayload(String payload, String encodingMode) throws Exception {
        String encoded = URLEncoder.encode(payload == null ? "" : payload, StandardCharsets.UTF_8.toString());
        if ("RFC3986".equalsIgnoreCase(valueOrDefault(encodingMode, "RFC3986"))) {
            return encoded.replace("+", "%20")
                    .replace("%7E", "~");
        }
        return encoded;
    }

    private String compactJson(String value) {
        if (value == null || value.trim().isEmpty()) {
            return "";
        }
        StringBuilder compact = new StringBuilder(value.length());
        boolean inString = false;
        boolean escaped = false;
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (escaped) {
                compact.append(current);
                escaped = false;
                continue;
            }
            if (current == '\\') {
                compact.append(current);
                escaped = inString;
                continue;
            }
            if (current == '"') {
                compact.append(current);
                inString = !inString;
                continue;
            }
            if (!inString && Character.isWhitespace(current)) {
                continue;
            }
            compact.append(current);
        }
        return compact.toString();
    }

    private void addUnifiedTraceLines(
            TestCase testCase,
            String env,
            String collection,
            String brand,
            String httpMethod,
            String endpoint,
            int responseCode,
            String payloadSource,
            RestValidationResult validationResult,
            long latencyMs) {
        traceContextHolder.currentOrCreate().addProtocolLine("APIGEE", "Env=" + value(env));
        traceContextHolder.currentOrCreate().addProtocolLine("APIGEE", "Collection=" + value(collection));
        traceContextHolder.currentOrCreate().addProtocolLine("APIGEE", "Brand=" + value(brand));
        traceContextHolder.currentOrCreate().addProtocolLine("APIGEE", "Protocol=REST");
        traceContextHolder.currentOrCreate().addProtocolLine("APIGEE", "Method=" + value(httpMethod));
        traceContextHolder.currentOrCreate().addProtocolLine("APIGEE", "Endpoint=" + value(endpoint));
        traceContextHolder.currentOrCreate().addProtocolLine("APIGEE", "Payload=" + value(payloadSource));
        traceContextHolder.currentOrCreate().addProtocolLine("APIGEE", "QueryParam="
                + value(flowConfig.queryParam(collection, getQueryParam)));
        traceContextHolder.currentOrCreate().addProtocolLine("APIGEE", "BookingID=NA");
        traceContextHolder.currentOrCreate().addProtocolLine("APIGEE", "HttpStatus=" + responseCode);
        traceContextHolder.currentOrCreate().addProtocolLine("APIGEE", "FlowStatus="
                + (validationResult != null && validationResult.isPassed() ? "SUCCESS" : "FAILED"));
        traceContextHolder.currentOrCreate().addValidationLine("HttpCode=" + responseCode);
        traceContextHolder.currentOrCreate().addValidationLine("ApiResponse="
                + (validationResult != null && validationResult.isPassed() ? "SUCCESS" : "FAILED"));
        traceContextHolder.currentOrCreate().addValidationLine("ApiFlow="
                + (validationResult != null && validationResult.isPassed() ? "SUCCESS" : "FAILED"));
        traceContextHolder.currentOrCreate().addSummaryLine("ApiResponseTimeMs=" + Math.max(0, latencyMs));
        traceContextHolder.currentOrCreate().addSummaryLine("LogAnalyzerEnabled=N");
    }

    private void addRequestDiagnosticLines(
            String collection,
            String defaultAccept,
            String configuredAccept,
            String combinedAccept) {
        traceContextHolder.currentOrCreate().addProtocolLine("APIGEE", "AcceptDefault=" + value(defaultAccept));
        traceContextHolder.currentOrCreate().addProtocolLine("APIGEE", "AcceptConfigured=" + value(configuredAccept));
        traceContextHolder.currentOrCreate().addProtocolLine("APIGEE", "AcceptCombined=" + value(combinedAccept));
        traceContextHolder.currentOrCreate().addProtocolLine("APIGEE", "QueryEncoding="
                + value(flowConfig.queryEncoding(collection)));
    }

    private ResolvedRestPayload resolvePayload(TestCase testCase, String collection) {
        String payload = testCase == null ? null : testCase.getPayload();
        String bookingId = testCase == null ? null : testCase.getBookingId();
        if (payload != null && looksLikeInlinePayload(payload)) {
            return new ResolvedRestPayload(payload, "REQUEST_BODY");
        }
        String configuredPayload = payload;
        if (configuredPayload == null || configuredPayload.trim().isEmpty()) {
            configuredPayload = flowConfig.payloadFile(testCase, collection);
        }
        PayloadResolution resolution = payloadResolver.resolve(
                configuredPayload,
                collection,
                bookingId,
                REST_EXTENSIONS,
                flowConfig.payloadRoot(),
                flowConfig.collectionPayloadFolder(collection));
        if (resolution != null) {
            return new ResolvedRestPayload(resolution.getContent(), resolution.getSource());
        }
        if (configuredPayload != null && looksLikePath(configuredPayload)) {
            throw new IllegalArgumentException("REST payload file not found: " + configuredPayload);
        }
        return new ResolvedRestPayload(configuredPayload == null || configuredPayload.trim().isEmpty() ? "{}" : configuredPayload, "INLINE");
    }

    private void printRestFlow(
            TestCase testCase,
            String env,
            String collection,
            String brand,
            String httpMethod,
            String resolvedEndpoint,
            int responseCode,
            String payloadSource) {
        System.out.println();
        System.out.println("-------------------- REST FLOW -------------------------");
        System.out.println("[REST]");
        System.out.println("System=APIGEE");
        System.out.println("Env=" + value(env));
        System.out.println("Collection=" + value(collection));
        System.out.println("Brand=" + value(brand));
        System.out.println("Protocol=REST");
        System.out.println("Method=" + httpMethod);
        System.out.println("Endpoint=" + resolvedEndpoint);
        System.out.println("Payload=" + value(payloadSource));
        System.out.println("BookingID=" + value(testCase.getBookingId()));
        System.out.println("FlowStatus=" + (isSuccess(responseCode) ? "SUCCESS" : "FAILED"));
    }

    private void printAssertions(RestValidationResult validationResult) {
        System.out.println("[ASSERT]");
        if (validationResult == null) {
            System.out.println("HTTP_CODE=FAIL");
            return;
        }
        for (RestAssertionResult assertion : validationResult.getAssertions()) {
            System.out.println(assertion.getName() + "=" + (assertion.isPassed() ? "PASS" : "FAIL"));
        }
    }

    private String readResponse(HttpURLConnection conn, int responseCode) throws Exception {
        InputStream stream = responseCode >= 200 && responseCode < 400
                ? conn.getInputStream()
                : conn.getErrorStream();
        if (stream == null) {
            return "";
        }
        StringBuilder response = new StringBuilder();
        String encoding = conn.getContentEncoding();
        InputStream responseStream = encoding != null && encoding.toLowerCase(Locale.ROOT).contains("gzip")
                ? new GZIPInputStream(stream)
                : stream;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(responseStream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                response.append(line).append(System.lineSeparator());
            }
        }
        return response.toString().trim();
    }

    private String trackingId(HttpURLConnection conn, String responseBody) {
        if (conn != null) {
            for (String header : TRACKING_HEADERS) {
                String value = trimToNull(conn.getHeaderField(header));
                if (value != null) {
                    return value;
                }
            }
        }
        String body = responseBody == null ? "" : responseBody;
        Matcher jsonMatcher = TRACKING_JSON_PATTERN.matcher(body);
        if (jsonMatcher.find()) {
            return jsonMatcher.group(1).trim();
        }
        Matcher xmlMatcher = TRACKING_XML_PATTERN.matcher(body);
        if (xmlMatcher.find()) {
            return xmlMatcher.group(1).trim();
        }
        return null;
    }

    private List<NormalizedTraceEvent> buildRestTrace(
            TestCase testCase,
            String collection,
            String resolvedEndpoint,
            long requestTimeMs,
            long responseTimeMs,
            int responseCode) {
        List<NormalizedTraceEvent> events = new ArrayList<>();
        events.add(apiEvent(testCase, collection, requestTimeMs));
        events.add(event(testCase, resolvedEndpoint, TracePhase.REQUEST, collection, requestTimeMs, TraceStatus.SUCCESS));
        events.add(event(
                testCase,
                resolvedEndpoint,
                TracePhase.REPLY,
                collection,
                responseTimeMs,
                isSuccess(responseCode) ? TraceStatus.SUCCESS : TraceStatus.FAILED));
        return timelineBuilder.build(events);
    }

    private NormalizedTraceEvent apiEvent(TestCase testCase, String collection, long epochMs) {
        return NormalizedTraceEvent.of(
                null,
                null,
                null,
                TraceSystem.API,
                TraceProtocol.REST,
                TracePhase.REQUEST,
                valueOrDefault(collection, "ApigeeSubmit"),
                OffsetDateTime.ofInstant(java.time.Instant.ofEpochMilli(epochMs), ZoneId.systemDefault()),
                TraceStatus.SUCCESS,
                null);
    }

    private NormalizedTraceEvent event(
            TestCase testCase,
            String resolvedEndpoint,
            TracePhase phase,
            String operation,
            long epochMs,
            TraceStatus status) {
        NormalizedTraceEvent event = NormalizedTraceEvent.of(
                null,
                null,
                null,
                TraceSystem.REST,
                TraceProtocol.REST,
                phase,
                operation,
                OffsetDateTime.ofInstant(java.time.Instant.ofEpochMilli(epochMs), ZoneId.systemDefault()),
                status,
                null);
        event.setFromEndpoint("API");
        event.setToEndpoint(resolvedEndpoint);
        return event;
    }

    private boolean looksLikeInlinePayload(String value) {
        if (value == null) {
            return false;
        }
        String trimmed = value.trim();
        return trimmed.startsWith("{") || trimmed.startsWith("[") || trimmed.startsWith("<");
    }

    private boolean looksLikePath(String value) {
        if (value == null) {
            return false;
        }
        String lower = value.toLowerCase(Locale.ROOT);
        return lower.endsWith(".json") || lower.endsWith(".xml") || lower.endsWith(".txt")
                || value.contains("/") || value.contains("\\");
    }

    private boolean isSuccess(int responseCode) {
        return responseCode >= 200 && responseCode < 300;
    }

    private String value(String value) {
        return value == null || value.trim().isEmpty() ? "NA" : value;
    }

    private String valueOrDefault(String value, String fallback) {
        return value == null || value.trim().isEmpty() ? fallback : value.trim();
    }

    private String missingApiKeyMessage(String env, String collection, String brand) {
        String normalizedEnv = safeToken(env);
        String normalizedCollection = flowConfig.normalizeCollection(collection);
        String normalizedBrand = safeToken(brand);
        List<String> candidates = new ArrayList<>();
        if (trimToNull(normalizedEnv) != null && trimToNull(normalizedCollection) != null
                && trimToNull(normalizedBrand) != null) {
            candidates.add("rest.api.key." + normalizedEnv + "." + normalizedCollection + "." + normalizedBrand);
        }
        if (trimToNull(normalizedEnv) != null && trimToNull(normalizedCollection) != null) {
            candidates.add("rest.api.key." + normalizedEnv + "." + normalizedCollection);
        }
        if (trimToNull(normalizedCollection) != null) {
            candidates.add("rest.api.key." + normalizedCollection);
        }
        candidates.add("rest.api.key.default");
        return "REST API key not configured for " + valueOrDefault(normalizedCollection, "REST")
                + " (" + valueOrDefault(normalizedEnv, "ENV") + "). Configure "
                + String.join(" or ", candidates) + " in application.properties.";
    }

    private String safeToken(String value) {
        String trimmed = trimToNull(value);
        return trimmed == null ? null : trimmed.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]+", "_");
    }

    private String trimToNull(String value) {
        return value == null || value.trim().isEmpty() ? null : value.trim();
    }

    private static class ResolvedRestPayload {
        private final String content;
        private final String source;

        private ResolvedRestPayload(String content, String source) {
            this.content = content;
            this.source = source;
        }
    }
}
