package com.hcl.observability.log;

import com.hcl.observability.sftp.SftpService;
import com.hcl.observability.sftp.SftpCommandResult;
import com.hcl.observability.trace.UnifiedTraceContextHolder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class LogAnalyzerService {

    private static final Logger log = Logger.getLogger(LogAnalyzerService.class.getName());

    private static final Pattern JOB_ID_PATTERN = Pattern.compile("\\bJobI[Dd]\\s*[:=]\\s*([A-Za-z0-9._:-]{2,})");
    private static final Pattern CORR_ID_PATTERN = Pattern
            .compile("\\b(?:CorrI[Dd]|JMSCorrelationID)\\s*[:=]\\s*([A-Za-z0-9._:-]{20,})");
    private final SftpService sftp;
    private final LogCleanupService logCleanupService;
    private final UnifiedTraceContextHolder traceContextHolder;
    private final String logDir;
    private final String localDir;
    private final String remoteRunAs;
    private final int grepRetryCount;
    private final long grepRetryWaitMs;
    private final int modifiedWithinDays;
    private final int correlationMaxDepth;
    private final int correlationMaxTokens;
    private final int correlationMaxJobIds;
    private final int correlationMaxTokensPerSearch;
    private final int correlationMaxBatchesPerDepth;
    private final int blockMaxMatchesPerFile;
    private final int localMinBlockLines;
    private final boolean logNameFilterEnabled;
    private final boolean unifiedTraceReportEnabled;
    private final Set<String> downloadedLocalFiles = ConcurrentHashMap.newKeySet();
    private final ConcurrentMap<String, List<RemoteLogFile>> bookingFileCache = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, List<RemoteLogFile>> corrFileCache = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, String> downloadedFileSignatures = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Object> downloadLocks = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, List<String>> matchingLineCache = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, SftpCommandResult> remoteBlockResultCache = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Set<String>> handledRemoteFilesByBooking = new ConcurrentHashMap<>();
    private final Set<String> printedFileSectionBookings = ConcurrentHashMap.newKeySet();
    private final Set<String> printedRetrySectionBookings = ConcurrentHashMap.newKeySet();
    private final Set<String> printedLocalReuseDecisions = ConcurrentHashMap.newKeySet();

    public LogAnalyzerService(
            SftpService sftp,
            LogCleanupService logCleanupService,
            UnifiedTraceContextHolder traceContextHolder,
            @Value("${sftp.payload.log.dir}") String logDir,
            @Value("${local.log.dir}") String localDir,
            @Value("${sftp.remote.run.as:}") String remoteRunAs,
            @Value("${sftp.grep.retry.count:3}") int grepRetryCount,
            @Value("${sftp.grep.retry.wait.ms:5000}") long grepRetryWaitMs,
            @Value("${sftp.search.modified.within.days:15}") int modifiedWithinDays,
            @Value("${sftp.correlation.max.depth:3}") int correlationMaxDepth,
            @Value("${sftp.correlation.token-limit:${sftp.correlation.max.tokens:100}}") int correlationMaxTokens,
            @Value("${sftp.correlation.max.jobids:50}") int correlationMaxJobIds,
            @Value("${sftp.correlation.tokens-per-search:${sftp.correlation.max.tokens-per-search:${sftp.correlation.max.tokens.per.search:3}}}") int correlationMaxTokensPerSearch,
            @Value("${sftp.correlation.max.batches.per.depth:3}") int correlationMaxBatchesPerDepth,
            @Value("${sftp.block.max.matches.per.file:10}") int blockMaxMatchesPerFile,
            @Value("${sftp.local.min.block.lines:10}") int localMinBlockLines,
            @Value("${sftp.log.name.filter.enabled:true}") boolean logNameFilterEnabled,
            @Value("${unified.trace.report.enabled:false}") boolean unifiedTraceReportEnabled) {
        this.sftp = sftp;
        this.logCleanupService = logCleanupService;
        this.traceContextHolder = traceContextHolder;
        this.logDir = trimTrailingSlash(logDir);
        this.localDir = localDir;
        this.remoteRunAs = remoteRunAs;
        this.grepRetryCount = Math.max(1, grepRetryCount);
        this.grepRetryWaitMs = Math.max(0, grepRetryWaitMs);
        this.modifiedWithinDays = Math.max(1, modifiedWithinDays);
        this.correlationMaxDepth = Math.max(1, correlationMaxDepth);
        this.correlationMaxTokens = Math.max(10, correlationMaxTokens);
        this.correlationMaxJobIds = Math.max(1, correlationMaxJobIds);
        this.correlationMaxTokensPerSearch = Math.max(1, correlationMaxTokensPerSearch);
        this.correlationMaxBatchesPerDepth = Math.max(1, correlationMaxBatchesPerDepth);
        this.blockMaxMatchesPerFile = Math.max(1, blockMaxMatchesPerFile);
        this.localMinBlockLines = Math.max(1, localMinBlockLines);
        this.logNameFilterEnabled = logNameFilterEnabled;
        this.unifiedTraceReportEnabled = unifiedTraceReportEnabled;
    }

    public List<String> fetchLogs(String bookingId) throws Exception {
        return fetchLogsDetailed(bookingId).getLines();
    }

    public LogSearchResult fetchLogsDetailed(String bookingId) throws Exception {
        logCleanupService.cleanupExpiredLogs();
        cleanupBookingCaches(bookingId);
        handledRemoteFilesByBooking.remove(bookingId);
        printedFileSectionBookings.remove(bookingId);
        printedRetrySectionBookings.remove(bookingId);
        printedLocalReuseDecisions.removeIf(key -> key.startsWith(bookingId + "|"));
        LogSearchResult result = grepDownloadAndRead("BookingID", bookingId, bookingId, bookingId, false);
        bookingFileCache.put(bookingId, toRemoteFiles(result.getRemoteFiles()));
        return result;
    }

    private void cleanupBookingCaches(String bookingId) {
        if (!hasText(bookingId)) {
            return;
        }

        cleanupPartialLocalArtifacts(bookingId);

        String bookingDirPrefix = (localDir + "/" + bookingId + "/").replace("\\", "/");
        matchingLineCache.keySet().removeIf(key -> normalizedPath(key).startsWith(bookingDirPrefix));
        downloadedLocalFiles.removeIf(path -> normalizedPath(path).startsWith(bookingDirPrefix));
        downloadedFileSignatures.keySet().removeIf(path -> normalizedPath(path).startsWith(bookingDirPrefix));
        downloadLocks.keySet().removeIf(path -> normalizedPath(path).startsWith(bookingDirPrefix));
        remoteBlockResultCache.keySet().removeIf(key -> key.startsWith(bookingId + "|"));
        printedLocalReuseDecisions.removeIf(key -> key.startsWith(bookingId + "|"));
        bookingFileCache.remove(bookingId);
        corrFileCache.remove(bookingId);
    }

    private void cleanupPartialLocalArtifacts(String bookingId) {
        File bookingDir = new File(localDir + "/" + bookingId);
        File[] partialFiles = bookingDir.listFiles(file -> file.isFile() && isPartialLocalArtifact(file.getName()));
        if (partialFiles == null) {
            return;
        }

        for (File partialFile : partialFiles) {
            deleteLocalFile(partialFile.getAbsolutePath());
        }
    }

    private String normalizedPath(String value) {
        return value == null ? "" : value.replace("\\", "/");
    }

    public LogSearchResult analyze(String bookingId) throws Exception {
        return analyzeRecursive(bookingId);
    }

    public LogSearchResult analyzeRecursive(String bookingId) throws Exception {
        if (!hasText(bookingId)) {
            return emptyScopedResult("Recursive Trace", "BookingID is required for recursive correlation");
        }

        long analyzerStartNanos = System.nanoTime();
        progress("LogAnalyzer started");
        logCleanupService.cleanupExpiredLogs();
        cleanupBookingCaches(bookingId);
        handledRemoteFilesByBooking.remove(bookingId);
        printedFileSectionBookings.remove(bookingId);
        printedRetrySectionBookings.remove(bookingId);
        printedLocalReuseDecisions.removeIf(key -> key.startsWith(bookingId + "|"));

        RecursiveResultAccumulator accumulator = new RecursiveResultAccumulator();
        Set<String> correlationTokens = new LinkedHashSet<>();
        Set<String> jobIds = new LinkedHashSet<>();
        Set<String> corrIds = new LinkedHashSet<>();
        Set<String> searchedTokens = new LinkedHashSet<>();
        correlationTokens.add(bookingId);
        int reachedDepth = 1;

        LogSearchResult bookingResult = grepDownloadAndRead("BookingID", bookingId, bookingId, bookingId, false);
        accumulator.add(bookingResult);
        bookingFileCache.put(bookingId, toRemoteFiles(bookingResult.getRemoteFiles()));
        searchedTokens.add(bookingId);

        addCorrelationIds(correlationTokens, jobIds, corrIds, bookingResult.getLines());
        if (isLocalOnlyReuse(bookingResult)) {
            List<String> localCorpusLines = readRelevantLocalLines(bookingId, correlationTokens);
            accumulator.lines.addAll(localCorpusLines);
            addCorrelationIds(correlationTokens, jobIds, corrIds, localCorpusLines);
            progress("Recursive correlation stopped Reason=LOCAL_COMPLETE Depth=1"
                    + " Tokens=" + correlationTokens.size()
                    + " JobIDs=" + jobIds.size()
                    + " CorrIDs=" + corrIds.size()
                    + " Files=" + bookingResult.getCompleteLocalFiles());
            String message = "Recursive correlation completed from local complete evidence: Depth=1"
                    + ", JobIDs=" + jobIds.size()
                    + ", CorrIDs=" + corrIds.size()
                    + ", Tokens=" + correlationTokens.size()
                    + ", localFiles=" + bookingResult.getCompleteLocalFiles()
                    + ", lines=" + accumulator.lines.size();
            progress("LogAnalyzer completed in " + elapsedMs(analyzerStartNanos) + " ms");
            return accumulator.toResult(message);
        }

        expandCorrelationTokensFromLocalFiles(bookingId, correlationTokens);

        for (int depth = 1; depth < correlationMaxDepth; depth++) {
            if (jobIds.size() >= correlationMaxJobIds) {
                progress("Recursive correlation stopped Reason=JOB_ID_LIMIT_REACHED Depth=" + depth
                        + " JobIDs=" + jobIds.size()
                        + " Limit=" + correlationMaxJobIds);
                break;
            }
            boolean depthDiscoveredNewIds = false;
            int batchesAtDepth = 0;
            while (searchedTokens.size() < correlationMaxTokens
                    && batchesAtDepth < correlationMaxBatchesPerDepth) {
                List<String> pendingTokens = pendingCorrelationTokens(correlationTokens, searchedTokens, bookingId);
                if (pendingTokens.isEmpty()) {
                    progress("Recursive correlation stopped Reason=NO_PENDING_TOKENS Depth=" + depth
                            + " Tokens=" + correlationTokens.size());
                    break;
                }

                List<String> traceTokens = limitedPendingTokens(pendingTokens,
                        Math.min(correlationMaxTokens - searchedTokens.size(), correlationMaxTokensPerSearch));
                if (traceTokens.isEmpty()) {
                    progress("Recursive correlation stopped Reason=TOKEN_BUDGET_EXHAUSTED Depth=" + depth
                            + " Tokens=" + correlationTokens.size());
                    break;
                }

                searchedTokens.addAll(traceTokens);
                reachedDepth = depth + 1;
                batchesAtDepth++;
                progress("Trace expansion Depth=" + (depth + 1)
                        + " Batch=" + batchesAtDepth
                        + " JobIDs=" + jobIds.size()
                        + " CorrIDs=" + corrIds.size()
                        + " PendingTokens=" + traceTokens.size());

                String traceExpression = traceSearchExpression(traceTokens);
                String traceDisplayValue = "Depth=" + depth + " Batch=" + batchesAtDepth
                        + " Tokens=" + traceTokens.size();
                LogSearchResult traceResult = grepDownloadAndRead(
                        "Trace",
                        traceExpression,
                        traceDisplayValue,
                        bookingId,
                        true);
                accumulator.add(traceResult);
                int tokenCountBeforeExpansion = correlationTokens.size();
                IdDelta traceDelta = addCorrelationIds(correlationTokens, jobIds, corrIds, traceResult.getLines());
                expandCorrelationTokensFromLocalFiles(bookingId, correlationTokens);
                if (!traceDelta.isEmpty() || correlationTokens.size() > tokenCountBeforeExpansion) {
                    depthDiscoveredNewIds = true;
                }
            }

            if (batchesAtDepth >= correlationMaxBatchesPerDepth
                    && !pendingCorrelationTokens(correlationTokens, searchedTokens, bookingId).isEmpty()) {
                progress("Recursive correlation paused Reason=BATCH_LIMIT_REACHED Depth=" + (depth + 1)
                        + " Batches=" + batchesAtDepth
                        + " BatchLimit=" + correlationMaxBatchesPerDepth
                        + " Tokens=" + correlationTokens.size()
                        + " SearchedTokens=" + searchedTokens.size());
            }

            if (!depthDiscoveredNewIds) {
                progress("Recursive correlation stopped Reason=NO_NEW_IDS Depth=" + (depth + 1)
                        + " Tokens=" + correlationTokens.size()
                        + " JobIDs=" + jobIds.size()
                        + " CorrIDs=" + corrIds.size()
                        + " Files=" + accumulator.remoteFiles.size());
                break;
            }
        }

        accumulator.lines.addAll(readRelevantLocalLines(bookingId, correlationTokens));

        String message = "Recursive correlation completed: Depth=" + reachedDepth
                + ", JobIDs=" + jobIds.size()
                + ", CorrIDs=" + corrIds.size()
                + ", Tokens=" + correlationTokens.size()
                + ", searchedTokens=" + searchedTokens.size()
                + ", uniqueFiles=" + accumulator.remoteFiles.size()
                + ", lines=" + accumulator.lines.size();
        progress("LogAnalyzer completed in " + elapsedMs(analyzerStartNanos) + " ms");
        return accumulator.toResult(message);
    }

    public List<String> searchByCorrelation(String corrId, String bookingId) throws Exception {
        return searchByCorrelationDetailed(corrId, bookingId).getLines();
    }

    public LogSearchResult searchByCorrelationDetailed(String corrId, String bookingId) throws Exception {
        return emptyScopedResult("CorrID",
                "CorrID standalone remote search is skipped; final trace search combines BookingID, CorrID, and JobID");
    }

    public List<String> searchByJobId(String jobId, String bookingId) throws Exception {
        return searchByJobIdDetailed(jobId, bookingId).getLines();
    }

    public LogSearchResult searchByJobIdDetailed(String jobId, String bookingId) throws Exception {
        return searchFinalTraceDetailed(bookingId, null, jobId);
    }

    public LogSearchResult searchFinalTraceDetailed(String bookingId, String corrId, String jobId) throws Exception {
        List<String> tokens = new ArrayList<>();
        if (hasUsableBookingId(bookingId)) {
            tokens.add(bookingId);
        }
        if (hasText(corrId)) {
            tokens.add(corrId);
        }
        if (hasText(jobId)) {
            tokens.add(jobId);
        }
        if (tokens.isEmpty()) {
            return emptyScopedResult("Final Trace",
                    "No BookingID, CorrID, or JobID values available for final trace search");
        }

        String expression = String.join("\n", tokens);
        String evidenceScope = evidenceScope(bookingId, corrId, jobId);
        LogSearchResult result = grepDownloadAndRead("Final Trace", expression,
                "BKGID=" + defaultValue(bookingId)
                        + " CorrID=" + defaultValue(corrId)
                        + " JobID=" + defaultValue(jobId),
                evidenceScope,
                true);
        return result;
    }

    public List<String> searchByCorrelation(String corrId) throws Exception {
        return searchByCorrelation(corrId, corrId);
    }

    public List<String> searchByJobId(String jobId) throws Exception {
        return searchByJobId(jobId, jobId);
    }

    private LogSearchResult emptyScopedResult(String label, String message) {
        return new LogSearchResult(new ArrayList<>(), false, 0, 0, 0,
                0, 0, 0, 100, new ArrayList<>(), 0, 0, 0, 0,
                label + " skipped: " + message);
    }

    private List<String> pendingCorrelationTokens(
            Set<String> correlationTokens,
            Set<String> searchedTokens,
            String bookingId) {
        List<String> pendingTokens = new ArrayList<>();

        for (String token : correlationTokens) {
            if (!hasText(token) || token.equals(bookingId) || searchedTokens.contains(token)) {
                continue;
            }
            pendingTokens.add(token);
        }

        return pendingTokens;
    }

    private boolean isLocalOnlyReuse(LogSearchResult result) {
        return result != null
                && !result.getLines().isEmpty()
                && result.getAttempts() == 0
                && result.getFilesTransferred() == 0
                && result.getCompleteLocalFiles() > 0
                && result.getRemoteFiles().isEmpty();
    }

    private List<String> limitedPendingTokens(List<String> pendingTokens, int remainingTokenBudget) {
        if (pendingTokens == null || pendingTokens.isEmpty() || remainingTokenBudget <= 0) {
            return Collections.emptyList();
        }

        List<String> orderedTokens = new ArrayList<>(pendingTokens);
        orderedTokens.sort((left, right) -> Boolean.compare(isUuidLike(left), isUuidLike(right)));
        int limit = Math.min(orderedTokens.size(), remainingTokenBudget);
        return new ArrayList<>(orderedTokens.subList(0, limit));
    }

    private boolean isUuidLike(String value) {
        return value != null && value.indexOf('-') >= 0 && value.length() >= 20;
    }

    private String traceSearchExpression(List<String> traceTokens) {
        List<String> quotedTokens = new ArrayList<>();

        for (String token : traceTokens) {
            if (hasText(token)) {
                quotedTokens.add(token);
            }
        }

        return String.join("\n", quotedTokens);
    }

    private IdDelta addCorrelationIds(
            Set<String> tokens,
            Set<String> jobIds,
            Set<String> corrIds,
            Iterable<String> lines) {
        IdDelta delta = new IdDelta();
        if (lines == null) {
            return delta;
        }

        for (String line : lines) {
            addTypedMatches(tokens, jobIds, JOB_ID_PATTERN.matcher(line), delta, true);
            addTypedMatches(tokens, corrIds, CORR_ID_PATTERN.matcher(line), delta, false);
            if (tokens.size() >= correlationMaxTokens) {
                break;
            }
        }

        return delta;
    }

    private void addTypedMatches(
            Set<String> tokens,
            Set<String> typedTokens,
            Matcher matcher,
            IdDelta delta,
            boolean jobId) {
        while (matcher.find()) {
            String value = normalizeCorrelationToken(matcher.group(1));
            if (!hasText(value)) {
                continue;
            }
            if (jobId && !typedTokens.contains(value) && typedTokens.size() >= correlationMaxJobIds) {
                continue;
            }
            boolean addedTypedToken = typedTokens.add(value);
            tokens.add(value);
            if (addedTypedToken) {
                if (jobId) {
                    delta.jobIds++;
                } else {
                    delta.corrIds++;
                }
            }
        }
    }

    private void addCorrelationTokensFromLocalFiles(String bookingId, Set<String> tokens) throws Exception {
        for (File file : localBookingFiles(bookingId)) {
            try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    addMatches(tokens, JOB_ID_PATTERN.matcher(line));
                    addMatches(tokens, CORR_ID_PATTERN.matcher(line));
                    if (tokens.size() >= correlationMaxTokens) {
                        return;
                    }
                }
            }
        }
    }

    private void expandCorrelationTokensFromLocalFiles(String bookingId, Set<String> tokens) throws Exception {
        int previousSize;
        int pass = 0;
        do {
            previousSize = tokens.size();
            addCorrelationTokensFromLocalFiles(bookingId, tokens);
            pass++;
        } while (tokens.size() > previousSize
                && tokens.size() < correlationMaxTokens
                && pass < correlationMaxDepth);
    }

    private List<String> readRelevantLocalLines(String bookingId, Set<String> tokens) throws Exception {
        Set<String> lines = new LinkedHashSet<>();

        for (File file : localBookingFiles(bookingId)) {
            try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (hasText(line)) {
                        lines.add(line);
                    }
                }
            }
        }

        return new ArrayList<>(lines);
    }

    private List<File> localBookingFiles(String bookingId) {
        if (!hasText(bookingId)) {
            return Collections.emptyList();
        }

        List<File> result = new ArrayList<>();
        File bookingDir = new File(localDir + "/" + bookingId);
        File[] files = bookingDir.listFiles(file -> file.isFile()
                && file.length() > 0
                && isScopedEvidenceFile(file.getName(), bookingId));
        if (files != null && files.length > 0) {
            List<File> directoryFiles = new ArrayList<>(java.util.Arrays.asList(files));
            directoryFiles.sort((left, right) -> left.getName().compareToIgnoreCase(right.getName()));
            result.addAll(directoryFiles);
        }
        return result;
    }

    private boolean isScopedEvidenceFile(String fileName, String bookingId) {
        if (fileName == null) {
            return false;
        }
        if (isPartialLocalArtifact(fileName)) {
            return false;
        }
        if (fileName.startsWith("processed_files_")) {
            return false;
        }
        return !fileName.equals(safeScope(bookingId) + ".log");
    }

    private void addMatches(Set<String> tokens, Matcher matcher) {
        while (matcher.find()) {
            String value = normalizeCorrelationToken(matcher.group(1));
            if (hasText(value)) {
                tokens.add(value);
            }
        }
    }

    private String normalizeCorrelationToken(String value) {
        if (value == null) {
            return "";
        }

        return value.trim().replaceAll("[,;\\])}]+$", "");
    }

    private LogSearchResult grepDownloadAndRead(
            String label,
            String searchValue,
            String displayValue,
            String bookingId,
            boolean extendedRegex) throws Exception {
        Set<String> matchedLines = new LinkedHashSet<>();
        Set<String> allUniqueFiles = new LinkedHashSet<>();
        Map<String, RemoteLogFile> allMatchedRemoteFiles = new java.util.TreeMap<>();
        int totalBlockLines = 0;
        int attemptsUsed = 0;
        boolean partialCoverage = false;
        StringBuilder warnings = new StringBuilder();
        AttemptSnapshot previousSnapshot = null;
        long sshConnectTimeMs = 0;
        long fileListTimeMs = 0;
        long remoteGrepTimeMs = 0;
        long downloadTimeMs = 0;
        int filesTransferred = 0;
        int completeLocalFiles = 0;

        if (searchValue == null || searchValue.trim().isEmpty()) {
            return new LogSearchResult(new ArrayList<>(), false, 0, 0, 0,
                    0, 0, 0, 100, new ArrayList<>(), 0, 0, 0, 0, "Search value is empty");
        }

        String evidenceScope = hasText(bookingId) ? bookingId : "UNSCOPED";
        File processedTrackerFile = processedTrackerFile(evidenceScope);
        Map<String, String> processedFiles = readProcessedTracker(processedTrackerFile);

        LogSearchResult localFastResult = localCompleteEvidenceResult(label, searchValue, evidenceScope, extendedRegex);
        if (localFastResult != null) {
            logRetry(label, bookingId, 1, "FOUND_LOCAL", "STOP");
            return localFastResult;
        }

        long fileListStartNanos = System.nanoTime();
        List<RemoteLogFile> allRecentFiles = discoverRecentRemoteFiles();
        fileListTimeMs += elapsedMs(fileListStartNanos);

        for (int attempt = 1; attempt <= grepRetryCount; attempt++) {
            attemptsUsed = attempt;

            long remoteGrepStartNanos = System.nanoTime();
            progress("LogAnalyzer remote grep started Stage=" + label
                    + " Attempt=" + attempt + "/" + grepRetryCount);
            RemoteMatchScanResult scanResult = scanRemoteMatches(
                    label,
                    searchValue,
                    extendedRegex,
                    allRecentFiles,
                    evidenceScope,
                    processedTrackerFile,
                    processedFiles,
                    warnings);
            List<RemoteLogFile> matchedRemoteFiles = scanResult.matchedRemoteFiles;
            List<RemoteLogFile> skippedProcessedFiles = scanResult.skippedProcessedFiles;
            sshConnectTimeMs += scanResult.sshConnectTimeMs;
            partialCoverage = partialCoverage || scanResult.partialCoverage;
            completeLocalFiles += scanResult.completeLocalFiles;
            matchedRemoteFiles.sort(Comparator
                    .comparingLong((RemoteLogFile file) -> file.modifiedEpoch)
                    .thenComparing(file -> file.remotePath, String.CASE_INSENSITIVE_ORDER));
            remoteGrepTimeMs += elapsedMs(remoteGrepStartNanos);
            Set<String> uniqueFiles = remoteFileNames(matchedRemoteFiles);
            uniqueFiles.addAll(remoteFileNames(skippedProcessedFiles));

            progress(label + " grep completed"
                    + " Attempt=" + attempt + "/" + grepRetryCount
                    + " Files=" + uniqueFiles.size()
                    + " SkippedProcessed=" + scanResult.skippedAlreadyProcessed
                    + " TimeMs=" + elapsedMs(remoteGrepStartNanos));

            addRemoteFileRecords(allUniqueFiles, allMatchedRemoteFiles, uniqueFiles,
                    matchedRemoteFiles, skippedProcessedFiles);

            long downloadStartNanos = System.nanoTime();
            FilterTransferResult transferResult = sequentialTransferMatchedFiles(
                    label,
                    searchValue,
                    extendedRegex,
                    matchedRemoteFiles,
                    evidenceScope,
                    processedTrackerFile,
                    processedFiles);
            downloadTimeMs += elapsedMs(downloadStartNanos);
            matchedLines.addAll(transferResult.lines);
            totalBlockLines += transferResult.linesFetched;
            logTransferProgress(label, matchedRemoteFiles, skippedProcessedFiles,
                    transferResult, elapsedMs(downloadStartNanos));
            filesTransferred += transferResult.filesFetched;
            completeLocalFiles += transferResult.completeLocalFiles;

            if (!matchedRemoteFiles.isEmpty()
                    && transferResult.filesFetched == 0
                    && transferResult.filesRestored == 0) {
                String failureMessage = label + " transfer failed: remote block extraction returned "
                        + matchedRemoteFiles.size() + " matched files"
                        + " files, but no local evidence file was transferred. "
                        + "Status=FAIL Reason=ZERO_FILE_TRANSFER";
                throw new LogTransferException(failureMessage,
                        stageResult(matchedLines, partialCoverage, attempt, totalBlockLines,
                                allUniqueFiles.size(), filesTransferred, completeLocalFiles,
                                allMatchedRemoteFiles, sshConnectTimeMs, fileListTimeMs,
                                remoteGrepTimeMs, downloadTimeMs, failureMessage));
            }

            if (matchedLines.isEmpty()) {
                matchedLines.addAll(readLocalMatchingLines(bookingId, searchValue, extendedRegex));
                if (!matchedLines.isEmpty()) {
                    logRetry(label, bookingId, attempt, "FOUND_LOCAL", "STOP");
                    break;
                }
            }

            if (matchedRemoteFiles.isEmpty() && !skippedProcessedFiles.isEmpty()) {
                logRetry(label, bookingId, attempt, "FOUND_LOCAL", "STOP");
                break;
            }

            if (!uniqueFiles.isEmpty() && !partialCoverage) {
                logRetry(label, bookingId, attempt, "FOUND", "STOP");
                break;
            }

            if (attempt == grepRetryCount || (!matchedLines.isEmpty() && uniqueFiles.isEmpty())) {
                logRetry(label, bookingId, attempt, "NOT_FOUND", "STOP");
                break;
            }

            AttemptSnapshot currentSnapshot = AttemptSnapshot.from(matchedRemoteFiles, new ArrayList<>(matchedLines),
                    matchedLines.size());
            if (previousSnapshot != null && !currentSnapshot.hasChangedSince(previousSnapshot)) {
                appendWarning(warnings,
                        "Retry stopped early because no new files, JobIDs, CorrIDs, or matching log lines appeared");
                logRetry(label, bookingId, attempt, "NO_CHANGE", "STOP");
                break;
            }
            previousSnapshot = currentSnapshot;

            logRetry(label, bookingId, attempt, "NOT_FOUND", "RETRY");
            waitForRetry(label, "no matching unprocessed files returned yet", attempt);
        }

        String message = buildResultMessage(matchedLines.size(), totalBlockLines, allUniqueFiles.size(),
                attemptsUsed, warnings);
        return new LogSearchResult(new ArrayList<>(matchedLines), partialCoverage, attemptsUsed,
                totalBlockLines, allUniqueFiles.size(), filesTransferred, completeLocalFiles,
                allRecentFiles.size(), allRecentFiles.size(), 0, 100,
                remoteFileRecords(new ArrayList<>(allMatchedRemoteFiles.values())),
                sshConnectTimeMs, fileListTimeMs, remoteGrepTimeMs, downloadTimeMs,
                message);
    }

    private LogSearchResult stageResult(
            Set<String> matchedLines,
            boolean partialCoverage,
            int attempts,
            int totalFilesFound,
            int uniqueFilesFound,
            int filesTransferred,
            int completeLocalFiles,
            Map<String, RemoteLogFile> matchedRemoteFiles,
            long sshConnectTimeMs,
            long fileListTimeMs,
            long remoteGrepTimeMs,
            long downloadTimeMs,
            String message) {
        return new LogSearchResult(new ArrayList<>(matchedLines), partialCoverage, attempts,
                totalFilesFound, uniqueFilesFound, filesTransferred, completeLocalFiles,
                0, 0, 0, 100,
                remoteFileRecords(new ArrayList<>(matchedRemoteFiles.values())),
                sshConnectTimeMs, fileListTimeMs, remoteGrepTimeMs, downloadTimeMs,
                message);
    }

    private LogSearchResult localCompleteEvidenceResult(
            String label,
            String searchValue,
            String bookingId,
            boolean extendedRegex) throws Exception {
        Set<String> lines = new LinkedHashSet<>();
        int completeFiles = 0;
        int matchedFiles = 0;

        for (File localFile : localBookingFiles(bookingId)) {
            if (!hasUsableLocalEvidence(localFile, searchValue, extendedRegex)) {
                continue;
            }

            boolean completeLocalEvidence = isCompleteLocalEvidence(localFile);
            if (requiresCompleteLocalEvidence(label) && !completeLocalEvidence) {
                continue;
            }
            matchedFiles++;
            if (completeLocalEvidence) {
                completeFiles++;
            }
            logLocalReuseDecision(label, localFile, completeLocalEvidence);
            lines.addAll(readFilteredLocalLines(localFile.getAbsolutePath()));
        }

        if (lines.isEmpty()) {
            return null;
        }

        String message = "Local complete evidence reused: lines=" + lines.size()
                + ", files=" + matchedFiles
                + ", remote skipped=true";
        return new LogSearchResult(new ArrayList<>(lines), false, 0,
                lines.size(), matchedFiles, 0, completeFiles,
                matchedFiles, matchedFiles, 0, 100,
                Collections.emptyList(), 0, 0, 0, 0, message);
    }

    private boolean requiresCompleteLocalEvidence(String label) {
        return "BookingID".equals(label);
    }

    private void logLocalReuseDecision(String label, File localFile, boolean completeLocalEvidence) {
        if (localFile == null) {
            return;
        }

        String reason = completeLocalEvidence ? "LOCAL_COMPLETE" : "LOCAL_PRESENT";
        String bookingKey = bookingKeyFromLocalFile(localFile.getAbsolutePath());
        String decisionKey = bookingKey + "|" + localFile.getName() + "|" + reason;
        String line = "File=" + padRight(localFile.getName(), 48)
                + " Exists=Y Action=SKIP Reason="
                + reason;
        traceContextHolder.addFileLine(line);
        if (!printedLocalReuseDecisions.add(decisionKey)) {
            return;
        }

        printFileSection(localFile.getAbsolutePath());
        if (unifiedTraceReportEnabled) {
            System.out.println("[FILES] " + line);
            progress("Remote scan skipped Stage=" + label
                    + " Reason=" + reason
                    + " File=" + localFile.getName());
        } else {
            System.out.println(line);
        }
    }

    private List<RemoteLogFile> discoverRecentRemoteFiles() throws Exception {
        SftpCommandResult result = sftp.executeCommand(recentFilesCommand());
        handleCommandStatus("Remote file discovery", result);
        logDiscoveryAccessIssues(result.getOutput());
        return uniqueRemoteFiles(result.getOutput());
    }

    private RemoteMatchScanResult scanRemoteMatches(
            String label,
            String searchValue,
            boolean extendedRegex,
            List<RemoteLogFile> allRecentFiles,
            String evidenceScope,
            File processedTrackerFile,
            Map<String, String> processedFiles,
            StringBuilder warnings) throws Exception {
        RemoteMatchScanResult result = new RemoteMatchScanResult();
        for (RemoteLogFile remoteFile : allRecentFiles) {
            File uniqueLocalFile = uniqueLocalEvidenceFile(evidenceScope, remoteFile);
            if (!isRemoteFileWithinModifiedWindow(remoteFile)) {
                logDownloadDecision(label, remoteFile.fullPath(logDir),
                        uniqueLocalFile.getAbsolutePath(), false,
                        "SKIPPED - remote file outside modified window");
                continue;
            }

            boolean processedInTracker = isProcessedInTracker(processedFiles, remoteFile);
            boolean uniqueLocalUsable = hasUsableLocalEvidence(uniqueLocalFile, searchValue, extendedRegex);
            if (uniqueLocalUsable) {
                if (!processedInTracker) {
                    appendProcessedTracker(processedTrackerFile, remoteFile);
                    processedFiles.put(remoteFile.fullPath(logDir), String.valueOf(remoteFile.modifiedEpoch));
                }
                result.skippedAlreadyProcessed++;
                result.skippedProcessedFiles.add(remoteFile);
                if (isCompleteLocalEvidence(uniqueLocalFile)) {
                    result.completeLocalFiles++;
                }
                logDownloadDecision(label, remoteFile.fullPath(logDir),
                        uniqueLocalFile.getAbsolutePath(), true,
                        "SKIPPED - unique local file already present");
                continue;
            }

            SftpCommandResult matchResult = sftp.executeCommand(
                    grepQuietCommand(searchValue, extendedRegex, remoteFile));
            result.sshConnectTimeMs += matchResult.getSshConnectTimeMs();
            if (matchResult.getExitStatus() == 0) {
                result.matchedRemoteFiles.add(remoteFile);
            } else if (matchResult.getExitStatus() > 1) {
                result.partialCoverage = true;
                String errorText = oneLine(matchResult.getError());
                if (!hasText(errorText)) {
                    errorText = oneLine(String.join(" ", matchResult.getOutput()));
                }
                String reason = matchResult.hasErrorText("permission denied")
                        || errorText.toLowerCase().contains("permission denied")
                                ? "ACCESS_DENIED"
                                : "GREP_FAILED";
                logDownloadDecision(label, remoteFile.fullPath(logDir),
                        uniqueLocalFile.getAbsolutePath(), false, reason);
                appendWarning(warnings, label + " grep match check failed for "
                        + remoteFile.remotePath + ": " + errorText);
            }
        }
        return result;
    }

    private void addRemoteFileRecords(
            Set<String> allUniqueFiles,
            Map<String, RemoteLogFile> allMatchedRemoteFiles,
            Set<String> uniqueFiles,
            List<RemoteLogFile> matchedRemoteFiles,
            List<RemoteLogFile> skippedProcessedFiles) {
        allUniqueFiles.addAll(uniqueFiles);
        for (RemoteLogFile remoteFile : matchedRemoteFiles) {
            allMatchedRemoteFiles.put(remoteFile.remotePath, remoteFile);
        }
        for (RemoteLogFile remoteFile : skippedProcessedFiles) {
            allMatchedRemoteFiles.put(remoteFile.remotePath, remoteFile);
        }
    }

    private void logTransferProgress(
            String label,
            List<RemoteLogFile> matchedRemoteFiles,
            List<RemoteLogFile> skippedProcessedFiles,
            FilterTransferResult transferResult,
            long elapsedMs) {
        if (matchedRemoteFiles.isEmpty() && !skippedProcessedFiles.isEmpty()) {
            progress("Filtered transfer skipped Stage=" + label
                    + " Reason=ALL_MATCHES_LOCAL_PRESENT"
                    + " SkippedFiles=" + skippedProcessedFiles.size()
                    + " Files=0 Restored=0 Lines=0"
                    + " TimeMs=" + elapsedMs);
            return;
        }

        progress("Filtered transfer completed Stage=" + label
                + " Files=" + transferResult.filesFetched
                + " Restored=" + transferResult.filesRestored
                + " Lines=" + transferResult.linesFetched
                + " TimeMs=" + elapsedMs);
    }

    private FilterTransferResult sequentialTransferMatchedFiles(
            String label,
            String searchValue,
            boolean extendedRegex,
            List<RemoteLogFile> matchedFiles,
            String evidenceScope,
            File processedTrackerFile,
            Map<String, String> processedFiles) throws Exception {
        FilterTransferResult result = new FilterTransferResult();
        if (matchedFiles == null || matchedFiles.isEmpty()) {
            return result;
        }

        for (RemoteLogFile remoteFile : matchedFiles) {
            File uniqueLocalFile = uniqueLocalEvidenceFile(evidenceScope, remoteFile);
            if (!isRemoteFileWithinModifiedWindow(remoteFile)) {
                logDownloadDecision(label, remoteFile.fullPath(logDir), uniqueLocalFile.getAbsolutePath(),
                        false, "SKIPPED - remote file outside modified window");
                continue;
            }
            boolean uniqueLocalPhysicalExists = uniqueLocalFile.exists()
                    && uniqueLocalFile.isFile()
                    && uniqueLocalFile.length() > 0;
            boolean uniqueLocalUsable = hasUsableLocalEvidence(uniqueLocalFile, searchValue, extendedRegex);
            boolean processedInTracker = isProcessedInTracker(processedFiles, remoteFile);
            if (processedInTracker && uniqueLocalUsable) {
                logDownloadDecision(label, remoteFile.fullPath(logDir), uniqueLocalFile.getAbsolutePath(),
                        true, "SKIPPED - unique local file already present");
                if (isCompleteLocalEvidence(uniqueLocalFile)) {
                    result.completeLocalFiles++;
                }
                continue;
            }

            logDownloadDecision(label, remoteFile.fullPath(logDir), uniqueLocalFile.getAbsolutePath(),
                    uniqueLocalPhysicalExists, "FILTER_FETCH");
            SftpCommandResult transferResult = appendGrepContext(
                    searchValue, extendedRegex, remoteFile);
            List<String> transferredLines = transferResult.getOutput();

            if (!isCompleteTransferEvidence(transferredLines, searchValue, extendedRegex)) {
                SftpCommandResult fallbackResult = appendFallbackGrep(searchValue, extendedRegex,
                        remoteFile);
                transferredLines = fallbackResult.getOutput();
                if (!isCompleteTransferEvidence(transferredLines, searchValue, extendedRegex)) {
                    throw new RuntimeException("Local transfer validation failed for "
                            + remoteFile.fullPath(logDir)
                            + ": output did not contain required JobID/CorrID evidence");
                }
            }

            int writtenLines = materializeUniqueLocalFile(uniqueLocalFile, transferredLines);
            if (writtenLines == 0) {
                throw new RuntimeException("Unique local transfer wrote zero lines: "
                        + uniqueLocalFile.getAbsolutePath());
            }

            appendProcessedTracker(processedTrackerFile, remoteFile);
            processedFiles.put(remoteFile.fullPath(logDir), String.valueOf(remoteFile.modifiedEpoch));
            result.lines.addAll(transferredLines);
            result.filesFetched++;
            result.linesFetched += transferredLines.size();
            if (isCompleteLocalEvidence(uniqueLocalFile)) {
                result.completeLocalFiles++;
            }
            downloadedLocalFiles.add(uniqueLocalFile.getAbsolutePath());
            downloadedFileSignatures.put(uniqueLocalFile.getAbsolutePath(), remoteFile.signature());
            markRemoteFileHandled(uniqueLocalFile.getName(), remoteFile);
        }

        return result;
    }

    private File uniqueLocalEvidenceFile(String evidenceScope, RemoteLogFile remoteFile) {
        String scope = hasText(evidenceScope) ? evidenceScope : "UNSCOPED";
        return new File(new File(localDir, safeScope(scope)), remoteFile.fileName());
    }

    private boolean hasUsableLocalEvidence(File localFile, String searchValue, boolean extendedRegex) throws Exception {
        if (localFile == null || !localFile.exists() || !localFile.isFile() || localFile.length() == 0) {
            return false;
        }
        List<String> localLines = readFilteredLocalLines(localFile.getAbsolutePath());
        if (containsLegacyGrepContextLines(localLines)) {
            return false;
        }
        return isCompleteTransferEvidence(localLines, searchValue, extendedRegex);
    }

    private boolean isCompleteLocalEvidence(File localFile) throws Exception {
        if (localFile == null || !localFile.exists() || !localFile.isFile() || localFile.length() == 0) {
            return false;
        }
        List<String> localLines = readFilteredLocalLines(localFile.getAbsolutePath());
        return !containsLegacyGrepContextLines(localLines) && isCompleteExecutionBlock(localLines);
    }

    private int materializeUniqueLocalFile(File uniqueLocalFile, List<String> transferredLines) throws Exception {
        if (uniqueLocalFile == null) {
            throw new RuntimeException("Unique local transfer target is missing");
        }
        if (transferredLines == null || transferredLines.isEmpty()) {
            throw new RuntimeException("Unique local transfer received no extracted lines: "
                    + uniqueLocalFile.getAbsolutePath());
        }

        List<String> existingLines = uniqueLocalFile.exists() && uniqueLocalFile.isFile()
                ? readFilteredLocalLines(uniqueLocalFile.getAbsolutePath())
                : Collections.emptyList();
        if (containsLegacyGrepContextLines(existingLines) && !containsLegacyGrepContextLines(transferredLines)) {
            existingLines = Collections.emptyList();
        }
        List<String> mergedLines = mergeExistingThenTransferred(existingLines, transferredLines);
        return transferExtractedBlock(uniqueLocalFile.getName(), uniqueLocalFile.getAbsolutePath(), mergedLines);
    }

    private boolean containsLegacyGrepContextLines(List<String> lines) {
        if (lines == null || lines.isEmpty()) {
            return false;
        }
        for (String line : lines) {
            if (line != null && line.matches("^\\d+[:-].*")) {
                return true;
            }
        }
        return false;
    }

    private SftpCommandResult appendGrepContext(
            String searchValue,
            boolean extendedRegex,
            RemoteLogFile remoteFile) throws Exception {
        return sftp.executeCommand(grepContextCommand(searchValue, extendedRegex, remoteFile));
    }

    private SftpCommandResult appendFallbackGrep(
            String searchValue,
            boolean extendedRegex,
            RemoteLogFile remoteFile) throws Exception {
        return sftp.executeCommand(grepFallbackCommand(searchValue, extendedRegex, remoteFile));
    }

    private boolean isCompleteTransferEvidence(
            List<String> transferredLines,
            String searchValue,
            boolean extendedRegex) {
        if (transferredLines == null || transferredLines.isEmpty()) {
            return false;
        }

        List<String> tokens = searchTokens(searchValue, extendedRegex);
        if (tokens.isEmpty()) {
            return false;
        }

        for (String line : transferredLines) {
            for (String token : tokens) {
                if (hasText(token) && line != null && line.contains(token)) {
                    return true;
                }
            }
        }
        return false;
    }

    private Map<String, String> readProcessedTracker(File trackerFile) throws Exception {
        Map<String, String> processed = new java.util.LinkedHashMap<>();
        if (trackerFile == null || !trackerFile.exists() || !trackerFile.isFile()) {
            return processed;
        }

        try (BufferedReader reader = new BufferedReader(new FileReader(trackerFile))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String[] parts = line.split("\\|", 2);
                if (parts.length == 2 && hasText(parts[0]) && hasText(parts[1])) {
                    processed.put(parts[0].trim(), parts[1].trim());
                }
            }
        }
        return processed;
    }

    private void appendProcessedTracker(File trackerFile, RemoteLogFile remoteFile) throws Exception {
        File parent = trackerFile.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new RuntimeException("Unable to create local log directory: " + parent.getAbsolutePath());
        }

        try (BufferedWriter writer = new BufferedWriter(new FileWriter(trackerFile, true))) {
            writer.write(remoteFile.fullPath(logDir) + "|" + remoteFile.modifiedEpoch);
            writer.newLine();
        }
    }

    private boolean isProcessedInTracker(Map<String, String> processedFiles, RemoteLogFile remoteFile) {
        if (processedFiles == null || remoteFile == null) {
            return false;
        }

        String trackedTimestamp = processedFiles.get(remoteFile.fullPath(logDir));
        return trackedTimestamp != null && trackedTimestamp.equals(String.valueOf(remoteFile.modifiedEpoch));
    }

    private File processedTrackerFile(String bookingId) {
        return new File(localDir, "processed_files_" + safeScope(bookingId) + ".txt");
    }

    private void handleCommandStatus(String label, SftpCommandResult commandResult) {
        boolean hasReturnedFiles = !commandResult.getOutput().isEmpty();

        if (commandResult.hasErrorText("COMMAND_TIMEOUT")) {
            return;
        }

        if (commandResult.hasErrorText("permission denied") && !hasReturnedFiles) {
            throw new RuntimeException(label + " remote grep failed: permission denied while reading "
                    + logDir + ". Give SSH user read/execute access to the log directory/files, "
                    + "or configure passwordless sudo and set sftp.remote.run.as.");
        }

        if (commandResult.getExitStatus() > 1 && !hasReturnedFiles) {
            throw new RuntimeException(label + " remote grep failed with exit status "
                    + commandResult.getExitStatus() + ": " + commandResult.getError());
        }

    }

    private void logDiscoveryAccessIssues(List<String> discoveryOutput) {
        if (discoveryOutput == null || discoveryOutput.isEmpty()) {
            return;
        }

        for (String line : discoveryOutput) {
            if (line == null || !line.startsWith("ACCESS\t")) {
                continue;
            }

            String[] fields = line.split("\\t", 4);
            String file = fields.length > 1 ? fields[1] : "UNKNOWN";
            String reason = fields.length > 2 ? fields[2] : "ACCESS_ISSUE";
            String detail = fields.length > 3 ? fields[3] : "";
            String message = "Remote discovery access issue File=" + fileName(file)
                    + " Reason=" + reason
                    + (hasText(detail) ? " Detail=" + oneLine(detail) : "");
            log.warning(message);
            System.out.println("[WARN] " + message);
            traceContextHolder.addFileLine("File=" + padRight(fileName(file), 48)
                    + " Exists=N Action=ACCESS_DENIED Reason=" + reason);
        }
    }

    private void waitForRetry(String label, String reason, int attempt) throws InterruptedException {
        long waitMs = retryBackoffMs(attempt);
        if (waitMs <= 0) {
            return;
        }

        Thread.sleep(waitMs);
    }

    private long retryBackoffMs(int attempt) {
        if (attempt <= 1) {
            return 2_000L;
        }
        if (attempt == 2) {
            return 5_000L;
        }
        if (attempt == 3) {
            return 10_000L;
        }
        return grepRetryWaitMs;
    }

    private void progress(String message) {
        if (unifiedTraceReportEnabled) {
            System.out.println("[PROGRESS] " + message);
        }
    }

    private void logRetry(String label, String bookingId, int attempt, String result, String action) {
        printRetrySection(bookingId);
        String line = "Stage=" + padRight(retryStage(label), 10)
                + " Attempt=" + attempt + "/" + grepRetryCount
                + " Result=" + result
                + " Action=" + action;
        traceContextHolder.addRetryLine(line);
        if (!unifiedTraceReportEnabled) {
            System.out.println(line);
        }
    }

    private String retryStage(String label) {
        return "BookingID".equals(label) ? "BookingID" : "Trace";
    }

    private void printRetrySection(String bookingId) {
        String bookingKey = bookingId == null ? "UNKNOWN" : bookingId;
        if (!unifiedTraceReportEnabled && printedRetrySectionBookings.add(bookingKey)) {
            System.out.println();
            System.out.println("-------------------- RETRY LOGIC -----------------------");
            System.out.println("[RETRY]");
        }
    }

    private int transferExtractedBlock(String remoteFile, String localFile, List<String> blockLines) throws Exception {
        String fileName = fileName(remoteFile);
        int extractedLines = blockLines == null ? 0 : blockLines.size();
        log.info("Transferring file=" + fileName + " lines=" + extractedLines);
        System.out.println("[TRANSFER] Transferring file=" + fileName + " lines=" + extractedLines);
        int writtenLines = writeFilteredLines(localFile, blockLines);
        log.info("Written file=" + new File(localFile).getName() + " lines=" + writtenLines);
        System.out.println("[TRANSFER] Written file=" + new File(localFile).getName()
                + " lines=" + writtenLines
                + " path=" + localFile);
        return writtenLines;
    }

    private int writeFilteredLines(String localFile, List<String> lines) throws Exception {
        if (lines == null || lines.isEmpty()) {
            throw new RuntimeException("Filtered transfer received no extracted block lines: " + localFile);
        }

        File target = new File(localFile);
        File parent = target.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new RuntimeException("Unable to create local log directory: " + parent.getAbsolutePath());
        }

        int writtenLines = 0;
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(target))) {
            for (String line : lines) {
                writer.write(line);
                writer.newLine();
                writtenLines++;
            }
        }

        if (!target.exists() || !target.isFile() || target.length() == 0) {
            throw new RuntimeException("Filtered transfer produced no local lines: " + localFile);
        }
        if (writtenLines == 0) {
            throw new RuntimeException("Filtered transfer wrote zero lines: " + localFile);
        }
        return writtenLines;
    }

    private void deleteLocalFile(String localFile) {
        File target = new File(localFile);
        if (target.exists() && target.isFile() && !target.delete()) {
            throw new RuntimeException("Unable to remove incomplete local log file: " + localFile);
        }
    }

    private List<String> mergeExistingThenTransferred(List<String> existingLines, List<String> transferredLines) {
        List<String> merged = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (String line : existingLines) {
            if (line != null && (!hasText(line) || seen.add(line))) {
                merged.add(line);
            }
        }
        for (String line : transferredLines) {
            if (line != null && (!hasText(line) || seen.add(line))) {
                merged.add(line);
            }
        }
        return merged;
    }

    private boolean isCompleteExecutionBlock(List<String> lines) {
        if (lines == null || lines.isEmpty()) {
            return false;
        }

        int requestIndex = -1;
        int processIndex = -1;
        int replyIndex = -1;
        int publishIndex = -1;
        int errorIndex = -1;
        int lineIndex = 0;
        for (String line : lines) {
            String upper = line == null ? "" : line.toUpperCase();
            if (requestIndex < 0 && containsAnyPhase(upper, "REQUEST")) {
                requestIndex = lineIndex;
            }
            if (processIndex < 0 && containsAnyPhase(upper, "PROCESS", "PROCESSING", "NOTIFY")) {
                processIndex = lineIndex;
            }
            if (replyIndex < 0 && containsAnyPhase(upper, "REPLY", "RESPONSE")) {
                replyIndex = lineIndex;
            }
            if (publishIndex < 0 && containsAnyPhase(upper, "PUBLISH", "PUBLISHED")) {
                publishIndex = lineIndex;
            }
            if (errorIndex < 0 && containsAnyPhase(upper, "ERROR", "EXCEPTION", "STACKTRACE")) {
                errorIndex = lineIndex;
            }
            lineIndex++;
        }

        int terminalIndex = Math.max(publishIndex, replyIndex);
        boolean phaseComplete = requestIndex >= 0 && terminalIndex > requestIndex;
        if (phaseComplete) {
            return true;
        }

        boolean errorComplete = requestIndex >= 0 && errorIndex >= requestIndex;
        if (errorComplete) {
            return true;
        }

        return lines.size() >= localMinBlockLines
                && requestIndex >= 0
                && (terminalIndex > requestIndex || errorIndex >= requestIndex);
    }

    private boolean containsAnyPhase(String upperLine, String... phases) {
        if (upperLine == null || phases == null) {
            return false;
        }

        for (String phase : phases) {
            if (phase != null && upperLine.contains(phase)) {
                return true;
            }
        }
        return false;
    }

    private boolean isPartialLocalArtifact(String fileName) {
        if (fileName == null) {
            return false;
        }

        return fileName.endsWith(".part")
                || fileName.endsWith(".partial")
                || fileName.endsWith(".partial.previous");
    }

    private List<String> readFilteredLocalLines(String localFile) throws Exception {
        List<String> lines = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new FileReader(localFile))) {
            String line;
            while ((line = reader.readLine()) != null) {
                lines.add(line);
            }
        }
        return lines;
    }

    private List<RemoteLogFile> uniqueRemoteFiles(List<String> files) {
        Map<String, RemoteLogFile> uniqueFiles = new java.util.TreeMap<>();

        for (String remoteFile : files) {
            if (remoteFile == null || remoteFile.startsWith("ACCESS\t")) {
                continue;
            }
            RemoteLogFile parsed = RemoteLogFile.from(remoteFile);
            if (!parsed.remotePath.isEmpty()) {
                uniqueFiles.put(parsed.remotePath, parsed);
            }
        }

        return new ArrayList<>(uniqueFiles.values());
    }

    private List<RemoteLogFile> toRemoteFiles(List<String> remoteFiles) {
        List<RemoteLogFile> files = new ArrayList<>();
        if (remoteFiles == null) {
            return files;
        }

        for (String remoteFile : remoteFiles) {
            RemoteLogFile parsed = RemoteLogFile.from(remoteFile);
            if (!parsed.remotePath.isEmpty()) {
                files.add(parsed);
            }
        }
        return files;
    }

    private Set<String> remoteFileNames(List<RemoteLogFile> files) {
        Set<String> names = new LinkedHashSet<>();

        for (RemoteLogFile file : files) {
            names.add(file.remotePath);
        }

        return names;
    }

    private List<String> remoteFileRecords(List<RemoteLogFile> remoteFiles) {
        List<String> records = new ArrayList<>();

        for (RemoteLogFile remoteFile : remoteFiles) {
            records.add(remoteFile.record());
        }

        return records;
    }

    private boolean isRemoteFileWithinModifiedWindow(RemoteLogFile remoteFile) {
        if (remoteFile == null || remoteFile.modifiedEpoch <= 0) {
            return true;
        }

        long nowEpochSeconds = System.currentTimeMillis() / 1000;
        long cutoffEpochSeconds = nowEpochSeconds - (modifiedWithinDays * 86_400L);
        return remoteFile.modifiedEpoch >= cutoffEpochSeconds;
    }

    private void markRemoteFileHandled(String bookingId, RemoteLogFile remoteFile) {
        handledRemoteFilesByBooking.computeIfAbsent(bookingId, key -> ConcurrentHashMap.newKeySet())
                .add(remoteFile.remotePath);
    }

    private List<String> readMatchingLines(
            String localFile,
            String searchValue,
            boolean extendedRegex,
            String remoteSignature) throws Exception {
        String cacheKey = cacheKey(localFile, searchValue, extendedRegex);
        List<String> cachedLines = matchingLineCache.get(cacheKey);
        if (cachedLines != null) {
            return cachedLines;
        }

        Set<String> lines = new LinkedHashSet<>();
        List<String> fixedTokens = extendedRegex ? fixedSearchTokens(searchValue) : Collections.emptyList();

        try (BufferedReader reader = new BufferedReader(new FileReader(localFile))) {
            String line;
            while ((line = reader.readLine()) != null) {
                boolean matches = extendedRegex ? containsAny(line, fixedTokens) : line.contains(searchValue);
                if (matches) {
                    lines.add(line);
                }
            }
        }

        List<String> result = Collections.unmodifiableList(new ArrayList<>(lines));
        matchingLineCache.put(cacheKey, result);
        return result;
    }

    private List<String> fixedSearchTokens(String searchValue) {
        List<String> tokens = new ArrayList<>();
        if (!hasText(searchValue)) {
            return tokens;
        }

        for (String token : searchValue.split("\\R")) {
            if (hasText(token)) {
                tokens.add(token.trim());
            }
        }
        return tokens;
    }

    private boolean containsAny(String line, List<String> tokens) {
        if (line == null || tokens == null || tokens.isEmpty()) {
            return false;
        }

        for (String token : tokens) {
            if (line.contains(token)) {
                return true;
            }
        }
        return false;
    }

    private List<String> readLocalMatchingLines(
            String bookingId,
            String searchValue,
            boolean extendedRegex) throws Exception {
        Set<String> lines = new LinkedHashSet<>();

        for (File file : localBookingFiles(bookingId)) {
            lines.addAll(readMatchingLines(file.getAbsolutePath(), searchValue, extendedRegex, "LOCAL"));
        }

        return new ArrayList<>(lines);
    }

    private String cacheKey(String localFile, String searchValue, boolean extendedRegex) {
        return localFile + "|" + extendedRegex + "|" + searchValue;
    }

    private void logDownloadDecision(
            String label,
            String remoteFile,
            String localFile,
            boolean localExists,
            String decision) {
        printFileSection(localFile);
        String line = "File=" + padRight(fileName(remoteFile), 48)
                + " Exists=" + yesNo(localExists)
                + " Action=" + decisionValue(decision)
                + " Reason=" + reasonValue(decision);
        traceContextHolder.addFileLine(line);
        if (unifiedTraceReportEnabled) {
            System.out.println("[FILES] " + line);
        } else {
            System.out.println(line);
        }
    }

    private void printFileSection(String localFile) {
        String bookingKey = bookingKeyFromLocalFile(localFile);
        if (!unifiedTraceReportEnabled && printedFileSectionBookings.add(bookingKey)) {
            System.out.println();
            System.out.println("-------------------- FILE HANDLING ---------------------");
            System.out.println("[FILES]");
        }
    }

    private String bookingKeyFromLocalFile(String localFile) {
        if (localFile == null || localFile.trim().isEmpty()) {
            return "UNKNOWN";
        }

        File file = new File(localFile);
        File parent = file.getParentFile();
        return parent == null ? "UNKNOWN" : parent.getName();
    }

    private String fileName(String path) {
        if (path == null || path.trim().isEmpty()) {
            return "";
        }
        return path.contains("/") ? path.substring(path.lastIndexOf("/") + 1) : path;
    }

    private String yesNo(boolean value) {
        return value ? "Y" : "N";
    }

    private String decisionValue(String decision) {
        if (decision == null) {
            return "UNKNOWN";
        }

        String normalized = decision.toUpperCase();
        if (normalized.contains("SKIP")) {
            return "SKIP";
        }
        if (normalized.contains("ACCESS_DENIED")) {
            return "ACCESS_DENIED";
        }
        if (normalized.contains("RESTORE")) {
            return "RESTORE";
        }
        if (normalized.contains("FILTER_FETCH")) {
            return "FILTER_FETCH";
        }
        if (normalized.contains("BLOCK_MERGE")) {
            return "FILTER_FETCH";
        }
        if (normalized.contains("PARTIAL_REFETCH")) {
            return "FILTER_FETCH";
        }
        return "FILTER_FETCH";
    }

    private String reasonValue(String decision) {
        if (decision == null) {
            return "UNKNOWN";
        }

        String normalized = decision.toUpperCase();
        if (normalized.contains("ALREADY HANDLED")) {
            return "FLOW_DEDUP";
        }
        if (normalized.contains("ACCESS_DENIED")) {
            return "PERMISSION_DENIED";
        }
        if (normalized.contains("BLOCK_MERGE") || normalized.contains("BLOCK CONTEXT")) {
            return "BLOCK_CONTEXT";
        }
        if (normalized.contains("PARTIAL_REFETCH") || normalized.contains("INCOMPLETE")) {
            return "LOCAL_PARTIAL";
        }
        if (normalized.contains("RESTORE")) {
            return "AGGREGATE_PRESENT";
        }
        if (normalized.contains("LOCAL")) {
            return "LOCAL_PRESENT";
        }
        if (normalized.contains("MODIFIED WINDOW")) {
            return "TIME_WINDOW";
        }
        if (normalized.contains("NO MATCHED LINES") || normalized.contains("NO BLOCK LINES")) {
            return "NO_HITS";
        }
        if (normalized.contains("FILTER_FETCH")) {
            return "REMOTE_HITS";
        }
        if (normalized.contains("SKIP")) {
            return "SKIPPED";
        }
        return "REMOTE_HITS";
    }

    private String padRight(String value, int width) {
        String safeValue = value == null ? "" : value;
        if (safeValue.length() >= width) {
            return safeValue;
        }
        return String.format("%-" + width + "s", safeValue);
    }

    private String recentFilesCommand() {
        StringBuilder inner = new StringBuilder("cd " + shellQuote(logDir));
        inner.append(" && find . -maxdepth 1 -type f ")
                .append(logNameFilterEnabled ? logNameFilter() : "-name '*.log*'")
                .append(" -mtime -")
                .append(modifiedWithinDays)
                .append(" -print0 | while IFS= read -r -d '' f; do ")
                .append("stat_out=$(stat -c '%s %Y' \"$f\" 2>&1); stat_rc=$?; ")
                .append("if [ $stat_rc -eq 0 ]; then set -- $stat_out; size=\"$1\"; mtime=\"$2\"; ")
                .append("else size=0; mtime=0; printf 'ACCESS\t%s\tSTAT_FAILED\t%s\n' \"$f\" \"$stat_out\"; fi; ")
                .append("printf 'FILE\t%s\t%s\t%s\n' \"$f\" \"$size\" \"$mtime\"; ")
                .append("if [ ! -r \"$f\" ]; then printf 'ACCESS\t%s\tNOT_READABLE\tread permission denied\n' \"$f\"; fi; ")
                .append("done");
        return remoteShellCommand(inner.toString());
    }

    private String grepQuietCommand(String searchValue, boolean extendedRegex, RemoteLogFile remoteFile) {
        String regex = grepSearchRegex(searchValue, extendedRegex);
        String inner = "grep -q -E -- " + shellQuote(regex)
                + " " + shellQuote(remoteFile.fullPath(logDir))
                + " 2>&1";
        return remoteShellCommand(inner);
    }

    private String grepContextCommand(String searchValue, boolean extendedRegex, RemoteLogFile remoteFile) {
        return remoteLogicalBlockCommand(searchValue, remoteFile);
    }

    private String grepFallbackCommand(String searchValue, boolean extendedRegex, RemoteLogFile remoteFile) {
        return remoteLogicalBlockCommand(searchValue, remoteFile);
    }

    private String remoteLogicalBlockCommand(String searchValue, RemoteLogFile remoteFile) {
        StringBuilder inner = new StringBuilder();
        inner.append("patterns=$(mktemp)")
                .append(" && trap 'rm -f \"$patterns\"' EXIT")
                .append(" && printf '%s\n' ")
                .append(shellQuote(searchValue))
                .append(" | sed '/^[[:space:]]*$/d' > \"$patterns\"")
                .append(" && awk -v patternFile=\"$patterns\" ")
                .append("-v maxStarts=")
                .append(blockMaxMatchesPerFile)
                .append(" ")
                .append(shellQuote(logicalBlockAwkScript()))
                .append(" ")
                .append(shellQuote(remoteFile.fullPath(logDir)))
                .append(" 2>&1");
        return remoteShellCommand(inner.toString());
    }

    private String grepSearchRegex(String searchValue, boolean extendedRegex) {
        List<String> tokens = searchTokens(searchValue, extendedRegex);
        if (tokens.isEmpty()) {
            return "$^";
        }

        List<String> escapedTokens = new ArrayList<>();
        for (String token : tokens) {
            escapedTokens.add(extendedRegexLiteral(token));
        }
        return String.join("|", escapedTokens);
    }

    private String logicalBlockAwkScript() {
        return "BEGIN { "
                + "IGNORECASE=1; "
                + "while ((getline p < patternFile) > 0) { if (p != \"\") token[++tokens]=p; } "
                + "close(patternFile); "
                + "} "
                + "function hasToken(s, i) { "
                + "for (i=1; i<=tokens; i++) { if (index(s, token[i]) > 0) return 1; } "
                + "return 0; "
                + "} "
                + "function recordStart(s) { "
                + "return (s ~ /^[0-9][0-9][0-9][0-9][ -][A-Za-z][A-Za-z][A-Za-z][ -][0-9][0-9]? [0-9][0-9]:[0-9][0-9]:[0-9][0-9]:[0-9][0-9][0-9]/ "
                + "|| s ~ /^[0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9][ T][0-9][0-9]:[0-9][0-9]:[0-9][0-9]/); "
                + "} "
                + "function terminal(s) { "
                + "return (s ~ /(^|[^A-Za-z0-9_])(REPLY|RESPONSE|PUBLISH|CONFIRM|ERROR|EXCEPTION|STACKTRACE)([^A-Za-z0-9_]|$)/); "
                + "} "
                + "function finalTerminal(s) { "
                + "return (s ~ /(^|[^A-Za-z0-9_])(PUBLISH|CONFIRM|ERROR|EXCEPTION|STACKTRACE)([^A-Za-z0-9_]|$)/); "
                + "} "
                + "function valueAfter(s, label, p) { "
                + "p=index(tolower(s), tolower(label)); "
                + "if (p <= 0) return \"\"; "
                + "s=substr(s, p + length(label)); "
                + "sub(/^[ \\t:=]+/, \"\", s); "
                + "sub(/[ \\t,;\\])}].*$/, \"\", s); "
                + "return s; "
                + "} "
                + "function extractJob(s, v) { "
                + "v=valueAfter(s, \"JobID\"); if (v == \"\") v=valueAfter(s, \"JobId\"); return v; "
                + "} "
                + "function extractCorr(s, v) { "
                + "v=valueAfter(s, \"CorrID\"); if (v == \"\") v=valueAfter(s, \"JMSCorrelationID\"); return v; "
                + "} "
                + "function appendRecordLine(s) { "
                + "rec[++recCount]=s; "
                + "recordText=recordText \"\\n\" s; "
                + "if (hasToken(s)) recHasToken=1; "
                + "if (recJob == \"\") recJob=extractJob(s); "
                + "if (recCorr == \"\") recCorr=extractCorr(s); "
                + "if (terminal(s)) recTerminal=1; "
                + "if (finalTerminal(s)) recFinalTerminal=1; "
                + "} "
                + "function recordRelated() { "
                + "return recHasToken "
                + "|| (activeCorr != \"\" && index(recordText, activeCorr) > 0) "
                + "|| (activeJob != \"\" && index(recordText, activeJob) > 0); "
                + "} "
                + "function emitRecord(i) { "
                + "for (i=1; i<=recCount; i++) print rec[i]; "
                + "} "
                + "function clearRecord(i) { "
                + "for (i=1; i<=recCount; i++) delete rec[i]; "
                + "recCount=0; recordText=\"\"; recHasToken=0; recJob=\"\"; recCorr=\"\"; recTerminal=0; recFinalTerminal=0; "
                + "} "
                + "function finishRecord(rel) { "
                + "if (recCount == 0) return; "
                + "rel=recordRelated(); "
                + "if (!capturing && recHasToken && starts < maxStarts) { "
                + "capturing=1; starts++; activeJob=recJob; activeCorr=recCorr; capturedRecords=1; "
                + "emitRecord(); if (recTerminal) terminalSeen=1; if (recFinalTerminal) finalSeen=1; "
                + "} else if (capturing && rel) { "
                + "if (activeJob == \"\") activeJob=recJob; if (activeCorr == \"\") activeCorr=recCorr; "
                + "capturedRecords++; emitRecord(); if (recTerminal) terminalSeen=1; if (recFinalTerminal) finalSeen=1; "
                + "} else if (capturing && !rel) { "
                + "if (!finalSeen && capturedRecords < 80) { "
                + "capturedRecords++; emitRecord(); if (recTerminal) terminalSeen=1; if (recFinalTerminal) finalSeen=1; "
                + "} else { "
                + "capturing=0; terminalSeen=0; finalSeen=0; capturedRecords=0; activeJob=\"\"; activeCorr=\"\"; "
                + "} "
                + "} "
                + "clearRecord(); "
                + "} "
                + "{ "
                + "if (recordStart($0)) finishRecord(); "
                + "appendRecordLine($0); "
                + "} "
                + "END { finishRecord(); }";
    }

    private List<String> searchTokens(String searchValue, boolean extendedRegex) {
        if (!hasText(searchValue)) {
            return Collections.emptyList();
        }

        if (extendedRegex) {
            return fixedSearchTokens(searchValue);
        }

        List<String> tokens = new ArrayList<>();
        tokens.add(searchValue.trim());
        return tokens;
    }

    private String logNameFilter() {
        return "\\( -name 'BookingDetails*.log*' "
                + "-o -name 'ManageBooking*.log*' "
                + "-o -name 'PostBookFlow*.log*' "
                + "-o -name 'BookFlow*.log*' "
                + "-o -name 'GIPBooking*.log*' "
                + "-o -name 'GMPBooking*.log*' "
                + "-o -name 'MongoDBBooking*.log*' "
                + "\\)";
    }

    private String remoteShellCommand(String inner) {
        if (remoteRunAs == null || remoteRunAs.trim().isEmpty()) {
            return "bash -lc " + shellQuote(inner);
        }

        return "sudo -u " + shellQuote(remoteRunAs) + " bash -lc " + shellQuote(inner);
    }

    private String trimTrailingSlash(String value) {
        if (value == null) {
            return "";
        }

        String trimmed = value.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    private String shellQuote(String value) {
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }

    private String extendedRegexLiteral(String value) {
        return value.replaceAll("([\\\\.\\[\\]{}()*+?^$|])", "\\\\$1");
    }

    private void appendWarning(StringBuilder warnings, String warning) {
        if (warning == null || warning.trim().isEmpty()) {
            return;
        }

        if (warnings.length() > 0) {
            warnings.append(" | ");
        }
        warnings.append(warning.trim());
    }

    private String buildResultMessage(
            int lineCount,
            int filesFound,
            int uniqueFilesFound,
            int attempts,
            StringBuilder warnings) {
        String message = "Logs found: " + lineCount
                + ", files returned: " + filesFound
                + ", unique files: " + uniqueFilesFound
                + ", attempts: " + attempts
                + ", modified window days: " + modifiedWithinDays;

        if (warnings.length() > 0) {
            message = message + ", warnings: " + warnings;
        }

        return message;
    }

    private String oneLine(String value) {
        if (value == null) {
            return "";
        }
        return value.replace('\r', ' ').replace('\n', ' ').trim();
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private String defaultValue(String value) {
        return hasText(value) ? value : "NA";
    }

    private boolean hasUsableBookingId(String value) {
        return hasText(value) && !"NA".equalsIgnoreCase(value.trim());
    }

    private String evidenceScope(String bookingId, String corrId, String jobId) {
        if (hasUsableBookingId(bookingId)) {
            return bookingId.trim();
        }
        if (hasText(jobId)) {
            return "JOB_" + safeScope(jobId);
        }
        if (hasText(corrId)) {
            return "CORR_" + safeScope(corrId);
        }
        return "UNSCOPED";
    }

    private String safeScope(String value) {
        return value == null ? "NA" : value.trim().replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    private static class RecursiveResultAccumulator {
        private final Set<String> lines = new LinkedHashSet<>();
        private final Map<String, String> remoteFiles = new java.util.TreeMap<>();
        private boolean partialCoverage;
        private int attempts;
        private int filesFound;
        private int filesTransferred;
        private int completeLocalFiles;
        private long sshConnectTimeMs;
        private long fileListTimeMs;
        private long remoteGrepTimeMs;
        private long downloadTimeMs;

        private void add(LogSearchResult result) {
            if (result == null) {
                return;
            }

            lines.addAll(result.getLines());
            partialCoverage = partialCoverage || result.isPartialCoverage();
            attempts += result.getAttempts();
            filesFound += result.getFilesFound();
            filesTransferred += result.getFilesTransferred();
            completeLocalFiles += result.getCompleteLocalFiles();
            sshConnectTimeMs += result.getSshConnectTimeMs();
            fileListTimeMs += result.getFileListTimeMs();
            remoteGrepTimeMs += result.getRemoteGrepTimeMs();
            downloadTimeMs += result.getDownloadTimeMs();

            for (String remoteFile : result.getRemoteFiles()) {
                RemoteLogFile parsed = RemoteLogFile.from(remoteFile);
                if (!parsed.remotePath.isEmpty()) {
                    remoteFiles.put(parsed.remotePath, parsed.record());
                }
            }
        }

        private LogSearchResult toResult(String message) {
            return new LogSearchResult(new ArrayList<>(lines), partialCoverage, attempts,
                    filesFound, remoteFiles.size(), filesTransferred, completeLocalFiles, 0, 0, 0, 100,
                    new ArrayList<>(remoteFiles.values()), sshConnectTimeMs,
                    fileListTimeMs, remoteGrepTimeMs, downloadTimeMs, message);
        }
    }

    private static class RemoteLogFile {
        private final String remotePath;
        private final long size;
        private final long modifiedEpoch;

        private RemoteLogFile(String remotePath, long size, long modifiedEpoch) {
            this.remotePath = normalize(remotePath);
            this.size = Math.max(0, size);
            this.modifiedEpoch = Math.max(0, modifiedEpoch);
        }

        private static RemoteLogFile from(String rawValue) {
            if (rawValue == null) {
                return new RemoteLogFile("", 0, 0);
            }

            if (rawValue.startsWith("FILE\t")) {
                String[] fields = rawValue.split("\\t", 4);
                if (fields.length >= 4) {
                    return new RemoteLogFile(fields[1], parseLong(fields[2]), parseLong(fields[3]));
                }
            }

            String[] fields = rawValue.trim().split("\\\\t|\\t|\\s+", 3);
            if (fields.length >= 3) {
                return new RemoteLogFile(fields[0], parseLong(fields[1]), parseLong(fields[2]));
            }

            return new RemoteLogFile(rawValue.trim(), 0, 0);
        }

        private String fullPath(String logDir) {
            return remotePath.startsWith("/") ? remotePath : logDir + "/" + remotePath;
        }

        private String fileName() {
            return remotePath.contains("/")
                    ? remotePath.substring(remotePath.lastIndexOf("/") + 1)
                    : remotePath;
        }

        private String signature() {
            return remotePath + "|" + size + "|" + modifiedEpoch;
        }

        private String record() {
            return remotePath + " " + size + " " + modifiedEpoch;
        }

        private static String normalize(String value) {
            String normalized = value == null ? "" : value.trim();
            if (normalized.startsWith("./")) {
                normalized = normalized.substring(2);
            }
            return normalized;
        }

        private static long parseLong(String value) {
            try {
                return Long.parseLong(value.trim());
            } catch (RuntimeException ignored) {
                return 0;
            }
        }
    }

    private static class RemoteMatchScanResult {
        private final List<RemoteLogFile> matchedRemoteFiles = new ArrayList<>();
        private final List<RemoteLogFile> skippedProcessedFiles = new ArrayList<>();
        private boolean partialCoverage;
        private int skippedAlreadyProcessed;
        private int completeLocalFiles;
        private long sshConnectTimeMs;
    }

    private static class FilterTransferResult {
        private final Set<String> lines = new LinkedHashSet<>();
        private int filesFetched;
        private int filesRestored;
        private int linesFetched;
        private int completeLocalFiles;
    }

    private static class IdDelta {
        private int jobIds;
        private int corrIds;

        private boolean isEmpty() {
            return jobIds == 0 && corrIds == 0;
        }
    }

    private static class AttemptSnapshot {
        private final Set<String> fileSignatures;
        private final Set<String> jobIds;
        private final Set<String> corrIds;
        private final int lineCount;

        private AttemptSnapshot(
                Set<String> fileSignatures,
                Set<String> jobIds,
                Set<String> corrIds,
                int lineCount) {
            this.fileSignatures = fileSignatures;
            this.jobIds = jobIds;
            this.corrIds = corrIds;
            this.lineCount = lineCount;
        }

        private static AttemptSnapshot from(List<RemoteLogFile> files, List<String> lines, int lineCount) {
            Set<String> signatures = new LinkedHashSet<>();
            for (RemoteLogFile file : files) {
                signatures.add(file.signature());
            }
            Set<String> jobIds = new LinkedHashSet<>();
            Set<String> corrIds = new LinkedHashSet<>();
            if (lines != null) {
                for (String line : lines) {
                    collectMatches(jobIds, JOB_ID_PATTERN.matcher(line));
                    collectMatches(corrIds, CORR_ID_PATTERN.matcher(line));
                }
            }
            return new AttemptSnapshot(signatures, jobIds, corrIds, lineCount);
        }

        private boolean hasChangedSince(AttemptSnapshot previous) {
            return lineCount != previous.lineCount
                    || !fileSignatures.equals(previous.fileSignatures)
                    || !jobIds.equals(previous.jobIds)
                    || !corrIds.equals(previous.corrIds);
        }

        private static void collectMatches(Set<String> values, Matcher matcher) {
            while (matcher.find()) {
                String value = matcher.group(1);
                if (value != null && !value.trim().isEmpty()) {
                    values.add(value.trim().replaceAll("[,;\\])}]+$", ""));
                }
            }
        }
    }

}
