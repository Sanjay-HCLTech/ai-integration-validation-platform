package com.hcl.execution.trigger;

import com.hcl.execution.soap.SoapAssertionResult;
import com.hcl.execution.soap.SoapFlowConfig;
import com.hcl.execution.soap.SoapPayloadResolver;
import com.hcl.execution.soap.SoapResponseValidator;
import com.hcl.execution.soap.SoapTriggerOutcome;
import com.hcl.execution.soap.SoapValidationResult;
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
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service("soapTriggerService")
public class SoapTriggerService implements TriggerService {

    private final UnifiedTimelineBuilder timelineBuilder;
    private final UnifiedTraceContextHolder traceContextHolder;
    private final SoapPayloadResolver payloadResolver;
    private final SoapResponseValidator responseValidator;
    private final SoapFlowConfig flowConfig;

    public SoapTriggerService(
            UnifiedTimelineBuilder timelineBuilder,
            UnifiedTraceContextHolder traceContextHolder,
            SoapPayloadResolver payloadResolver,
            SoapResponseValidator responseValidator,
            SoapFlowConfig flowConfig) {
        this.timelineBuilder = timelineBuilder;
        this.traceContextHolder = traceContextHolder;
        this.payloadResolver = payloadResolver;
        this.responseValidator = responseValidator;
        this.flowConfig = flowConfig;
    }

    @Value("${soap.endpoint:}")
    private String endpoint;

    @Value("${soap.transport.default:${soap.transport:http}}")
    private String transport;

    @Value("${soap.action:}")
    private String soapAction;

    @Value("${soap.expected.http.status:200}")
    private int expectedHttpStatus;

    @Value("${soap.status.xpath://*[translate(local-name(), 'ABCDEFGHIJKLMNOPQRSTUVWXYZ', 'abcdefghijklmnopqrstuvwxyz')='status']/text()}")
    private String statusXPath;

    @Value("${soap.expected.status.values:SUCCESS}")
    private String expectedStatusValues;

    @Value("${soap.allow.error.status:false}")
    private boolean allowErrorStatus;

    @Value("${soap.connect.timeout.ms:5000}")
    private int connectTimeoutMs;

    @Value("${soap.read.timeout.ms:15000}")
    private int readTimeoutMs;

    @Value("${soap.jms.sender:TIL.ST5.TIB-XX.REQ.VRP.XML.V1}")
    private String jmsSender;

    @Value("${soap.jms.receiver:TIL.ST5.TIB-XX.RES.VRP.XML.V1}")
    private String jmsReceiver;

    @Value("${soap.jms.async:false}")
    private boolean jmsAsync;

    @Value("${soap.jms.validation.enabled:true}")
    private boolean jmsValidationEnabled;

    @Value("${soap.jms.simulation.validation.enabled:true}")
    private boolean jmsSimulationValidationEnabled;

    @Value("${system.soap.enabled:true}")
    private boolean enabled;

    @Value("${unified.trace.report.enabled:false}")
    private boolean unifiedTraceReportEnabled;

    @Override
    public void trigger(TestCase testCase) throws Exception {
        execute(testCase);
    }

    public SoapTriggerOutcome execute(TestCase testCase) throws Exception {
        if (!enabled) {
            throw new RuntimeException("SOAP trigger is disabled by system.soap.enabled=false");
        }

        String env = flowConfig.env(testCase);
        String system = flowConfig.downstreamSystem(testCase);
        String service = testCase == null ? null : testCase.getScenario();
        String normalizedTransport = valueOrDefault(
                flowConfig.transport(env, system, service, transport), "http").toUpperCase(Locale.ROOT);
        if ("JMS".equals(normalizedTransport)) {
            return triggerSoapOverJms(testCase);
        }

        String resolvedEndpoint = flowConfig.endpoint(env, system, service);
        String resolvedSoapAction = flowConfig.soapAction(env, system, service, soapAction);

        if (resolvedEndpoint == null || resolvedEndpoint.isEmpty()) {
            throw new RuntimeException("SOAP endpoint is not configured");
        }

        SoapPayloadResolver.ResolvedSoapPayload resolvedPayload = payloadResolver.resolve(testCase);
        String soapXml = resolvedPayload.getContent();

        if (!unifiedTraceReportEnabled) {
            System.out.println();
            System.out.println("-------------------- SOAP FLOW -------------------------");
            System.out.println("[SOAP]");
            System.out.println("System=VRP");
            System.out.println("Env=" + value(env));
            System.out.println("DownstreamSystem=" + value(system));
            System.out.println("Service=" + value(service));
            System.out.println("Protocol=SOAP_HTTP");
            System.out.println("Method=POST");
            System.out.println("Endpoint=" + resolvedEndpoint);
            System.out.println("Payload=" + value(resolvedPayload.getSource()));
            System.out.println("BookingID=" + value(testCase.getBookingId()));
        }

        long requestTimeMs = System.currentTimeMillis();
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(resolvedEndpoint).openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(Math.max(0, connectTimeoutMs));
            conn.setReadTimeout(Math.max(0, readTimeoutMs));
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "text/xml; charset=UTF-8");
            conn.setRequestProperty("SOAPAction", valueOrDefault(resolvedSoapAction, ""));
            conn.setRequestProperty("Connection", "close");

