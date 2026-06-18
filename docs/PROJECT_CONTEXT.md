# AI Integration Validation Platform - Project Context

## Final Flow

```text
POST /execute
      |
      v
ExecutionController
      |
      v
OrchestratorService
      |
      v
TestCaseExecutorService
      |
      v
Trigger (REST / EMS)
      |
      v
SftpService
      |
      v
LogAnalyzerService
      |
      v
Logs -> BookingID, JobID, CorrID -> Timeline
      |
      v
Validation
      |
      v
(Optional) AI Intelligence
      |
      v
Final Result
```

Implemented execution stages:
- API trigger with retry.
- SFTP grep by BookingID.
- Download only remote files matched by the BookingID grep.
- Read downloaded logs from `local.log.dir/{bookingId}/`.
- Extract BookingID, CorrID, and JobID context.
- Use CorrID and JobID to expand the trace.
- Run analyzer validation.
- Validate CorrID timeline.
- Print execution timing summary.
- Return final `ExecutionResult`.

Console timing format:

```text
==== EXECUTION START ====

[API] Time: {ms} ms
[LOG FETCH] Time: {ms} ms
[SSH CONNECT] Time: {ms} ms
[FILE LIST] Time: {ms} ms
[REMOTE GREP] Time: {ms} ms
[DOWNLOAD] Time: {ms} ms
[CORRELATION] Time: {ms} ms

==== EXECUTION END ====
Total Execution Time: {ms} ms ({sec} sec)
```

## Layer-By-Layer Validation Plan

Validate these layers in order. Do not move to the next layer until the current layer has a clear pass signal.

### 1. API Trigger

Purpose:
- Confirm `POST /execute` reaches `ExecutionController`.
- Confirm `OrchestratorService` calls `TestCaseExecutorService`.
- Confirm REST or EMS trigger runs before log lookup.

Run:

```text
POST /execute?bookingId={bookingId}&payload=tc_postbook.json
```

Pass signal:
- Response contains step `Trigger - REST` or `Trigger - EMS`.
- Trigger step status is `PASS`.

Fail signals:
- Trigger step status is `FAIL`.
- Missing `Trigger - ...` step means execution-core did not start trigger flow.

### 2. SFTP Download

Purpose:
- Search remote logs with BookingID as the primary key.
- Use remote grep plus file metadata to identify matching files and their size/mtime signature.
- Only scan files modified within `sftp.search.modified.within.days`; default is 30 days.
- Search only known booking log families with a direct `find ... -mtime ... -exec grep -l ...` command.
- Download matched BookingID, CorrID, and JobID files to `local.log.dir/{bookingId}/`.
- Parse downloaded local files with streaming line-by-line reads for CorrID, JobID, timeline, and validation.
- Download decision is local-presence based only: if the local file exists and is non-empty, skip download.
- Download to a temporary `.part` file first, then move to the final path only after a non-empty file is present.
- Always close SFTP channel/session in `finally`; download failures must include remote and local paths.
- Run remote grep as the SSH login user by default.
- Use `sftp.remote.run.as` only when the server allows passwordless non-interactive sudo.
- Skip duplicate downloads when the same remote file signature is already cached locally.
- Download multiple changed files in parallel, then read them in deterministic sorted file order.
- Retry remote grep when no matches are returned yet, because logs may still be written after the API trigger.
- Stop retry immediately on `Permission denied`; retry cannot fix remote ACLs.
- Retry only when no results are found; stop as soon as matching log lines exist.
- Stop retries immediately when matching files are returned.
- While retrying no-result searches, stop early when no new file signatures or matching log lines appear.

Pass signal:
- Response contains step `BookingID Log Search`.
- `BookingID Log Search` status is `PASS`.
- Local folder exists:

```text
C:/logs/{bookingId}/
```

- The folder contains only files returned by the remote BookingID grep.
- Repeated CorrID or JobID trace searches do not re-download files already present locally.

