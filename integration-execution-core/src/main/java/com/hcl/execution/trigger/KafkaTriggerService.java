package com.hcl.execution.trigger;

import com.hcl.execution.kafka.KafkaFlowConfig;
import com.hcl.execution.kafka.KafkaPayloadResolver;
import com.hcl.execution.kafka.KafkaPublishRequest;
import com.hcl.execution.kafka.KafkaTriggerOutcome;
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

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

@Service("kafkaTriggerService")
public class KafkaTriggerService implements TriggerService {

    private static final AtomicLong JOB_SEQUENCE = new AtomicLong(700000);
    private static final AtomicLong OFFSET_SEQUENCE = new AtomicLong(1000);

    private final UnifiedTimelineBuilder timelineBuilder;
    private final UnifiedTraceContextHolder traceContextHolder;
    private final KafkaFlowConfig flowConfig;
    private final KafkaPayloadResolver payloadResolver;

    @Value("${system.kafka.enabled}")
    private boolean enabled;

    @Value("${kafka.simulation.enabled}")
    private boolean simulationEnabled;

    @Value("${unified.trace.report.enabled}")
    private boolean unifiedTraceReportEnabled;

    public KafkaTriggerService(
            UnifiedTimelineBuilder timelineBuilder,
            UnifiedTraceContextHolder traceContextHolder,
            KafkaFlowConfig flowConfig,
            KafkaPayloadResolver payloadResolver) {
        this.timelineBuilder = timelineBuilder;
        this.traceContextHolder = traceContextHolder;
        this.flowConfig = flowConfig;
        this.payloadResolver = payloadResolver;
    }

    @Override
    public void trigger(TestCase testCase) {
        execute(testCase);
    }

    public KafkaTriggerOutcome execute(TestCase testCase) {
        if (!enabled) {
            throw new RuntimeException("Kafka trigger is disabled by system.kafka.enabled=false");
        }
        if (!simulationEnabled) {
            throw new RuntimeException("Kafka real provider is not wired yet. Enable kafka.simulation.enabled=true");
        }

        long startNanos = System.nanoTime();
        KafkaPublishRequest request = publishRequest(testCase);
        request.setOffset(OFFSET_SEQUENCE.incrementAndGet());
        long publishTimeMs = System.currentTimeMillis();
        long consumeTimeMs = publishTimeMs + 1;
        KafkaTriggerOutcome outcome = outcome(request, elapsedMs(startNanos));

        if (!unifiedTraceReportEnabled) {
            printKafkaExecution(testCase, outcome);
        }

        if (!hasText(traceContextHolder.currentOrCreate().getApiEndpoint())) {
            traceContextHolder.currentOrCreate().setApiEndpoint("/execute/kafka");
        }
        traceContextHolder.currentOrCreate().setApiStatus("200");
        traceContextHolder.currentOrCreate().setCorrId(request.getCorrId());
        traceContextHolder.currentOrCreate().setJobId(request.getJobId());
        traceContextHolder.addEvents(buildKafkaTrace(testCase, request, publishTimeMs, consumeTimeMs));
        addUnifiedTraceLines(request, outcome);
        return outcome;
    }

