package com.hcl.execution.protocol;

import com.hcl.execution.kafka.KafkaTriggerOutcome;
import com.hcl.execution.model.TestCase;
import com.hcl.execution.trigger.KafkaTriggerService;
import org.springframework.stereotype.Service;

@Service
public class KafkaExecutionService {

    private final KafkaTriggerService kafkaTriggerService;

    public KafkaExecutionService(KafkaTriggerService kafkaTriggerService) {
        this.kafkaTriggerService = kafkaTriggerService;
    }

    public ProtocolExecutionResult execute(TestCase testCase) {
        long startNanos = System.nanoTime();
        try {
            KafkaTriggerOutcome outcome = kafkaTriggerService.execute(testCase);
            ProtocolExecutionResult result = result("KAFKA", "ASYNC",
                    outcome.getStatus(), elapsedMs(startNanos), outcome.getMessage());
            result.setCorrId(outcome.getCorrId());
            result.setTrackingId(outcome.getTrackingId());
            result.setJobId(outcome.getJobId());
            result.setPayloadSource(outcome.getPayloadSource());
            result.setEndpointOrDestination(outcome.getTopic() + "[" + outcome.getPartition() + "]@"
                    + outcome.getOffset() + " -> " + outcome.getConsumerGroup());
            result.setProcessStatus("PASS");
            result.setDownstreamStatus("SUCCESS");
            result.setErrorFound("NO");
            result.setValidationComplete(true);
            log(result);
            return result;
        } catch (RuntimeException e) {
            ProtocolExecutionResult result = result("KAFKA", "ASYNC",
                    "ERROR", elapsedMs(startNanos), e.getMessage());
            result.setProcessStatus("FAIL");
            result.setDownstreamStatus("FAILED");
            result.setErrorFound("YES");
            result.setValidationComplete(true);
            log(result);
            return result;
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
        System.out.println("[PROTOCOL_EXECUTION]");
        System.out.println("Protocol=" + result.getProtocol()
                + " Mode=" + result.getMode()
                + " Status=" + result.getStatus()
                + " LatencyMs=" + result.getLatencyMs());
    }
}