Fail signals:
- `BookingID Log Search` status is `FAIL`.
- `C:/logs/{bookingId}/` is missing or empty after remote matches are expected.
- Remote grep stderr contains `Permission denied`; this is an observability gap and means the SSH user needs read/execute access to the remote log path, or passwordless sudo must be configured with `sftp.remote.run.as`.
- `Observability Coverage` is `FAIL`; the distributed trace may miss CDS, GIP, Mongo, or subscriber hops.

Remote permission fix:
- The SSH/runtime user must have execute access on every parent directory in the log path.
- The SSH/runtime user must have read access on all `*.*` log files under `sftp.payload.log.dir`.
- If logs must be read as `tibco`, configure passwordless non-interactive sudo for only the grep/read commands, then set `sftp.remote.run.as=tibco`.

### 3. Log Read

Purpose:
- Confirm downloaded files are readable.
- Confirm log lines containing BookingID are collected for analysis.
- Deduplicate repeated log lines before analysis.

Pass signal:
- `BookingID Log Search` message reports `Logs found: {count}`.
- Count is greater than zero.

Fail signals:
- Count is zero.
- Execution result says no logs found for BookingID.

### 4. BookingID, CorrID, and JobID Extraction

Purpose:
- Use BookingID logs to extract CorrID and relevant JobID.
- Use CorrID and JobID to trace the full flow.
- Download additional CorrID and JobID matched files into the same `local.log.dir/{bookingId}/` folder.

Pass signal:
- `CorrID Extraction` status is `PASS`.
- `JobID Extraction` status is `PASS`.
- `Final Trace Search` is `PASS` or `WARN`.

Fail signals:
- `CorrID Extraction` status is `FAIL`.
- `JobID Extraction` status is `FAIL`.
- Missing `Final Trace Search` when CorrID or JobID exists.

### 5. Analyzer and Validation

Purpose:
- Validate basic log event shape.
- Validate CorrID timeline order.
- Treat `REPLY before REQUEST` as a hard timeline violation.
- Sort timestamped log events before validation; use original read sequence only when timestamps are unavailable.
- Validate each operation pair independently, for example `BookFlow/BookFlow_v3/CreateBooking_v3` and `BookFlow/Atcore_VRP/Booking`.
- Deduplicate repeated timeline events across repeated reads and rotated logs.
- Tag timeline entries by system/application/operation boundary.
- Nested flow is valid when it follows `REQUEST -> REQUEST -> REPLY -> REPLY`.
- Each operation must have at least one paired `REQUEST -> REPLY`.
- Decide final execution status.

Pass signal:
- `Analyzer Validation` status is `PASS`.
- `Timeline Validation` status is `PASS`.
- Final response `finalStatus` is `PASS`.

Fail signals:
- `Analyzer Validation` is `FAIL`.
- `Timeline Validation` is `FAIL`.
- `Timeline Validation` message contains `REPLY before REQUEST`.
- Final response `finalStatus` is `FAIL` or `ERROR`.

## Configuration Strategy

There is exactly one runtime configuration file:

```text
integration-api-gateway/src/main/resources/application.properties
```

All runtime configuration belongs there:

```properties
# SFTP
sftp.host=...
sftp.port=...
sftp.username=...
sftp.private.key=...
sftp.private.key.passphrase=...
sftp.remote.run.as=
sftp.payload.log.dir=...

# LOGS
local.log.dir=...
local.log.retention.days=7
sftp.grep.retry.count=3
sftp.grep.retry.wait.ms=5000
sftp.grep.retry.on.partial=true
sftp.download.full.files.enabled=true
sftp.search.modified.within.days=30

# API
rest.endpoint=...
rest.api.key=...

# EXECUTION
execution.retry.count=3
execution.wait.ms=3000
```

Strict rules:
- No config files in `integration-execution-core`.
- No config files in `integration-observability-core`.
- No hardcoded environment values in Java code.
- Everything is injected from the gateway runtime context.

## Data Handling

Input payloads are test data and live under the gateway resources folder:

```text
integration-api-gateway/src/main/resources/payloads/
    tc_postbook.json
    tc_cancel.json
    tc_update.json
```

Downloaded logs are runtime data and must not live inside any code module. Their location is controlled only by `local.log.dir`.

