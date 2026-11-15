param([switch]$Offline, [string]$JavaHome, [string]$AndroidSdk, [string]$PythonExecutable = 'python')
$ErrorActionPreference = 'Stop'
$projectDir = Split-Path $PSScriptRoot -Parent
$previousJava = $env:JAVA_HOME
$previousAndroid = $env:ANDROID_HOME
Push-Location $projectDir
try {
    if ($JavaHome) { $env:JAVA_HOME = $JavaHome }
    if (-not $env:JAVA_HOME) {
        $existingJdk = Join-Path $projectDir '../Hyundai_ReviA/.phase0/jdk17/jdk-17.0.20.1+1'
        if (Test-Path -LiteralPath $existingJdk) { $env:JAVA_HOME = (Resolve-Path -LiteralPath $existingJdk).Path }
    }
    if ($AndroidSdk) { $env:ANDROID_HOME = $AndroidSdk }
    if (-not $env:ANDROID_HOME) { $env:ANDROID_HOME = Join-Path $env:LOCALAPPDATA 'Android/Sdk' }
    if (-not $env:JAVA_HOME) { throw 'Set JAVA_HOME to a JDK17 installation or pass -JavaHome.' }
    & $PythonExecutable -X utf8 tools/verify.py
    if ($LASTEXITCODE -ne 0) { throw 'Design integrity failed.' }
    $arguments = @('--no-daemon', '--console=plain', '--dependency-verification', 'strict',
        ':app:testDebugUnitTest', ':app:assembleDebug', ':app:assembleDebugAndroidTest', ':app:lintDebug')
    if ($Offline) { $arguments = @('--offline') + $arguments }
    $runId = [DateTime]::UtcNow.ToString('yyyyMMdd-HHmmss-fff')
    $logPath = "evidence/build-$runId.log"
    & ./gradlew.bat @arguments 2>&1 | Tee-Object -FilePath $logPath
    if ($LASTEXITCODE -ne 0) { throw "Gradle verification failed. See $logPath" }
    & $PythonExecutable -X utf8 tools/verify.py --report --log $logPath
    if ($LASTEXITCODE -ne 0) { throw 'Evidence collection failed.' }
} finally {
    $env:JAVA_HOME = $previousJava
    $env:ANDROID_HOME = $previousAndroid
    Pop-Location
}
