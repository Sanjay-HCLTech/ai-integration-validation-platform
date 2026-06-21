package com.hcl.execution.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class TraceContext {

    private String bookingId;
    private String corrId;
    private String jobId;
    private final List<String> files = new ArrayList<>();

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

    public List<String> getFiles() {
        return Collections.unmodifiableList(files);
    }

    public void addFile(String file) {
        if (file != null && !file.trim().isEmpty() && !files.contains(file.trim())) {
            files.add(file.trim());
        }
    }
}
