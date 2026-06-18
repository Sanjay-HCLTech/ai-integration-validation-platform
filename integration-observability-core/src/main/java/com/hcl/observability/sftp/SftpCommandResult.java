package com.hcl.observability.sftp;

import java.util.List;

public class SftpCommandResult {

    private final List<String> output;
    private final String error;
    private final int exitStatus;
    private final long sshConnectTimeMs;

    public SftpCommandResult(List<String> output, String error, int exitStatus) {
        this(output, error, exitStatus, 0);
    }

    public SftpCommandResult(List<String> output, String error, int exitStatus, long sshConnectTimeMs) {
        this.output = output;
        this.error = error;
        this.exitStatus = exitStatus;
        this.sshConnectTimeMs = sshConnectTimeMs;
    }

    public List<String> getOutput() {
        return output;
    }

    public String getError() {
        return error;
    }

    public int getExitStatus() {
        return exitStatus;
    }

    public long getSshConnectTimeMs() {
        return sshConnectTimeMs;
    }

    public boolean hasErrorText(String text) {
        return error != null && error.toLowerCase().contains(text.toLowerCase());
    }
}
