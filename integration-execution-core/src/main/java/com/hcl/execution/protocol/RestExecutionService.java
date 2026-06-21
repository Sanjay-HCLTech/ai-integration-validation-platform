package com.hcl.execution.protocol;

import com.hcl.execution.model.TestCase;
import com.hcl.execution.rest.RestFlowConfig;
import com.hcl.execution.rest.RestTriggerOutcome;
import com.hcl.execution.trigger.RestTriggerService;
import com.hcl.observability.sftp.SftpProfileContext;
import com.hcl.observability.sftp.SftpService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Service
public class RestExecutionService {

    private final RestTriggerService restTriggerService;
    private final RestFlowConfig restFlowConfig;
    private final SftpService sftpService;
    private final String restRemoteLogPath;
    private final String localLogDir;
    private final boolean restLogSnapshotEnabled;
    private final String restLogSnapshotEnvs;
    private final int restLogSnapshotMaxFiles;
    private final int restLogSnapshotModifiedWithinDays;

    public RestExecutionService(
            RestTriggerService restTriggerService,
            RestFlowConfig restFlowConfig,
            SftpService sftpService,
            @Value("${rest.log.remote.path:${rest.sftp.payload.log.dir:}}") String restRemoteLogPath,
            @Value("${local.log.dir:C:/logs}") String localLogDir,
            @Value("${rest.log.snapshot.enabled:false}") boolean restLogSnapshotEnabled,
            @Value("${rest.log.snapshot.envs:ST5}") String restLogSnapshotEnvs,
            @Value("${rest.log.snapshot.max.files:3}") int restLogSnapshotMaxFiles,
            @Value("${rest.log.snapshot.modified.within.days:${sftp.search.modified.within.days:15}}")
            int restLogSnapshotModifiedWithinDays) {
        this.restTriggerService = restTriggerService;
        this.restFlowConfig = restFlowConfig;
        this.sftpService = sftpService;
        this.restRemoteLogPath = restRemoteLogPath;
        this.localLogDir = localLogDir;
        this.restLogSnapshotEnabled = restLogSnapshotEnabled;
        this.restLogSnapshotEnvs = restLogSnapshotEnvs;
        this.restLogSnapshotMaxFiles = Math.max(1, restLogSnapshotMaxFiles);
        this.restLogSnapshotModifiedWithinDays = Math.max(1, restLogSnapshotModifiedWithinDays);
    }

    public ProtocolExecutionResult execute(TestCase testCase) throws Exception {
        long startNanos = System.nanoTime();
        try {
            RestTriggerOutcome outcome = restTriggerService.execute(testCase);
            String snapshotMessage = snapshotRestLogs(testCase, outcome);
            ProtocolExecutionResult result = result("REST", "SYNC",
                    outcome.isSuccess() ? "SUCCESS" : "ERROR",
                    elapsedMs(startNanos),
                    appendMessage(outcome.getMessage(), snapshotMessage));
            result.setHttpStatus(outcome.getHttpStatus());
            result.setResponseBody(outcome.getResponseBody());
            result.setValidationComplete(true);
            result.setProcessStatus(outcome.isSuccess() ? "PASS" : "FAIL");
            result.setDownstreamStatus(outcome.isSuccess() ? "SYNC_RESPONSE_VALIDATED" : "FAILED");
            result.setErrorFound(outcome.isSuccess() ? "NO" : "YES");
            result.setPayloadSource(outcome.getPayloadSource());
            result.setEndpointOrDestination(outcome.getEndpoint());
            log(result);
            return result;
        } catch (Exception e) {
            ProtocolExecutionResult result = result("REST", "SYNC", "ERROR", elapsedMs(startNanos), e.getMessage());
            result.setValidationComplete(true);
            result.setProcessStatus("FAIL");
            result.setDownstreamStatus("FAILED");
            result.setErrorFound("YES");
            applyFallbackContext(testCase, result);
            log(result);
            return result;
        }
    }

