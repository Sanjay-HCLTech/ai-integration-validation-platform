package com.hcl.observability.sftp;

import com.jcraft.jsch.ChannelExec;
import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.Session;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Service
public class SftpService {

    private final String defaultLogDir;
    private final SftpConnectionConfig defaultConfig;
    private final SftpConnectionConfig rabbitNordicsConfig;
    private final String rabbitNordicsRemoteLogPath;
    private final String apigeeRestLogDir;
    private final int modifiedWithinDays;
    private final int connectTimeoutMs;
    private final int commandTimeoutMs;
    private final int keepAliveIntervalMs;
    private Session sharedSession;
    private String sharedSessionProfile;

    public SftpService(
            @Value("${sftp.host}") String host,
            @Value("${sftp.port}") int port,
            @Value("${sftp.username}") String user,
            @Value("${sftp.private.key}") String key,
            @Value("${sftp.private.key.passphrase}") String passphrase,
            @Value("${sftp.password}") String password,
            @Value("${sftp.payload.log.dir}") String defaultLogDir,
            @Value("${rabbit.nordics.sftp.host}") String rabbitNordicsHost,
            @Value("${rabbit.nordics.sftp.port}") int rabbitNordicsPort,
            @Value("${rabbit.nordics.sftp.username}") String rabbitNordicsUser,
            @Value("${rabbit.nordics.sftp.password}") String rabbitNordicsPassword,
            @Value("${rabbit.nordics.sftp.private.key}") String rabbitNordicsKey,
            @Value("${rabbit.nordics.sftp.private.key.passphrase}") String rabbitNordicsPassphrase,
            @Value("${rabbit.nordics.sftp.payload.log.dir}") String rabbitNordicsRemoteLogPath,
            @Value("${rest.sftp.payload.log.dir}") String apigeeRestLogDir,
            @Value("${sftp.connect.timeout.ms}") int connectTimeoutMs,
            @Value("${sftp.command.timeout.ms}") int commandTimeoutMs,
            @Value("${sftp.keepalive.interval.ms}") int keepAliveIntervalMs,
            @Value("${sftp.search.modified.within.days}") int modifiedWithinDays) {
        this.defaultLogDir = trimTrailingSlash(defaultLogDir);
        this.defaultConfig = new SftpConnectionConfig(host, port, user, password, key, passphrase);
        this.rabbitNordicsConfig = new SftpConnectionConfig(
                rabbitNordicsHost,
                rabbitNordicsPort,
                rabbitNordicsUser,
                rabbitNordicsPassword,
                rabbitNordicsKey,
                rabbitNordicsPassphrase);
        this.rabbitNordicsRemoteLogPath = rabbitNordicsRemoteLogPath;
        this.apigeeRestLogDir = trimTrailingSlash(apigeeRestLogDir);
        this.connectTimeoutMs = Math.max(1000, connectTimeoutMs);
        this.commandTimeoutMs = Math.max(this.connectTimeoutMs, commandTimeoutMs);
        this.keepAliveIntervalMs = Math.max(1000, keepAliveIntervalMs);
        this.modifiedWithinDays = Math.max(1, modifiedWithinDays);
    }

    public List<String> execute(String command) throws Exception {
        return executeCommand(command).getOutput();
    }

    public synchronized SftpCommandResult executeCommand(String command) throws Exception {
        List<String> output = new ArrayList<>();
        ChannelExec channel = null;
        long sshConnectTimeMs = 0;

        try {
            long sshConnectStartNanos = System.nanoTime();
            Session session = connectedSession();
            sshConnectTimeMs = elapsedMs(sshConnectStartNanos);

            channel = (ChannelExec) session.openChannel("exec");
            channel.setCommand(profileAwareCommand(command));
            channel.setInputStream(null);

            InputStream input = channel.getInputStream();
            InputStream error = channel.getErrStream();

            channel.connect(connectTimeoutMs);

            long started = System.currentTimeMillis();
            ByteArrayOutputStream stdout = new ByteArrayOutputStream();
            ByteArrayOutputStream stderr = new ByteArrayOutputStream();
            while (!channel.isClosed()) {
                drainAvailable(input, stdout);
                drainAvailable(error, stderr);
                if (System.currentTimeMillis() - started > commandTimeoutMs) {
                    drainAvailable(input, stdout);
                    drainAvailable(error, stderr);
                    output.addAll(lines(stdout.toString(StandardCharsets.UTF_8.name())));
                    String timeoutError = "COMMAND_TIMEOUT after " + commandTimeoutMs
                            + " ms. PartialOutputLines=" + output.size();
                    String errorText = stderr.toString(StandardCharsets.UTF_8.name());
                    if (errorText != null && !errorText.trim().isEmpty()) {
                        timeoutError = timeoutError + ". stderr=" + errorText.trim();
                    }
                    return new SftpCommandResult(output, timeoutError, 124, sshConnectTimeMs);
                }
                Thread.sleep(100);
            }

            drainAvailable(input, stdout);
            drainAvailable(error, stderr);
            output.addAll(lines(stdout.toString(StandardCharsets.UTF_8.name())));
            int exitStatus = channel.getExitStatus();

            return new SftpCommandResult(output, stderr.toString(StandardCharsets.UTF_8.name()),
                    exitStatus, sshConnectTimeMs);
        } catch (Exception e) {
            closeSharedSession();
            throw e;
        } finally {
            if (channel != null && channel.isConnected()) {
                channel.disconnect();
            }
        }
    }

