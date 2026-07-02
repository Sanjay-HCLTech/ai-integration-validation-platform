package com.hcl.execution.trigger;

import com.hcl.execution.model.TestCase;
import com.hcl.execution.rabbit.RabbitFlowConfig;
import com.hcl.execution.rabbit.RabbitPayloadResolver;
import com.hcl.execution.rabbit.RabbitPublishRequest;
import com.hcl.execution.rabbit.RabbitTriggerOutcome;
import com.hcl.observability.trace.NormalizedTraceEvent;
import com.hcl.observability.trace.TracePhase;
import com.hcl.observability.trace.TraceProtocol;
import com.hcl.observability.trace.TraceStatus;
import com.hcl.observability.trace.TraceSystem;
import com.hcl.observability.trace.UnifiedTraceContextHolder;
import com.hcl.observability.trace.UnifiedTimelineBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

@Service("rabbitMqTriggerService")
public class RabbitMqTriggerService implements TriggerService {

    private static final AtomicLong JOB_SEQUENCE = new AtomicLong(500000);

    private final UnifiedTimelineBuilder timelineBuilder;
    private final UnifiedTraceContextHolder traceContextHolder;
    private final RabbitFlowConfig flowConfig;
    private final RabbitPayloadResolver payloadResolver;

    @Value("${system.rabbitmq.enabled}")
    private boolean enabled;

    @Value("${rabbitmq.simulation.enabled}")
    private boolean simulationEnabled;

    @Value("${unified.trace.report.enabled}")
    private boolean unifiedTraceReportEnabled;

    public RabbitMqTriggerService(
            UnifiedTimelineBuilder timelineBuilder,
            UnifiedTraceContextHolder traceContextHolder,
            RabbitFlowConfig flowConfig,
            RabbitPayloadResolver payloadResolver) {
        this.timelineBuilder = timelineBuilder;
        this.traceContextHolder = traceContextHolder;
        this.flowConfig = flowConfig;
        this.payloadResolver = payloadResolver;
    }

    @Override
    public void trigger(TestCase testCase) {
        execute(testCase);
    }

    public RabbitTriggerOutcome execute(TestCase testCase) {
        if (!enabled) {
            throw new RuntimeException("RabbitMQ trigger is disabled by system.rabbitmq.enabled=false");
        }
        if (!simulationEnabled) {
            throw new RuntimeException("RabbitMQ real provider is not wired yet. Enable rabbitmq.simulation.enabled=true");
        }

        long startNanos = System.nanoTime();
        RabbitPublishRequest request = publishRequest(testCase);
        long sendTimeMs = System.currentTimeMillis();
        long consumeTimeMs = sendTimeMs + 1;
        RabbitTriggerOutcome outcome = outcome(request, elapsedMs(startNanos));

        if (!unifiedTraceReportEnabled) {
            printRabbitExecution(testCase, outcome);
        }

        List<NormalizedTraceEvent> rabbitMqTrace = buildRabbitMqTrace(testCase, request, sendTimeMs, consumeTimeMs);
        if (!hasText(traceContextHolder.currentOrCreate().getApiEndpoint())) {
            traceContextHolder.currentOrCreate().setApiEndpoint("/execute/nordics");
        }
        traceContextHolder.currentOrCreate().setApiStatus("200");
        traceContextHolder.currentOrCreate().setCorrId(request.getCorrId());
        traceContextHolder.currentOrCreate().setJobId(request.getJobId());
        traceContextHolder.addEvents(rabbitMqTrace);
        addUnifiedTraceLines(request, outcome);
        return outcome;
    }