Final local log structure:

```text
C:/logs/
    31835146/
        file1.log
        file2.log
    31835147/
        file1.log
```

Cleanup policy:
- `local.log.retention.days` decides how long downloaded booking log folders are retained.
- `integration-observability-core` owns cleanup implementation.
- Cleanup may delete only child directories under the configured `local.log.dir`.

## Repository Structure

```text
ai-integration-validation-platform/
    pom.xml
    integration-api-gateway/
        src/main/java/com/hcl/gateway/
            Application.java
            controller/ExecutionController.java
            service/OrchestratorService.java
            config/
        src/main/resources/
            application.properties
            payloads/
    integration-execution-core/
        src/main/java/com/hcl/execution/
            executor/TestCaseExecutorService.java
            trigger/TriggerService.java
            trigger/RestTriggerService.java
            trigger/EmsTriggerService.java
            validator/LogValidationExecutor.java
            model/
    integration-observability-core/
        src/main/java/com/hcl/observability/
            sftp/SftpService.java
            log/LogAnalyzerService.java
            log/LogCleanupService.java
            validation/LogValidationService.java
            correlation/
    integration-ai-intelligence/
        src/main/java/com/hcl/ai/
```

## Dependency Flow

```text
integration-api-gateway
      |
      v
integration-execution-core
      |
      v
integration-observability-core

integration-ai-intelligence -> optional consumer
```

## Module Responsibilities

### integration-api-gateway

Entry point for the platform.

Responsibilities:
- Accept HTTP requests.
- Build or receive a `TestCase`.
- Call `integration-execution-core`.
- Return `ExecutionResult`.
- Provide the runtime `application.properties` as the single configuration source.

Must not:
- Own execution decisions.
- Fetch logs directly.
- Access SFTP directly.
- Perform root cause analysis.

### integration-execution-core

Execution brain for the platform.

Responsibilities:
- Own the full test execution flow.
- Trigger REST, EMS, or SOAP flows.
- Apply retry behavior.
- Build step-level execution results.
- Decide final pass, fail, or error status.
- Call observability services for log visibility.

Must not:
- Read log files directly.
- Access SFTP directly.
- Own external configuration loading.

### integration-observability-core

Visibility and log data layer.

Responsibilities:
- Fetch logs through SFTP.
- Parse and analyze logs.
- Extract correlation IDs and job IDs.
- Validate log timeline and log structure.
- Provide structured log data back to execution-core.

Must not:
- Trigger business flows.
- Decide final test pass or fail.
- Control execution order.

### integration-ai-intelligence

Optional intelligence layer after execution.

Responsibilities:
- Analyze completed execution results.
- Provide root cause analysis.
- Detect log patterns.
- Generate insights and recommendations.
- Integrate with LLM providers when enabled.

Must not:
- Trigger execution.
- Control the execution flow.
- Decide final execution status.

## Current Build Command

Run the platform from the repository root:

```powershell
mvn -pl integration-api-gateway -am spring-boot:run
```

Primary execution endpoint:

```text
POST /execute?bookingId={bookingId}&payload={payloadFile}
```

Browser convenience endpoint:

```text
GET /execute/run?bookingId={bookingId}&payload={payloadFile}
```

Build all modules:

```powershell
mvn -q -DskipTests package
```

## Near-Term Progress Plan

1. Keep the module boundaries clean and enforce them through constructor injection.
2. Move execution-core away from manually loading configuration.
3. Make observability services Spring-managed beans.
4. Expand `TestCaseExecutorService` to actually trigger configured steps before validating logs.
5. Add AI intelligence only after the execution result contract is stable.

## SFTP Functional Flow

API Trigger (/execute/run?bookingId=31835146)

→ STEP 1: SFTP connect (SSH key)
→ STEP 2: grep using BookingID (PRIMARY)
→ STEP 3: Download matching files → C:\logs
→ STEP 4: Parse logs → extract CorrID + JobID
→ STEP 5: grep again using CorrID + JobID
→ STEP 6: Download additional logs → same C:\logs
→ STEP 7: Build full timeline + validation
