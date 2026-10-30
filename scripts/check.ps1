$ErrorActionPreference = 'Stop'
$projectDirectory = Split-Path -Parent $PSScriptRoot
Push-Location -LiteralPath $projectDirectory
try {
    & npm.cmd run check
    exit $LASTEXITCODE
} finally {
    Pop-Location
}
