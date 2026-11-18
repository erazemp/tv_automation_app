package com.erazem.streamtest;

import android.accessibilityservice.AccessibilityService;
import android.app.ActivityManager;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

/**
 * The engine. Runs as an accessibility service so it may launch apps from the background,
 * press "home" and click the focused button (profile pickers, "Are you still watching?").
 */
public class EngineService extends AccessibilityService {
    static volatile EngineService instance;
    static volatile boolean running = false;
    static volatile String current = "";
    private static volatile boolean cancel = false;
    private volatile String lastWindowPkg = "unknown";
    private static final int HEARTBEAT_S = 60;

    @Override protected void onServiceConnected() { instance = this; Logbook.line(this, "Engine ready"); }
    @Override public boolean onUnbind(Intent i) { instance = null; return super.onUnbind(i); }
    @Override public void onInterrupt() { }
    @Override public void onAccessibilityEvent(AccessibilityEvent e) {
        if (e != null && e.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && e.getPackageName() != null)
            lastWindowPkg = e.getPackageName().toString();
    }

    // ---------- public control ----------
    synchronized boolean start(final Config cfg) {
        if (running) return false;
        running = true; cancel = false;
        new Thread(() -> {
            try { session(cfg); }
            catch (Throwable t) { Logbook.line(this, "ERROR: " + t); }
            finally { running = false; current = ""; }
        }, "stream-session").start();
        return true;
    }
    static void stop() { cancel = true; }

    // ---------- session ----------
    private void session(Config cfg) {
        String sid = Logbook.stamp();
        Logbook.line(this, "Session " + sid + " - " + cfg.minutes + " min per service");
        if (!hasMediaAccess(this)) Logbook.line(this, "WARNING: no media-session access - playback state will show 'none'");
        String yt = findPkg("com.google.android.youtube.tv", "com.google.android.youtube");
        String tw = findPkg("tv.twitch.android.app", "tv.twitch.android.viewer");
        if (tw == null) tw = searchPkg("twitch");
        String nf = findPkg("com.netflix.ninja", "com.netflix.mediaclient");
        Logbook.line(this, "Packages: YouTube=" + yt + " Twitch=" + tw + " Netflix=" + nf);

        runService(sid, cfg, "YouTube", yt, "https://www.youtube.com/watch?v=" + cfg.yt);
        if (!cancel) runService(sid, cfg, "Twitch", tw,
                cfg.tw.isEmpty() ? "featured stream" : "https://www.twitch.tv/" + cfg.tw);
        if (!cancel) runService(sid, cfg, "Netflix", nf, "https://www.netflix.com/watch/" + cfg.nf);

        performGlobalAction(GLOBAL_ACTION_HOME);
        Logbook.line(this, cancel ? "Session stopped by user - back to home screen" : "Back to home screen. Session finished.");
    }

    private void runService(String sid, Config cfg, String name, String pkg, String content) {
        current = name;
        if (pkg == null) {
            Logbook.line(this, "SKIP " + name + " - app not installed");
            Logbook.csv(this, sid + "," + name + ",," + content + ",,,,0,false,0,0");
            return;
        }
        String launched = Logbook.ts();
        Logbook.line(this, "=== " + name + " (" + pkg + ") launching: " + content);
        launch(cfg, name, pkg);

        boolean confirmed = false;
        for (int i = 1; i <= 5 && !cancel; i++) {
            if (!sleep(12)) break;
            String st = state(pkg), fg = foreground();
            Logbook.line(this, "  [" + name + "] startup check " + i + ": foreground=" + fg + " playback=" + st);
            if ("playing".equals(st)) { confirmed = true; break; }
            if ("paused".equals(st)) play(pkg); else clickFocused();
        }

        String start = Logbook.ts();
        long t0 = System.currentTimeMillis(), tEnd = t0 + (long) (cfg.minutes * 60_000L);
        int ok = 0, bad = 0, pausedRun = 0;
        Logbook.line(this, "START " + name + "  " + start + "  (playback confirmed: " + confirmed + ")");
        while (!cancel && System.currentTimeMillis() < tEnd) {
            long left = (tEnd - System.currentTimeMillis()) / 1000;
            if (!sleep((int) Math.max(1, Math.min(HEARTBEAT_S, left)))) break;
            String st = state(pkg), fg = foreground();
            if ("playing".equals(st)) { ok++; pausedRun = 0; } else bad++;
            Logbook.line(this, "  [" + name + "] heartbeat: foreground=" + fg + " playback=" + st);
            if (!fg.equals(pkg) && !fg.equals("unknown") && !fg.equals("com.android.systemui")) {
                Logbook.line(this, "  [" + name + "] app left foreground -> relaunching");
                launch(cfg, name, pkg);
            } else if ("paused".equals(st)) {
                pausedRun++;
                if (pausedRun == 1) play(pkg); else clickFocused();   // 2nd time: dismiss "still watching?"
                Logbook.line(this, "  [" + name + "] paused -> nudged play");
            }
        }
        String end = Logbook.ts();
        long dur = (System.currentTimeMillis() - t0) / 1000;
        close(pkg);
        Logbook.line(this, "END   " + name + "  " + end + "  (" + dur + "s, heartbeats playing=" + ok + " not=" + bad + ")");
        Logbook.csv(this, sid + "," + name + "," + pkg + "," + content + "," + launched + "," + start + "," + end + ","
                + dur + "," + confirmed + "," + ok + "," + bad);
    }

