param()

$ErrorActionPreference = "Stop"

$root = Split-Path -Parent $MyInvocation.MyCommand.Path

function Write-Step {
    param([string]$Message)
    Write-Host "[BUILD] $Message"
}

function Invoke-Maven {
    param([string[]]$Arguments)

    $mvn = (Get-Command mvn).Source
    if (-not (Test-Path -LiteralPath $mvn)) {
        throw "Maven command not found."
    }

    & $mvn @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "Maven failed: mvn $($Arguments -join ' ')"
    }
}

Push-Location $root
try {
    Write-Step "Enforcing configuration governance"
    & (Join-Path $root "enforce-config-governance.ps1") -SummaryOnly
    if (-not $?) {
        throw "Configuration governance failed."
    }

    # Keep the gateway runtime deterministic: install upstream modules first,
    # then compile the gateway against those freshly installed jars.
    Write-Step "Installing observability core"
    Invoke-Maven @("-pl", "integration-observability-core", "install")

    Write-Step "Installing execution core"
    Invoke-Maven @("-pl", "integration-execution-core", "install")

    Write-Step "Installing AI intelligence"
    Invoke-Maven @("-pl", "integration-ai-intelligence", "install")

    Write-Step "Compiling API gateway"
    Invoke-Maven @("-pl", "integration-api-gateway", "compile")
} finally {
    Pop-Location
}
