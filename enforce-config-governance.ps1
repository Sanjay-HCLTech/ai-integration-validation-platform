param(
    [string]$ConfigFile = "integration-api-gateway/src/main/resources/application.properties",
    [string]$ReportFile = "target/config-governance-report.txt",
    [switch]$SummaryOnly
)

$ErrorActionPreference = "Stop"
$root = $PSScriptRoot
$configPath = Join-Path $root $ConfigFile
$reportPath = Join-Path $root $ReportFile
$reportDir = Split-Path -Parent $reportPath

function Add-Line {
    param([System.Collections.Generic.List[string]]$Lines, [string]$Value)
    $Lines.Add($Value) | Out-Null
}

function Is-SecretKey {
    param([string]$Key)
    $k = $Key.ToLowerInvariant()
    return $k.Contains("password") -or $k.Contains("api.key") -or $k.Contains("api-key") -or
            $k.Contains("secret") -or $k.EndsWith(".token") -or $k.Contains(".token.")
}

function Mask-Value {
    param([string]$Key, [string]$Value)
    if (Is-SecretKey $Key) {
        if ([string]::IsNullOrWhiteSpace($Value)) {
            return "<empty>"
        }
        return "<configured>"
    }
    return $Value
}

if (-not (Test-Path -LiteralPath $configPath)) {
    throw "Unified application.properties not found: $ConfigFile"
}

$failures = [System.Collections.Generic.List[string]]::new()
$warnings = [System.Collections.Generic.List[string]]::new()
$summary = [System.Collections.Generic.List[string]]::new()
$properties = @{}
$propertyLines = @{}
$seen = @{}

$lineNumber = 0
foreach ($line in Get-Content -LiteralPath $configPath) {
    $lineNumber++
    $trimmed = $line.Trim()
    if ($trimmed.Length -eq 0 -or $trimmed.StartsWith("#") -or -not $trimmed.Contains("=")) {
        continue
    }
    $idx = $trimmed.IndexOf("=")
    $key = $trimmed.Substring(0, $idx).Trim()
    $value = $trimmed.Substring($idx + 1)
    $canonical = $key.ToLowerInvariant()
    if ($key -cne $canonical) {
        Add-Line $failures "NON_LOWERCASE_KEY ${ConfigFile}:$lineNumber key=$key expected=$canonical"
    }
    if ($key -notmatch '^[a-z0-9]+([.-][a-z0-9]+)*$') {
        Add-Line $failures "INVALID_KEY_FORMAT ${ConfigFile}:$lineNumber key=$key"
    }
    if ($seen.ContainsKey($canonical)) {
        Add-Line $failures "DUPLICATE_KEY key=$canonical firstLine=$($seen[$canonical]) duplicateLine=$lineNumber"
    } else {
        $seen[$canonical] = $lineNumber
    }
    $properties[$canonical] = $value
    $propertyLines[$canonical] = $lineNumber
}