    // ---------- app control ----------
    private void launch(Config cfg, String name, String pkg) {
        Intent i;
        try {
            switch (name) {
                case "YouTube":
                    i = view("https://www.youtube.com/watch?v=" + cfg.yt, pkg);
                    break;
                case "Twitch":
                    i = cfg.tw.isEmpty() ? launchIntent(pkg) : view("https://www.twitch.tv/" + cfg.tw, pkg);
                    break;
                default: // Netflix
                    i = view("https://www.netflix.com/watch/" + cfg.nf, null);
                    if (pkg.equals("com.netflix.ninja")) {
                        i.setClassName(pkg, "com.netflix.ninja.MainActivity");
                        i.addCategory("android.intent.category.LEANBACK_LAUNCHER");
                        i.putExtra("source", "30");
                        i.addFlags(0x10808000);
                    } else i.setPackage(pkg);
            }
            if (i == null) { Logbook.line(this, "  no launch intent for " + pkg); return; }
            startActivity(i);
        } catch (ActivityNotFoundException e) {
            Logbook.line(this, "  launch failed (" + e.getMessage() + ") - trying plain launch");
            Intent li = launchIntent(pkg);
            if (li != null) try { startActivity(li); } catch (Exception ignored) { }
        } catch (Exception e) {
            Logbook.line(this, "  launch error: " + e);
        }
    }
    private Intent view(String url, String pkg) {
        Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
        if (pkg != null) i.setPackage(pkg);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return i;
    }
    private Intent launchIntent(String pkg) {
        PackageManager pm = getPackageManager();
        Intent i = pm.getLeanbackLaunchIntentForPackage(pkg);
        if (i == null) i = pm.getLaunchIntentForPackage(pkg);
        if (i != null) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return i;
    }
    private void close(String pkg) {
        MediaController c = controller(pkg);
        if (c != null) try { c.getTransportControls().stop(); } catch (Exception ignored) { }
        performGlobalAction(GLOBAL_ACTION_HOME);
        sleep(2);
        try { ((ActivityManager) getSystemService(ACTIVITY_SERVICE)).killBackgroundProcesses(pkg); } catch (Exception ignored) { }
    }
    private void play(String pkg) {
        MediaController c = controller(pkg);
        if (c != null) c.getTransportControls().play(); else clickFocused();
    }
    private void clickFocused() {
        try {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root == null) return;
            AccessibilityNodeInfo n = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
            if (n == null) n = root.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY);
            while (n != null && !n.isClickable()) n = n.getParent();
            if (n != null) n.performAction(AccessibilityNodeInfo.ACTION_CLICK);
        } catch (Exception ignored) { }
    }

    // ---------- observation ----------
    private String foreground() {
        try {
            AccessibilityNodeInfo r = getRootInActiveWindow();
            if (r != null && r.getPackageName() != null) return r.getPackageName().toString();
        } catch (Exception ignored) { }
        return lastWindowPkg;
    }
    private MediaController controller(String pkg) {
        try {
            MediaSessionManager m = (MediaSessionManager) getSystemService(Context.MEDIA_SESSION_SERVICE);
            for (MediaController c : m.getActiveSessions(new ComponentName(this, NotifListener.class)))
                if (pkg.equals(c.getPackageName())) return c;
        } catch (Exception ignored) { }
        return null;
    }
    private String state(String pkg) {
        MediaController c = controller(pkg);
        if (c == null) return "none";
        PlaybackState s = c.getPlaybackState();
        if (s == null) return "none";
        switch (s.getState()) {
            case PlaybackState.STATE_PLAYING: return "playing";
            case PlaybackState.STATE_PAUSED: return "paused";
            case PlaybackState.STATE_BUFFERING: return "buffering";
            case PlaybackState.STATE_STOPPED: return "stopped";
            case PlaybackState.STATE_CONNECTING: return "connecting";
            case PlaybackState.STATE_ERROR: return "error";
            default: return "state" + s.getState();
        }
    }
    static boolean hasMediaAccess(Context ctx) {
        try {
            MediaSessionManager m = (MediaSessionManager) ctx.getSystemService(Context.MEDIA_SESSION_SERVICE);
            m.getActiveSessions(new ComponentName(ctx, NotifListener.class));
            return true;
        } catch (SecurityException e) { return false; }
    }
    private String findPkg(String... names) {
        PackageManager pm = getPackageManager();
        for (String n : names) try { pm.getPackageInfo(n, 0); return n; } catch (Exception ignored) { }
        return null;
    }
    private String searchPkg(String needle) {
        for (PackageInfo p : getPackageManager().getInstalledPackages(0))
            if (p.packageName.toLowerCase().contains(needle)) return p.packageName;
        return null;
    }
    private boolean sleep(int seconds) {
        for (int i = 0; i < seconds; i++) {
            if (cancel) return false;
            try { Thread.sleep(1000); } catch (InterruptedException e) { return false; }
        }
        return !cancel;
    }
}
