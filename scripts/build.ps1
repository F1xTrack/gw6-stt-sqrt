param(
    [string]$SdkPath = $env:ANDROID_SDK_ROOT,
    [string]$JavaPath = $env:JAVA_HOME
)
$ErrorActionPreference = 'Stop'
$taskRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
if ($JavaPath) { $env:JAVA_HOME = $JavaPath }
if ($SdkPath) {
    $taskSdk = [IO.Path]::GetFullPath($SdkPath).Replace('\', '/')
    [IO.File]::WriteAllText((Join-Path $taskRoot 'local.properties'), "sdk.dir=$taskSdk`n", [Text.UTF8Encoding]::new($false))
}
Push-Location $taskRoot
try {
    & .\gradlew.bat :core:test :phone:assembleDebug :wear:assembleDebug :phone:lintDebug :wear:lintDebug --console plain
    if ($LASTEXITCODE -ne 0) { throw 'Build or validation failed.' }
    $taskArtifacts = Join-Path $taskRoot 'artifacts'
    New-Item -ItemType Directory -Path $taskArtifacts -Force | Out-Null
    Copy-Item -LiteralPath 'phone\build\outputs\apk\debug\phone-debug.apk' -Destination (Join-Path $taskArtifacts 'gw6-voice-phone.apk')
    Copy-Item -LiteralPath 'wear\build\outputs\apk\debug\wear-debug.apk' -Destination (Join-Path $taskArtifacts 'gw6-voice-wear.apk')
    Get-FileHash -LiteralPath (Join-Path $taskArtifacts 'gw6-voice-phone.apk'),(Join-Path $taskArtifacts 'gw6-voice-wear.apk') -Algorithm SHA256 |
        ForEach-Object { $_.Hash.ToLowerInvariant() + '  ' + [IO.Path]::GetFileName($_.Path) } |
        Set-Content -LiteralPath (Join-Path $taskArtifacts 'SHA256.txt') -Encoding utf8
    Write-Output "APKs: $taskArtifacts"
} finally { Pop-Location }
