package com.hcl.execution.protocol;

import com.hcl.execution.model.TestCase;
import com.hcl.execution.trigger.RabbitMqTriggerService;
import org.springframework.stereotype.Service;

@Service
public class RabbitExecutionService {

    private final RabbitMqTriggerService rabbitMqTriggerService;

    public RabbitExecutionService(RabbitMqTriggerService rabbitMqTriggerService) {
        this.rabbitMqTriggerService = rabbitMqTriggerService;
    }

    public ProtocolExecutionResult execute(TestCase testCase) {
        long startNanos = System.nanoTime();
        try {
            rabbitMqTriggerService.trigger(testCase);
            ProtocolExecutionResult result = result("RABBITMQ", "ASYNC",
                    "CONSUMED", elapsedMs(startNanos), "Triggered successfully");
            log(result);
            return result;
        } catch (RuntimeException e) {
            ProtocolExecutionResult result = result("RABBITMQ", "ASYNC",
                    "ERROR", elapsedMs(startNanos), e.getMessage());
            log(result);
            throw e;
        }
    }

    private ProtocolExecutionResult result(String protocol, String mode, String status, long latencyMs, String message) {
        ProtocolExecutionResult result = new ProtocolExecutionResult();
        result.setProtocol(protocol);
        result.setMode(mode);
        result.setStatus(status);
        result.setLatencyMs(latencyMs);
        result.setMessage(message);
        return result;
    }

    private long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    private void log(ProtocolExecutionResult result) {
        System.out.println("[EXECUTION]");
        System.out.println("Protocol=" + result.getProtocol()
                + " Mode=" + result.getMode()
                + " Status=" + result.getStatus()
                + " LatencyMs=" + result.getLatencyMs());
    }
}
