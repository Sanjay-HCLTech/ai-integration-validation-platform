package com.hcl.execution.protocol;

import com.hcl.execution.model.TestCase;
import com.hcl.execution.trigger.SoapTriggerService;
import org.springframework.stereotype.Service;

@Service
public class SoapExecutionService {

    private final SoapTriggerService soapTriggerService;

    public SoapExecutionService(SoapTriggerService soapTriggerService) {
        this.soapTriggerService = soapTriggerService;
    }

    public ProtocolExecutionResult execute(TestCase testCase, String mode) throws Exception {
        long startNanos = System.nanoTime();
        try {
            soapTriggerService.trigger(testCase);
            ProtocolExecutionResult result = result("SOAP", valueOrDefault(mode, "SYNC"),
                    "SUCCESS", elapsedMs(startNanos), "Triggered successfully");
            log(result);
            return result;
        } catch (Exception e) {
            ProtocolExecutionResult result = result("SOAP", valueOrDefault(mode, "SYNC"),
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

    private String valueOrDefault(String value, String fallback) {
        return value == null || value.trim().isEmpty() ? fallback : value.trim();
    }

    private void log(ProtocolExecutionResult result) {
        System.out.println("[EXECUTION]");
        System.out.println("Protocol=" + result.getProtocol()
                + " Mode=" + result.getMode()
                + " Status=" + result.getStatus()
                + " LatencyMs=" + result.getLatencyMs());
    }
}
