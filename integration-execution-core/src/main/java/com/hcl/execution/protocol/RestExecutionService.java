package com.hcl.execution.protocol;

import com.hcl.execution.model.TestCase;
import com.hcl.execution.rest.RestFlowConfig;
import com.hcl.execution.rest.RestTriggerOutcome;
import com.hcl.execution.trigger.RestTriggerService;
import com.hcl.observability.sftp.SftpProfileContext;
import com.hcl.observability.sftp.SftpService;
import com.hcl.observability.trace.UnifiedTraceContextHolder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Service
public class RestExecutionService {

    private final RestTriggerService restTriggerService;
    private final RestFlowConfig restFlowConfig;
    private final SftpService sftpService;
    private final UnifiedTraceContextHolder traceContextHolder;
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
            UnifiedTraceContextHolder traceContextHolder,
            @Value("${rest.sftp.payload.log.dir}") String restRemoteLogPath,
            @Value("${local.log.dir}") String localLogDir,
            @Value("${rest.log.snapshot.enabled}") boolean restLogSnapshotEnabled,
            @Value("${rest.log.snapshot.envs}") String restLogSnapshotEnvs,
            @Value("${rest.log.snapshot.max.files}") int restLogSnapshotMaxFiles,
            @Value("${rest.log.snapshot.modified.within.days}")
            int restLogSnapshotModifiedWithinDays) {
        this.restTriggerService = restTriggerService;
        this.restFlowConfig = restFlowConfig;
        this.sftpService = sftpService;
        this.traceContextHolder = traceContextHolder;
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
            result.setTrackingId(outcome.getTrackingId());
            result.setResponseBody(outcome.getResponseBody());
            result.setValidationComplete(true);
            result.setProcessStatus(outcome.isSuccess() ? "PASS" : "FAIL");
            result.setDownstreamStatus(outcome.isSuccess() ? "SYNC_RESPONSE_VALIDATED" : "FAILED");
            result.setErrorFound(outcome.isSuccess() ? "NO" : "YES");
            result.setPayloadSource(outcome.getPayloadSource());
            result.setEndpointOrDestination(outcome.getEndpoint());
            addRestMetadata(testCase, outcome, result, snapshotMessage);
            log(result);
            return result;
        } catch (Exception e) {
            ProtocolExecutionResult result = result("REST", "SYNC", "ERROR", elapsedMs(startNanos), e.getMessage());
            result.setValidationComplete(true);
            result.setProcessStatus("FAIL");
            result.setDownstreamStatus("FAILED");
            result.setErrorFound("YES");
            applyFallbackContext(testCase, result);
            result.setMessage(appendMessage(result.getMessage(), requestDiagnostic(testCase)));
            addRestMetadata(testCase, null, result, "");
            log(result);
            return result;
        }
    }

    private void addRestMetadata(
            TestCase testCase,
            RestTriggerOutcome outcome,
            ProtocolExecutionResult result,
            String snapshotMessage) {
        if (result == null) {
            return;
        }
        String env = restFlowConfig.env(testCase);
        String collection = outcome == null ? restFlowConfig.collection(testCase) : outcome.getCollection();
        String brand = outcome == null ? restFlowConfig.brand(testCase) : outcome.getBrand();
        String method = outcome == null ? restFlowConfig.method(collection, "GET") : outcome.getMethod();
        String remoteLogPath = restFlowConfig.logRemotePath(env, collection, restRemoteLogPath);
        result.putMetadata("REST_ENV", value(env));
        result.putMetadata("REST_COLLECTION", value(collection));
        result.putMetadata("REST_BRAND", value(brand));
        result.putMetadata("REST_METHOD", value(method));
        result.putMetadata("REST_QUERY_PARAM", value(restFlowConfig.queryParam(collection, "request")));
        result.putMetadata("REST_ACCEPT_DEFAULT", value(restFlowConfig.defaultAcceptHeader(collection)));
        result.putMetadata("REST_ACCEPT_CONFIGURED", value(restFlowConfig.acceptHeader(collection, "application/json")));
        result.putMetadata("REST_ACCEPT_COMBINED", value(restFlowConfig.combinedAcceptHeader(collection)));
        result.putMetadata("REST_QUERY_ENCODING", value(restFlowConfig.queryEncoding(collection)));
        result.putMetadata("REST_LOG_SNAPSHOT_STATUS", snapshotStatus(snapshotMessage));
        result.putMetadata("REST_LOG_SNAPSHOT_REMOTE_PATH", value(remoteLogPath));
        result.putMetadata("REST_LOG_SNAPSHOT_MESSAGE", value(snapshotMessage));
        result.putMetadata("REST_LOG_SNAPSHOT_FILES", metric(snapshotMessage, "files"));
        result.putMetadata("REST_LOG_SNAPSHOT_LOCAL_DIR", metric(snapshotMessage, "localDir"));
    }

    private String snapshotStatus(String snapshotMessage) {
        if (!hasText(snapshotMessage)) {
            return restLogSnapshotEnabled ? "NO_ACTIVITY" : "DISABLED";
        }
        String normalized = snapshotMessage.toUpperCase();
        if (normalized.contains("TRANSFERRED")) {
            return "SUCCESS";
        }
        if (normalized.contains("FAILED")) {
            return "FAILED";
        }
        return "SKIPPED";
    }

    private String metric(String message, String key) {
        if (!hasText(message) || !hasText(key)) {
            return "";
        }
        String marker = key + "=";
        int start = message.indexOf(marker);
        if (start < 0) {
            return "";
        }
        int valueStart = start + marker.length();
        int valueEnd = message.indexOf(' ', valueStart);
        if (valueEnd < 0) {
            valueEnd = message.length();
        }
        return message.substring(valueStart, valueEnd).trim();
    }

    private String snapshotRestLogs(TestCase testCase, RestTriggerOutcome outcome) {
        if (!restLogSnapshotEnabled) {
            return "";
        }
        String env = restFlowConfig.env(testCase);
        String collection = outcome == null ? restFlowConfig.collection(testCase) : outcome.getCollection();
        String remoteLogPath = restFlowConfig.logRemotePath(env, collection, restRemoteLogPath);
        String excludePatterns = restFlowConfig.logSnapshotExclude(env, collection);
        if (!hasText(remoteLogPath)) {
            return "REST log snapshot skipped because rest.log.remote.path is not configured";
        }

        if (!snapshotAllowedForEnv(env)) {
            return "REST log snapshot skipped because env " + value(env)
                    + " is not in rest.log.snapshot.envs=" + value(restLogSnapshotEnvs);
        }
        String snapshotDir = snapshotDirectory(env, collection);
        try (AutoCloseable ignored = SftpProfileContext.use("apigee-rest")) {
            List<RemoteFile> remoteFiles = recentRemoteFiles(remoteLogPath, excludePatterns);
            if (remoteFiles.isEmpty()) {
                addRestFileLine("REST_REMOTE_SNAPSHOT", "N", "SKIP",
                        remoteLogPath, "", "No recent remote files after configured filters");
                return "REST log snapshot found no recent remote files under " + remoteLogPath;
            }

            List<SftpService.DownloadRequest> requests = new ArrayList<>();
            List<SnapshotDownload> downloads = new ArrayList<>();
            for (RemoteFile remoteFile : remoteFiles) {
                String remotePath = remoteFile.fullPath(remoteLogPath);
                String localPath = new File(snapshotDir, safeFileName(remoteFile.name)).getPath();
                requests.add(new SftpService.DownloadRequest(remotePath, localPath));
                downloads.add(new SnapshotDownload(remoteFile.name, remotePath, localPath));
            }
            sftpService.downloadBatch(requests);
            for (SnapshotDownload download : downloads) {
                addRestFileLine(download.name, "Y", "DOWNLOADED",
                        download.remotePath, download.localPath, "REST_SNAPSHOT");
            }
            String message = "REST log snapshot transferred files=" + requests.size()
                    + " localDir=" + snapshotDir;
            System.out.println("[REST_LOG_SNAPSHOT]");
            System.out.println("Status=SUCCESS");
            System.out.println("RemotePath=" + value(remoteLogPath));
            System.out.println("LocalDir=" + snapshotDir);
            System.out.println("Files=" + requests.size());
            return message;
        } catch (Exception e) {
            String message = "REST log snapshot transfer failed: " + oneLine(e.getMessage());
            addRestFileLine("REST_REMOTE_SNAPSHOT", "N", "FAILED",
                    remoteLogPath, "", oneLine(e.getMessage()));
            System.out.println("[REST_LOG_SNAPSHOT]");
            System.out.println("Status=FAILED");
            System.out.println("RemotePath=" + value(remoteLogPath));
            System.out.println("Message=" + message);
            return message;
        }
    }

    private void addRestFileLine(
            String name,
            String existsLocal,
            String action,
            String remotePath,
            String localPath,
            String reason) {
        StringBuilder line = new StringBuilder();
        line.append("File=").append(padRight(safeFileName(name), 48))
                .append(" Exists=").append(value(existsLocal))
                .append(" Action=").append(value(action));
        if (hasText(remotePath)) {
            line.append(" RemotePath=").append(remotePath);
        }
        if (hasText(localPath)) {
            line.append(" LocalPath=").append(localPath);
        }
        if (hasText(reason)) {
            line.append(" Reason=").append(oneLine(reason));
        }
        traceContextHolder.addFileLine(line.toString());
    }

    private List<RemoteFile> recentRemoteFiles(String remoteLogPath, String excludePatterns) throws Exception {
        List<String> output = sftpService.execute(recentFilesCommand(remoteLogPath));
        List<RemoteFile> files = new ArrayList<>();
        for (String line : output) {
            RemoteFile remoteFile = RemoteFile.parse(line);
            if (remoteFile != null && !isExcluded(remoteFile.name, excludePatterns)) {
                files.add(remoteFile);
            }
        }
        files.sort(Comparator.comparingLong((RemoteFile file) -> file.modifiedEpoch).reversed());
        if (files.size() <= restLogSnapshotMaxFiles) {
            return files;
        }
        return new ArrayList<>(files.subList(0, restLogSnapshotMaxFiles));
    }

    private boolean isExcluded(String fileName, String excludePatterns) {
        if (!hasText(fileName) || !hasText(excludePatterns)) {
            return false;
        }
        for (String pattern : excludePatterns.split("[,;|]")) {
            String normalizedPattern = pattern == null ? "" : pattern.trim();
            if (normalizedPattern.isEmpty()) {
                continue;
            }
            if (fileName.equalsIgnoreCase(normalizedPattern)
                    || fileName.toLowerCase().contains(normalizedPattern.toLowerCase())) {
                return true;
            }
        }
        return false;
    }

    private String recentFilesCommand(String remoteLogPath) {
        StringBuilder inner = new StringBuilder("cd " + shellQuote(trimTrailingSlash(remoteLogPath)));
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
        String snapshotDate = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        return new File(base, snapshotDate).getPath();
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

    private String requestDiagnostic(TestCase testCase) {
        if (testCase == null) {
            return "";
        }
        try {
            String env = restFlowConfig.env(testCase);
            String collection = restFlowConfig.collection(testCase);
            String brand = restFlowConfig.brand(testCase);
            String method = restFlowConfig.method(collection, "GET");
            String endpoint = restFlowConfig.endpoint(env, collection, brand);
            String payload = restFlowConfig.payloadFile(testCase, collection);
            String queryParam = restFlowConfig.queryParam(collection, "request");
            String acceptDefault = restFlowConfig.defaultAcceptHeader(collection);
            String acceptConfigured = restFlowConfig.acceptHeader(collection, "application/json");
            String acceptCombined = restFlowConfig.combinedAcceptHeader(collection);
            String contentType = restFlowConfig.contentType(collection, "application/json");
            String apiKeyStatus = hasText(restFlowConfig.apiKey(env, collection, brand)) ? "CONFIGURED" : "MISSING";
            return "REST request config: Collection=" + value(collection)
                    + "; Env=" + value(env)
                    + "; Brand=" + value(brand)
                    + "; Method=" + value(method)
                    + "; QueryParam=" + value(queryParam)
                    + "; PayloadSource=" + value(payload)
                    + "; Endpoint=" + value(endpoint)
                    + "; AcceptDefault=" + value(acceptDefault)
                    + "; AcceptConfigured=" + value(acceptConfigured)
                    + "; AcceptCombined=" + value(acceptCombined)
                    + "; ContentType=" + value(contentType)
                    + "; ApiKey=" + apiKeyStatus;
        } catch (Exception ignored) {
            return "";
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

    private String padRight(String value, int width) {
        String safeValue = value(value);
        if (safeValue.length() >= width) {
            return safeValue;
        }
        StringBuilder builder = new StringBuilder(safeValue);
        while (builder.length() < width) {
            builder.append(' ');
        }
        return builder.toString();
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

    private static class SnapshotDownload {
        private final String name;
        private final String remotePath;
        private final String localPath;

        private SnapshotDownload(String name, String remotePath, String localPath) {
            this.name = name;
            this.remotePath = remotePath;
            this.localPath = localPath;
        }
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
