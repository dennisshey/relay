param(
    [Parameter(Mandatory = $true)]
    [string]$Serial,
    [string]$Apk = (Join-Path $PSScriptRoot '../app/build/outputs/apk/release/app-release.apk'),
    [string]$Adb = $(if ($env:ANDROID_HOME) {
        Join-Path $env:ANDROID_HOME 'platform-tools/adb.exe'
    } else {
        Join-Path $env:LOCALAPPDATA 'Android/Sdk/platform-tools/adb.exe'
    })
)

$ErrorActionPreference = 'Stop'
$package = 'com.sidephone.aviary'

function Invoke-Adb {
    param([string[]]$AdbArgs)
    $output = & $Adb -s $Serial @AdbArgs 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw "adb failed: $($output -join [Environment]::NewLine)"
    }
    return $output
}

if (-not (Test-Path -LiteralPath $Apk -PathType Leaf)) {
    throw "Release APK missing: $Apk. Run :app:assembleRelease first."
}

Invoke-Adb -AdbArgs @('install', '-r', (Resolve-Path -LiteralPath $Apk).Path)
$packageInfo = Invoke-Adb -AdbArgs @('shell', 'dumpsys', 'package', $package)
if (($packageInfo -join "`n") -match '\bDEBUGGABLE\b') {
    throw 'The installed APK is debuggable. Build and install the release variant.'
}

# A sideload on SP-01 can leave the APK in verify-only mode. Compile before launching so
# scrolling does not depend on Android eventually performing idle/background optimization.
Write-Host 'Compiling installed release for speed (this can take a few minutes)...'
$compiled = Invoke-Adb -AdbArgs @('shell', 'cmd', 'package', 'compile', '-m', 'speed', '-f', $package)
if (($compiled -join "`n") -notmatch '(?m)^Success\s*$') {
    throw "Android did not confirm compilation: $($compiled -join ' ')"
}

# Reload the compiled code. Force-stop preserves the app's messages and account data.
Invoke-Adb -AdbArgs @('shell', 'am', 'force-stop', $package)
Invoke-Adb -AdbArgs @('shell', 'am', 'start', '-W', '-n', "$package/.MainActivity")
Write-Host 'Release installed, compiled, and started.'
