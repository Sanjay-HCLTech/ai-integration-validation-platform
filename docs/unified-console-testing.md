# Unified Execution Console Testing

This guide covers repeatable checks for the IVP Unified Execution Console.

## Start The Gateway

From the repository root:

```powershell
mvn -pl integration-api-gateway spring-boot:run
```

Open:

```text
http://localhost:8080/console.html
```

The console should show the input panel, summary bar, Recent Runs, and result table.

## Run The UI E2E Check

Use the dedicated E2E runner:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\run-ui-console-e2e.ps1
```

For a faster run after compiling:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\run-ui-console-e2e.ps1 -SkipCompile
```

This verifies page load, executeAll, history, stored execution, logs, report, and stop endpoint behavior.

## Run The Stabilization Pack

Use:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\run-stabilization-pack.ps1
```

For a faster run after compiling:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\run-stabilization-pack.ps1 -SkipCompile
```

The stabilization pack verifies:

- Console page smoke
- SOAP execution smoke
- REST execution smoke
- JMS execution smoke
- Rabbit execution smoke
- Multi-flow matrix
- Invalid flow handling
- History pruning
- Log/report download shape
- North Star log snapshot rule

## Expected Simulation Boundaries

JMS and Rabbit currently run through simulation/phase-ready paths unless real providers are wired and enabled.

SOAP is configured with `soap.transport=jms` by default, so SOAP smoke follows the SOAP-over-JMS lane.

REST may call a configured endpoint. In offline or blocked-network environments, a REST row may be `FAIL`; the stabilization pack still passes when the failure is controlled and stored with executionId/history/log/report output.

## Troubleshooting

If port 8080 is already in use:

```powershell
Get-NetTCPConnection -LocalPort 8080 -State Listen
```

If a manual Maven background launch behaves strangely on Windows paths with spaces, use the provided scripts. They start the gateway with a PowerShell `Start-Job` harness and clean it up after tests.

Temporary runner and log files are written under `target/`.

## North Star Rule

The log snapshot extraction logic must remain unchanged.

Check:

```powershell
git diff -- integration-observability-core/src/main/java/com/hcl/observability/log/LogAnalyzerService.java
```

Expected result: no output.
