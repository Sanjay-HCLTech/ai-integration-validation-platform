package com.hcl.execution.jms;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Locale;
import java.util.UUID;

@Service
public class JmsProducerService {

    private final JmsQueueService queueService;
    private final JmsProcessingService processingService;
    private final EmsQueueProperties emsQueueProperties;
    private final int defaultRetryCount;

    public JmsProducerService(
            JmsQueueService queueService,
            JmsProcessingService processingService,
            EmsQueueProperties emsQueueProperties,
            @Value("${jms.simulation.retry.count}") int defaultRetryCount) {
        this.queueService = queueService;
        this.processingService = processingService;
        this.emsQueueProperties = emsQueueProperties;
        this.defaultRetryCount = Math.max(0, defaultRetryCount);
    }

    public JmsProcessingResult send(String bookingId, String payload, String sourceSystem, boolean async) {
        JmsPublishRequest request = new JmsPublishRequest();
        request.setBookingId(bookingId);
        request.setPayload(payload);
        request.setSourceSystem(sourceSystem);
        request.setAsync(async);
        return send(request);
    }

    public JmsProcessingResult send(JmsPublishRequest request) {
        JmsMessage message = buildMessage(request);

        if (!message.isAsync()) {
            return processingService.processDirectly(message);
        }

        queueService.send(message);
        return JmsProcessingResult.fromMessage(message, "ASYNC", "QUEUED",
                "Message accepted by " + queueService.providerName() + " queue for async processing");
    }

    private JmsMessage buildMessage(JmsPublishRequest request) {
        JmsMessage message = new JmsMessage();
        message.setBookingId(request == null ? null : request.getBookingId());
        message.setCorrId(UUID.randomUUID().toString());
        message.setJmsMessageId("ID:" + UUID.randomUUID());
        String normalizedSource = normalizeSourceSystem(request == null ? null : request.getSourceSystem());
        message.setSourceSystem(normalizedSource);
        message.setEnv(request == null ? "" : value(request.getEnv()));
        message.setDestinationType(valueOrDefault(request == null ? null : request.getDestinationType(), "QUEUE").toUpperCase(Locale.ROOT));
        message.setDestinationName(valueOrDefault(request == null ? null : request.getDestinationName(), senderQueueName(normalizedSource)));
        message.setSenderQueue(message.getDestinationName());
        message.setReceiverQueue(receiverQueueName());
        message.setMessageType(valueOrDefault(request == null ? null : request.getMessageType(), "DATAHUB_EVENT"));
        message.setPayload(request == null ? null : request.getPayload());
        message.setPayloadSource(valueOrDefault(request == null ? null : request.getPayloadSource(), "INLINE"));
        message.setAsync(request != null && request.isAsync());
        message.setRetryCount(defaultRetryCount);
        message.setTimestamp(System.currentTimeMillis());
        return message;
    }

    private String normalizeSourceSystem(String sourceSystem) {
        if (sourceSystem == null || sourceSystem.trim().isEmpty()) {
            return "DATAHUB";
        }

        String normalized = sourceSystem.trim().replace("\\", "/");
        int slash = normalized.lastIndexOf('/');
        if (slash >= 0 && slash < normalized.length() - 1) {
            normalized = normalized.substring(slash + 1);
        }
        return normalized.toUpperCase(Locale.ROOT);
    }

    private String senderQueueName(String sourceSystem) {
        return "Payload.DataHub." + sourceSystem;
    }

    private String receiverQueueName() {
        String configuredQueue = emsQueueProperties.getReceiverQueue();
        if (configuredQueue == null || configuredQueue.trim().isEmpty()) {
            return "BookingDetails.Queue";
        }
        return configuredQueue.trim();
    }

    private String valueOrDefault(String value, String fallback) {
        return value == null || value.trim().isEmpty() ? fallback : value.trim();
    }

    private String value(String value) {
        return value == null ? "" : value.trim();
    }
}
