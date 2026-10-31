param([switch]$Install, [switch]$WithDeviceTests, [string]$DeviceSerial = '', [string]$ApiUrl = 'https://tino-api-labitconf.vercel.app')
$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
$toolsRoot = 'C:\Users\tavan\Downloads\AI Build Lab\Neto\.tools'
if (-not $env:JAVA_HOME) { $env:JAVA_HOME = Join-Path $toolsRoot 'jdk\jdk-21.0.12.1+1' }
if (-not $env:ANDROID_HOME) { $env:ANDROID_HOME = Join-Path $toolsRoot 'android-sdk' }
$env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
$env:GRADLE_USER_HOME = Join-Path $repoRoot '.cache\gradle'
$env:ANDROID_USER_HOME = Join-Path $repoRoot '.cache\android-user'
$env:PATH = (Join-Path $env:JAVA_HOME 'bin') + ';' + $env:PATH
$env:TINO_API_URL = $ApiUrl
$env:TINO_KEYSTORE_FILE = Join-Path $repoRoot '.cache\signing\tino-release.jks'
$signingFile = Join-Path $repoRoot '.cache\secrets\tino-release-signing.env'
if (-not (Test-Path -LiteralPath $env:TINO_KEYSTORE_FILE) -or -not (Test-Path -LiteralPath $signingFile)) { throw 'Falta la clave privada de firma de TINO. Restaurá el respaldo seguro; no generes otra clave para actualizar instalaciones existentes.' }
$signingLine = [IO.File]::ReadAllLines($signingFile) | Where-Object { $_.StartsWith('TINO_KEYSTORE_PASSWORD=') } | Select-Object -First 1
if (-not $signingLine) { throw 'Falta la configuración de firma.' }
$env:TINO_KEYSTORE_PASSWORD = $signingLine.Substring('TINO_KEYSTORE_PASSWORD='.Length)
Push-Location -LiteralPath (Join-Path $repoRoot 'apps\android')
try {
    $tasks = @('--no-daemon', ':app:testDebugUnitTest', ':app:assembleRelease')
    if ($WithDeviceTests) { $tasks += ':app:assembleReleaseAndroidTest' }
    & .\gradlew.bat @tasks
    if ($LASTEXITCODE -ne 0) { throw 'No se pudo compilar la versión firmada de TINO.' }
    $output = Join-Path (Get-Location) 'app\build\outputs\apk\release'
    $metadata = Get-Content -LiteralPath (Join-Path $output 'output-metadata.json') -Raw | ConvertFrom-Json
    if ($metadata.applicationId -ne 'org.nieto.assistant.tino' -or @($metadata.elements).Count -ne 1) { throw 'Identificador o variantes del APK de producción inesperados.' }
    $element = $metadata.elements[0]
    $apk = Join-Path $output $element.outputFile
    $artifacts = Join-Path $repoRoot 'artifacts'
    New-Item -ItemType Directory -Path $artifacts -Force | Out-Null
    $published = Join-Path $artifacts 'tino-release.apk'
    Copy-Item -LiteralPath $apk -Destination $published -Force
    $sha = (Get-FileHash -LiteralPath $published -Algorithm SHA256).Hash.ToLowerInvariant()
    $manifest = [ordered]@{ schemaVersion=1; apkFile='tino-release.apk'; applicationId=$metadata.applicationId; versionCode=$element.versionCode; versionName=$element.versionName; apiUrl=$ApiUrl; builtAt=[DateTime]::UtcNow.ToString('o'); size=(Get-Item -LiteralPath $published).Length; sha256=$sha; buildType='release' }
    [IO.File]::WriteAllText((Join-Path $artifacts 'tino-release.json'), (($manifest | ConvertTo-Json)+"`n"), [Text.UTF8Encoding]::new($false))
    [IO.File]::WriteAllText((Join-Path $artifacts 'SHA256SUMS.release.txt'), ($sha+"  tino-release.apk`n"), [Text.UTF8Encoding]::new($false))
    Write-Host "APK de producción firmado: $published"
    Write-Host "SHA256: $sha"
    if ($Install) {
        if (-not $DeviceSerial) { throw 'Indicá DeviceSerial para instalar solo en la tablet elegida.' }
        & (Join-Path $env:ANDROID_HOME 'platform-tools\adb.exe') -s $DeviceSerial install -r $published
        if ($LASTEXITCODE -ne 0) { throw 'No se pudo instalar TINO en la tablet.' }
        if ($WithDeviceTests) {
            $testApk = Join-Path (Get-Location) 'app\build\outputs\apk\androidTest\release\app-release-androidTest.apk'
            & (Join-Path $env:ANDROID_HOME 'platform-tools\adb.exe') -s $DeviceSerial install -r $testApk
            if ($LASTEXITCODE -ne 0) { throw 'No se pudo instalar el verificador nativo.' }
            & (Join-Path $env:ANDROID_HOME 'platform-tools\adb.exe') -s $DeviceSerial shell am instrument -w org.nieto.assistant.tino.test/org.nieto.assistant.presence.VoiceSmokeInstrumentation
            if ($LASTEXITCODE -ne 0) { throw 'El verificador nativo terminó con error.' }
        }
    }
} finally {
    Pop-Location
    Remove-Item Env:\TINO_KEYSTORE_PASSWORD -ErrorAction SilentlyContinue
}