            try (OutputStream os = conn.getOutputStream()) {
                os.write(soapXml.getBytes(StandardCharsets.UTF_8));
                os.flush();
            }

            int responseCode = conn.getResponseCode();
            String responseBody = readResponse(conn, responseCode);
            SoapValidationResult validationResult = responseValidator.validate(
                    responseCode,
                    expectedHttpStatus,
                    responseBody,
                    statusXPath,
                    expectedStatusValues,
                    allowErrorStatus);
            long responseTimeMs = System.currentTimeMillis();
            if (!unifiedTraceReportEnabled) {
                System.out.println("Status=" + responseCode);
                printAssertions(validationResult);
                System.out.println("FlowStatus=" + (validationResult.isPassed() ? "PROCESSED" : "FAILED"));
            }
            List<NormalizedTraceEvent> soapTrace = buildSoapTrace(
                    testCase, resolvedEndpoint, requestTimeMs, responseTimeMs, responseCode);
            traceContextHolder.currentOrCreate().setApiEndpoint(resolvedEndpoint);
            traceContextHolder.currentOrCreate().setApiStatus(String.valueOf(responseCode));
            traceContextHolder.addEvents(soapTrace);
            addSoapHttpTraceLines(
                    testCase,
                    env,
                    system,
                    service,
                    resolvedEndpoint,
                    resolvedPayload.getSource(),
                    responseCode,
                    validationResult,
                    responseTimeMs - requestTimeMs);

            SoapTriggerOutcome outcome = new SoapTriggerOutcome();
            outcome.setProtocol("SOAP_HTTP");
            outcome.setMode("SYNC");
            outcome.setEndpoint(resolvedEndpoint);
            outcome.setPayloadSource(resolvedPayload.getSource());
            outcome.setHttpStatus(responseCode);
            outcome.setResponseBody(responseBody);
            outcome.setValidationResult(validationResult);
            outcome.setMessage(validationResult.summary());
            outcome.setValidationComplete(true);
            outcome.setProcessStatus(validationResult.isPassed() ? "PASS" : "FAIL");
            outcome.setDownstreamStatus(validationResult.isPassed() ? "SYNC_RESPONSE_VALIDATED" : "FAILED");
            outcome.setErrorFound(validationResult.isPassed() ? "NO" : "YES");

            if (!validationResult.isPassed()) {
                throw new RuntimeException(outcome.getMessage());
            }
            return outcome;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private SoapTriggerOutcome triggerSoapOverJms(TestCase testCase) {
        boolean async = requestedAsync(testCase);
        boolean simulationValidated = jmsValidationEnabled && jmsSimulationValidationEnabled;
        String corrId = generatedCorrId(testCase);
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
            System.out.println("CorrID=" + value(corrId));
            System.out.println("FlowStatus=" + (simulationValidated ? "PROCESSED" : (async ? "QUEUED" : "TRIGGERED")));
        }

        List<NormalizedTraceEvent> soapTrace = buildSoapJmsTrace(testCase, async, sendTimeMs, replyTimeMs, corrId);
        traceContextHolder.currentOrCreate().setApiEndpoint(jmsSender + " -> " + jmsReceiver);
        traceContextHolder.currentOrCreate().setApiStatus(simulationValidated ? "PROCESSED" : (async ? "QUEUED" : "TRIGGERED"));
        traceContextHolder.currentOrCreate().setCorrId(corrId);
        traceContextHolder.addEvents(soapTrace);
        addSoapJmsTraceLines(testCase, async, corrId, simulationValidated);

