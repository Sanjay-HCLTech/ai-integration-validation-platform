package com.hcl.execution.rabbit;

import java.util.LinkedHashMap;
import java.util.Map;

public class RabbitTriggerOutcome {

    private boolean success;
    private String status;
    private String message;
    private String env;
    private String system;
    private String exchange;
    private String routingKey;
    private String queue;
    private String payloadSource;
    private String corrId;
    private String trackingId;
    private String messageId;
    private String jobId;
    private Map<String, String> messageHeaders = new LinkedHashMap<>();
    private Map<String, String> messageProperties = new LinkedHashMap<>();
    private long timeMs;

    public boolean isSuccess() {
        return success;
    }

    public void setSuccess(boolean success) {
        this.success = success;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public String getEnv() {
        return env;
    }

    public void setEnv(String env) {
        this.env = env;
    }

    public String getSystem() {
        return system;
    }

    public void setSystem(String system) {
        this.system = system;
    }

    public String getExchange() {
        return exchange;
    }

    public void setExchange(String exchange) {
        this.exchange = exchange;
    }

    public String getRoutingKey() {
        return routingKey;
    }

    public void setRoutingKey(String routingKey) {
        this.routingKey = routingKey;
    }

    public String getQueue() {
        return queue;
    }

    public void setQueue(String queue) {
        this.queue = queue;
    }

    public String getPayloadSource() {
        return payloadSource;
    }

    public void setPayloadSource(String payloadSource) {
        this.payloadSource = payloadSource;
    }

    public String getCorrId() {
        return corrId;
    }

    public void setCorrId(String corrId) {
        this.corrId = corrId;
    }

    public String getTrackingId() {
        return trackingId;
    }

    public void setTrackingId(String trackingId) {
        this.trackingId = trackingId;
    }

    public String getMessageId() {
        return messageId;
    }

    public void setMessageId(String messageId) {
        this.messageId = messageId;
    }

    public String getJobId() {
        return jobId;
    }

    public void setJobId(String jobId) {
        this.jobId = jobId;
    }

    public Map<String, String> getMessageHeaders() {
        return messageHeaders;
    }

    public void setMessageHeaders(Map<String, String> messageHeaders) {
        this.messageHeaders = messageHeaders == null ? new LinkedHashMap<>() : new LinkedHashMap<>(messageHeaders);
    }

    public Map<String, String> getMessageProperties() {
        return messageProperties;
    }

    public void setMessageProperties(Map<String, String> messageProperties) {
        this.messageProperties = messageProperties == null ? new LinkedHashMap<>() : new LinkedHashMap<>(messageProperties);
    }

    public long getTimeMs() {
        return timeMs;
    }

    public void setTimeMs(long timeMs) {
        this.timeMs = timeMs;
    }
}
