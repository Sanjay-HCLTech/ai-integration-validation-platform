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

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Service("soapTriggerService")
public class SoapTriggerService implements TriggerService {

    private final UnifiedTimelineBuilder timelineBuilder;
    private final UnifiedTraceContextHolder traceContextHolder;

    public SoapTriggerService(
            UnifiedTimelineBuilder timelineBuilder,
            UnifiedTraceContextHolder traceContextHolder) {
        this.timelineBuilder = timelineBuilder;
        this.traceContextHolder = traceContextHolder;
    }

    @Value("${soap.endpoint:}")
    private String endpoint;

    @Value("${soap.transport:http}")
    private String transport;

    @Value("${soap.action:}")
    private String soapAction;

    @Value("${soap.jms.sender:TIL.ST5.TIB-XX.REQ.VRP.XML.V1}")
    private String jmsSender;

    @Value("${soap.jms.receiver:TIL.ST5.TIB-XX.RES.VRP.XML.V1}")
    private String jmsReceiver;

    @Value("${soap.jms.async:false}")
    private boolean jmsAsync;

    @Value("${system.soap.enabled:true}")
    private boolean enabled;

    @Value("${unified.trace.report.enabled:false}")
    private boolean unifiedTraceReportEnabled;

    @Override
    public void trigger(TestCase testCase) throws Exception {
        if (!enabled) {
            throw new RuntimeException("SOAP trigger is disabled by system.soap.enabled=false");
        }

        String normalizedTransport = valueOrDefault(transport, "http").toUpperCase(Locale.ROOT);
        if ("JMS".equals(normalizedTransport)) {
            triggerSoapOverJms(testCase);
            return;
        }

        if (endpoint == null || endpoint.isEmpty()) {
            throw new RuntimeException("SOAP endpoint is not configured");
        }

        String soapXml = soapPayload(testCase);

        if (!unifiedTraceReportEnabled) {
            System.out.println();
            System.out.println("-------------------- SOAP FLOW -------------------------");
            System.out.println("[SOAP]");
            System.out.println("System=VRP");
            System.out.println("Protocol=SOAP_HTTP");
            System.out.println("Method=POST");
            System.out.println("Endpoint=" + endpoint);
            System.out.println("BookingID=" + value(testCase.getBookingId()));
        }

        long requestTimeMs = System.currentTimeMillis();
        HttpURLConnection conn = (HttpURLConnection) new URL(endpoint).openConnection();
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "text/xml; charset=UTF-8");
        conn.setRequestProperty("SOAPAction", valueOrDefault(soapAction, ""));

        try (OutputStream os = conn.getOutputStream()) {
            os.write(soapXml.getBytes());
            os.flush();
        }

        int responseCode = conn.getResponseCode();
        long responseTimeMs = System.currentTimeMillis();
        if (!unifiedTraceReportEnabled) {
            System.out.println("Status=" + responseCode);
            System.out.println("FlowStatus=" + (isSuccess(responseCode) ? "PROCESSED" : "FAILED"));
        }
        List<NormalizedTraceEvent> soapTrace = buildSoapTrace(testCase, requestTimeMs, responseTimeMs, responseCode);
        traceContextHolder.currentOrCreate().setApiEndpoint(endpoint);
        traceContextHolder.currentOrCreate().setApiStatus(String.valueOf(responseCode));
        traceContextHolder.addEvents(soapTrace);

