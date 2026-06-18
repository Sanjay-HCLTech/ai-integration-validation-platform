package com.hcl.execution.trigger;

import com.hcl.execution.model.TestCase;
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
import java.io.OutputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Service("restTriggerService")
public class RestTriggerService implements TriggerService {

    private final UnifiedTimelineBuilder timelineBuilder;
    private final UnifiedTraceContextHolder traceContextHolder;

    public RestTriggerService(
            UnifiedTimelineBuilder timelineBuilder,
            UnifiedTraceContextHolder traceContextHolder) {
        this.timelineBuilder = timelineBuilder;
        this.traceContextHolder = traceContextHolder;
    }

    @Value("${rest.endpoint}")
    private String endpoint;

    @Value("${rest.api.key}")
    private String apiKey;

    @Value("${rest.method:POST}")
    private String method;

    @Value("${rest.accept:application/json}")
    private String acceptHeader;

    @Value("${rest.content.type:application/json}")
    private String contentType;

    @Value("${system.rest.enabled:true}")
    private boolean enabled;

    @Value("${unified.trace.report.enabled:false}")
    private boolean unifiedTraceReportEnabled;

    @Override
    public void trigger(TestCase testCase) throws Exception {
        if (!enabled) {
            throw new RuntimeException("REST trigger is disabled by system.rest.enabled=false");
        }

        if (endpoint == null || endpoint.isEmpty()) {
            throw new RuntimeException("REST endpoint not configured");
        }

        if (apiKey == null || apiKey.isEmpty()) {
            throw new RuntimeException("REST API key not configured");
        }

        String payload = testCase.getPayload() == null ? "{}" : testCase.getPayload();
        String httpMethod = method == null ? "POST" : method.trim().toUpperCase(Locale.ROOT);
        String urlStr = endpoint;
        if ("GET".equals(httpMethod)) {
            String encodedPayload = URLEncoder.encode(payload, StandardCharsets.UTF_8.toString());
            urlStr = endpoint + "?getPackageOffersRequest=" + encodedPayload;
        }

        if (!unifiedTraceReportEnabled) {
            System.out.println();
            System.out.println("[API]");
            System.out.println("Endpoint=" + endpoint);
        }

        long requestTimeMs = System.currentTimeMillis();
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setRequestMethod(httpMethod);
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(5000);
        conn.setRequestProperty("Accept", valueOrDefault(acceptHeader, "application/json"));
        conn.setRequestProperty("Content-Type", valueOrDefault(contentType, "application/json"));
        conn.setRequestProperty("User-Agent", "Mozilla/5.0");
        conn.setRequestProperty("Connection", "keep-alive");
        conn.setRequestProperty("x-api-key", apiKey);
        if (!"GET".equals(httpMethod)) {
            conn.setDoOutput(true);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(payload.getBytes(StandardCharsets.UTF_8));
            }
        }

        int responseCode = conn.getResponseCode();
        long responseTimeMs = System.currentTimeMillis();
        if (!unifiedTraceReportEnabled) {
            System.out.println("Status=" + responseCode);
            printRestFlow(testCase, httpMethod, responseCode);
        }
        List<NormalizedTraceEvent> restTrace = buildRestTrace(testCase, requestTimeMs, responseTimeMs, responseCode);
        traceContextHolder.currentOrCreate().setApiEndpoint(endpoint);
        traceContextHolder.currentOrCreate().setApiStatus(String.valueOf(responseCode));
        traceContextHolder.addEvents(restTrace);

        if (responseCode < 200 || responseCode >= 300) {
            throw new RuntimeException("REST trigger failed with HTTP " + responseCode);
        }

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()))) {
            while (reader.readLine() != null) {
                // Drain response stream so the HTTP connection completes cleanly.
            }
        }
    }

    private void printRestFlow(TestCase testCase, String httpMethod, int responseCode) {
        System.out.println();
        System.out.println("-------------------- REST FLOW -------------------------");
        System.out.println("[REST]");
        System.out.println("System=APIGEE");
        System.out.println("Protocol=REST");
        System.out.println("Method=" + httpMethod);
        System.out.println("Endpoint=" + endpoint);
        System.out.println("BookingID=" + value(testCase.getBookingId()));
        System.out.println("FlowStatus=" + (isSuccess(responseCode) ? "SUCCESS" : "FAILED"));
    }

    private List<NormalizedTraceEvent> buildRestTrace(
            TestCase testCase,
            long requestTimeMs,
            long responseTimeMs,
            int responseCode) {
        List<NormalizedTraceEvent> events = new ArrayList<>();
        events.add(event(testCase, TracePhase.REQUEST, "PackageOffers", requestTimeMs, TraceStatus.SUCCESS));
        events.add(event(
                testCase,
                TracePhase.REPLY,
                "PackageOffers",
                responseTimeMs,
                isSuccess(responseCode) ? TraceStatus.SUCCESS : TraceStatus.FAILED));
        return timelineBuilder.build(events);
    }

    private NormalizedTraceEvent event(
            TestCase testCase,
            TracePhase phase,
            String operation,
            long epochMs,
            TraceStatus status) {
        NormalizedTraceEvent event = NormalizedTraceEvent.of(
                testCase.getBookingId(),
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
        event.setToEndpoint(endpoint);
        return event;
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
}
