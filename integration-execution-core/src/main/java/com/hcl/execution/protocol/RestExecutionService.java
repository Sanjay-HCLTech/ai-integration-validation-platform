package com.hcl.execution.protocol;

import com.hcl.execution.model.TestCase;
import com.hcl.execution.trigger.RestTriggerService;
import org.springframework.stereotype.Service;

@Service
public class RestExecutionService {

    private final RestTriggerService restTriggerService;

    public RestExecutionService(RestTriggerService restTriggerService) {
        this.restTriggerService = restTriggerService;
    }

    public ProtocolExecutionResult execute(TestCase testCase) throws Exception {
        return executeWithTrigger(testCase, "REST", "SYNC", () -> restTriggerService.trigger(testCase));
    }

    private ProtocolExecutionResult executeWithTrigger(
            TestCase testCase,
            String protocol,
            String mode,
            TriggerCall triggerCall) throws Exception {
        long startNanos = System.nanoTime();
        try {
            triggerCall.run();
            ProtocolExecutionResult result = result(protocol, mode, "SUCCESS", elapsedMs(startNanos), "Triggered successfully");
            log(result);
            return result;
        } catch (Exception e) {
            ProtocolExecutionResult result = result(protocol, mode, "ERROR", elapsedMs(startNanos), e.getMessage());
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

    private interface TriggerCall {
        void run() throws Exception;
    }
}
