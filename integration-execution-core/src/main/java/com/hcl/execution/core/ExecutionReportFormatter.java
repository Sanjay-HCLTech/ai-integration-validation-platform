package com.hcl.execution.core;

import com.hcl.execution.adapter.TriggerResult;

public class ExecutionReportFormatter {

    public String format(ExecutionReport report) {
        StringBuilder output = new StringBuilder();
        ExecutionRequest request = report == null ? null : report.getRequest();
        TriggerResult trigger = report == null ? null : report.getTriggerResult();
        TraceContext trace = report == null ? null : report.getTraceContext();

        output.append("[EXECUTION]").append(System.lineSeparator());
        output.append("FlowType=").append(value(request == null ? null : request.getFlowType())).append(System.lineSeparator());
        output.append("Protocol=").append(protocolValue(request, trigger)).append(System.lineSeparator());
        output.append("Mode=").append(value(request == null ? null : request.effectiveMode())).append(System.lineSeparator());
        output.append("Env=").append(value(request == null ? null : request.getEnv())).append(System.lineSeparator());
        output.append("System=").append(value(request == null ? null : request.getSystem())).append(System.lineSeparator());
        output.append("Service=").append(serviceValue(request)).append(System.lineSeparator());
        output.append("Scenario=").append(scenarioValue(request)).append(System.lineSeparator());
        output.append("Trigger=").append(value(request == null ? null : request.getTrigger())).append(System.lineSeparator());
        output.append("PayloadSource=").append(payloadValue(request, trigger)).append(System.lineSeparator());
        output.append("EndpointOrDestination=").append(endpointOrDestination(trigger)).append(System.lineSeparator());
        output.append("TriggerStatus=").append(value(trigger == null ? null : trigger.getStatus())).append(System.lineSeparator());
        output.append("CorrID=").append(value(trigger == null ? null : trigger.getCorrId())).append(System.lineSeparator());
        output.append("TrackingID=").append(metadataValue(trigger, "TRACKING_ID")).append(System.lineSeparator());
        output.append("LatencyMs=").append(trigger == null ? 0 : trigger.getTimeMs()).append(System.lineSeparator());
        output.append(System.lineSeparator());

        output.append("[TRACE]").append(System.lineSeparator());
        output.append("BookingID=").append(value(trace == null ? null : trace.getBookingId())).append(System.lineSeparator());
        output.append("JobID=").append(value(trace == null ? null : trace.getJobId())).append(System.lineSeparator());
        output.append("CorrID=").append(value(trace == null ? null : trace.getCorrId())).append(System.lineSeparator());
        output.append("TrackingID=").append(metadataValue(trigger, "TRACKING_ID")).append(System.lineSeparator());
        output.append(System.lineSeparator());

        output.append("[ASSERT]").append(System.lineSeparator());
        output.append("TRIGGER=").append(trigger != null && trigger.isSuccess() ? "SUCCESS" : "FAIL").append(System.lineSeparator());
        output.append("PUBLISH=").append(publishValue(request, trigger)).append(System.lineSeparator());
        output.append("ROUTED=").append(routedValue(request, trigger)).append(System.lineSeparator());
        output.append("CONSUMER_TRIGGER=").append(consumerTriggerValue(request, trigger)).append(System.lineSeparator());
        output.append("HTTP_CODE=").append(httpCodeValue(trigger)).append(System.lineSeparator());
        output.append("PROCESS=").append(processValue(report, request, trigger)).append(System.lineSeparator());
        output.append("DOWNSTREAM=").append(downstreamValue(report, request, trigger)).append(System.lineSeparator());
        output.append("ERROR=").append(errorValue(trigger)).append(System.lineSeparator());
        output.append("DLQ=").append(dlqValue(request, trigger)).append(System.lineSeparator());
        output.append(System.lineSeparator());

        output.append("[RESULT]").append(System.lineSeparator());
        output.append("Status=").append(status(report)).append(System.lineSeparator());
        String evidence = evidence(report, request, trigger);
        if (evidence != null && !evidence.trim().isEmpty()) {
            output.append("Evidence=").append(evidence).append(System.lineSeparator());
        }
        String reason = reason(report);
        if (reason != null && !reason.trim().isEmpty()) {
            output.append("Reason=").append(reason).append(System.lineSeparator());
        }
        String nextAction = nextAction(report, request, trigger);
        if (nextAction != null && !nextAction.trim().isEmpty()) {
            output.append("NextAction=").append(nextAction).append(System.lineSeparator());
        }
        return output.toString();
    }

