param(
    [Parameter(Mandatory = $true)]
    [string]$BookingId,

    [string]$GeneratedRoot = "C:\logs",

    [string]$ReferenceDir = "reference-log-samples",

    [double]$MinLineRatio = 0.60
)

$ErrorActionPreference = "Stop"

$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$generatedDir = Join-Path $GeneratedRoot $BookingId
$referencePath = Join-Path $root $ReferenceDir

if (-not (Test-Path -LiteralPath $generatedDir)) {
    throw "Generated log directory not found: $generatedDir"
}

if (-not (Test-Path -LiteralPath $referencePath)) {
    throw "Reference log directory not found: $referencePath"
}

$phasePatterns = [ordered]@{
    REQUEST = '\bREQUEST\b'
    PROCESS = '\b(PROCESS|PROCESSING|NOTIFY)\b'
    REPLY = '\b(REPLY|RESPONSE)\b'
    PUBLISH = '\bPUBLISH\b'
    CONFIRM = '\bCONFIRM\b'
    ERROR = '\b(ERROR|EXCEPTION|STACKTRACE)\b'
}
$legacyGrepPattern = '^\d+[:-]'

function Base-LogName {
    param([string]$Name)
    return ($Name -replace '\.log\.\d+$', '.log')
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

function Phase-Counts {
    param([string[]]$Lines)

    $counts = [ordered]@{}
    foreach ($phase in $phasePatterns.Keys) {
        $counts[$phase] = Count-Matches -Lines $Lines -Pattern $phasePatterns[$phase]
    }
    return $counts
}

function Missing-Reference-Phases {
    param(
        [hashtable]$LocalCounts,
        [hashtable]$ReferenceCounts
    )

    $missing = @()
    foreach ($phase in $phasePatterns.Keys) {
        if ($ReferenceCounts[$phase] -gt 0 -and $LocalCounts[$phase] -eq 0) {
            $missing += $phase
        }
    }
    return $missing
}

function Phase-Summary {
    param([hashtable]$Counts)

    $parts = @()
    foreach ($phase in $phasePatterns.Keys) {
        $parts += "$phase=$($Counts[$phase])"
    }
    return ($parts -join ";")
}

$referenceFiles = @{}
Get-ChildItem -LiteralPath $referencePath -File -Filter "*.log" | ForEach-Object {
    $referenceFiles[$_.Name] = $_
}

$rows = @()
$issues = @()

$generatedFiles = Get-ChildItem -LiteralPath $generatedDir -File -Filter "*.log*" | Sort-Object Name
if ($generatedFiles.Count -eq 0) {
    throw "No generated *.log* files found under $generatedDir"
}

$generatedFiles | ForEach-Object {
    $generatedFile = $_
    $baseName = Base-LogName -Name $generatedFile.Name
    $localLines = Get-Content -LiteralPath $generatedFile.FullName
    $localCounts = Phase-Counts -Lines $localLines
    $legacyLines = Count-Matches -Lines $localLines -Pattern $legacyGrepPattern

    $status = "PASS"
    $issueText = ""
    $referenceLineCount = 0
    $lineRatio = 0
    $referencePhaseSummary = "NO_REFERENCE"

    if (-not $referenceFiles.ContainsKey($baseName)) {
        $status = "WARN"
        $issueText = "No matching reference sample"
    } else {
        $referenceLines = Get-Content -LiteralPath $referenceFiles[$baseName].FullName
        $referenceLineCount = $referenceLines.Count
        $referenceCounts = Phase-Counts -Lines $referenceLines
        $referencePhaseSummary = Phase-Summary -Counts $referenceCounts
        $lineRatio = if ($referenceLineCount -gt 0) {
            [Math]::Round($localLines.Count / $referenceLineCount, 2)
        } else {
            0
        }

        $missingPhases = Missing-Reference-Phases -LocalCounts $localCounts -ReferenceCounts $referenceCounts
        if ($lineRatio -lt $MinLineRatio) {
            $issues += "$($generatedFile.Name): line ratio $lineRatio below $MinLineRatio compared with $baseName"
        }
        if ($missingPhases.Count -gt 0) {
            $issues += "$($generatedFile.Name): missing reference phases $($missingPhases -join ',')"
        }
        if ($legacyLines -gt 0) {
            $issues += "$($generatedFile.Name): contains $legacyLines legacy grep context lines"
        }

        if ($legacyLines -gt 0 -or $missingPhases.Count -gt 0 -or $lineRatio -lt $MinLineRatio) {
            $status = "FAIL"
            $issueParts = @()
            if ($legacyLines -gt 0) {
                $issueParts += "LEGACY_GREP_LINES=$legacyLines"
            }
            if ($missingPhases.Count -gt 0) {
                $issueParts += "MISSING_PHASES=$($missingPhases -join ',')"
            }
            if ($lineRatio -lt $MinLineRatio) {
                $issueParts += "LOW_LINE_RATIO=$lineRatio"
            }
            $issueText = $issueParts -join "|"
        }
    }

    $rows += [pscustomobject]@{
        Status = $status
        File = $generatedFile.Name
        Reference = $baseName
        Lines = $localLines.Count
        ReferenceLines = $referenceLineCount
        LineRatio = $lineRatio
        LegacyGrepLines = $legacyLines
        LocalPhases = Phase-Summary -Counts $localCounts
        ReferencePhases = $referencePhaseSummary
        Issue = $issueText
    }
}

Write-Host "Generated log comparison for BookingID=$BookingId"
Write-Host "Generated: $generatedDir"
Write-Host "Reference: $referencePath"
Write-Host ""
$rows | Format-Table -AutoSize

if ($issues.Count -gt 0) {
    Write-Host ""
    Write-Host "Data-loss risks:"
    $issues | ForEach-Object { Write-Host "- $_" }
    exit 1
}

Write-Host ""
Write-Host "Generated logs are comparable with reference samples."