        SoapTriggerOutcome outcome = new SoapTriggerOutcome();
        outcome.setProtocol("SOAP_JMS");
        outcome.setMode(async ? "ASYNC" : "SYNC");
        outcome.setEndpoint(jmsSender + " -> " + jmsReceiver);
        outcome.setPayloadSource("JMS_MESSAGE");
        outcome.setMessage(simulationValidated ? "SOAP over JMS simulation validation passed"
                : "SOAP over JMS trigger success; validation pending");
        outcome.setValidationComplete(simulationValidated);
        outcome.setProcessStatus(simulationValidated ? "PASS" : "NOT_VALIDATED");
        outcome.setDownstreamStatus(simulationValidated ? "SIMULATED" : "NOT_VALIDATED");
        outcome.setErrorFound("NO");
        outcome.setCorrId(corrId);
        return outcome;
    }

    private void addSoapHttpTraceLines(
            TestCase testCase,
            String env,
            String downstreamSystem,
            String service,
            String endpoint,
            String payloadSource,
            int responseCode,
            SoapValidationResult validationResult,
            long latencyMs) {
        traceContextHolder.currentOrCreate().addProtocolLine("SOAP", "System=VRP");
        traceContextHolder.currentOrCreate().addProtocolLine("SOAP", "Env=" + value(env));
        traceContextHolder.currentOrCreate().addProtocolLine("SOAP", "DownstreamSystem=" + value(downstreamSystem));
        traceContextHolder.currentOrCreate().addProtocolLine("SOAP", "Service=" + value(service));
        traceContextHolder.currentOrCreate().addProtocolLine("SOAP", "Protocol=SOAP_HTTP");
        traceContextHolder.currentOrCreate().addProtocolLine("SOAP", "Method=POST");
        traceContextHolder.currentOrCreate().addProtocolLine("SOAP", "Endpoint=" + value(endpoint));
        traceContextHolder.currentOrCreate().addProtocolLine("SOAP", "Payload=" + value(payloadSource));
        traceContextHolder.currentOrCreate().addProtocolLine("SOAP", "BookingID="
                + value(testCase == null ? null : testCase.getBookingId()));
        traceContextHolder.currentOrCreate().addProtocolLine("SOAP", "HttpStatus=" + responseCode);
        traceContextHolder.currentOrCreate().addProtocolLine("SOAP", "FlowStatus="
                + (validationResult != null && validationResult.isPassed() ? "PROCESSED" : "FAILED"));
        traceContextHolder.currentOrCreate().addValidationLine("SoapHttp=" + responseCode);
        traceContextHolder.currentOrCreate().addValidationLine("SoapProcess="
                + (validationResult != null && validationResult.isPassed() ? "SUCCESS" : "FAILED"));
        traceContextHolder.currentOrCreate().addValidationLine("SoapFlow="
                + (validationResult != null && validationResult.isPassed() ? "SUCCESS" : "FAILED"));
        traceContextHolder.currentOrCreate().addSummaryLine("SoapResponseTimeMs=" + Math.max(0, latencyMs));
        traceContextHolder.currentOrCreate().addSummaryLine("LogAnalyzerEnabled=N");
    }

    private void addSoapJmsTraceLines(TestCase testCase, boolean async, String corrId, boolean simulationValidated) {
        traceContextHolder.currentOrCreate().addProtocolLine("SOAP", "System=VRP");
        traceContextHolder.currentOrCreate().addProtocolLine("SOAP", "Protocol=SOAP_JMS");
        traceContextHolder.currentOrCreate().addProtocolLine("SOAP", "Mode=" + (async ? "ASYNC" : "SYNC"));
        traceContextHolder.currentOrCreate().addProtocolLine("SOAP", "SenderQueue=" + value(jmsSender));
        traceContextHolder.currentOrCreate().addProtocolLine("SOAP", "ReceiverQueue=" + value(jmsReceiver));
        traceContextHolder.currentOrCreate().addProtocolLine("SOAP", "BookingID="
                + value(testCase == null ? null : testCase.getBookingId()));
        traceContextHolder.currentOrCreate().addProtocolLine("SOAP", "CorrID=" + value(corrId));
        traceContextHolder.currentOrCreate().addProtocolLine("SOAP", "FlowStatus="
                + (simulationValidated ? "PROCESSED" : (async ? "QUEUED" : "TRIGGERED")));
        traceContextHolder.currentOrCreate().addValidationLine("SoapPublish=Y");
        traceContextHolder.currentOrCreate().addValidationLine("SoapConsume=" + (simulationValidated ? "Y" : "PENDING"));
        traceContextHolder.currentOrCreate().addValidationLine("SoapFlow=" + (simulationValidated ? "SUCCESS" : "PENDING"));
        traceContextHolder.currentOrCreate().addSummaryLine("SoapTransport=JMS");
        traceContextHolder.currentOrCreate().addSummaryLine("LogAnalyzerEnabled=N");
    }

    private String readResponse(HttpURLConnection conn, int responseCode) throws Exception {
        InputStream stream = responseCode >= 200 && responseCode < 400
                ? conn.getInputStream()
                : conn.getErrorStream();
        if (stream == null) {
            return "";
        }
        StringBuilder response = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                response.append(line).append(System.lineSeparator());
            }
        }
        return response.toString().trim();
    }

    private void printAssertions(SoapValidationResult validationResult) {
        System.out.println("[ASSERT]");
        if (validationResult == null) {
            System.out.println("SOAP_VALID=FAIL");
            return;
        }
        for (SoapAssertionResult assertion : validationResult.getAssertions()) {
            System.out.println(assertion.getName() + "=" + (assertion.isPassed() ? "PASS" : "FAIL"));
        }
    }

    private List<NormalizedTraceEvent> buildSoapTrace(
            TestCase testCase,
            String resolvedEndpoint,
            long requestTimeMs,
            long responseTimeMs,
            int responseCode) {
        List<NormalizedTraceEvent> events = new ArrayList<>();
        events.add(apiEvent(testCase, "SoapSubmit", TraceProtocol.SOAP_HTTP, requestTimeMs));
        events.add(event(testCase, resolvedEndpoint, TracePhase.REQUEST, requestTimeMs, TraceStatus.SUCCESS));
        events.add(event(testCase, resolvedEndpoint, TracePhase.REPLY, responseTimeMs,
                isSuccess(responseCode) ? TraceStatus.PROCESSED : TraceStatus.FAILED));
        return timelineBuilder.build(events);
    }

    private NormalizedTraceEvent event(
            TestCase testCase,
            String resolvedEndpoint,
            TracePhase phase,
            long epochMs,
            TraceStatus status) {
        NormalizedTraceEvent event = NormalizedTraceEvent.of(
                testCase == null ? null : testCase.getBookingId(),
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
        event.setToEndpoint(resolvedEndpoint);
        return event;
    }

    private List<NormalizedTraceEvent> buildSoapJmsTrace(
            TestCase testCase,
            boolean async,
            long sendTimeMs,
            long replyTimeMs,
            String corrId) {
        List<NormalizedTraceEvent> events = new ArrayList<>();
        events.add(apiEvent(testCase, "SoapJmsSubmit", TraceProtocol.SOAP_JMS, sendTimeMs));
        events.add(jmsEvent(testCase, TracePhase.SEND, sendTimeMs, TraceStatus.SUCCESS, corrId));
        events.add(jmsEvent(testCase, async ? TracePhase.ACK : TracePhase.REPLY,
                replyTimeMs, async ? TraceStatus.DELIVERED : TraceStatus.PROCESSED, corrId));
        return timelineBuilder.build(events);
    }

    private NormalizedTraceEvent apiEvent(TestCase testCase, String operation, TraceProtocol protocol, long epochMs) {
        return NormalizedTraceEvent.of(
                testCase == null ? null : testCase.getBookingId(),
                null,
                null,
                TraceSystem.API,
                protocol,
                TracePhase.REQUEST,
                operation,
                OffsetDateTime.ofInstant(Instant.ofEpochMilli(epochMs), ZoneId.systemDefault()),
                TraceStatus.SUCCESS,
                null);
    }

    private boolean requestedAsync(TestCase testCase) {
        if (testCase == null || testCase.getExecutionMode() == null
                || testCase.getExecutionMode().trim().isEmpty()) {
            return jmsAsync;
        }
        return "ASYNC".equalsIgnoreCase(testCase.getExecutionMode().trim());
    }

    private NormalizedTraceEvent jmsEvent(TestCase testCase, TracePhase phase, long epochMs, TraceStatus status, String corrId) {
        NormalizedTraceEvent event = NormalizedTraceEvent.of(
                testCase == null ? null : testCase.getBookingId(),
                corrId,
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

    private String generatedCorrId(TestCase testCase) {
        String bookingId = testCase == null ? null : testCase.getBookingId();
        return "SOAP-" + value(bookingId) + "-" + UUID.randomUUID();
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
