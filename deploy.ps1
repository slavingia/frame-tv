# Installs frame.apk on a TV over adb, then pushes your settings from device.local.ps1 (not committed).
#   .\deploy.ps1 -Device 192.168.1.50
param([string]$Device, [string]$Adb = 'adb')
$ErrorActionPreference = 'Stop'
$here = $PSScriptRoot
$local = Join-Path $here 'device.local.ps1'
$Settings = @{}
if (Test-Path $local) { . $local }   # may set $Device, $Adb and $Settings
if (-not $Device) { throw 'Pass -Device <tv-ip> or set $Device in device.local.ps1' }

$target = if ($Device -match ':') { $Device } else { "${Device}:5555" }
& $Adb connect $target | Out-Host
& $Adb -s $target install -r "$here\frame.apk"
if ($LASTEXITCODE -ne 0) { throw 'adb install failed' }

$extras = @()
foreach ($k in $Settings.Keys) { $extras += '--es', $k, "'$($Settings[$k])'" }
& $Adb -s $target shell am start -n com.home.frame/.MainActivity @extras | Out-Host
Write-Host "Frame installed on $target"