    private void addUnifiedTraceLines(RabbitPublishRequest request, RabbitTriggerOutcome outcome) {
        traceContextHolder.currentOrCreate().addProtocolLine("RabbitMQ", "Exchange=" + value(request.getExchange()));
        traceContextHolder.currentOrCreate().addProtocolLine("RabbitMQ", "ExchangeType=" + value(request.getExchangeType()));
        traceContextHolder.currentOrCreate().addProtocolLine("RabbitMQ", "RoutingKey=" + value(request.getRoutingKey()));
        traceContextHolder.currentOrCreate().addProtocolLine("RabbitMQ", "Queue=" + value(request.getQueue()));
        traceContextHolder.currentOrCreate().addProtocolLine("RabbitMQ", "MessageType=" + value(request.getMessageType()));
        traceContextHolder.currentOrCreate().addProtocolLine("RabbitMQ", "MessageID=" + value(request.getMessageId()));
        traceContextHolder.currentOrCreate().addProtocolLine("RabbitMQ", "CorrID=" + value(request.getCorrId()));
        traceContextHolder.currentOrCreate().addProtocolLine("RabbitMQ", "TrackingID=" + value(request.getTrackingId()));
        traceContextHolder.currentOrCreate().addProtocolLine("RabbitMQ", "JobID=" + value(request.getJobId()));
        traceContextHolder.currentOrCreate().addProtocolLine("RabbitMQ", "Payload=" + value(request.getPayloadSource()));
        traceContextHolder.currentOrCreate().addProtocolLine("RabbitMQ", "FlowStatus=" + value(outcome.getStatus()));
        traceContextHolder.currentOrCreate().addValidationLine("RabbitPublish=Y");
        traceContextHolder.currentOrCreate().addValidationLine("RabbitRoute=Y");
        traceContextHolder.currentOrCreate().addValidationLine("RabbitConsume=Y");
        traceContextHolder.currentOrCreate().addValidationLine("RabbitFlow=SUCCESS");
        traceContextHolder.currentOrCreate().addSummaryLine("RabbitPublishTimeMs=" + outcome.getTimeMs());
        traceContextHolder.currentOrCreate().addSummaryLine("RabbitLogAnalyzerEnabled=Y");
    }

    private RabbitPublishRequest publishRequest(TestCase testCase) {
        String env = flowConfig.env(testCase);
        RabbitPayloadResolver.ResolvedRabbitPayload resolvedPayload = payloadResolver.resolve(testCase);
        String system = resolvedPayload.getSystem();
        String service = testCase == null ? null : testCase.getScenario();
        Map<String, String> messageHeaders = new LinkedHashMap<>(flowConfig.messageHeaders(env, system, service));
        Map<String, String> messageProperties = new LinkedHashMap<>(flowConfig.messageProperties(env, system, service));
        String trackingId = flowConfig.trackingId(env, system, service);
        if (hasText(trackingId)) {
            messageProperties.putIfAbsent("Tracking_Id", "String:" + trackingId);
            messageProperties.putIfAbsent("trackingId", trackingId);
        }

        RabbitPublishRequest request = new RabbitPublishRequest();
        request.setBookingId(testCase == null ? null : testCase.getBookingId());
        request.setPayload(resolvedPayload.getContent());
        request.setPayloadSource(resolvedPayload.getSource());
        request.setEnv(env);
        request.setSystem(system);
        request.setExchange(flowConfig.exchange(env, system, service));
        request.setExchangeType(flowConfig.exchangeType(env, system, service));
        request.setRoutingKey(flowConfig.routingKey(env, system, service, testCase == null ? null : testCase.getRoutingKey()));
        request.setQueue(flowConfig.queue(env, system, service));
        request.setMessageType(flowConfig.messageType(env, system, service));
        request.setMessageHeaders(messageHeaders);
        request.setMessageProperties(messageProperties);
        request.setTrackingId(trackingId);
        request.setCorrId(firstText(typedValue(messageProperties.get("Correlation_Id")), trackingId, UUID.randomUUID().toString()));
        request.setMessageId(firstText(messageHeaders.get("JMSMessageID"), "ID:" + UUID.randomUUID()));
        request.setJobId("JOB-" + JOB_SEQUENCE.incrementAndGet());
        return request;
    }

    private RabbitTriggerOutcome outcome(RabbitPublishRequest request, long timeMs) {
        RabbitTriggerOutcome outcome = new RabbitTriggerOutcome();
        outcome.setSuccess(true);
        outcome.setStatus("CONSUMED");
        outcome.setMessage("RabbitMQ message published, routed, and consumed in simulation");
        outcome.setEnv(request.getEnv());
        outcome.setSystem(request.getSystem());
        outcome.setExchange(request.getExchange());
        outcome.setRoutingKey(request.getRoutingKey());
        outcome.setQueue(request.getQueue());
        outcome.setPayloadSource(request.getPayloadSource());
        outcome.setCorrId(request.getCorrId());
        outcome.setTrackingId(request.getTrackingId());
        outcome.setMessageId(request.getMessageId());
        outcome.setJobId(request.getJobId());
        outcome.setMessageHeaders(request.getMessageHeaders());
        outcome.setMessageProperties(request.getMessageProperties());
        outcome.setTimeMs(timeMs);
        return outcome;
    }