$disallowedConfigFiles = Get-ChildItem -LiteralPath $root -Recurse -File -ErrorAction SilentlyContinue |
        Where-Object {
            try {
                $rel = Resolve-Path -LiteralPath $_.FullName -Relative
            } catch {
                return $false
            }
            $rel = $rel.TrimStart(".\").Replace("\", "/")
            if ($rel -eq $ConfigFile.Replace("\", "/")) { return $false }
            if ($rel -eq "integration-api-gateway/src/main/resources/META-INF/additional-spring-configuration-metadata.json") { return $false }
            if ($rel -like "target/*" -or $rel -like "*/target/*" -or $rel -like ".git/*") { return $false }
            return $_.Name -match '(^\.env$|\.ya?ml$|application.*\.properties$|.*\.local\.properties$)'
        }
foreach ($file in $disallowedConfigFiles) {
    $rel = Resolve-Path -LiteralPath $file.FullName -Relative
    Add-Line $failures "SCATTERED_CONFIG_FILE $rel"
}

$javaRoots = @(
    "integration-api-gateway/src/main/java",
    "integration-execution-core/src/main/java",
    "integration-ai-intelligence/src/main/java",
    "integration-observability-core/src/main/java"
)
$javaFiles = foreach ($javaRoot in $javaRoots) {
    $full = Join-Path $root $javaRoot
    if (Test-Path -LiteralPath $full) {
        Get-ChildItem -LiteralPath $full -Recurse -File -Filter *.java
    }
}

$usedKeys = [System.Collections.Generic.HashSet[string]]::new()
$valuePattern = [regex]'@Value\("\$\{([^}]+)\}"\)'
$fallbackPattern = [regex]'@Value\("\$\{[^}:]+:[^"]+\}"\)'
$envPattern = [regex]'System\.(getenv|getProperty)\s*\('
$hardcodedConfigPattern = [regex]'("(?:https?://|[A-Za-z]:/|/tui/|/var/log/|10\.\d+\.\d+\.\d+)[^"]*")'
$configFieldDefaultPattern = [regex]'private\s+(?:String|int|long|boolean|Integer|Long|Boolean)\s+\w+\s*=\s*[^;]+;'

foreach ($file in $javaFiles) {
    $relative = (Resolve-Path -LiteralPath $file.FullName -Relative).TrimStart(".\")
    $text = Get-Content -LiteralPath $file.FullName -Raw
    foreach ($match in $fallbackPattern.Matches($text)) {
        Add-Line $failures "INLINE_VALUE_FALLBACK $relative value=$($match.Value)"
    }
    foreach ($match in $valuePattern.Matches($text)) {
        $key = $match.Groups[1].Value.Trim().ToLowerInvariant()
        [void]$usedKeys.Add($key)
        if (-not $properties.ContainsKey($key)) {
            Add-Line $failures "MISSING_PROPERTY_REFERENCE $relative key=$key"
        }
    }
    foreach ($match in $envPattern.Matches($text)) {
        Add-Line $failures "DIRECT_ENV_OR_SYSTEM_PROPERTY_ACCESS $relative value=$($match.Value)"
    }
    foreach ($match in $hardcodedConfigPattern.Matches($text)) {
        if ($match.Groups[1].Value.Contains("schemas.xmlsoap.org")) {
            continue
        }
        Add-Line $failures "HARDCODED_CONFIG_VALUE $relative value=$($match.Groups[1].Value)"
    }
    if ($relative.Replace("\", "/") -like "integration-api-gateway/src/main/java/com/hcl/gateway/config/*") {
        foreach ($match in $configFieldDefaultPattern.Matches($text)) {
            Add-Line $failures "CONFIGURATION_PROPERTIES_FIELD_DEFAULT $relative value=$($match.Value.Trim())"
        }
    }
}

foreach ($key in ($properties.Keys | Sort-Object)) {
    if (-not $usedKeys.Contains($key)) {
        if ($key -match '^(rest|soap|jms|kafka|rabbit|sftp|ems|payload|platform|local|system|unified|execution|console|security|intelligence)\.') {
            Add-Line $warnings "UNUSED_OR_DYNAMIC_PROPERTY key=$key line=$($propertyLines[$key]) value=$(Mask-Value $key $properties[$key])"
        }
    }
}

Add-Line $summary "Configuration Governance Report"
Add-Line $summary "Unified config: $ConfigFile"
Add-Line $summary "Properties loaded: $($properties.Count)"
Add-Line $summary "Hard failures: $($failures.Count)"
Add-Line $summary "Warnings: $($warnings.Count)"
Add-Line $summary ""
Add-Line $summary "FAILURES"
if ($failures.Count -eq 0) {
    Add-Line $summary "NONE"
} else {
    foreach ($failure in $failures) { Add-Line $summary $failure }
}
Add-Line $summary ""
Add-Line $summary "WARNINGS"
if ($warnings.Count -eq 0) {
    Add-Line $summary "NONE"
} else {
    foreach ($warning in $warnings) { Add-Line $summary $warning }
}

New-Item -ItemType Directory -Force -Path $reportDir | Out-Null
Set-Content -LiteralPath $reportPath -Value $summary -Encoding UTF8
if ($SummaryOnly) {
    Write-Host "Configuration Governance Report"
    Write-Host "Unified config: $ConfigFile"
    Write-Host "Properties loaded: $($properties.Count)"
    Write-Host "Hard failures: $($failures.Count)"
    Write-Host "Warnings: $($warnings.Count)"
    Write-Host "Full report: $ReportFile"
} else {
    Write-Host ($summary -join [Environment]::NewLine)
}

if ($failures.Count -gt 0) {
    exit 1
}
exit 0
