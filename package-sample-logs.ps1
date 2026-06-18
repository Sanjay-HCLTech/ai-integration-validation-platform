param(
    [string]$SampleDir = "reference-log-samples",
    [string]$OutputDir = "sample-log-files",
    [string]$ZipName = "sample-log-files.zip"
)

$ErrorActionPreference = "Stop"

$workspace = Split-Path -Parent $MyInvocation.MyCommand.Path
$sourceDir = Join-Path $workspace $SampleDir
$targetDir = Join-Path $workspace $OutputDir
$zipPath = Join-Path $workspace $ZipName

if (-not (Test-Path -LiteralPath $sourceDir)) {
    throw "Sample directory not found: $sourceDir"
}

& (Join-Path $workspace "verify-reference-log-samples.ps1") -SampleDir $SampleDir

New-Item -ItemType Directory -Force -Path $targetDir | Out-Null
Get-ChildItem -LiteralPath $targetDir -File -ErrorAction SilentlyContinue | Remove-Item -Force

Get-ChildItem -LiteralPath $sourceDir -File |
    Where-Object { $_.Name -ne ".gitignore" } |
    ForEach-Object {
        Copy-Item -LiteralPath $_.FullName -Destination (Join-Path $targetDir $_.Name) -Force
    }

if (Test-Path -LiteralPath $zipPath) {
    Remove-Item -LiteralPath $zipPath -Force
}

Compress-Archive -Path (Join-Path $targetDir "*") -DestinationPath $zipPath -Force

Write-Host ""
Write-Host "Packaged reference log samples:"
Get-ChildItem -LiteralPath $targetDir -File | Select-Object Name, Length, LastWriteTime | Format-Table -AutoSize
Write-Host "Zip: $zipPath"