    private String snapshotRestLogs(TestCase testCase, RestTriggerOutcome outcome) {
        if (!restLogSnapshotEnabled) {
            return "";
        }
        if (!hasText(restRemoteLogPath)) {
            return "REST log snapshot skipped because rest.log.remote.path is not configured";
        }

        String env = restFlowConfig.env(testCase);
        if (!snapshotAllowedForEnv(env)) {
            return "REST log snapshot skipped because env " + value(env)
                    + " is not in rest.log.snapshot.envs=" + value(restLogSnapshotEnvs);
        }
        String collection = outcome == null ? restFlowConfig.collection(testCase) : outcome.getCollection();
        String snapshotDir = snapshotDirectory(env, collection);
        try (AutoCloseable ignored = SftpProfileContext.use("apigee-rest")) {
            List<RemoteFile> remoteFiles = recentRemoteFiles();
            if (remoteFiles.isEmpty()) {
                return "REST log snapshot found no recent remote files under " + restRemoteLogPath;
            }

            List<SftpService.DownloadRequest> requests = new ArrayList<>();
            for (RemoteFile remoteFile : remoteFiles) {
                requests.add(new SftpService.DownloadRequest(
                        remoteFile.fullPath(restRemoteLogPath),
                        new File(snapshotDir, safeFileName(remoteFile.name)).getPath()));
            }
            sftpService.downloadBatch(requests);
            String message = "REST log snapshot transferred files=" + requests.size()
                    + " localDir=" + snapshotDir;
            System.out.println("[REST_LOG_SNAPSHOT]");
            System.out.println("Status=SUCCESS");
            System.out.println("RemotePath=" + value(restRemoteLogPath));
            System.out.println("LocalDir=" + snapshotDir);
            System.out.println("Files=" + requests.size());
            return message;
        } catch (Exception e) {
            String message = "REST log snapshot transfer failed: " + oneLine(e.getMessage());
            System.out.println("[REST_LOG_SNAPSHOT]");
            System.out.println("Status=FAILED");
            System.out.println("RemotePath=" + value(restRemoteLogPath));
            System.out.println("Message=" + message);
            return message;
        }
    }

    private List<RemoteFile> recentRemoteFiles() throws Exception {
        List<String> output = sftpService.execute(recentFilesCommand());
        List<RemoteFile> files = new ArrayList<>();
        for (String line : output) {
            RemoteFile remoteFile = RemoteFile.parse(line);
            if (remoteFile != null) {
                files.add(remoteFile);
            }
        }
        files.sort(Comparator.comparingLong((RemoteFile file) -> file.modifiedEpoch).reversed());
        if (files.size() <= restLogSnapshotMaxFiles) {
            return files;
        }
        return new ArrayList<>(files.subList(0, restLogSnapshotMaxFiles));
    }

    private String recentFilesCommand() {
        StringBuilder inner = new StringBuilder("cd " + shellQuote(trimTrailingSlash(restRemoteLogPath)));
        inner.append(" && find . -maxdepth 1 -type f -mtime -")
                .append(restLogSnapshotModifiedWithinDays)
                .append(" -print0 | while IFS= read -r -d '' f; do ")
                .append("stat_out=$(stat -c '%s %Y' \"$f\" 2>&1); stat_rc=$?; ")
                .append("if [ $stat_rc -eq 0 ]; then set -- $stat_out; ")
                .append("printf 'FILE\t%s\t%s\t%s\n' \"$f\" \"$1\" \"$2\"; fi; ")
                .append("done");
        return "bash -lc " + shellQuote(inner.toString());
    }

    private String snapshotDirectory(String env, String collection) {
        File base = new File(new File(new File(localLogDir, "rest"), safePathSegment(env)), safePathSegment(collection));
        String runId = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        return new File(base, runId).getPath();
    }

    private String appendMessage(String message, String addition) {
        if (!hasText(addition)) {
            return message;
        }
        if (!hasText(message)) {
            return addition;
        }
        return message + "; " + addition;
    }

