package com.hcl.execution.adapter;

import com.hcl.execution.core.ExecutionMode;
import com.hcl.execution.core.FlowType;
import com.hcl.execution.protocol.ProtocolExecutionResult;

import java.util.Locale;

public final class ProtocolResultMapper {

    private ProtocolResultMapper() {
    }

    public static TriggerResult from(FlowType flowType, ExecutionMode mode, ProtocolExecutionResult protocolResult) {
        TriggerResult result = new TriggerResult();
        result.setFlowType(flowType);
        result.setExecutionMode(mode);
        if (protocolResult == null) {
            result.setSuccess(false);
            result.setStatus("ERROR");
            result.setMessage("Protocol execution returned no result");
            return result;
        }
        result.setStatus(protocolResult.getStatus());
        result.setSuccess(isSuccess(protocolResult.getStatus()));
        result.setCorrId(protocolResult.getCorrId());
        result.setJobId(protocolResult.getJobId());
        result.setHttpStatus(protocolResult.getHttpStatus());
        result.setResponseBody(protocolResult.getResponseBody());
        result.setTimeMs(protocolResult.getLatencyMs());
        result.setMessage(protocolResult.getMessage());
        result.putMetadata("VALIDATION_COMPLETE", String.valueOf(protocolResult.isValidationComplete()));
        result.putMetadata("PROCESS", protocolResult.getProcessStatus());
        result.putMetadata("DOWNSTREAM", protocolResult.getDownstreamStatus());
        result.putMetadata("ERROR", protocolResult.getErrorFound());
        result.putMetadata("PAYLOAD_SOURCE", protocolResult.getPayloadSource());
        result.putMetadata("ENDPOINT_OR_DESTINATION", protocolResult.getEndpointOrDestination());
        result.putMetadata("TRACKING_ID", protocolResult.getTrackingId());
        for (java.util.Map.Entry<String, String> entry : protocolResult.getMetadata().entrySet()) {
            result.putMetadata(entry.getKey(), entry.getValue());
        }
        return result;
    }

    private static boolean isSuccess(String status) {
        if (status == null || status.trim().isEmpty()) {
            return false;
        }
        String normalized = status.trim().toUpperCase(Locale.ROOT);
        return "SUCCESS".equals(normalized)
                || "PASS".equals(normalized)
                || "QUEUED".equals(normalized)
                || "CONSUMED".equals(normalized)
                || "PUBLISHED".equals(normalized);
    }
}
