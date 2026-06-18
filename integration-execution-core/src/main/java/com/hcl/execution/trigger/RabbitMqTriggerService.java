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

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

@Service("rabbitMqTriggerService")
public class RabbitMqTriggerService implements TriggerService {

    private final UnifiedTimelineBuilder timelineBuilder;
    private final UnifiedTraceContextHolder traceContextHolder;

    @Value("${system.rabbitmq.enabled:true}")
    private boolean enabled;

    @Value("${rabbitmq.simulation.enabled:true}")
    private boolean simulationEnabled;

    @Value("${rabbitmq.exchange:NORDICS.BOOKING.EXCHANGE}")
    private String exchange;

    @Value("${rabbitmq.routing.key:nordics.booking.update}")
    private String routingKey;

    @Value("${rabbitmq.queue.receiver:Nordics.Booking.Queue}")
    private String receiverQueue;

    @Value("${unified.trace.report.enabled:false}")
    private boolean unifiedTraceReportEnabled;

    public RabbitMqTriggerService(
            UnifiedTimelineBuilder timelineBuilder,
            UnifiedTraceContextHolder traceContextHolder) {
        this.timelineBuilder = timelineBuilder;
        this.traceContextHolder = traceContextHolder;
    }

    @Override
    public void trigger(TestCase testCase) {
        if (!enabled) {
            throw new RuntimeException("RabbitMQ trigger is disabled by system.rabbitmq.enabled=false");
        }
        if (!simulationEnabled) {
            throw new RuntimeException("RabbitMQ real provider is not wired yet. Enable rabbitmq.simulation.enabled=true");
        }

        long sendTimeMs = System.currentTimeMillis();
        long consumeTimeMs = sendTimeMs + 1;

        if (!unifiedTraceReportEnabled) {
            System.out.println();
            System.out.println("-------------------- RabbitMQ FLOW --------------------------");
            System.out.println("[RabbitMQ]");
            System.out.println("System=NORDICS");
            System.out.println("Protocol=RabbitMQ");
            System.out.println("Exchange=" + value(exchange));
            System.out.println("RoutingKey=" + value(routingKey));
            System.out.println("ReceiverQueue=" + value(receiverQueue));
            System.out.println("BookingID=" + value(testCase.getBookingId()));
            System.out.println("FlowStatus=CONSUMED");
        }

        List<NormalizedTraceEvent> rabbitMqTrace = buildRabbitMqTrace(testCase, sendTimeMs, consumeTimeMs);
        traceContextHolder.currentOrCreate().setApiEndpoint(exchange + "/" + routingKey);
        traceContextHolder.currentOrCreate().setApiStatus("CONSUMED");
        traceContextHolder.addEvents(rabbitMqTrace);
    }

    private List<NormalizedTraceEvent> buildRabbitMqTrace(TestCase testCase, long sendTimeMs, long consumeTimeMs) {
        List<NormalizedTraceEvent> events = new ArrayList<>();
        events.add(event(testCase, TracePhase.SEND, sendTimeMs, TraceStatus.SUCCESS));
        events.add(event(testCase, TracePhase.CONSUME, consumeTimeMs, TraceStatus.CONSUMED));
        return timelineBuilder.build(events);
    }

    private NormalizedTraceEvent event(TestCase testCase, TracePhase phase, long epochMs, TraceStatus status) {
        NormalizedTraceEvent event = NormalizedTraceEvent.of(
                testCase.getBookingId(),
                null,
                null,
                TraceSystem.RABBITMQ,
                TraceProtocol.RABBITMQ,
                phase,
                "BookingUpdate",
                OffsetDateTime.ofInstant(Instant.ofEpochMilli(epochMs), ZoneId.systemDefault()),
                status,
                null);
        event.setFromEndpoint(exchange + "/" + routingKey);
        event.setToEndpoint(receiverQueue);
        return event;
    }

    private String value(String value) {
        return value == null || value.trim().isEmpty() ? "NA" : value;
    }
}
