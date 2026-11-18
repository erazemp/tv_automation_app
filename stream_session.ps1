<#
.SYNOPSIS
  Streams YouTube -> Twitch -> Netflix on an Android/Google TV over ADB,
  each for a set time (default 60 min), then returns to the home screen.
  Logs launch/start/end times per service + a heartbeat log of playback state.

.EXAMPLE
  .\run.bat -Device 192.168.137.109:41234
.EXAMPLE  Quick test, 1 minute per service:
  .\run.bat -Device 192.168.137.109:41234 -DurationMinutes 1
.EXAMPLE  Choose content:
  .\run.bat -Device 192.168.137.109:41234 -YouTubeVideoId dQw4w9WgXcQ -TwitchChannel shroud -NetflixTitleId 80057281
#>
param(
  [string]$Device = "",                                # ip:port; empty = auto-detect
  [double]$DurationMinutes = 60,
  [string]$YouTubeVideoId = "jfKfPfyJRdk",              # long/24-7 live video
  [string]$TwitchChannel  = "",                         # empty = play Twitch's featured stream
  [string]$NetflixTitleId = "80057281",                 # netflix.com/watch/<id>
  [int]$HeartbeatSeconds  = 60,
  [string]$Adb    = "$env:USERPROFILE\platform-tools\adb.exe",
  [string]$LogDir = ""
)

$ErrorActionPreference = "Continue"
if (-not $LogDir) {
  $here = Split-Path -Parent $MyInvocation.MyCommand.Path
  if (-not $here) { $here = (Get-Location).Path }
  $LogDir = Join-Path $here "logs"
}
New-Item -ItemType Directory -Force -Path $LogDir | Out-Null
$stamp     = Get-Date -Format "yyyyMMdd_HHmmss"
$DetailLog = Join-Path $LogDir "session_$stamp.log"
$CsvLog    = Join-Path $LogDir "streams.csv"
if (-not (Test-Path $CsvLog)) {
  Add-Content $CsvLog "session,service,package,content,launched,start,end,duration_min,playback_confirmed,heartbeats_playing,heartbeats_not_playing"
}

function TS([datetime]$d) { $d.ToString("yyyy-MM-dd HH:mm:ss") }
function Log([string]$msg) { $l = "$(TS (Get-Date))  $msg"; Write-Host $l; Add-Content -Path $DetailLog -Value $l }

function AdbRaw { (& $Adb @args 2>&1 | ForEach-Object { "$_" } | Out-String).Trim() }
function AdbShell([string]$cmd) { AdbRaw -s $Device shell $cmd }

# Find the TV: already-connected network device, else mDNS (wireless debugging) discovery.
function Find-Device {
  $lines = (AdbRaw devices) -split "`r?`n"
  $d = $lines | Where-Object { $_ -match '^(\S+:\d+)\s+device$' } | ForEach-Object { ($_ -split '\s+')[0] } | Select-Object -First 1
  if ($d) { return $d }
  $m = (AdbRaw mdns services) -split "`r?`n" | Where-Object { $_ -match '_adb-tls-connect' } | Select-Object -First 1
  if ($m -and $m -match '(\d+\.\d+\.\d+\.\d+:\d+)') {
    AdbRaw connect $Matches[1] | Out-Null; Start-Sleep 2; return $Matches[1]
  }
  return $null
}
function Key([string]$code)     { AdbShell "input keyevent $code" | Out-Null }

function Ensure-Connected {
  $s = AdbRaw -s $Device get-state
  if ($s -eq "device") { return $true }
  Log "ADB not connected ($s) - reconnecting to $Device"
  AdbRaw connect $Device | Out-Null
  Start-Sleep 3
  $s = AdbRaw -s $Device get-state
  if ($s -eq "device") { Log "Reconnected"; return $true }
  Log "WARNING: still not connected ($s). If the TV rebooted/changed network, the port changed."
  return $false
}

function Find-Package([string[]]$known, [string]$pattern) {
  $pkgs = (AdbShell "pm list packages") -split "`r?`n" |
          ForEach-Object { ($_ -replace '^package:', '').Trim() } | Where-Object { $_ }
  foreach ($k in $known) { if ($pkgs -contains $k) { return $k } }
  return ($pkgs | Where-Object { $_ -match $pattern } | Select-Object -First 1)
}

function Get-Foreground {
  $o = AdbShell "dumpsys activity activities | grep -E 'mResumedActivity|topResumedActivity'"
  if ($o -match '\s([A-Za-z0-9_.]+)/') { return $Matches[1] }
  return "unknown"
}

# Reads Android media sessions; returns playing / paused / buffering / stopped / none ...
function Get-PlaybackState([string]$pkg) {
  $cur = $null; $result = "none"
  foreach ($l in ((AdbShell "dumpsys media_session") -split "`r?`n")) {
    if ($l -match '^\s*package=([A-Za-z0-9_.]+)') { $cur = $Matches[1]; continue }
    if ($cur -eq $pkg -and $l -match 'state=PlaybackState \{state=(?:[A-Z_]+\()?(\d+)') {
      $name = switch ([int]$Matches[1]) {
        0 {"none"} 1 {"stopped"} 2 {"paused"} 3 {"playing"} 6 {"buffering"} 7 {"error"} 8 {"connecting"} default {"state$($Matches[1])"}
      }
      if ($name -eq "playing") { return "playing" }
      $result = $name
    }
  }
  return $result
}

