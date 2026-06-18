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
            @Value("${jms.simulation.retry.count:3}") int defaultRetryCount) {
        this.queueService = queueService;
        this.processingService = processingService;
        this.emsQueueProperties = emsQueueProperties;
        this.defaultRetryCount = Math.max(0, defaultRetryCount);
    }

    public JmsProcessingResult send(String bookingId, String payload, String sourceSystem, boolean async) {
        JmsMessage message = buildMessage(bookingId, payload, sourceSystem, async);

        if (!message.isAsync()) {
            return processingService.processDirectly(message);
        }

        queueService.send(message);
        return JmsProcessingResult.fromMessage(message, "ASYNC", "QUEUED",
                "Message accepted by " + queueService.providerName() + " queue for async processing");
    }

    private JmsMessage buildMessage(String bookingId, String payload, String sourceSystem, boolean async) {
        JmsMessage message = new JmsMessage();
        message.setBookingId(bookingId);
        message.setCorrId(UUID.randomUUID().toString());
        String normalizedSource = normalizeSourceSystem(sourceSystem);
        message.setSourceSystem(normalizedSource);
        message.setSenderQueue(senderQueueName(normalizedSource));
        message.setReceiverQueue(receiverQueueName());
        message.setMessageType("BookingUpdate");
        message.setPayload(payload);
        message.setAsync(async);
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
        return "BookingDetails.Queue";
    }
}
