package com.hcl.gateway.console;

import java.util.ArrayList;
import java.util.List;

public class ConsoleResultRow {

    private String service;
    private String flow;
    private String system;
    private String env;
    private String status;
    private String bookingId;
    private String trackingId;
    private String corrId;
    private String jobId;
    private long timeMs;
    private String message;
    private List<String> trace = new ArrayList<>();
    private List<String> assertions = new ArrayList<>();
    private List<String> timeline = new ArrayList<>();
    private List<String> terminalLog = new ArrayList<>();

    public String getService() {
        return service;
    }

    public void setService(String service) {
        this.service = service;
    }

    public String getFlow() {
        return flow;
    }

    public void setFlow(String flow) {
        this.flow = flow;
    }

    public String getSystem() {
        return system;
    }

    public void setSystem(String system) {
        this.system = system;
    }

    public String getEnv() {
        return env;
    }

    public void setEnv(String env) {
        this.env = env;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getBookingId() {
        return bookingId;
    }

    public void setBookingId(String bookingId) {
        this.bookingId = bookingId;
    }

    public String getTrackingId() {
        return trackingId;
    }

    public void setTrackingId(String trackingId) {
        this.trackingId = trackingId;
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

    public long getTimeMs() {
        return timeMs;
    }

    public void setTimeMs(long timeMs) {
        this.timeMs = timeMs;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public List<String> getTrace() {
        return trace;
    }

    public void setTrace(List<String> trace) {
        this.trace = trace;
    }

    public List<String> getAssertions() {
        return assertions;
    }

    public void setAssertions(List<String> assertions) {
        this.assertions = assertions;
    }

    public List<String> getTimeline() {
        return timeline;
    }

    public void setTimeline(List<String> timeline) {
        this.timeline = timeline;
    }

    public List<String> getTerminalLog() {
        return terminalLog;
    }

    public void setTerminalLog(List<String> terminalLog) {
        this.terminalLog = terminalLog;
    }
}
