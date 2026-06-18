package com.hcl.execution.model;

public class LogResult {

    private String bookingId;
    private String corrId;
    private String jobId;
    private int totalLines;
    private int expandedLines;
    private String status;
    private String message;

    public String getBookingId() { return bookingId; }
    public void setBookingId(String bookingId) { this.bookingId = bookingId; }

    public String getCorrId() { return corrId; }
    public void setCorrId(String corrId) { this.corrId = corrId; }

    public String getJobId() { return jobId; }
    public void setJobId(String jobId) { this.jobId = jobId; }

    public int getTotalLines() { return totalLines; }
    public void setTotalLines(int totalLines) { this.totalLines = totalLines; }

    public int getExpandedLines() { return expandedLines; }
    public void setExpandedLines(int expandedLines) { this.expandedLines = expandedLines; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
}
