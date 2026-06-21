package com.hcl.execution.kafka;

import java.util.LinkedHashMap;
import java.util.Map;

public class KafkaTriggerOutcome {

    private boolean success;
    private String status;
    private String message;
    private String env;
    private String system;
    private String topic;
    private String key;
    private int partition;
    private long offset;
    private String consumerGroup;
    private String payloadSource;
    private String corrId;
    private String trackingId;
    private String messageId;
    private String jobId;
    private Map<String, String> headers = new LinkedHashMap<>();
    private long timeMs;

    public boolean isSuccess() { return success; }
    public void setSuccess(boolean success) { this.success = success; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
    public String getEnv() { return env; }
    public void setEnv(String env) { this.env = env; }
    public String getSystem() { return system; }
    public void setSystem(String system) { this.system = system; }
    public String getTopic() { return topic; }
    public void setTopic(String topic) { this.topic = topic; }
    public String getKey() { return key; }
    public void setKey(String key) { this.key = key; }
    public int getPartition() { return partition; }
    public void setPartition(int partition) { this.partition = partition; }
    public long getOffset() { return offset; }
    public void setOffset(long offset) { this.offset = offset; }
    public String getConsumerGroup() { return consumerGroup; }
    public void setConsumerGroup(String consumerGroup) { this.consumerGroup = consumerGroup; }
    public String getPayloadSource() { return payloadSource; }
    public void setPayloadSource(String payloadSource) { this.payloadSource = payloadSource; }
    public String getCorrId() { return corrId; }
    public void setCorrId(String corrId) { this.corrId = corrId; }
    public String getTrackingId() { return trackingId; }
    public void setTrackingId(String trackingId) { this.trackingId = trackingId; }
    public String getMessageId() { return messageId; }
    public void setMessageId(String messageId) { this.messageId = messageId; }
    public String getJobId() { return jobId; }
    public void setJobId(String jobId) { this.jobId = jobId; }
    public Map<String, String> getHeaders() { return headers; }
    public void setHeaders(Map<String, String> headers) {
        this.headers = headers == null ? new LinkedHashMap<>() : new LinkedHashMap<>(headers);
    }
    public long getTimeMs() { return timeMs; }
    public void setTimeMs(long timeMs) { this.timeMs = timeMs; }
}