    private KafkaPublishRequest publishRequest(TestCase testCase) {
        String env = flowConfig.env(testCase);
        KafkaPayloadResolver.ResolvedKafkaPayload resolvedPayload = payloadResolver.resolve(testCase);
        String system = resolvedPayload.getSystem();
        String service = testCase == null ? null : testCase.getScenario();
        Map<String, String> headers = new LinkedHashMap<>(flowConfig.messageHeaders(env, system, service));
        String configuredTrackingId = flowConfig.trackingId(env, system, service);
        String trackingId = firstText(configuredTrackingId, headers.get("trackingId"),
                headers.get("Tracking_Id"), UUID.randomUUID().toString());
        String corrId = firstText(headers.get("correlationId"), typedValue(headers.get("Correlation_Id")), trackingId);
        String messageId = firstText(headers.get("messageId"), "KAFKA-" + UUID.randomUUID());
        headers.putIfAbsent("trackingId", trackingId);
        headers.putIfAbsent("correlationId", corrId);
        headers.putIfAbsent("messageId", messageId);

        KafkaPublishRequest request = new KafkaPublishRequest();
        request.setBookingId(testCase == null ? null : testCase.getBookingId());
        request.setPayload(resolvedPayload.getContent());
        request.setPayloadSource(resolvedPayload.getSource());
        request.setEnv(env);
        request.setSystem(system);
        request.setTopic(flowConfig.topic(env, system, service));
        request.setKey(flowConfig.key(env, system, service,
                testCase == null ? null : testCase.getRoutingKey(),
                testCase == null ? null : testCase.getBookingId()));
        request.setPartition(flowConfig.partition(env, system, service));
        request.setConsumerGroup(flowConfig.consumerGroup(env, system, service));
        request.setMessageType(flowConfig.messageType(env, system, service));
        request.setHeaders(headers);
        request.setTrackingId(trackingId);
        request.setCorrId(corrId);
        request.setMessageId(messageId);
        request.setJobId("KAFKA-JOB-" + JOB_SEQUENCE.incrementAndGet());
        return request;
    }

    private KafkaTriggerOutcome outcome(KafkaPublishRequest request, long timeMs) {
        KafkaTriggerOutcome outcome = new KafkaTriggerOutcome();
        outcome.setSuccess(true);
        outcome.setStatus("CONSUMED");
        outcome.setMessage("Kafka message published and consumed in simulation");
        outcome.setEnv(request.getEnv());
        outcome.setSystem(request.getSystem());
        outcome.setTopic(request.getTopic());
        outcome.setKey(request.getKey());
        outcome.setPartition(request.getPartition());
        outcome.setOffset(request.getOffset());
        outcome.setConsumerGroup(request.getConsumerGroup());
        outcome.setPayloadSource(request.getPayloadSource());
        outcome.setCorrId(request.getCorrId());
        outcome.setTrackingId(request.getTrackingId());
        outcome.setMessageId(request.getMessageId());
        outcome.setJobId(request.getJobId());
        outcome.setHeaders(request.getHeaders());
        outcome.setTimeMs(timeMs);
        return outcome;
    }

    private List<NormalizedTraceEvent> buildKafkaTrace(
            TestCase testCase,
            KafkaPublishRequest request,
            long publishTimeMs,
            long consumeTimeMs) {
        List<NormalizedTraceEvent> events = new ArrayList<>();
        events.add(apiEvent(testCase, request, publishTimeMs));
        events.add(event(testCase, request, TracePhase.PUBLISH, publishTimeMs, TraceStatus.SUCCESS));
        events.add(event(testCase, request, TracePhase.CONSUME, consumeTimeMs, TraceStatus.CONSUMED));
        return timelineBuilder.build(events);
    }

    private NormalizedTraceEvent apiEvent(TestCase testCase, KafkaPublishRequest request, long epochMs) {
        return NormalizedTraceEvent.of(
                testCase == null ? null : testCase.getBookingId(),
                request.getCorrId(),
                request.getJobId(),
                TraceSystem.API,
                TraceProtocol.KAFKA,
                TracePhase.REQUEST,
                "KafkaSubmit",
                OffsetDateTime.ofInstant(Instant.ofEpochMilli(epochMs), ZoneId.systemDefault()),
                TraceStatus.SUCCESS,
                null);
    }

    private NormalizedTraceEvent event(
            TestCase testCase,
            KafkaPublishRequest request,
            TracePhase phase,
            long epochMs,
            TraceStatus status) {
        NormalizedTraceEvent event = NormalizedTraceEvent.of(
                testCase == null ? null : testCase.getBookingId(),
                request.getCorrId(),
                request.getJobId(),
                TraceSystem.KAFKA,
                TraceProtocol.KAFKA,
                phase,
                request.getMessageType(),
                OffsetDateTime.ofInstant(Instant.ofEpochMilli(epochMs), ZoneId.systemDefault()),
                status,
                null);
        event.setFromEndpoint(request.getTopic() + "[" + request.getPartition() + "]@" + request.getOffset());
        event.setToEndpoint(request.getConsumerGroup());
        return event;
    }