    private String status(ExecutionReport report) {
        if (report == null) {
            return "NA";
        }
        if (isAsyncPending(report, report.getRequest(), report.getTriggerResult())) {
            return "TRIGGER_SUCCESS_ASYNC_VALIDATION_PENDING";
        }
        if (report.isValidationComplete()) {
            return value(report.getStatus());
        }
        TriggerResult trigger = report.getTriggerResult();
        return trigger != null && trigger.isSuccess() ? "TRIGGER_SUCCESS_VALIDATION_PENDING" : "FAIL";
    }

    private String reason(ExecutionReport report) {
        if (report == null) {
            return "";
        }
        TriggerResult trigger = report.getTriggerResult();
        String message = firstText(report.getMessage(), trigger == null ? null : trigger.getMessage());
        if (message == null || message.trim().isEmpty()) {
            return "";
        }
        if (trigger != null && trigger.isSuccess() && report.isValidationComplete()) {
            return "";
        }
        if (trigger != null
                && trigger.isSuccess()
                && !report.isValidationComplete()
                && !"TRACE_PENDING".equalsIgnoreCase(metadataValue(trigger, "PROCESS"))) {
            return "";
        }
        return oneLine(message);
    }

    private String evidence(ExecutionReport report, ExecutionRequest request, TriggerResult trigger) {
        if (report == null || request == null || trigger == null) {
            return "";
        }
        if (request.getFlowType() != FlowType.RABBIT || !trigger.isSuccess() || !report.isValidationComplete()) {
            return "";
        }
        String message = firstText(report.getMessage(), trigger.getMessage());
        return message == null || message.trim().isEmpty() ? "" : oneLine(message);
    }

    private boolean isAsyncPending(ExecutionReport report, ExecutionRequest request, TriggerResult trigger) {
        return report != null
                && request != null
                && trigger != null
                && trigger.isSuccess()
                && !report.isValidationComplete()
                && request.effectiveMode() == ExecutionMode.ASYNC;
    }

    private boolean isSyncPending(ExecutionReport report, ExecutionRequest request, TriggerResult trigger) {
        return report != null
                && request != null
                && trigger != null
                && trigger.isSuccess()
                && !report.isValidationComplete()
                && request.effectiveMode() == ExecutionMode.SYNC;
    }

    private String payloadValue(ExecutionRequest request, TriggerResult trigger) {
        String payloadPath = request == null ? null : request.getPayloadPath();
        if (payloadPath != null && !payloadPath.trim().isEmpty()) {
            return value(payloadPath);
        }
        if (trigger != null) {
            String payloadSource = trigger.getMetadata().get("PAYLOAD_SOURCE");
            if (payloadSource != null && !payloadSource.trim().isEmpty()) {
                return value(payloadSource);
            }
        }
        return "NA";
    }

    private String protocolValue(ExecutionRequest request, TriggerResult trigger) {
        if (request == null) {
            return "NA";
        }
        if (request.getFlowType() == FlowType.RABBIT) {
            return "RABBITMQ";
        }
        if (request.getFlowType() == FlowType.KAFKA) {
            return "KAFKA";
        }
        return value(request.getFlowType());
    }

    private String serviceValue(ExecutionRequest request) {
        if (request == null) {
            return "NA";
        }
        String service = request.getAttributes().get("service");
        if (service != null && !service.trim().isEmpty()) {
            return value(service);
        }
        return request.getTestCase() == null ? "NA" : value(request.getTestCase().getScenario());
    }

    private String scenarioValue(ExecutionRequest request) {
        return request == null || request.getTestCase() == null
                ? "NA"
                : value(request.getTestCase().getScenario());
    }

    private String endpointOrDestination(TriggerResult trigger) {
        if (trigger == null) {
            return "NA";
        }
        return value(trigger.getMetadata().get("ENDPOINT_OR_DESTINATION"));
    }

    private String publishValue(ExecutionRequest request, TriggerResult trigger) {
        if (trigger == null || !isMessaging(request)) {
            return "NA";
        }
        return trigger.isSuccess() ? "SUCCESS" : "FAIL";
    }