    private boolean snapshotAllowedForEnv(String env) {
        if (!hasText(restLogSnapshotEnvs)) {
            return false;
        }
        String normalizedEnv = value(env);
        for (String token : restLogSnapshotEnvs.split("[,;|]")) {
            if ("*".equals(token.trim()) || normalizedEnv.equalsIgnoreCase(token.trim())) {
                return true;
            }
        }
        return false;
    }

    private void applyFallbackContext(TestCase testCase, ProtocolExecutionResult result) {
        if (testCase == null || result == null) {
            return;
        }
        try {
            String env = restFlowConfig.env(testCase);
            String collection = restFlowConfig.collection(testCase);
            String brand = restFlowConfig.brand(testCase);
            result.setEndpointOrDestination(restFlowConfig.endpoint(env, collection, brand));
            String payloadFile = restFlowConfig.payloadFile(testCase, collection);
            if (payloadFile != null && !payloadFile.trim().isEmpty()) {
                result.setPayloadSource(payloadFile.trim());
            }
        } catch (Exception ignored) {
            // Preserve the original failure reason; fallback context is best-effort only.
        }
    }

    private ProtocolExecutionResult result(String protocol, String mode, String status, long latencyMs, String message) {
        ProtocolExecutionResult result = new ProtocolExecutionResult();
        result.setProtocol(protocol);
        result.setMode(mode);
        result.setStatus(status);
        result.setLatencyMs(latencyMs);
        result.setMessage(message);
        return result;
    }

    private long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    private String safePathSegment(String value) {
        String text = hasText(value) ? value.trim() : "NA";
        return text.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private String safeFileName(String value) {
        String text = hasText(value) ? value.trim() : "remote.log";
        int slash = Math.max(text.lastIndexOf('/'), text.lastIndexOf('\\'));
        if (slash >= 0 && slash < text.length() - 1) {
            text = text.substring(slash + 1);
        }
        return safePathSegment(text);
    }

    private String trimTrailingSlash(String value) {
        String trimmed = value == null ? "" : value.trim();
        while (trimmed.endsWith("/") && trimmed.length() > 1) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    private String shellQuote(String value) {
        String safeValue = value == null ? "" : value;
        return "'" + safeValue.replace("'", "'\"'\"'") + "'";
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private String oneLine(String value) {
        return value == null ? "" : value.replace('\r', ' ').replace('\n', ' ').trim();
    }

    private String value(String value) {
        return hasText(value) ? value.trim() : "NA";
    }

    private void log(ProtocolExecutionResult result) {
        System.out.println("[PROTOCOL_EXECUTION]");
        System.out.println("Protocol=" + result.getProtocol()
                + " Mode=" + result.getMode()
                + " Status=" + result.getStatus()
                + " LatencyMs=" + result.getLatencyMs());
    }

    private static class RemoteFile {
        private final String name;
        private final long modifiedEpoch;

        private RemoteFile(String name, long modifiedEpoch) {
            this.name = name;
            this.modifiedEpoch = modifiedEpoch;
        }

        private String fullPath(String remoteDir) {
            String normalizedDir = remoteDir == null ? "" : remoteDir.trim();
            while (normalizedDir.endsWith("/") && normalizedDir.length() > 1) {
                normalizedDir = normalizedDir.substring(0, normalizedDir.length() - 1);
            }
            String relative = name == null ? "" : name.trim();
            while (relative.startsWith("./")) {
                relative = relative.substring(2);
            }
            while (relative.startsWith("/")) {
                relative = relative.substring(1);
            }
            return normalizedDir + "/" + relative;
        }

        private static RemoteFile parse(String line) {
            if (line == null || !line.startsWith("FILE\t")) {
                return null;
            }
            String[] parts = line.split("\\t", 4);
            if (parts.length < 4) {
                return null;
            }
            try {
                return new RemoteFile(parts[1], Long.parseLong(parts[3]));
            } catch (NumberFormatException e) {
                return null;
            }
        }
    }
}
