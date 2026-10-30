$ErrorActionPreference = 'Stop'
$projectDirectory = Split-Path -Parent $PSScriptRoot
Push-Location -LiteralPath $projectDirectory
try {
    if (-not (Get-Command node -ErrorAction SilentlyContinue)) {
        throw 'Instalá Node.js 24 LTS (mínimo 22.15) y volvé a abrir la terminal.'
    }
    if (-not (Test-Path -LiteralPath (Join-Path $projectDirectory 'node_modules'))) {
        throw 'Ejecutá npm install en la raíz del proyecto antes de iniciar.'
    }
    if (-not (Test-Path -LiteralPath (Join-Path $projectDirectory '.env'))) {
        Write-Host 'No hay .env: OpenArg y acceso institucional necesitan configuración. Ver README.md.'
    }
    & npm.cmd run dev
    exit $LASTEXITCODE
} finally {
    Pop-Location
}
