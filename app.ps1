<#
  Helper for the "Stream Test" TV app.
    .\app.bat setup    # one-time: install StreamTest.apk (in this folder) + grant permissions
    .\app.bat logs     # download streams.csv + session.log to .\logs\app\
    .\app.bat start    # optional: start a session remotely (uses the settings saved in the app)
    .\app.bat status   # show last log lines
  Optional: -Device ip:port (otherwise auto-detected)
#>
param(
  [Parameter(Position = 0)][ValidateSet("setup", "logs", "start", "status")][string]$Action = "status",
  [string]$Device = "",
  [string]$Adb = "$env:USERPROFILE\platform-tools\adb.exe"
)
$ErrorActionPreference = "Continue"
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$Pkg = "com.erazem.streamtest"
$Files = "/sdcard/Android/data/$Pkg/files"

function AdbRaw { (& $Adb @args 2>&1 | ForEach-Object { "$_" } | Out-String).Trim() }
function Find-Device {
  $d = (AdbRaw devices) -split "`r?`n" | Where-Object { $_ -match '^(\S+)\s+device$' } |
       ForEach-Object { ($_ -split '\s+')[0] } | Select-Object -First 1
  if ($d) { return $d }
  $m = (AdbRaw mdns services) -split "`r?`n" | Where-Object { $_ -match '_adb-tls-connect' } | Select-Object -First 1
  if ($m -and $m -match '(\d+\.\d+\.\d+\.\d+:\d+)') { AdbRaw connect $Matches[1] | Out-Null; Start-Sleep 2; return $Matches[1] }
  return $null
}
if (-not (Test-Path $Adb)) { Write-Host "adb not found at $Adb"; exit 1 }
if (-not $Device) { $Device = Find-Device }
if (-not $Device) { Write-Host "TV not found. Run: adb connect <ip>:<port> (TV > Developer options > Wireless debugging)"; exit 1 }
function Sh([string]$c) { AdbRaw -s $Device shell $c }
Write-Host "TV: $Device"

switch ($Action) {
  "setup" {
    $apk = Join-Path $here "StreamTest.apk"
    if (-not (Test-Path $apk)) { Write-Host "StreamTest.apk not found in $here"; exit 1 }
    Write-Host (AdbRaw -s $Device install -r -g $apk)
    $svc = "$Pkg/$Pkg.EngineService"
    $cur = Sh "settings get secure enabled_accessibility_services"
    if ($cur -and $cur -ne "null" -and $cur -notmatch [regex]::Escape($svc)) { $new = "$cur`:$svc" } else { $new = $svc }
    Sh "settings put secure enabled_accessibility_services $new" | Out-Null
    Sh "settings put secure accessibility_enabled 1" | Out-Null
    Sh "cmd notification allow_listener $Pkg/$Pkg.NotifListener" | Out-Null
    Sh "appops set $Pkg SYSTEM_ALERT_WINDOW allow" | Out-Null
    Write-Host "Accessibility: $(Sh 'settings get secure enabled_accessibility_services')"
    Write-Host "Setup done. On the TV open Apps > 'Stream Test' - both lines should say ON."
  }
  "logs" {
    $dst = Join-Path $here "logs\app"; New-Item -ItemType Directory -Force -Path $dst | Out-Null
    foreach ($f in "streams.csv", "session.log") { Write-Host (AdbRaw -s $Device pull "$Files/$f" (Join-Path $dst $f)) }
    Write-Host "Saved to $dst"
  }
  "start" {
    Write-Host (Sh "am start -n $Pkg/.MainActivity --ez autostart true")
    Write-Host "Started. Switch the TV to the main Wi-Fi if it is on the hotspot."
  }
  "status" { Write-Host (Sh "tail -n 15 $Files/session.log") }
}
