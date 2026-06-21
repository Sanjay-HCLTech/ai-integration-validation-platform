param(
    [string]$BaseUrl = "http://localhost:8080",

    [int]$TimeoutSeconds = 90,

    [switch]$SkipCompile
)

$ErrorActionPreference = "Stop"

$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$targetDir = Join-Path $root "target"
$runner = Join-Path $targetDir "run-gateway-stabilization.cmd"
$outLog = Join-Path $targetDir "stabilization-gateway.out.log"
$errLog = Join-Path $targetDir "stabilization-gateway.err.log"

function Write-Step {
    param([string]$Message)
    Write-Host "[STABILIZE] $Message"
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
            # Keep polling.
        }
    }
    return $false
}

function Tail-File {
    param([string]$Path)

    if (Test-Path -LiteralPath $Path) {
        Get-Content -LiteralPath $Path -Tail 100
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

function Invoke-JsonPost {
    param(
        [string]$Path,
        [hashtable]$Payload
    )

    $body = $Payload | ConvertTo-Json -Depth 8
    return Invoke-RestMethod -Uri "$BaseUrl$Path" -Method Post -ContentType "application/json" -Body $body -TimeoutSec 90
}

function Assert-ExecutionResponse {
    param(
        [object]$Response,
        [string]$Name,
        [int]$ExpectedRows = 1,
        [string]$ExpectedBookingId = "31835146"
    )

    Require ([bool]$Response.executionId) "$Name did not return executionId."
    Require ([bool]$Response.summary) "$Name did not return summary."
    Require (($Response.rows | Measure-Object).Count -eq $ExpectedRows) "$Name returned unexpected row count."
    if ($ExpectedBookingId) {
        foreach ($row in $Response.rows) {
            Require ($row.bookingId -eq $ExpectedBookingId) "$Name returned unexpected BookingID: $($row.bookingId)"
        }
    }

    $history = Invoke-RestMethod -Uri "$BaseUrl/execute/history" -Method Get -TimeoutSec 20
    Require ([bool]($history | Where-Object { $_.executionId -eq $Response.executionId })) "$Name executionId missing from history."

    $stored = Invoke-RestMethod -Uri "$BaseUrl/execute/$($Response.executionId)" -Method Get -TimeoutSec 20
    Require ($stored.executionId -eq $Response.executionId) "$Name stored execution mismatch."

    $logs = Invoke-WebRequest -Uri "$BaseUrl/execute/$($Response.executionId)/logs" -UseBasicParsing -TimeoutSec 20
    Require ($logs.Content.Contains("[EXECUTION]")) "$Name logs missing [EXECUTION]."
    Require ($logs.Content.Contains("[RESULT]")) "$Name logs missing [RESULT]."

    $report = Invoke-WebRequest -Uri "$BaseUrl/execute/$($Response.executionId)/report" -UseBasicParsing -TimeoutSec 20
    Require ($report.Content.Contains('ExecutionId","Service","Flow","System","Env","Status')) "$Name report missing CSV headers."
}

function New-Request {
    param(
        [string[]]$FlowTypes,
        [string[]]$Systems = @("DMS"),
        [string[]]$Services = @("BookingDetails"),
        [bool]$Parallel = $false
    )

    return @{
        env = "ST5"
        systems = $Systems
        flowTypes = $FlowTypes
        services = $Services
        payloadMode = "AUTO"
        parallel = $Parallel
        traceEnabled = $true
    }
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

    Write-Step "Console page smoke"
    $page = Invoke-WebRequest -Uri "$BaseUrl/console.html" -UseBasicParsing -TimeoutSec 20
    Require ($page.Content.Contains("Unified Execution Console")) "Console page missing title."
    Require ($page.Content.Contains("Recent Runs")) "Console page missing Recent Runs."

    Write-Step "Protocol smoke: SOAP"
    $soap = Invoke-JsonPost -Path "/execute/executeAll" -Payload (New-Request -FlowTypes @("SOAP"))
    Assert-ExecutionResponse -Response $soap -Name "SOAP"
    Require ($soap.executionStatus -eq "COMPLETED") "SOAP execution did not complete."
    Require ($soap.rows[0].status -eq "PASS") "SOAP row did not pass."
    Require ($soap.rows[0].corrId -and $soap.rows[0].corrId -ne "NA") "SOAP did not return CorrID."
    Require ([bool]($soap.rows[0].assertions | Where-Object { $_ -eq "PROCESS=PASS" })) "SOAP assertions missing PROCESS=PASS."
    Require ([bool]($soap.rows[0].assertions | Where-Object { $_ -eq "DOWNSTREAM=SIMULATED" })) "SOAP assertions missing DOWNSTREAM=SIMULATED."

    Write-Step "Protocol smoke: REST"
    $rest = Invoke-JsonPost -Path "/execute/executeAll" -Payload (New-Request -FlowTypes @("REST") -Services @("PackageOffer"))
    Assert-ExecutionResponse -Response $rest -Name "REST" -ExpectedBookingId $null

    Write-Step "Protocol smoke: JMS"
    $jms = Invoke-JsonPost -Path "/execute/executeAll" -Payload (New-Request -FlowTypes @("JMS"))
    Assert-ExecutionResponse -Response $jms -Name "JMS"

    Write-Step "Protocol smoke: Rabbit"
    $rabbit = Invoke-JsonPost -Path "/execute/executeAll" -Payload (New-Request -FlowTypes @("RABBIT"))
    Assert-ExecutionResponse -Response $rabbit -Name "Rabbit"

    Write-Step "Protocol smoke: Kafka"
    $kafka = Invoke-JsonPost -Path "/execute/executeAll" -Payload (New-Request -FlowTypes @("KAFKA"))
    Assert-ExecutionResponse -Response $kafka -Name "Kafka"
    Require ($kafka.executionStatus -eq "COMPLETED") "Kafka execution did not complete."
    Require ($kafka.rows[0].status -eq "PASS") "Kafka row did not pass."
    Require ([bool]($kafka.rows[0].assertions | Where-Object { $_ -eq "PROCESS=PASS" })) "Kafka assertions missing PROCESS=PASS."
    Require ([bool]($kafka.rows[0].assertions | Where-Object { $_ -eq "DOWNSTREAM=SUCCESS" })) "Kafka assertions missing DOWNSTREAM=SUCCESS."

    Write-Step "Multi-flow matrix smoke"
    $matrixRequest = New-Request -FlowTypes @("JMS", "RABBIT", "KAFKA") -Services @("BookingDetails", "AccomOffers") -Parallel $true
    $matrix = Invoke-JsonPost -Path "/execute/executeAll" -Payload $matrixRequest
    Assert-ExecutionResponse -Response $matrix -Name "Matrix" -ExpectedRows 6
    Require ($matrix.summary.total -eq 6) "Matrix summary total was not 6."

    Write-Step "Invalid request smoke"
    $invalid = Invoke-JsonPost -Path "/execute/executeAll" -Payload (New-Request -FlowTypes @("UNKNOWN"))
    Require ([bool]$invalid.executionId) "Invalid flow did not return controlled executionId."
    Require ($invalid.executionStatus -eq "FAILED") "Invalid flow did not return FAILED status."
    Require (($invalid.rows | Measure-Object).Count -eq 1) "Invalid flow did not return a failure row."

    Write-Step "History pruning smoke"
    for ($index = 1; $index -le 26; $index++) {
        $payload = New-Request -FlowTypes @("UNKNOWN")
        $payload.executionId = "PRUNE_$index"
        Invoke-JsonPost -Path "/execute/executeAll" -Payload $payload | Out-Null
    }
    $historyAfterPrune = Invoke-RestMethod -Uri "$BaseUrl/execute/history" -Method Get -TimeoutSec 20
    Require (($historyAfterPrune | Measure-Object).Count -le 25) "History exceeded configured limit of 25."

    Write-Step "North Star log snapshot check"
    $northStarDiff = & git diff -- integration-observability-core/src/main/java/com/hcl/observability/log/LogAnalyzerService.java
    Require ([string]::IsNullOrWhiteSpace(($northStarDiff | Out-String))) "North Star violation: LogAnalyzerService.java has a diff."

    [pscustomobject]@{
        ConsolePage = "PASS"
        SoapSmoke = $soap.executionStatus
        RestSmoke = $rest.executionStatus
        JmsSmoke = $jms.executionStatus
        RabbitSmoke = $rabbit.executionStatus
        KafkaSmoke = $kafka.executionStatus
        MatrixRows = ($matrix.rows | Measure-Object).Count
        InvalidFlow = $invalid.executionStatus
        HistoryCount = ($historyAfterPrune | Measure-Object).Count
        NorthStar = "PASS"
    } | Format-List

    Write-Step "Stabilization pack PASS"
} finally {
    if ($startedByScript -and $serverProcess) {
        Write-Step "Stopping gateway test process tree"
        Stop-ProcessTree -ProcessId $serverProcess.Id
    }
}
