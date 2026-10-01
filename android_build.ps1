# Rebuilds the Android APK with the current database: snapshot + photos -> APK -> output\.
# Run after changes on the PC so the phone receives them with the next installation.
$ErrorActionPreference = 'Stop'
Set-Location -LiteralPath $PSScriptRoot
$env:PYTHONUTF8 = '1'
$python = Join-Path $PSScriptRoot '.venv\Scripts\python.exe'
& $python -m rezhim.android_snapshot
if ($LASTEXITCODE -ne 0) { throw 'Snapshot build failed.' }
Push-Location (Join-Path $PSScriptRoot 'android')
try {
    # The wrapper downloads Gradle 8.14.3 once; afterwards it builds from the local cache without a network.
    & .\gradlew.bat -q testDebugUnitTest assembleRelease
    if ($LASTEXITCODE -ne 0) { throw 'APK build failed.' }
} finally { Pop-Location }
$version = (Select-String -Path 'android\app\build.gradle' -Pattern "versionName '([^']+)'").Matches[0].Groups[1].Value
$target = Join-Path $PSScriptRoot "output\Marki-UPE-$version.apk"
Copy-Item 'android\app\build\outputs\apk\release\app-release.apk' $target -Force
Write-Output $target