    public synchronized SftpCommandResult executeCommandAppending(
            String command,
            String localFile,
            String header,
            String footer) throws Exception {
        List<String> output = new ArrayList<>();
        ChannelExec channel = null;
        long sshConnectTimeMs = 0;

        try {
            File target = new File(localFile);
            File parent = target.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                throw new RuntimeException("Unable to create local log directory: " + parent.getAbsolutePath());
            }

            long sshConnectStartNanos = System.nanoTime();
            Session session = connectedSession();
            sshConnectTimeMs = elapsedMs(sshConnectStartNanos);

            channel = (ChannelExec) session.openChannel("exec");
            channel.setCommand(profileAwareCommand(command));
            channel.setInputStream(null);

            InputStream input = channel.getInputStream();
            InputStream error = channel.getErrStream();

            channel.connect(connectTimeoutMs);

            long started = System.currentTimeMillis();
            ByteArrayOutputStream stdout = new ByteArrayOutputStream();
            ByteArrayOutputStream stderr = new ByteArrayOutputStream();
            boolean timedOut = false;

            try (FileOutputStream fileOutput = new FileOutputStream(target, true)) {
                writeText(fileOutput, header);

                while (!channel.isClosed() || input.available() > 0 || error.available() > 0) {
                    drainAvailable(input, stdout, fileOutput);
                    drainAvailable(error, stderr);
                    if (!channel.isClosed() && System.currentTimeMillis() - started > commandTimeoutMs) {
                        timedOut = true;
                        break;
                    }
                    Thread.sleep(100);
                }

                drainAvailable(input, stdout, fileOutput);
                drainAvailable(error, stderr);
                writeText(fileOutput, footer);
            }

            output.addAll(lines(stdout.toString(StandardCharsets.UTF_8.name())));
            String errorText = stderr.toString(StandardCharsets.UTF_8.name());
            if (timedOut) {
                String timeoutError = "COMMAND_TIMEOUT after " + commandTimeoutMs
                        + " ms. PartialOutputLines=" + output.size();
                if (errorText != null && !errorText.trim().isEmpty()) {
                    timeoutError = timeoutError + ". stderr=" + errorText.trim();
                }
                return new SftpCommandResult(output, timeoutError, 124, sshConnectTimeMs);
            }

            return new SftpCommandResult(output, errorText, channel.getExitStatus(), sshConnectTimeMs);
        } catch (Exception e) {
            closeSharedSession();
            throw e;
        } finally {
            if (channel != null && channel.isConnected()) {
                channel.disconnect();
            }
        }
    }

    public void download(String remoteFile, String localFile) throws Exception {
        downloadBatch(Collections.singletonList(new DownloadRequest(remoteFile, localFile)));
    }

    public void downloadBatch(List<DownloadRequest> requests) throws Exception {
        if (requests == null || requests.isEmpty()) {
            return;
        }

        Session session = null;
        ChannelSftp sftp = null;

        try {
            session = connectedSession();

            sftp = (ChannelSftp) session.openChannel("sftp");
            sftp.connect(connectTimeoutMs);

            for (DownloadRequest request : requests) {
                downloadWithOpenChannel(sftp, profileAwareRemotePath(request.remoteFile), request.localFile);
            }
        } catch (Exception e) {
            throw new RuntimeException("SFTP batch download failed. files=" + requests.size()
                    + ", cause=" + e.getMessage(), e);
        } finally {
            if (sftp != null && sftp.isConnected()) {
                sftp.disconnect();
            }
        }
    }

    private Session connectedSession() throws Exception {
        String profile = SftpProfileContext.currentProfile();
        if (sharedSession != null && sharedSession.isConnected() && profile.equals(sharedSessionProfile)) {
            return sharedSession;
        }
        closeSharedSession();

        SftpConnectionConfig config = activeConfig();
        JSch jsch = createJsch(config);
        sharedSession = jsch.getSession(config.user, config.host, config.port);
        configureSession(sharedSession, config);
        sharedSession.connect(connectTimeoutMs);
        sharedSessionProfile = profile;
        return sharedSession;
    }

    private void closeSharedSession() {
        if (sharedSession != null && sharedSession.isConnected()) {
            sharedSession.disconnect();
        }
        sharedSession = null;
        sharedSessionProfile = null;
    }

    private void downloadWithOpenChannel(ChannelSftp sftp, String remoteFile, String localFile) throws Exception {
        if (remoteFile == null || remoteFile.trim().isEmpty()) {
            throw new RuntimeException("Remote file path is empty");
        }
        if (localFile == null || localFile.trim().isEmpty()) {
            throw new RuntimeException("Local file path is empty");
        }

        File target = new File(localFile);
        File parent = target.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new RuntimeException("Unable to create local log directory: " + parent.getAbsolutePath());
        }

        String tempFile = localFile + ".part";
        try {
            sftp.get(remoteFile, tempFile);
            moveTempFile(tempFile, localFile);
        } catch (Exception e) {
            deleteQuietly(tempFile);
            throw e;
        }
    }

    private JSch createJsch(SftpConnectionConfig config) throws Exception {
        JSch jsch = new JSch();
        if (hasText(config.key)) {
            if (hasText(config.passphrase)) {
                jsch.addIdentity(config.key, config.passphrase);
            } else {
                jsch.addIdentity(config.key);
            }
        } else if (!hasText(config.password)) {
            throw new RuntimeException("SFTP key/password is missing. Check configuration!");
        }
        return jsch;
    }

    private void configureSession(Session session, SftpConnectionConfig config) throws Exception {
        if (hasText(config.password)) {
            session.setPassword(config.password);
        }
        session.setConfig("StrictHostKeyChecking", "no");
        session.setTimeout(commandTimeoutMs);
        session.setServerAliveInterval(keepAliveIntervalMs);
        session.setServerAliveCountMax(2);
    }

    private SftpConnectionConfig activeConfig() {
        return SftpProfileContext.isRabbitNordics() ? rabbitNordicsConfig : defaultConfig;
    }

    private String profileAwareCommand(String command) {
        if (SftpProfileContext.isApigeeRest()) {
            return command == null ? null : command.replace(defaultLogDir, resolvedApigeeRestLogDir());
        }

        if (!SftpProfileContext.isRabbitNordics()) {
            return command;
        }

        String remoteLogPath = resolvedRabbitNordicsRemoteLogPath();
        if (!hasText(remoteLogPath)) {
            return command;
        }

        if (command != null && command.contains("find . -maxdepth 1 -type f")) {
            return rabbitNordicsAuditDiscoveryCommand(remoteLogPath);
        }

        return command == null ? null : command.replace(defaultLogDir, remoteDirectory(remoteLogPath));
    }

    private String profileAwareRemotePath(String remoteFile) {
        if (SftpProfileContext.isApigeeRest() && remoteFile != null) {
            return remoteFile.replace(defaultLogDir, resolvedApigeeRestLogDir());
        }

        if (!SftpProfileContext.isRabbitNordics() || remoteFile == null) {
            return remoteFile;
        }
        String remoteLogPath = resolvedRabbitNordicsRemoteLogPath();
        if (!hasText(remoteLogPath)) {
            return remoteFile;
        }
        return remoteFile.replace(defaultLogDir, remoteDirectory(remoteLogPath));
    }

    private String rabbitNordicsAuditDiscoveryCommand(String remoteLogPath) {
        String remoteDir = remoteDirectory(remoteLogPath);
        String auditFile = fileName(remoteLogPath);
        StringBuilder inner = new StringBuilder("cd " + shellQuote(remoteDir));
        inner.append(" && find . -maxdepth 1 -type f -name ")
                .append(shellQuote(auditFile))
                .append(" -mtime -")
                .append(modifiedWithinDays)
                .append(" -print0 | while IFS= read -r -d '' f; do ")
                .append("stat_out=$(stat -c '%s %Y' \"$f\" 2>&1); stat_rc=$?; ")
                .append("if [ $stat_rc -eq 0 ]; then set -- $stat_out; size=\"$1\"; mtime=\"$2\"; ")
                .append("else size=0; mtime=0; printf 'ACCESS\t%s\tSTAT_FAILED\t%s\n' \"$f\" \"$stat_out\"; fi; ")
                .append("printf 'FILE\t%s\t%s\t%s\n' \"$f\" \"$size\" \"$mtime\"; ")
                .append("if [ ! -r \"$f\" ]; then printf 'ACCESS\t%s\tNOT_READABLE\tread permission denied\n' \"$f\"; fi; ")
                .append("done");
        return "bash -lc " + shellQuote(inner.toString());
    }

    private String resolvedRabbitNordicsRemoteLogPath() {
        String value = rabbitNordicsRemoteLogPath == null ? "" : rabbitNordicsRemoteLogPath.trim();
        if (value.isEmpty()) {
            return value;
        }
        String today = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE);
        return trimTrailingSlash(value
                .replace("<todays date>", today)
                .replace("${today}", today)
                .replace("{today}", today));
    }

    private String resolvedApigeeRestLogDir() {
        return trimTrailingSlash(apigeeRestLogDir);
    }

    private String remoteDirectory(String path) {
        String normalized = trimTrailingSlash(path);
        int slash = normalized.lastIndexOf('/');
        return slash <= 0 ? normalized : normalized.substring(0, slash);
    }

    private String fileName(String path) {
        String normalized = path == null ? "" : path.trim();
        int slash = normalized.lastIndexOf('/');
        return slash < 0 ? normalized : normalized.substring(slash + 1);
    }

    private String trimTrailingSlash(String value) {
        if (value == null) {
            return "";
        }

        String trimmed = value.trim();
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

    private void drainAvailable(InputStream input, ByteArrayOutputStream output) throws Exception {
        byte[] buffer = new byte[4096];
        while (input.available() > 0) {
            int read = input.read(buffer);
            if (read < 0) {
                break;
            }
            output.write(buffer, 0, read);
        }
    }

    private void drainAvailable(
            InputStream input,
            ByteArrayOutputStream output,
            FileOutputStream fileOutput) throws Exception {
        byte[] buffer = new byte[4096];
        while (input.available() > 0) {
            int read = input.read(buffer);
            if (read < 0) {
                break;
            }
            output.write(buffer, 0, read);
            fileOutput.write(buffer, 0, read);
            fileOutput.flush();
        }
    }

    private void writeText(FileOutputStream output, String value) throws Exception {
        if (value == null || value.isEmpty()) {
            return;
        }
        output.write(value.getBytes(StandardCharsets.UTF_8));
        output.flush();
    }

    private List<String> lines(String value) throws Exception {
        List<String> lines = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new java.io.ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8)),
                        StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                lines.add(line);
            }
        }
        return lines;
    }

    private long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    private void moveTempFile(String tempFile, String localFile) {
        File temp = new File(tempFile);
        File target = new File(localFile);

        if (!temp.exists() || !temp.isFile() || temp.length() == 0) {
            throw new RuntimeException("Downloaded temp file is missing or empty: " + tempFile);
        }

        if (target.exists() && !target.delete()) {
            throw new RuntimeException("Unable to replace existing local file: " + localFile);
        }

        if (!temp.renameTo(target)) {
            throw new RuntimeException("Unable to move temp file to final path: " + localFile);
        }
    }

    private void deleteQuietly(String path) {
        try {
            File file = new File(path);
            if (file.exists() && file.isFile()) {
                file.delete();
            }
        } catch (RuntimeException ignored) {
        }
    }

    public static class DownloadRequest {
        private final String remoteFile;
        private final String localFile;

        public DownloadRequest(String remoteFile, String localFile) {
            this.remoteFile = remoteFile;
            this.localFile = localFile;
        }
    }

    private static final class SftpConnectionConfig {
        private final String host;
        private final int port;
        private final String user;
        private final String password;
        private final String key;
        private final String passphrase;

        private SftpConnectionConfig(
                String host,
                int port,
                String user,
                String password,
                String key,
                String passphrase) {
            this.host = host;
            this.port = port;
            this.user = user;
            this.password = password;
            this.key = key;
            this.passphrase = passphrase;
        }
    }
}
