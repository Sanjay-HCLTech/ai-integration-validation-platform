package com.hcl.execution.protocol;

import com.hcl.execution.model.TestCase;
import com.hcl.execution.soap.SoapTriggerOutcome;
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
            SoapTriggerOutcome outcome = soapTriggerService.execute(testCase);
            ProtocolExecutionResult result = result("SOAP", valueOrDefault(mode, "SYNC"),
                    outcome.isSuccess() ? "SUCCESS" : "ERROR",
                    elapsedMs(startNanos),
                    outcome.getMessage());
            result.setHttpStatus(outcome.getHttpStatus());
            result.setResponseBody(outcome.getResponseBody());
            result.setCorrId(outcome.getCorrId());
            result.setJobId(outcome.getJobId());
            result.setValidationComplete(outcome.isValidationComplete());
            result.setProcessStatus(outcome.getProcessStatus());
            result.setDownstreamStatus(outcome.getDownstreamStatus());
            result.setErrorFound(outcome.getErrorFound());
            result.setPayloadSource(outcome.getPayloadSource());
            result.setEndpointOrDestination(outcome.getEndpoint());
            log(result);
            return result;
        } catch (Exception e) {
            ProtocolExecutionResult result = result("SOAP", valueOrDefault(mode, "SYNC"),
                    "ERROR", elapsedMs(startNanos), e.getMessage());
            result.setValidationComplete(true);
            result.setProcessStatus("FAIL");
            result.setDownstreamStatus("FAILED");
            result.setErrorFound("YES");
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

    private String valueOrDefault(String value, String fallback) {
        return value == null || value.trim().isEmpty() ? fallback : value.trim();
    }

    private void log(ProtocolExecutionResult result) {
        System.out.println("[PROTOCOL_EXECUTION]");
        System.out.println("Protocol=" + result.getProtocol()
                + " Mode=" + result.getMode()
                + " Status=" + result.getStatus()
                + " LatencyMs=" + result.getLatencyMs());
    }
}
