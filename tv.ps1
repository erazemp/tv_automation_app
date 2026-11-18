<#
  Control the on-TV streaming script.
    .\tv.bat start  [-DurationMinutes 60] [-YouTubeVideoId id] [-TwitchChannel name] [-NetflixTitleId id]
    .\tv.bat status     # is it running + last log lines
    .\tv.bat logs       # download logs to .\logs\tv\
    .\tv.bat stop
  Optional: -Device ip:port (otherwise auto-detected)
#>
param(
  [Parameter(Position = 0)][ValidateSet("start", "status", "logs", "stop")][string]$Action = "status",
  [string]$Device = "",
  [double]$DurationMinutes = 60,
  [string]$YouTubeVideoId = "jfKfPfyJRdk",
  [string]$TwitchChannel = "",
  [string]$NetflixTitleId = "80057281",
  [string]$Adb = "$env:USERPROFILE\platform-tools\adb.exe"
)
$ErrorActionPreference = "Continue"
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$Remote = "/data/local/tmp/nlsn"

function AdbRaw { (& $Adb @args 2>&1 | ForEach-Object { "$_" } | Out-String).Trim() }
function Find-Device {
  $d = (AdbRaw devices) -split "`r?`n" | Where-Object { $_ -match '^(\S+:\d+)\s+device$' } |
       ForEach-Object { ($_ -split '\s+')[0] } | Select-Object -First 1
  if ($d) { return $d }
  $m = (AdbRaw mdns services) -split "`r?`n" | Where-Object { $_ -match '_adb-tls-connect' } | Select-Object -First 1
  if ($m -and $m -match '(\d+\.\d+\.\d+\.\d+:\d+)') { AdbRaw connect $Matches[1] | Out-Null; Start-Sleep 2; return $Matches[1] }
  return $null
}

if (-not (Test-Path $Adb)) { Write-Host "adb not found at $Adb"; exit 1 }
if (-not $Device) { $Device = Find-Device }
if (-not $Device) {
  Write-Host "TV not found. Connect TV to the laptop hotspot, then: adb connect <ip>:<port> (TV > Developer options > Wireless debugging)"
  exit 1
}
function Sh([string]$c) { AdbRaw -s $Device shell $c }
Write-Host "TV: $Device"

switch ($Action) {
  "start" {
    $running = Sh "cat $Remote/pid 2>/dev/null"
    if ($running -and (Sh "kill -0 $running 2>/dev/null && echo yes") -eq "yes") { Write-Host "Already running (pid $running). Use 'stop' first."; exit 1 }
    Sh "mkdir -p $Remote" | Out-Null
    Write-Host (AdbRaw -s $Device push (Join-Path $here "tv_stream.sh") "$Remote/tv_stream.sh")
    $sec = [int]($DurationMinutes * 60)
    Sh "DUR=$sec YT='$YouTubeVideoId' TW='$TwitchChannel' NF='$NetflixTitleId' setsid nohup sh $Remote/tv_stream.sh >/dev/null 2>&1 </dev/null &" | Out-Null
    Start-Sleep 3
    Write-Host (Sh "tail -n 3 $Remote/session.log")
    Write-Host ""
    Write-Host "Started on the TV. Now switch the TV to your main Wi-Fi - streaming begins once it has internet."
    Write-Host "Total time ~ $([Math]::Round($DurationMinutes*3)) min. Afterwards reconnect to the hotspot and run: .\tv.bat logs"
  }
  "status" {
    $p = Sh "cat $Remote/pid 2>/dev/null"
    if ($p -and (Sh "kill -0 $p 2>/dev/null && echo yes") -eq "yes") { Write-Host "RUNNING (pid $p)" } else { Write-Host "Not running" }
    Write-Host (Sh "tail -n 15 $Remote/session.log 2>/dev/null")
  }
  "logs" {
    $dst = Join-Path $here "logs\tv"; New-Item -ItemType Directory -Force -Path $dst | Out-Null
    foreach ($f in "session.log", "streams.csv") { Write-Host (AdbRaw -s $Device pull "$Remote/$f" (Join-Path $dst $f)) }
    Write-Host "Saved to $dst"
  }
  "stop" {
    $p = Sh "cat $Remote/pid 2>/dev/null"
    if ($p) { Sh "kill $p; rm -f $Remote/pid; input keyevent KEYCODE_HOME" | Out-Null; Write-Host "Stopped pid $p" } else { Write-Host "Nothing running" }
  }
}
