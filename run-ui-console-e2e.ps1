param(
    [string]$BaseUrl = "http://localhost:8080",

    [int]$TimeoutSeconds = 90,

    [switch]$SkipCompile
)

$ErrorActionPreference = "Stop"

$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$targetDir = Join-Path $root "target"
$runner = Join-Path $targetDir "run-gateway-e2e.cmd"
$outLog = Join-Path $targetDir "ui-console-e2e-gateway.out.log"
$errLog = Join-Path $targetDir "ui-console-e2e-gateway.err.log"

function Write-Step {
    param([string]$Message)
    Write-Host "[E2E] $Message"
}

function Require {
    param(
        [bool]$Condition,
        [string]$Message
    )

    if (-not $Condition) {
        throw $Message
    }
}

function Wait-Console {
    param(
        [string]$Url,
        [int]$Seconds
    )

    $deadline = (Get-Date).AddSeconds($Seconds)
    while ((Get-Date) -lt $deadline) {
        Start-Sleep -Seconds 2
        try {
            $response = Invoke-WebRequest -Uri "$Url/console.html" -UseBasicParsing -TimeoutSec 5
            if ($response.StatusCode -eq 200 -and
                $response.Content.Contains("Unified Execution Console") -and
                $response.Content.Contains("Recent Runs")) {
                return $true
            }
        } catch {
            # Keep polling until the timeout; startup can take a few seconds.
        }
    }
    return $false
}

function Tail-File {
    param(
        [string]$Path,
        [int]$Lines = 80
    )

    if (Test-Path -LiteralPath $Path) {
        Get-Content -LiteralPath $Path -Tail $Lines
    }
}

function Stop-ProcessTree {
    param([int]$ProcessId)

    $children = Get-CimInstance Win32_Process -Filter "ParentProcessId=$ProcessId" -ErrorAction SilentlyContinue
    foreach ($child in $children) {
        Stop-ProcessTree -ProcessId $child.ProcessId
    }

    Stop-Process -Id $ProcessId -Force -ErrorAction SilentlyContinue
}

New-Item -ItemType Directory -Force -Path $targetDir | Out-Null

$mvn = (Get-Command mvn).Source
Require (Test-Path -LiteralPath $mvn) "Maven command not found."
$baseUri = [Uri]$BaseUrl
$serverPort = if ($baseUri.IsDefaultPort) { 8080 } else { $baseUri.Port }
$springRunArguments = if ($serverPort -eq 8080) { "" } else { " -Dspring-boot.run.arguments=--server.port=$serverPort" }

if (-not $SkipCompile) {
    Write-Step "Preparing gateway runtime modules"
} else {
    Write-Step "Refreshing gateway runtime modules"
}
& (Join-Path $root "build-gateway-runtime.ps1")

