package com.hcl.observability.log;

public class LogTransferException extends RuntimeException {

    private final LogSearchResult partialResult;

    public LogTransferException(String message, LogSearchResult partialResult) {
        super(message);
        this.partialResult = partialResult;
    }

    public LogSearchResult getPartialResult() {
        return partialResult;
    }
}