# After launch: wait for playback, nudging past profile pickers / featured tiles.
function Ensure-Playing([string]$pkg, [string]$label) {
  for ($i = 1; $i -le 5; $i++) {
    Start-Sleep 12
    $st = Get-PlaybackState $pkg; $fg = Get-Foreground
    Log "  [$label] startup check ${i}: foreground=$fg playback=$st"
    if ($st -eq "playing") { return $true }
    if ($st -eq "paused") { Key KEYCODE_MEDIA_PLAY } else { Key KEYCODE_DPAD_CENTER }
  }
  return $false
}

function Watch([string]$pkg, [string]$label, [datetime]$endTime, [scriptblock]$Launch) {
  $ok = 0; $bad = 0; $pausedRun = 0
  while ((Get-Date) -lt $endTime) {
    $left = ($endTime - (Get-Date)).TotalSeconds
    Start-Sleep -Seconds ([int][Math]::Max(1, [Math]::Min($HeartbeatSeconds, $left)))
    if (-not (Ensure-Connected)) { $bad++; continue }
    $st = Get-PlaybackState $pkg; $fg = Get-Foreground
    if ($st -eq "playing") { $ok++; $pausedRun = 0 } else { $bad++ }
    Log "  [$label] heartbeat: foreground=$fg playback=$st"
    if ($fg -ne $pkg -and $fg -ne "unknown") {
      Log "  [$label] app left foreground -> relaunching"; & $Launch | Out-Null
    } elseif ($st -eq "paused") {
      $pausedRun++
      # 1st time: MEDIA_PLAY; again: OK button (dismisses "Are you still watching?")
      if ($pausedRun -eq 1) { Key KEYCODE_MEDIA_PLAY } else { Key KEYCODE_DPAD_CENTER }
      Log "  [$label] paused -> nudged play"
    }
  }
  return @($ok, $bad)
}

function Run-Service([string]$name, [string]$pkg, [string]$content, [scriptblock]$Launch) {
  if (-not $pkg) {
    Log "SKIP $name - app not found on TV"
    Add-Content $CsvLog "$stamp,$name,,$content,,,,0,False,0,0"; return
  }
  Ensure-Connected | Out-Null
  $launched = Get-Date
  Log "=== $name ($pkg) launching: $content"
  & $Launch | Out-Null
  $confirmed = Ensure-Playing $pkg $name
  $start = Get-Date
  Log "START $name  $(TS $start)  (playback confirmed: $confirmed)"
  $stats = Watch $pkg $name ($start.AddMinutes($DurationMinutes)) $Launch
  $end = Get-Date
  AdbShell "am force-stop $pkg" | Out-Null
  $dur = [Math]::Round(($end - $start).TotalMinutes, 2)
  Log "END   $name  $(TS $end)  ($dur min, heartbeats playing=$($stats[0]) not=$($stats[1]))"
  Add-Content $CsvLog ("{0},{1},{2},{3},{4},{5},{6},{7},{8},{9},{10}" -f `
    $stamp, $name, $pkg, $content, (TS $launched), (TS $start), (TS $end), $dur, $confirmed, $stats[0], $stats[1])
}

# ---------------- main ----------------
if (-not (Test-Path $Adb)) { Write-Host "adb not found at $Adb (use -Adb <path>)"; exit 1 }
if (-not $Device) {
  $Device = Find-Device
  if (-not $Device) {
    Write-Host "Could not find the TV automatically. Run: adb connect <ip>:<port> (port from TV > Wireless debugging), or pass -Device <ip>:<port>"
    exit 1
  }
}
Log "Session $stamp - device $Device - $DurationMinutes min per service"
if (-not (Ensure-Connected)) { Log "Cannot reach TV. Check the port on the Wireless debugging screen."; exit 1 }
Log ("TV: " + (AdbShell "getprop ro.product.model").Trim() + ", Android " + (AdbShell "getprop ro.build.version.release").Trim())
Key KEYCODE_WAKEUP

$yt = Find-Package @("com.google.android.youtube.tv", "com.google.android.youtube") 'youtube\.tv|android\.youtube$'
$tw = Find-Package @("tv.twitch.android.app", "tv.twitch.android.viewer") 'twitch'
$nf = Find-Package @("com.netflix.ninja", "com.netflix.mediaclient") 'netflix'
Log "Packages: YouTube=$yt  Twitch=$tw  Netflix=$nf"

try {
  Run-Service "YouTube" $yt "https://www.youtube.com/watch?v=$YouTubeVideoId" {
    AdbShell "am start -a android.intent.action.VIEW -d 'https://www.youtube.com/watch?v=$YouTubeVideoId' $yt"
  }

  $twContent = if ($TwitchChannel) { "https://www.twitch.tv/$TwitchChannel" } else { "featured stream" }
  Run-Service "Twitch" $tw $twContent {
    if ($TwitchChannel) {
      AdbShell "am start -a android.intent.action.VIEW -d 'https://www.twitch.tv/$TwitchChannel' $tw"
    } else {
      AdbShell "monkey -p $tw -c android.intent.category.LEANBACK_LAUNCHER 1"
    }
  }

  Run-Service "Netflix" $nf "https://www.netflix.com/watch/$NetflixTitleId" {
    $o = AdbShell "am start -c android.intent.category.LEANBACK_LAUNCHER -a android.intent.action.VIEW -d 'https://www.netflix.com/watch/$NetflixTitleId' -f 0x10808000 -e source 30 $nf/.MainActivity"
    if ($o -match 'Error') { AdbShell "am start -a android.intent.action.VIEW -d 'https://www.netflix.com/watch/$NetflixTitleId' $nf" }
  }
}
finally {
  Key KEYCODE_HOME
  Log "Back to home screen. Session finished."
  Log "Logs: $DetailLog  |  $CsvLog"
}
