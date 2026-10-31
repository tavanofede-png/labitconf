param([switch]$Offline, [switch]$Install)
$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
$androidProject = Join-Path $repoRoot 'apps\android'
$legacyTools = 'C:\Users\tavan\Downloads\AI Build Lab\Neto\.tools'
if (-not $env:JAVA_HOME) {
    $jdkDirectory = Join-Path $legacyTools 'jdk\jdk-21.0.12.1+1'
    if (Test-Path -LiteralPath (Join-Path $jdkDirectory 'bin\java.exe')) { $env:JAVA_HOME = $jdkDirectory }
}
if (-not $env:ANDROID_HOME) {
    $sdkDirectory = Join-Path $legacyTools 'android-sdk'
    if (Test-Path -LiteralPath $sdkDirectory) { $env:ANDROID_HOME = $sdkDirectory }
}
if (-not $env:JAVA_HOME -or -not $env:ANDROID_HOME) { throw 'Configurá JAVA_HOME (JDK 17+) y ANDROID_HOME (SDK 36 / build-tools 35.0.0).' }
$env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
$env:GRADLE_USER_HOME = Join-Path $repoRoot '.cache\gradle'
$env:ANDROID_USER_HOME = Join-Path $repoRoot '.cache\android-user'
$env:PATH = (Join-Path $env:JAVA_HOME 'bin') + ';' + $env:PATH
$sdkEscaped = $env:ANDROID_HOME.Replace('\', '/')
[IO.File]::WriteAllText((Join-Path $androidProject 'local.properties'), "sdk.dir=$sdkEscaped`n")
$gradleArguments = @('--no-daemon', ':app:testDebugUnitTest', ':app:assembleDebug')
if ($Offline) { $gradleArguments += '--offline' }
Push-Location -LiteralPath $androidProject
try {
    & .\gradlew.bat @gradleArguments
    if ($LASTEXITCODE -ne 0) { throw "Gradle terminó con código $LASTEXITCODE" }
    $apk = Join-Path $androidProject 'app\build\outputs\apk\debug\app-debug.apk'
    Write-Host "APK listo: $apk"
    $artifactDirectory = Join-Path $repoRoot 'artifacts'
    New-Item -ItemType Directory -Path $artifactDirectory -Force | Out-Null
    if ((Get-Item -LiteralPath $artifactDirectory).Attributes -band [IO.FileAttributes]::ReparsePoint) { throw 'La carpeta de artifacts no puede ser un enlace.' }
    $distribution = Join-Path $artifactDirectory 'nieto-debug.apk'
    $temporary = Join-Path $artifactDirectory 'nieto-debug.building.apk'
    $manifest = Join-Path $artifactDirectory 'android-build.json'
    $manifestTemporary = Join-Path $artifactDirectory 'android-build.building.json'
    $checksumTemporary = Join-Path $artifactDirectory 'SHA256SUMS.building.txt'
    try {
        $outputMetadata = Get-Content -LiteralPath (Join-Path $androidProject 'app\build\outputs\apk\debug\output-metadata.json') -Raw | ConvertFrom-Json
        if ($outputMetadata.applicationId -ne 'org.nieto.assistant' -or @($outputMetadata.elements).Count -ne 1) { throw 'La compilación no identifica un único APK de NIETO.' }
        $variant = $outputMetadata.elements[0]
        Copy-Item -LiteralPath $apk -Destination $temporary -Force
        $builtHash = (Get-FileHash -LiteralPath $apk -Algorithm SHA256).Hash
        if ((Get-FileHash -LiteralPath $temporary -Algorithm SHA256).Hash -ne $builtHash) { throw 'La copia del APK no coincide con la compilación.' }
        Move-Item -LiteralPath $temporary -Destination $distribution -Force
        if ((Get-FileHash -LiteralPath $distribution -Algorithm SHA256).Hash -ne $builtHash) { throw 'La distribución del APK no coincide con la compilación.' }
        $buildManifest = [ordered]@{ schemaVersion=1; apkFile='nieto-debug.apk'; applicationId=$outputMetadata.applicationId; versionCode=$variant.versionCode; versionName=$variant.versionName; builtAt=[DateTime]::UtcNow.ToString('o'); size=(Get-Item -LiteralPath $distribution).Length; sha256=$builtHash.ToLowerInvariant() }
        [IO.File]::WriteAllText($manifestTemporary, (($buildManifest | ConvertTo-Json) + "`n"), [Text.UTF8Encoding]::new($false))
        Move-Item -LiteralPath $manifestTemporary -Destination $manifest -Force
        [IO.File]::WriteAllText($checksumTemporary, ($builtHash.ToLowerInvariant() + "  nieto-debug.apk`n"), [Text.UTF8Encoding]::new($false))
        Move-Item -LiteralPath $checksumTemporary -Destination (Join-Path $artifactDirectory 'SHA256SUMS.txt') -Force
        Write-Host "APK de distribución actualizado: $distribution (SHA256 $builtHash)"
        Write-Host "Manifiesto de versión y SHA256SUMS actualizados: $manifest"
    } finally { foreach ($temporaryArtifact in @($temporary,$manifestTemporary,$checksumTemporary)) { if (Test-Path -LiteralPath $temporaryArtifact) { Remove-Item -LiteralPath $temporaryArtifact -Force } } }
    if ($Install) {
        & (Join-Path $env:ANDROID_HOME 'platform-tools\adb.exe') install -r $apk
        if ($LASTEXITCODE -ne 0) { throw 'No se pudo instalar el APK. Revisá el teléfono conectado y la depuración USB.' }
    }
} finally { Pop-Location }
