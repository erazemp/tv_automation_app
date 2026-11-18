#!/system/bin/sh
# Runs ON the TV. Started detached by tv.ps1. Env: DUR (sec), YT, TW, NF
DUR=${DUR:-3600}; YT=${YT:-jfKfPfyJRdk}; TW=${TW:-}; NF=${NF:-80057281}
DIR=/data/local/tmp/nlsn
LOG=$DIR/session.log; CSV=$DIR/streams.csv; HB=60
mkdir -p $DIR
echo $$ > $DIR/pid
ts(){ date '+%Y-%m-%d %H:%M:%S'; }
log(){ echo "$(ts)  $*" >> $LOG; }
[ -f $CSV ] || echo "service,package,content,launched,start,end,duration_s,playback_confirmed,hb_playing,hb_not_playing" > $CSV

has_internet(){
  ping -c1 -W3 8.8.8.8 >/dev/null 2>&1 && return 0
  dumpsys connectivity 2>/dev/null | grep -q 'VALIDATED' && return 0
  return 1
}
find_pkg(){ for p in "$@"; do pm list packages 2>/dev/null | sed 's/^package://' | grep -qx "$p" && { echo "$p"; return; }; done; }
fg_pkg(){ dumpsys activity activities 2>/dev/null | grep -E 'mResumedActivity|topResumedActivity' | head -1 | sed -E 's/.* ([A-Za-z0-9_.]+)\/.*/\1/'; }
play_state(){
  dumpsys media_session 2>/dev/null | awk -v p="$1" '
    /^[ \t]*package=/ { s=$0; sub(/.*package=/,"",s); split(s,a," "); cur=a[1]; next }
    cur==p && /state=PlaybackState \{state=/ { s=$0; sub(/.*state=PlaybackState \{state=/,"",s);
      if (match(s,/[0-9]+/)) { n=substr(s,RSTART,RLENGTH); if (n==3) { found=1; exit } last=n } }
    END { if (found) print "playing"; else if (last=="") print "none"; else if (last==2) print "paused";
          else if (last==6) print "buffering"; else print "state" last }' 2>/dev/null || echo none
}
key(){ input keyevent "$1"; }

launch(){ # $1 = service
  case "$1" in
    YouTube) am start -a android.intent.action.VIEW -d "https://www.youtube.com/watch?v=$YT" "$YT_PKG" >/dev/null 2>&1 ;;
    Twitch)  if [ -n "$TW" ]; then am start -a android.intent.action.VIEW -d "https://www.twitch.tv/$TW" "$TW_PKG" >/dev/null 2>&1
             else monkey -p "$TW_PKG" -c android.intent.category.LEANBACK_LAUNCHER 1 >/dev/null 2>&1; fi ;;
    Netflix) am start -c android.intent.category.LEANBACK_LAUNCHER -a android.intent.action.VIEW \
               -d "https://www.netflix.com/watch/$NF" -f 0x10808000 -e source 30 "$NF_PKG/.MainActivity" 2>&1 | grep -q Error && \
             am start -a android.intent.action.VIEW -d "https://www.netflix.com/watch/$NF" "$NF_PKG" >/dev/null 2>&1 ;;
  esac
}

run_service(){ # name pkg content
  name=$1; pkg=$2; content=$3
  if [ -z "$pkg" ]; then log "SKIP $name - app not installed"; echo "$name,,$content,,,,0,false,0,0" >> $CSV; return; fi
  launched=$(ts); log "=== $name ($pkg) launching: $content"
  launch "$name"
  confirmed=false; i=1
  while [ $i -le 5 ]; do
    sleep 12; st=$(play_state "$pkg"); fg=$(fg_pkg)
    log "  [$name] startup check $i: foreground=$fg playback=$st"
    [ "$st" = playing ] && { confirmed=true; break; }
    if [ "$st" = paused ]; then key KEYCODE_MEDIA_PLAY; else key KEYCODE_DPAD_CENTER; fi
    i=$((i+1))
  done
  start=$(ts); t0=$(date +%s); tend=$((t0+DUR)); ok=0; bad=0; pz=0
  log "START $name  $start  (playback confirmed: $confirmed)"
  while [ $(date +%s) -lt $tend ]; do
    left=$((tend-$(date +%s))); [ $left -gt $HB ] && left=$HB; [ $left -lt 1 ] && left=1
    sleep $left
    st=$(play_state "$pkg"); fg=$(fg_pkg)
    if [ "$st" = playing ]; then ok=$((ok+1)); pz=0; else bad=$((bad+1)); fi
    log "  [$name] heartbeat: foreground=$fg playback=$st"
    if [ -n "$fg" ] && [ "$fg" != "$pkg" ]; then log "  [$name] app left foreground -> relaunching"; launch "$name"
    elif [ "$st" = paused ]; then pz=$((pz+1)); if [ $pz -eq 1 ]; then key KEYCODE_MEDIA_PLAY; else key KEYCODE_DPAD_CENTER; fi; log "  [$name] paused -> nudged play"; fi
  done
  end=$(ts); am force-stop "$pkg"
  dur=$(( $(date +%s) - t0 ))
  log "END   $name  $end  (${dur}s, heartbeats playing=$ok not=$bad)"
  echo "$name,$pkg,$content,$launched,$start,$end,$dur,$confirmed,$ok,$bad" >> $CSV
}

log "Session start - $DUR s per service. Waiting for internet (switch TV to main Wi-Fi now)..."
n=0; until has_internet; do sleep 5; n=$((n+1)); [ $n -ge 180 ] && { log "No internet after 15 min - aborting"; rm -f $DIR/pid; exit 1; }; done
log "Internet OK"; sleep 10
key KEYCODE_WAKEUP
YT_PKG=$(find_pkg com.google.android.youtube.tv com.google.android.youtube)
TW_PKG=$(find_pkg tv.twitch.android.app tv.twitch.android.viewer)
[ -z "$TW_PKG" ] && TW_PKG=$(pm list packages 2>/dev/null | sed 's/^package://' | grep -i twitch | head -1)
NF_PKG=$(find_pkg com.netflix.ninja com.netflix.mediaclient)
log "Packages: YouTube=$YT_PKG Twitch=$TW_PKG Netflix=$NF_PKG"

run_service YouTube "$YT_PKG" "https://www.youtube.com/watch?v=$YT"
if [ -n "$TW" ]; then twc="https://www.twitch.tv/$TW"; else twc="featured stream"; fi
run_service Twitch "$TW_PKG" "$twc"
run_service Netflix "$NF_PKG" "https://www.netflix.com/watch/$NF"
key KEYCODE_HOME
log "Back to home screen. Session finished."
rm -f $DIR/pid