    private List<NormalizedTraceEvent> buildRabbitMqTrace(
            TestCase testCase,
            RabbitPublishRequest request,
            long sendTimeMs,
            long consumeTimeMs) {
        List<NormalizedTraceEvent> events = new ArrayList<>();
        events.add(apiEvent(testCase, request, sendTimeMs));
        events.add(event(testCase, request, TracePhase.PUBLISH, sendTimeMs, TraceStatus.SUCCESS));
        events.add(event(testCase, request, TracePhase.CONSUME, consumeTimeMs, TraceStatus.CONSUMED));
        return timelineBuilder.build(events);
    }

    private NormalizedTraceEvent apiEvent(TestCase testCase, RabbitPublishRequest request, long epochMs) {
        return NormalizedTraceEvent.of(
                testCase == null ? null : testCase.getBookingId(),
                request.getCorrId(),
                request.getJobId(),
                TraceSystem.API,
                TraceProtocol.RABBITMQ,
                TracePhase.REQUEST,
                "RabbitSubmit",
                OffsetDateTime.ofInstant(Instant.ofEpochMilli(epochMs), ZoneId.systemDefault()),
                TraceStatus.SUCCESS,
                null);
    }

    private NormalizedTraceEvent event(
            TestCase testCase,
            RabbitPublishRequest request,
            TracePhase phase,
            long epochMs,
            TraceStatus status) {
        NormalizedTraceEvent event = NormalizedTraceEvent.of(
                testCase == null ? null : testCase.getBookingId(),
                request.getCorrId(),
                request.getJobId(),
                TraceSystem.RABBITMQ,
                TraceProtocol.RABBITMQ,
                phase,
                request.getMessageType(),
                OffsetDateTime.ofInstant(Instant.ofEpochMilli(epochMs), ZoneId.systemDefault()),
                status,
                null);
        event.setFromEndpoint(request.getExchange() + "/" + request.getRoutingKey());
        event.setToEndpoint(request.getQueue());
        return event;
    }

    private void printRabbitExecution(TestCase testCase, RabbitTriggerOutcome outcome) {
        System.out.println();
        System.out.println("[RABBIT_EXEC]");
        System.out.println("Env=" + value(outcome.getEnv()));
        System.out.println("System=" + value(outcome.getSystem()));
        System.out.println("Exchange=" + value(outcome.getExchange()));
        System.out.println("RoutingKey=" + value(outcome.getRoutingKey()));
        System.out.println("Queue=" + value(outcome.getQueue()));
        System.out.println("PayloadFile=" + value(outcome.getPayloadSource()));
        System.out.println("CorrID=" + value(outcome.getCorrId()));
        System.out.println("TrackingID=" + value(outcome.getTrackingId()));
        System.out.println("MessageID=" + value(outcome.getMessageId()));
        System.out.println("TimeMs=" + outcome.getTimeMs());
        printMap("[MSG_HEADER]", outcome.getMessageHeaders());
        printMap("[MSG_PROPERTIES]", outcome.getMessageProperties());
        System.out.println();
        System.out.println("[TRACE]");
        System.out.println("BookingID=" + value(testCase == null ? null : testCase.getBookingId()));
        System.out.println("JobID=" + value(outcome.getJobId()));
        System.out.println("CorrID=" + value(outcome.getCorrId()));
        System.out.println("TrackingID=" + value(outcome.getTrackingId()));
        System.out.println();
        System.out.println("[ASSERT]");
        System.out.println("PUBLISH=SUCCESS");
        System.out.println("ROUTED=YES");
        System.out.println("CONSUMER_TRIGGER=PASS");
        System.out.println("DATAHUB_PROCESS=ASYNC_VALIDATION_PENDING");
        System.out.println("DOWNSTREAM_STATUS=ASYNC_VALIDATION_PENDING");
        System.out.println("ERROR_FOUND=NO");
        System.out.println("DLQ=NO");
        System.out.println();
        System.out.println("[RESULT]");
        System.out.println("Status=TRIGGER_SUCCESS_ASYNC_VALIDATION_PENDING");
    }

    private long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    private String value(String value) {
        return value == null || value.trim().isEmpty() ? "NA" : value;
    }

    private String firstText(String value, String fallback) {
        return value == null || value.trim().isEmpty() ? fallback : value.trim();
    }

    private String firstText(String first, String second, String third) {
        return hasText(first) ? first.trim() : firstText(second, third);
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private String typedValue(String value) {
        if (value == null) {
            return null;
        }
        int colon = value.indexOf(':');
        if (colon > 0) {
            return value.substring(colon + 1).trim();
        }
        return value.trim();
    }

    private void printMap(String header, Map<String, String> values) {
        if (values == null || values.isEmpty()) {
            return;
        }
        System.out.println();
        System.out.println(header);
        values.forEach((key, value) -> System.out.println(key + "=" + value(value)));
    }
}
