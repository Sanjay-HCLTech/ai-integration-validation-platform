package com.hcl.observability.log;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class LogSearchResult {

    private final List<String> lines;
    private final boolean partialCoverage;
    private final int attempts;
    private final int filesFound;
    private final int uniqueFilesFound;
    private final int filesTransferred;
    private final int completeLocalFiles;
    private final int totalFilesScanned;
    private final int accessibleFiles;
    private final int deniedFiles;
    private final int coveragePercent;
    private final List<String> remoteFiles;
    private final long sshConnectTimeMs;
    private final long fileListTimeMs;
    private final long remoteGrepTimeMs;
    private final long downloadTimeMs;
    private final String message;

    public LogSearchResult(
            List<String> lines,
            boolean partialCoverage,
            int attempts,
            int filesFound,
            int uniqueFilesFound,
            int totalFilesScanned,
            int accessibleFiles,
            int deniedFiles,
            int coveragePercent,
            List<String> remoteFiles,
            long sshConnectTimeMs,
            long fileListTimeMs,
            long remoteGrepTimeMs,
            long downloadTimeMs,
            String message) {
        this(lines, partialCoverage, attempts, filesFound, uniqueFilesFound, 0, 0,
                totalFilesScanned, accessibleFiles, deniedFiles, coveragePercent, remoteFiles,
                sshConnectTimeMs, fileListTimeMs, remoteGrepTimeMs, downloadTimeMs, message);
    }

    public LogSearchResult(
            List<String> lines,
            boolean partialCoverage,
            int attempts,
            int filesFound,
            int uniqueFilesFound,
            int filesTransferred,
            int completeLocalFiles,
            int totalFilesScanned,
            int accessibleFiles,
            int deniedFiles,
            int coveragePercent,
            List<String> remoteFiles,
            long sshConnectTimeMs,
            long fileListTimeMs,
            long remoteGrepTimeMs,
            long downloadTimeMs,
            String message) {
        this.lines = Collections.unmodifiableList(new ArrayList<>(lines));
        this.partialCoverage = partialCoverage;
        this.attempts = attempts;
        this.filesFound = filesFound;
        this.uniqueFilesFound = uniqueFilesFound;
        this.filesTransferred = filesTransferred;
        this.completeLocalFiles = completeLocalFiles;
        this.totalFilesScanned = totalFilesScanned;
        this.accessibleFiles = accessibleFiles;
        this.deniedFiles = deniedFiles;
        this.coveragePercent = coveragePercent;
        this.remoteFiles = Collections.unmodifiableList(new ArrayList<>(remoteFiles));
        this.sshConnectTimeMs = sshConnectTimeMs;
        this.fileListTimeMs = fileListTimeMs;
        this.remoteGrepTimeMs = remoteGrepTimeMs;
        this.downloadTimeMs = downloadTimeMs;
        this.message = message;
    }

    public List<String> getLines() {
        return lines;
    }

    public boolean isPartialCoverage() {
        return partialCoverage;
    }

    public int getAttempts() {
        return attempts;
    }

    public int getFilesFound() {
        return filesFound;
    }

    public int getUniqueFilesFound() {
        return uniqueFilesFound;
    }

    public int getFilesTransferred() {
        return filesTransferred;
    }

    public int getCompleteLocalFiles() {
        return completeLocalFiles;
    }

    public int getBlockLines() {
        return filesFound;
    }

    public int getTotalFilesScanned() {
        return totalFilesScanned;
    }

    public int getAccessibleFiles() {
        return accessibleFiles;
    }

    public int getDeniedFiles() {
        return deniedFiles;
    }

    public int getCoveragePercent() {
        return coveragePercent;
    }

    public List<String> getRemoteFiles() {
        return remoteFiles;
    }

    public long getSshConnectTimeMs() {
        return sshConnectTimeMs;
    }

    public long getFileListTimeMs() {
        return fileListTimeMs;
    }

    public long getRemoteGrepTimeMs() {
        return remoteGrepTimeMs;
    }

    public long getDownloadTimeMs() {
        return downloadTimeMs;
    }

    public String getMessage() {
        return message;
    }
}
