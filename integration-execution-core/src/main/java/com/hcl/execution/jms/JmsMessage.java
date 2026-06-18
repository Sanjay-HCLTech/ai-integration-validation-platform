package com.hcl.execution.jms;

public class JmsMessage {

    private String bookingId;
    private String corrId;
    private String jobId;
    private String sourceSystem;
    private String senderQueue;
    private String receiverQueue;
    private String messageType;
    private String payload;
    private boolean async;
    private int retryCount;
    private long timestamp;

    public String getBookingId() {
        return bookingId;
    }

    public void setBookingId(String bookingId) {
        this.bookingId = bookingId;
    }

    public String getCorrId() {
        return corrId;
    }

    public void setCorrId(String corrId) {
        this.corrId = corrId;
    }

    public String getJobId() {
        return jobId;
    }

    public void setJobId(String jobId) {
        this.jobId = jobId;
    }

    public String getSourceSystem() {
        return sourceSystem;
    }

    public void setSourceSystem(String sourceSystem) {
        this.sourceSystem = sourceSystem;
    }

    public String getSenderQueue() {
        return senderQueue;
    }

    public void setSenderQueue(String senderQueue) {
        this.senderQueue = senderQueue;
    }

    public String getReceiverQueue() {
        return receiverQueue;
    }

    public void setReceiverQueue(String receiverQueue) {
        this.receiverQueue = receiverQueue;
    }

    public String getMessageType() {
        return messageType;
    }

    public void setMessageType(String messageType) {
        this.messageType = messageType;
    }

    public String getPayload() {
        return payload;
    }

    public void setPayload(String payload) {
        this.payload = payload;
    }

    public boolean isAsync() {
        return async;
    }

    public void setAsync(boolean async) {
        this.async = async;
    }

    public int getRetryCount() {
        return retryCount;
    }

    public void setRetryCount(int retryCount) {
        this.retryCount = retryCount;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(long timestamp) {
        this.timestamp = timestamp;
    }
}
