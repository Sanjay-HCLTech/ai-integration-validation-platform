param(
    [string]$SampleDir = "reference-log-samples"
)

$ErrorActionPreference = "Stop"

$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$samplePath = Join-Path $root $SampleDir

if (-not (Test-Path -LiteralPath $samplePath)) {
    throw "Reference sample directory not found: $samplePath"
}

$timestampPattern = '\d{4}\s+[A-Za-z]{3}\s+\d{1,2}\s+\d{2}:\d{2}:\d{2}:\d{3}'
$jobPattern = 'JobID\s*[:=]\s*([A-Za-z0-9._:-]+)'
$corrPattern = '(?:CorrID|JMSCorrelationID)\s*[:=]\s*([A-Za-z0-9._:-]{20,})'
$bizPattern = 'BizKey\s*[:=][ \t]*([A-Za-z0-9+._:-]+)'

$sampleMetadata = @{
    "BookFlow_v2_InfoBooking_v2.log" = [pscustomobject]@{ BizKey = "NULL"; Service = "BookFlow_v2"; Operation = "InfoBooking_v2" }
    "BookFlow_v3_CreateBooking_v3.log" = [pscustomobject]@{ BizKey = "NULL"; Service = "BookFlow_v3"; Operation = "CreateBooking_v3" }
    "BookFlow_v3_ItemSearch_v3.log" = [pscustomobject]@{ BizKey = "NULL"; Service = "BookFlow_v3"; Operation = "ItemSearch_v3" }
    "BookingDetails_v2_GetBookingIDs.log" = [pscustomobject]@{ BizKey = "31001768"; Service = "BookingDetails_v2"; Operation = "GetBookingIDs" }
    "BookingDetails_v2_PubBookingDetails_v2.log" = [pscustomobject]@{ BizKey = "31860470"; Service = "BookingDetails_v2"; Operation = "PubBookingDetails_v2" }
    "DisplayBooking_v3.log" = [pscustomobject]@{ BizKey = "42007907"; Service = "DisplayBooking_v3"; Operation = "DisplayBooking_v3" }
    "GIPBookingAdapter_BFE_v1_SubscribeBookingDetailsGIP_v1.log" = [pscustomobject]@{ BizKey = "30544300"; Service = "GIPBookingAdapter_BFE_v1"; Operation = "SubscribeBookingDetailsGIP_v1" }
    "M3HotelsSubscriber_v1_SubscribeBookingM3Hotels_v1.log" = [pscustomobject]@{ BizKey = "32036355"; Service = "M3HotelsSubscriber_v1"; Operation = "SubscribeBookingM3Hotels_v1" }
    "ManageBooking_v2_ModifyBooking_v2.log" = [pscustomobject]@{ BizKey = "42005437"; Service = "ManageBooking_v2"; Operation = "ModifyBooking_v2" }
    "ManageBooking_v2_SearchBooking_v2.log" = [pscustomobject]@{ BizKey = "12180887"; Service = "ManageBooking_v2"; Operation = "SearchBooking_v2" }
    "ManageBooking_v2_UpdateCustomerId_v2.log" = [pscustomobject]@{ BizKey = "32075181"; Service = "ManageBooking_v2"; Operation = "UpdateCustomerId_v2" }
    "MongoDBBookingSubscriber_v1.log" = [pscustomobject]@{ BizKey = "42007911"; Service = "MongoDBBookingSubscriber_v1"; Operation = "MongoDBBookingSubscriber_v1" }
    "NordicBookingSubscriber_v1.log" = [pscustomobject]@{ BizKey = "30581458"; Service = "NordicBookingSubscriber"; Operation = "NordicBookingSubscriber_v1" }
    "NordicCustomerIDSubscriber_v1_SubscribeNordicCustomerID_v1.log" = [pscustomobject]@{ BizKey = "42004679"; Service = "NordicCustomerIDSubscriber_v1"; Operation = "SubscribeNordicCustomerID_v1" }
    "PostBookFlowSubscriber_v2_ManageDocument.log" = [pscustomobject]@{ BizKey = "42007914"; Service = "PostBookFlowSubscriber_v2"; Operation = "ManageDocument" }
    "PostBookFlowSubscriber_v2_SendBookingDetails.log" = [pscustomobject]@{ BizKey = "21309925"; Service = "PostBookFlowSubscriber_v2"; Operation = "SendBookingDetails" }
    "PostBookFlowSubscriber_v2_SendDataToGIP.log" = [pscustomobject]@{ BizKey = "42007921"; Service = "PostBookFlowSubscriber_v2"; Operation = "SendDataToGIP" }
    "PostBookFlowSubscriber_v2_SubscribeBookingDetails_v2.log" = [pscustomobject]@{ BizKey = "29987672"; Service = "PostBookFlowSubscriber_v2"; Operation = "SubscribeBookingDetails_v2" }
    "ShorexBookingSubscriber_v1_SubscriberBookingDetailsShorex_v1.log" = [pscustomobject]@{ BizKey = "42007921"; Service = "ShorexBookingSubscriber_v1"; Operation = "SubscriberBookingDetailsShorex_v1" }
}

