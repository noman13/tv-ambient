# The only build entry point on Windows PowerShell; same as scripts/build.sh (see there). Everything runs in Docker.
#
#   .\scripts\build.ps1                    release APK -> dist\tv-ambient-<version>.apk (debug-signed)
#   $env:KEYSTORE_DIR='D:\path\to\keys'; .\scripts\build.ps1    release APK signed with keystore.properties
#   .\scripts\build.ps1 debug | test | shell
param([ValidateSet('release', 'debug', 'test', 'shell')][string]$What = 'release')
$ErrorActionPreference = 'Stop'

$Root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$Image = if ($env:IMAGE) { $env:IMAGE } else { 'tv-ambient-builder' }
$GradleVolume = if ($env:GRADLE_VOLUME) { $env:GRADLE_VOLUME } else { 'tv-ambient-builder-gradle' }
$CargoVolume = if ($env:CARGO_VOLUME) { $env:CARGO_VOLUME } else { 'tv-ambient-builder-cargo' }

if (-not (Get-Command docker -ErrorAction SilentlyContinue)) { throw 'Docker is not installed or not on PATH.' }

docker image inspect $Image *> $null
if ($LASTEXITCODE -ne 0) {
    Write-Host "Building the $Image image once (several minutes)..."
    docker build --platform linux/amd64 -t $Image (Join-Path $Root 'docker')
    if ($LASTEXITCODE -ne 0) { throw 'docker build failed' }
}

$keyArgs = @()
$gradleProps = @()
if ($What -eq 'release' -and $env:KEYSTORE_DIR) {
    $props = Join-Path $env:KEYSTORE_DIR 'keystore.properties'
    if (-not (Test-Path $props)) { throw "No keystore.properties in $env:KEYSTORE_DIR" }
    $keyArgs = @('-v', "$((Resolve-Path $env:KEYSTORE_DIR).Path):/keys:ro")
    $gradleProps = @('-PkeystoreProperties=/keys/keystore.properties')
    Write-Host "Signing with $props"
} elseif ($What -eq 'release') {
    Write-Host "No KEYSTORE_DIR: the release APK is signed with the build container's debug key."
}

function Invoke-Build([string[]]$Command, [switch]$Interactive) {
    $dockerArgs = @('run', '--rm', '--platform', 'linux/amd64')
    if ($Interactive) { $dockerArgs += '-it' }
    $dockerArgs += $keyArgs + @('-v', "${Root}:/src", '-v', "${GradleVolume}:/gradle", '-v', "${CargoVolume}:/cargo",
        '-w', '/src', '-e', 'ANDROID_USER_HOME=/gradle/.android', '-e', 'CARGO_HOME=/cargo',
        '-e', 'CARGO_TARGET_DIR=/src/target', $Image) + $Command
    & docker @dockerArgs
    if ($LASTEXITCODE -ne 0) { throw "build step failed: $($Command -join ' ')" }
}

$version = (Select-String -Path (Join-Path $Root 'app\build.gradle') -Pattern "versionName '(.*)'").Matches[0].Groups[1].Value
$dist = Join-Path $Root 'dist'
switch ($What) {
    'test' { Invoke-Build @('cargo', 'test', '-p', 'tvambient-core') }
    'shell' { Invoke-Build @('bash') -Interactive }
    'debug' {
        Invoke-Build @('sh', './gradlew', '--no-daemon', '--console=plain', 'assembleDebug')
        New-Item -ItemType Directory -Force $dist | Out-Null
        Copy-Item (Join-Path $Root 'app\build\outputs\apk\debug\app-debug.apk') (Join-Path $dist "tv-ambient-$version-debug.apk")
        Write-Host "Built dist\tv-ambient-$version-debug.apk"
    }
    'release' {
        Invoke-Build (@('sh', './gradlew', '--no-daemon', '--console=plain') + $gradleProps + @('assembleRelease'))
        New-Item -ItemType Directory -Force $dist | Out-Null
        Copy-Item (Join-Path $Root 'app\build\outputs\apk\release\app-release.apk') (Join-Path $dist "tv-ambient-$version.apk")
        Write-Host "Built dist\tv-ambient-$version.apk"
    }
}
