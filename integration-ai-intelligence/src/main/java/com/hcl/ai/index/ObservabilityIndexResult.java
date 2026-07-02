package com.hcl.ai.index;

import java.util.ArrayList;
import java.util.List;

public class ObservabilityIndexResult {

    private String bookingId;
    private String localPath;
    private int filesScanned;
    private int linesIndexed;
    private List<IndexedLogLine> entries = new ArrayList<>();
    private List<String> warnings = new ArrayList<>();

    public String getBookingId() {
        return bookingId;
    }

    public void setBookingId(String bookingId) {
        this.bookingId = bookingId;
    }

    public String getLocalPath() {
        return localPath;
    }

    public void setLocalPath(String localPath) {
        this.localPath = localPath;
    }

    public int getFilesScanned() {
        return filesScanned;
    }

    public void setFilesScanned(int filesScanned) {
        this.filesScanned = filesScanned;
    }

    public int getLinesIndexed() {
        return linesIndexed;
    }

    public void setLinesIndexed(int linesIndexed) {
        this.linesIndexed = linesIndexed;
    }

    public List<IndexedLogLine> getEntries() {
        return entries;
    }

    public void setEntries(List<IndexedLogLine> entries) {
        this.entries = entries;
    }

    public List<String> getWarnings() {
        return warnings;
    }

    public void setWarnings(List<String> warnings) {
        this.warnings = warnings;
    }
}