        if (responseCode < 200 || responseCode >= 300) {
            throw new RuntimeException("SOAP trigger failed with HTTP " + responseCode);
        }
    }

    private void triggerSoapOverJms(TestCase testCase) {
        boolean async = requestedAsync(testCase);
        long sendTimeMs = System.currentTimeMillis();
        long replyTimeMs = sendTimeMs + 1;

        if (!unifiedTraceReportEnabled) {
            System.out.println();
            System.out.println("-------------------- SOAP FLOW -------------------------");
            System.out.println("[SOAP]");
            System.out.println("System=VRP");
            System.out.println("Protocol=SOAP_JMS");
            System.out.println("Mode=" + (async ? "ASYNC" : "SYNC"));
            System.out.println("SenderQueue=" + value(jmsSender));
            System.out.println("ReceiverQueue=" + value(jmsReceiver));
            System.out.println("BookingID=" + value(testCase.getBookingId()));
            System.out.println("FlowStatus=" + (async ? "QUEUED" : "PROCESSED"));
        }

        List<NormalizedTraceEvent> soapTrace = buildSoapJmsTrace(testCase, async, sendTimeMs, replyTimeMs);
        traceContextHolder.currentOrCreate().setApiEndpoint(jmsSender + " -> " + jmsReceiver);
        traceContextHolder.currentOrCreate().setApiStatus(async ? "QUEUED" : "PROCESSED");
        traceContextHolder.addEvents(soapTrace);
    }

    private List<NormalizedTraceEvent> buildSoapTrace(
            TestCase testCase,
            long requestTimeMs,
            long responseTimeMs,
            int responseCode) {
        List<NormalizedTraceEvent> events = new ArrayList<>();
        events.add(event(testCase, TracePhase.REQUEST, requestTimeMs, TraceStatus.SUCCESS));
        events.add(event(testCase, TracePhase.REPLY, responseTimeMs,
                isSuccess(responseCode) ? TraceStatus.PROCESSED : TraceStatus.FAILED));
        return timelineBuilder.build(events);
    }

    private NormalizedTraceEvent event(TestCase testCase, TracePhase phase, long epochMs, TraceStatus status) {
        NormalizedTraceEvent event = NormalizedTraceEvent.of(
                testCase.getBookingId(),
                null,
                null,
                TraceSystem.SOAP,
                TraceProtocol.SOAP_HTTP,
                phase,
                "BookingRequest",
                OffsetDateTime.ofInstant(Instant.ofEpochMilli(epochMs), ZoneId.systemDefault()),
                status,
                null);
        event.setFromEndpoint("SOAPUI");
        event.setToEndpoint(endpoint);
        return event;
    }

    private List<NormalizedTraceEvent> buildSoapJmsTrace(
            TestCase testCase,
            boolean async,
            long sendTimeMs,
            long replyTimeMs) {
        List<NormalizedTraceEvent> events = new ArrayList<>();
        events.add(jmsEvent(testCase, TracePhase.SEND, sendTimeMs, TraceStatus.SUCCESS));
        events.add(jmsEvent(testCase, async ? TracePhase.ACK : TracePhase.REPLY,
                replyTimeMs, async ? TraceStatus.DELIVERED : TraceStatus.PROCESSED));
        return timelineBuilder.build(events);
    }

    private boolean requestedAsync(TestCase testCase) {
        if (testCase == null || testCase.getExecutionMode() == null
                || testCase.getExecutionMode().trim().isEmpty()) {
            return jmsAsync;
        }
        return "ASYNC".equalsIgnoreCase(testCase.getExecutionMode().trim());
    }

    private NormalizedTraceEvent jmsEvent(TestCase testCase, TracePhase phase, long epochMs, TraceStatus status) {
        NormalizedTraceEvent event = NormalizedTraceEvent.of(
                testCase.getBookingId(),
                null,
                null,
                TraceSystem.SOAP,
                TraceProtocol.SOAP_JMS,
                phase,
                "VRPSoapMessage",
                OffsetDateTime.ofInstant(Instant.ofEpochMilli(epochMs), ZoneId.systemDefault()),
                status,
                null);
        event.setFromEndpoint(jmsSender);
        event.setToEndpoint(jmsReceiver);
        return event;
    }

    private String soapPayload(TestCase testCase) {
        if (testCase.getPayload() != null && !testCase.getPayload().trim().isEmpty()) {
            return testCase.getPayload();
        }
        return "<soapenv:Envelope xmlns:soapenv=\"http://schemas.xmlsoap.org/soap/envelope/\">"
                + "<soapenv:Header/>"
                + "<soapenv:Body>"
                + "<bookingRequest>"
                + "<bookingId>" + value(testCase.getBookingId()) + "</bookingId>"
                + "</bookingRequest>"
                + "</soapenv:Body>"
                + "</soapenv:Envelope>";
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
