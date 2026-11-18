# Stream Test (Android TV)

Plays YouTube -> Twitch -> Netflix for N minutes each, then returns to Home.
Logs per-service launch/start/end times to `/sdcard/Android/data/com.erazem.streamtest/files/streams.csv`
plus a per-minute heartbeat log (`session.log`).

GitHub Actions builds the APK on every push to `main` and publishes it to the `apk` branch (`StreamTest.apk`).

One-time setup on the TV (over ADB):
```
adb install -r StreamTest.apk
adb shell settings put secure enabled_accessibility_services com.erazem.streamtest/com.erazem.streamtest.EngineService
adb shell settings put secure accessibility_enabled 1
adb shell cmd notification allow_listener com.erazem.streamtest/com.erazem.streamtest.NotifListener
```
Then open "Stream Test" from the TV apps row and press Start.