function First-MatchGroup {
    param(
        [string[]]$Lines,
        [string]$Pattern
    )

    foreach ($line in $Lines) {
        $match = [regex]::Match($line, $Pattern, [System.Text.RegularExpressions.RegexOptions]::IgnoreCase)
        if ($match.Success) {
            return $match.Groups[1].Value
        }
    }
    return ""
}

function Count-Matches {
    param(
        [string[]]$Lines,
        [string]$Pattern
    )

    $count = 0
    foreach ($line in $Lines) {
        if ($line -match $Pattern) {
            $count++
        }
    }
    return $count
}

function Test-OrderedPhase {
    param(
        [string[]]$Lines
    )

    $requestIndex = -1
    $terminalIndex = -1
    for ($i = 0; $i -lt $Lines.Count; $i++) {
        $upper = $Lines[$i].ToUpperInvariant()
        if ($requestIndex -lt 0 -and $upper -match '\bREQUEST\b') {
            $requestIndex = $i
        }
        if ($requestIndex -ge 0 -and $i -ge $requestIndex -and $upper -match '\b(ERROR|EXCEPTION|STACKTRACE)\b') {
            $terminalIndex = $i
            break
        }
        if ($requestIndex -ge 0 -and $i -gt $requestIndex -and $upper -match '\b(REPLY|RESPONSE|PUBLISH|CONFIRM)\b') {
            $terminalIndex = $i
            break
        }
    }

    return ($requestIndex -ge 0 -and $terminalIndex -ge $requestIndex)
}

$files = Get-ChildItem -LiteralPath $samplePath -File -Filter "*.log" | Sort-Object Name
if ($files.Count -eq 0) {
    throw "No .log files found in $samplePath"
}

$rows = @()
$failures = @()

foreach ($file in $files) {
    $lines = Get-Content -LiteralPath $file.FullName
    $jobId = First-MatchGroup -Lines $lines -Pattern $jobPattern
    $corrId = First-MatchGroup -Lines $lines -Pattern $corrPattern
    $bizKey = First-MatchGroup -Lines $lines -Pattern $bizPattern
    if ($bizKey -match '^(CorrID|TranxID|JobID):?$') {
        $bizKey = ""
    }
    $service = ""
    $operation = ""
    if ($sampleMetadata.ContainsKey($file.Name)) {
        $metadata = $sampleMetadata[$file.Name]
        $bizKey = $metadata.BizKey
        $service = $metadata.Service
        $operation = $metadata.Operation
    }
    $requestCount = Count-Matches -Lines $lines -Pattern '\bREQUEST\b'
    $processCount = Count-Matches -Lines $lines -Pattern '\b(PROCESS|PROCESSING|NOTIFY)\b'
    $replyCount = Count-Matches -Lines $lines -Pattern '\b(REPLY|RESPONSE)\b'
    $publishCount = Count-Matches -Lines $lines -Pattern '\b(PUBLISH|CONFIRM)\b'
    $errorCount = Count-Matches -Lines $lines -Pattern '\b(ERROR|EXCEPTION|STACKTRACE)\b'
    $timestampCount = Count-Matches -Lines $lines -Pattern $timestampPattern
    $hasCorrelation = -not [string]::IsNullOrWhiteSpace($jobId) -or -not [string]::IsNullOrWhiteSpace($corrId)
    $complete = (Test-OrderedPhase -Lines $lines) -and $hasCorrelation
    $status = if ($complete) { "PASS" } else { "FAIL" }

    if (-not $complete) {
        $failures += "$($file.Name): missing ordered REQUEST -> terminal phase or correlation ID"
    }

    $rows += [pscustomobject]@{
        File = $file.Name
        Status = $status
        Lines = $lines.Count
        Timestamps = $timestampCount
        JobID = $jobId
        CorrID = $corrId
        BizKey = $bizKey
        Service = $service
        Operation = $operation
        REQUEST = $requestCount
        PROCESS = $processCount
        REPLY = $replyCount
        PUBLISH = $publishCount
        ERROR = $errorCount
    }
}

$manifestPath = Join-Path $samplePath "sample-log-manifest.csv"
$rows | Export-Csv -NoTypeInformation -Path $manifestPath

Write-Host "Reference log sample verification:"
$rows | Format-Table -AutoSize
Write-Host ""
Write-Host "Manifest updated: $manifestPath"

if ($failures.Count -gt 0) {
    Write-Host ""
    Write-Host "Failures:"
    $failures | ForEach-Object { Write-Host "- $_" }
    exit 1
}

Write-Host ""
Write-Host "All reference samples satisfy the complete block contract."