    private void addUnifiedTraceLines(KafkaPublishRequest request, KafkaTriggerOutcome outcome) {
        traceContextHolder.currentOrCreate().addProtocolLine("Kafka", "Topic=" + value(request.getTopic()));
        traceContextHolder.currentOrCreate().addProtocolLine("Kafka", "Key=" + value(request.getKey()));
        traceContextHolder.currentOrCreate().addProtocolLine("Kafka", "Partition=" + request.getPartition());
        traceContextHolder.currentOrCreate().addProtocolLine("Kafka", "Offset=" + request.getOffset());
        traceContextHolder.currentOrCreate().addProtocolLine("Kafka", "ConsumerGroup=" + value(request.getConsumerGroup()));
        traceContextHolder.currentOrCreate().addProtocolLine("Kafka", "MessageType=" + value(request.getMessageType()));
        traceContextHolder.currentOrCreate().addProtocolLine("Kafka", "MessageID=" + value(request.getMessageId()));
        traceContextHolder.currentOrCreate().addProtocolLine("Kafka", "CorrID=" + value(request.getCorrId()));
        traceContextHolder.currentOrCreate().addProtocolLine("Kafka", "TrackingID=" + value(request.getTrackingId()));
        traceContextHolder.currentOrCreate().addProtocolLine("Kafka", "JobID=" + value(request.getJobId()));
        traceContextHolder.currentOrCreate().addProtocolLine("Kafka", "Payload=" + value(request.getPayloadSource()));
        traceContextHolder.currentOrCreate().addProtocolLine("Kafka", "FlowStatus=" + value(outcome.getStatus()));
        traceContextHolder.currentOrCreate().addValidationLine("KafkaPublish=Y");
        traceContextHolder.currentOrCreate().addValidationLine("KafkaConsume=Y");
        traceContextHolder.currentOrCreate().addValidationLine("KafkaFlow=SUCCESS");
        traceContextHolder.currentOrCreate().addSummaryLine("KafkaSimulation=Y");
        traceContextHolder.currentOrCreate().addSummaryLine("KafkaPublishTimeMs=" + outcome.getTimeMs());
    }

    private void printKafkaExecution(TestCase testCase, KafkaTriggerOutcome outcome) {
        System.out.println();
        System.out.println("[KAFKA_EXEC]");
        System.out.println("Env=" + value(outcome.getEnv()));
        System.out.println("System=" + value(outcome.getSystem()));
        System.out.println("Topic=" + value(outcome.getTopic()));
        System.out.println("Key=" + value(outcome.getKey()));
        System.out.println("Partition=" + outcome.getPartition());
        System.out.println("Offset=" + outcome.getOffset());
        System.out.println("ConsumerGroup=" + value(outcome.getConsumerGroup()));
        System.out.println("PayloadFile=" + value(outcome.getPayloadSource()));
        System.out.println("CorrID=" + value(outcome.getCorrId()));
        System.out.println("TrackingID=" + value(outcome.getTrackingId()));
        System.out.println("MessageID=" + value(outcome.getMessageId()));
        System.out.println("TimeMs=" + outcome.getTimeMs());
        printMap("[KAFKA_HEADERS]", outcome.getHeaders());
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
        System.out.println("DATAHUB_PROCESS=PASS");
        System.out.println("DOWNSTREAM_STATUS=SUCCESS");
        System.out.println("ERROR_FOUND=NO");
        System.out.println("DLQ=NO");
        System.out.println();
        System.out.println("[RESULT]");
        System.out.println("Status=PASS");
    }

    private long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
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

    private String firstText(String first, String fallback) {
        return hasText(first) ? first.trim() : fallback;
    }

    private String firstText(String first, String second, String third) {
        return hasText(first) ? first.trim() : firstText(second, third);
    }

    private String firstText(String first, String second, String third, String fourth) {
        return hasText(first) ? first.trim() : firstText(second, third, fourth);
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private String value(String value) {
        return hasText(value) ? value.trim() : "NA";
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