    private String routedValue(ExecutionRequest request, TriggerResult trigger) {
        if (request == null || (request.getFlowType() != FlowType.RABBIT && request.getFlowType() != FlowType.KAFKA)
                || trigger == null) {
            return "NA";
        }
        return trigger.isSuccess() ? "YES" : "NO";
    }

    private String consumerTriggerValue(ExecutionRequest request, TriggerResult trigger) {
        if (trigger == null || !isMessaging(request)) {
            return "NA";
        }
        if (!trigger.isSuccess()) {
            return "FAIL";
        }
        if (request.getFlowType() == FlowType.RABBIT || request.getFlowType() == FlowType.KAFKA) {
            return "PASS";
        }
        return request.effectiveMode() == ExecutionMode.ASYNC ? "ASYNC_VALIDATION_PENDING" : "PASS";
    }

    private String httpCodeValue(TriggerResult trigger) {
        return trigger == null || trigger.getHttpStatus() == null ? "NA" : String.valueOf(trigger.getHttpStatus());
    }

    private String processValue(ExecutionReport report, ExecutionRequest request, TriggerResult trigger) {
        String configured = metadataValue(trigger, "PROCESS");
        if (!"NA".equals(configured)) {
            return configured;
        }
        if (trigger == null || !trigger.isSuccess()) {
            return "FAIL";
        }
        if (isAsyncPending(report, request, trigger)) {
            return "ASYNC_VALIDATION_PENDING";
        }
        if (isSyncPending(report, request, trigger)) {
            return "VALIDATION_PENDING";
        }
        return report != null && report.isValidationComplete() ? "PASS" : "NA";
    }

    private String downstreamValue(ExecutionReport report, ExecutionRequest request, TriggerResult trigger) {
        String configured = metadataValue(trigger, "DOWNSTREAM");
        if (!"NA".equals(configured)) {
            return configured;
        }
        if (trigger == null || !trigger.isSuccess()) {
            return "FAILED";
        }
        if (isAsyncPending(report, request, trigger)) {
            return "ASYNC_VALIDATION_PENDING";
        }
        if (isSyncPending(report, request, trigger)) {
            return "VALIDATION_PENDING";
        }
        return report != null && report.isValidationComplete() ? "SUCCESS" : "NA";
    }

    private String errorValue(TriggerResult trigger) {
        String configured = metadataValue(trigger, "ERROR");
        if (!"NA".equals(configured)) {
            return configured;
        }
        return trigger != null && trigger.isSuccess() ? "NO" : "YES";
    }

    private String dlqValue(ExecutionRequest request, TriggerResult trigger) {
        if (request == null || (request.getFlowType() != FlowType.RABBIT && request.getFlowType() != FlowType.KAFKA)) {
            return "NA";
        }
        return trigger != null && trigger.isSuccess() ? "NO" : "UNKNOWN";
    }

    private String nextAction(ExecutionReport report, ExecutionRequest request, TriggerResult trigger) {
        if (trigger == null || !trigger.isSuccess()) {
            return "Review Reason, payload resolution, endpoint/destination config, and protocol connectivity";
        }
        if (isAsyncPending(report, request, trigger)) {
            if ("TRACE_PENDING".equalsIgnoreCase(metadataValue(trigger, "PROCESS"))) {
                return "Review Rabbit/Nordics audit log availability, SFTP profile, remote audit.log path, and TrackingID fallback";
            }
            return "Run trace/log validation for BookingID-CorrID-JobID correlation";
        }
        if (isSyncPending(report, request, trigger)) {
            return "Review synchronous response validation configuration";
        }
        return "";
    }

    private boolean isMessaging(ExecutionRequest request) {
        return request != null && (request.getFlowType() == FlowType.JMS
                || request.getFlowType() == FlowType.RABBIT
                || request.getFlowType() == FlowType.KAFKA);
    }

    private String metadataValue(TriggerResult trigger, String key) {
        if (trigger == null || key == null) {
            return "NA";
        }
        String value = trigger.getMetadata().get(key);
        return value(value);
    }

    private String firstText(String first, String second) {
        return first != null && !first.trim().isEmpty() ? first : second;
    }

    private String oneLine(String value) {
        if (value == null) {
            return "";
        }
        return value.replace('\r', ' ').replace('\n', ' ').trim();
    }

    private String value(Object value) {
        if (value == null) {
            return "NA";
        }
        String text = String.valueOf(value);
        return text.trim().isEmpty() ? "NA" : text;
    }
}