$runnerLines = @(
    "@echo off",
    "cd /d `"$root`"",
    "call `"$mvn`" -pl integration-api-gateway spring-boot:run$springRunArguments > `"$outLog`" 2> `"$errLog`""
)
Set-Content -LiteralPath $runner -Value $runnerLines -Encoding ASCII

$serverProcess = $null
$startedByScript = $false

try {
    Write-Step "Checking console availability at $BaseUrl"
    $ready = Wait-Console -Url $BaseUrl -Seconds 5

    if (-not $ready) {
        Write-Step "Starting gateway with managed test process"
        Remove-Item -LiteralPath $outLog, $errLog -ErrorAction SilentlyContinue
        $serverProcess = Start-Process -FilePath "cmd.exe" `
            -ArgumentList @("/c", "`"$runner`"") `
            -WorkingDirectory $root `
            -PassThru `
            -WindowStyle Hidden
        $startedByScript = $true

        $ready = Wait-Console -Url $BaseUrl -Seconds $TimeoutSeconds
        if (-not $ready) {
            Write-Host "--- gateway stdout tail ---"
            Tail-File -Path $outLog
            Write-Host "--- gateway stderr tail ---"
            Tail-File -Path $errLog
            throw "Gateway did not become ready at $BaseUrl within $TimeoutSeconds seconds."
        }
    }

    Write-Step "Running console API checks"
    $body = @{
        env = "ST5"
        systems = @("DMS")
        flowTypes = @("JMS")
        services = @("BookingDetails")
        payloadMode = "AUTO"
        parallel = $true
        traceEnabled = $true
    } | ConvertTo-Json -Depth 5

    $run = Invoke-RestMethod -Uri "$BaseUrl/execute/executeAll" -Method Post -ContentType "application/json" -Body $body -TimeoutSec 60
    $executionId = $run.executionId
    Require ([bool]$executionId) "Execution response did not include executionId."
    Require ($run.executionStatus -eq "COMPLETED") "Execution status was not COMPLETED. Actual: $($run.executionStatus)"
    Require (($run.rows | Measure-Object).Count -gt 0) "Execution response did not include result rows."
    Require ($run.rows[0].bookingId -eq "31835146") "Execution did not use default BookingID=31835146."

    $history = Invoke-RestMethod -Uri "$BaseUrl/execute/history" -Method Get -TimeoutSec 20
    Require ([bool]($history | Where-Object { $_.executionId -eq $executionId })) "History did not include executionId $executionId."

    $stored = Invoke-RestMethod -Uri "$BaseUrl/execute/$executionId" -Method Get -TimeoutSec 20
    Require ($stored.executionId -eq $executionId) "Stored execution did not match executionId $executionId."

    $logs = Invoke-WebRequest -Uri "$BaseUrl/execute/$executionId/logs" -UseBasicParsing -TimeoutSec 20
    Require ($logs.Content.Contains("[EXECUTION]")) "Logs download missing [EXECUTION]."
    Require ($logs.Content.Contains("[TRACE]")) "Logs download missing [TRACE]."
    Require ($logs.Content.Contains("[ASSERT]")) "Logs download missing [ASSERT]."
    Require ($logs.Content.Contains("[RESULT]")) "Logs download missing [RESULT]."

    $report = Invoke-WebRequest -Uri "$BaseUrl/execute/$executionId/report" -UseBasicParsing -TimeoutSec 20
    Require ($report.Content.Contains('ExecutionId","Service","Flow","System","Env","Status')) "Report download missing expected CSV headers."

    $stopExecutionId = "E2E_STOP_" + [guid]::NewGuid().ToString("N")
    $stopBody = @{
        executionId = $stopExecutionId
        env = "ST5"
        systems = @("DMS", "SAP", "AO")
        flowTypes = @("JMS")
        services = @("BookingDetails", "AccomOffers")
        payloadMode = "AUTO"
        parallel = $false
        traceEnabled = $true
    } | ConvertTo-Json -Depth 5

    $stopRun = Start-Job -ScriptBlock {
        param($Url, $Payload)
        Invoke-RestMethod -Uri "$Url/execute/executeAll" -Method Post -ContentType "application/json" -Body $Payload -TimeoutSec 90
    } -ArgumentList $BaseUrl, $stopBody

    $created = $false
    $deadline = (Get-Date).AddSeconds(20)
    while ((Get-Date) -lt $deadline -and -not $created) {
        Start-Sleep -Milliseconds 250
        try {
            $probe = Invoke-RestMethod -Uri "$BaseUrl/execute/$stopExecutionId" -Method Get -TimeoutSec 5
            $created = $probe.executionId -eq $stopExecutionId
        } catch {
            # Execution record may not be created yet.
        }
    }
    Require $created "Stop test execution record was not created."

    try {
        $stop = Invoke-WebRequest -Uri "$BaseUrl/execute/$stopExecutionId/stop" -Method Post -UseBasicParsing -TimeoutSec 20
        $stopStatusCode = $stop.StatusCode
        $stopContent = $stop.Content
    } catch [System.Net.WebException] {
        $stopResponse = $_.Exception.Response
        $stopStatusCode = [int]$stopResponse.StatusCode
        $reader = New-Object System.IO.StreamReader($stopResponse.GetResponseStream())
        try {
            $stopContent = $reader.ReadToEnd()
        } finally {
            $reader.Close()
        }
    }
    Require (($stopStatusCode -eq 200 -and $stopContent -eq "STOP_REQUESTED") -or
        $stopStatusCode -eq 409) `
        "Stop endpoint returned unexpected status/body: $stopStatusCode $stopContent"
    Wait-Job $stopRun -Timeout 100 | Out-Null
    Receive-Job $stopRun -ErrorAction SilentlyContinue | Out-Null
    Remove-Job $stopRun -Force -ErrorAction SilentlyContinue

    [pscustomobject]@{
        ConsolePage = "PASS"
        ExecutionId = $executionId
        RunStatus = $run.executionStatus
        RowCount = ($run.rows | Measure-Object).Count
        History = "PASS"
        StoredExecution = "PASS"
        LogsDownload = "PASS"
        ReportDownload = "PASS"
        StopEndpoint = "PASS"
    } | Format-List

    Write-Step "UI Console E2E PASS"
} finally {
    if ($startedByScript -and $serverProcess) {
        Write-Step "Stopping gateway test process tree"
        Stop-ProcessTree -ProcessId $serverProcess.Id
    }
}
