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

        runService(sid, cfg, "YouTube", yt, "https://www.youtube.com/watch?v=" + ytId(cfg.yt));
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
        Logbook.line(this, "=== " + name + " (" + pkg + ") content: " + content);
        killBg(pkg);
        boolean confirmed = startPlayback(cfg, name, pkg);
        if (!confirmed) {
            Logbook.line(this, "  [" + name + "] could not confirm playback - using attempt " + (preferred(name) + 1) + " anyway");
            launchAttempt(cfg, name, pkg, preferred(name));
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
                launchAttempt(cfg, name, pkg, preferred(name));
            } else if (!"playing".equals(st) && clickKnownButtonLogged(name)) {
                // dialog dismissed ("still watching?", guest prompt, cookies, retry)
            } else if (!"playing".equals(st) && !"paused".equals(st) && name.equals("Twitch") && clickStreamCard()) {
                Logbook.line(this, "  [Twitch] not playing -> opened first live stream");
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
    /** Candidate ways to open the content, tried in order until playback is confirmed. */
    private java.util.List<Intent> attempts(Config cfg, String name, String pkg) {
        java.util.List<Intent> l = new java.util.ArrayList<>();
        switch (name) {
            case "YouTube": {
                String id = ytId(cfg.yt);
                l.add(view("https://www.youtube.com/watch?v=" + id, pkg));
                Intent shell = view("https://www.youtube.com/watch?v=" + id, null);
                shell.setClassName(pkg, "com.google.android.apps.youtube.tv.activity.ShellActivity");
                l.add(shell);
                l.add(view("vnd.youtube:" + id, pkg));
                l.add(view("https://youtu.be/" + id, pkg));
                l.add(view("https://www.youtube.com/tv#/watch?v=" + id, pkg));
                Intent bare = view("https://www.youtube.com/watch?v=" + id, null);   // let Android pick
                l.add(bare);
                break;
            }
            case "Twitch":
                if (!cfg.tw.isEmpty()) {
                    l.add(view("https://www.twitch.tv/" + cfg.tw, pkg));
                    l.add(view("twitch://stream/" + cfg.tw, pkg));
                }
                l.add(launchIntent(pkg));
                break;
            default: { // Netflix
                if (pkg.equals("com.netflix.ninja")) {
                    Intent n = view("https://www.netflix.com/watch/" + cfg.nf, null);
                    n.setClassName(pkg, "com.netflix.ninja.MainActivity");
                    n.addCategory("android.intent.category.LEANBACK_LAUNCHER");
                    n.putExtra("source", "30");
                    n.addFlags(0x10808000);
                    l.add(n);
                }
                l.add(view("https://www.netflix.com/watch/" + cfg.nf, pkg));
                l.add(view("netflix://title/" + cfg.nf, pkg));
                l.add(launchIntent(pkg));
            }
        }
        l.removeIf(java.util.Objects::isNull);
        return l;
    }

    /** Try each attempt (remembered winner first) until the app reports PLAYING. */
    private boolean startPlayback(Config cfg, String name, String pkg) {
        java.util.List<Intent> l = attempts(cfg, name, pkg);
        int first = Math.min(preferred(name), l.size() - 1);
        for (int k = 0; k < l.size() && !cancel; k++) {
            int idx = (first + k) % l.size();
            if (k > 0) { performGlobalAction(GLOBAL_ACTION_HOME); sleep(2); killBg(pkg); }
            launchAttempt(cfg, name, pkg, idx);
            for (int i = 1; i <= 3 && !cancel; i++) {
                if (!sleep(i == 1 ? 15 : 8)) return false;
                String st = state(pkg), fg = foreground();
                Logbook.line(this, "  [" + name + "] attempt " + (idx + 1) + " check " + i + ": foreground=" + fg + " playback=" + st);
                if ("playing".equals(st)) {
                    getSharedPreferences("cfg", MODE_PRIVATE).edit().putInt("attempt_" + name, idx).apply();
                    Logbook.line(this, "  [" + name + "] playing with attempt " + (idx + 1));
                    return true;
                }
                String clicked = clickKnownButton();
                if (clicked != null) {
                    Logbook.line(this, "  [" + name + "] pressed '" + clicked + "'");
                    if (clicked.contains("guest") || clicked.contains("try again") || clicked.contains("retry")) {
                        sleep(4); launchAttempt(cfg, name, pkg, idx);       // guest/retry often lands on home: reopen video
                    }
                } else if ("paused".equals(st)) play(pkg);
                else if (name.equals("Twitch") && clickStreamCard()) Logbook.line(this, "  [Twitch] opened first live stream");
                else if (!name.equals("YouTube")) clickFocused();   // profile picker / featured tile
            }
            Logbook.line(this, "  [" + name + "] attempt " + (idx + 1) + " not playing. Screen: " + screenText());
        }
        return false;
    }

    private int preferred(String name) { return getSharedPreferences("cfg", MODE_PRIVATE).getInt("attempt_" + name, 0); }

    private void launchAttempt(Config cfg, String name, String pkg, int idx) {
        java.util.List<Intent> l = attempts(cfg, name, pkg);
        if (l.isEmpty()) { Logbook.line(this, "  no launch intent for " + pkg); return; }
        Intent i = l.get(Math.max(0, Math.min(idx, l.size() - 1)));
        Logbook.line(this, "  [" + name + "] launch attempt " + (idx + 1) + ": " + i.toUri(0));
        try { startActivity(i); }
        catch (Exception e) { Logbook.line(this, "  launch error: " + e.getClass().getSimpleName() + " " + e.getMessage()); }
    }

    static String ytId(String in) {
        String s = in == null ? "" : in.trim();
        java.util.regex.Matcher m = java.util.regex.Pattern
            .compile("(?:v=|youtu\\.be/|/shorts/|/live/|/embed/)([A-Za-z0-9_-]{11})").matcher(s);
        if (m.find()) return m.group(1);
        m = java.util.regex.Pattern.compile("^([A-Za-z0-9_-]{11})").matcher(s);
        return m.find() ? m.group(1) : s;
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
    private void killBg(String pkg) {
        try { ((ActivityManager) getSystemService(ACTIVITY_SERVICE)).killBackgroundProcesses(pkg); } catch (Exception ignored) { }
    }
    /** Buttons we are happy to press when playback is blocked (lower-case, in priority order). */
    private static final String[] BUTTONS = {
        "accept all", "accept all cookies", "accept cookies", "i accept", "accept", "i agree", "agree", "allow all", "got it",
        "watch as guest", "continue as guest", "use as guest", "guest",
        "continue watching", "keep watching", "i'm still watching", "yes",
        "try again", "retry", "skip", "not now", "no thanks", "dismiss", "close", "ok"
    };
    /** Clicks the first visible button whose text matches BUTTONS; returns the label or null. */
    private String clickKnownButton() {
        AccessibilityNodeInfo root;
        try { root = getRootInActiveWindow(); } catch (Exception e) { return null; }
        if (root == null) return null;
        java.util.List<AccessibilityNodeInfo> nodes = new java.util.ArrayList<>();
        flatten(root, nodes, 0);
        for (String b : BUTTONS) {
            for (AccessibilityNodeInfo n : nodes) {
                String t = label(n);
                if (t.isEmpty()) continue;
                boolean match = t.equals(b) || (b.length() >= 5 && t.contains(b) && t.length() <= b.length() + 25);
                if (!match) continue;
                AccessibilityNodeInfo c = n;
                while (c != null && !c.isClickable()) c = c.getParent();
                if (c != null && c.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return b;
                if (n.performAction(AccessibilityNodeInfo.ACTION_FOCUS)) {   // TV: focus, then press OK
                    clickFocused(); return b;
                }
            }
        }
        return null;
    }
    /** Twitch home: click the first tile that looks like a live stream (shows viewers / LIVE). */
    private boolean clickStreamCard() {
        AccessibilityNodeInfo root;
        try { root = getRootInActiveWindow(); } catch (Exception e) { return false; }
        if (root == null) return false;
        java.util.List<AccessibilityNodeInfo> nodes = new java.util.ArrayList<>();
        flatten(root, nodes, 0);
        for (String key : new String[]{"viewer", "live"}) {
            for (AccessibilityNodeInfo n : nodes) {
                if (!n.isClickable()) continue;
                String t = subtreeText(n, 0).toLowerCase(java.util.Locale.ROOT);
                if (!t.contains(key) || t.length() > 300) continue;
                if (n.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true;
                if (n.performAction(AccessibilityNodeInfo.ACTION_FOCUS)) { clickFocused(); return true; }
            }
        }
        return false;
    }
    private String subtreeText(AccessibilityNodeInfo n, int depth) {
        if (n == null || depth > 8) return "";
        StringBuilder sb = new StringBuilder(label(n)).append(' ');
        for (int i = 0; i < n.getChildCount() && sb.length() < 300; i++) sb.append(subtreeText(n.getChild(i), depth + 1));
        return sb.toString();
    }
    private boolean clickKnownButtonLogged(String name) {
        String b = clickKnownButton();
        if (b != null) Logbook.line(this, "  [" + name + "] pressed '" + b + "'");
        return b != null;
    }
    private static String label(AccessibilityNodeInfo n) {
        CharSequence t = n.getText();
        if (t == null || t.length() == 0) t = n.getContentDescription();
        return t == null ? "" : t.toString().trim().toLowerCase(java.util.Locale.ROOT);
    }
    private void flatten(AccessibilityNodeInfo n, java.util.List<AccessibilityNodeInfo> out, int depth) {
        if (n == null || depth > 30 || out.size() > 600) return;
        out.add(n);
        for (int i = 0; i < n.getChildCount(); i++) flatten(n.getChild(i), out, depth + 1);
    }
    /** Visible text on screen (for diagnosing error dialogs). */
    private String screenText() {
        StringBuilder sb = new StringBuilder();
        try { collect(getRootInActiveWindow(), sb, 0); } catch (Exception ignored) { }
        String t = sb.toString().replace('\n', ' ').trim();
        return t.isEmpty() ? "(no readable text)" : (t.length() > 300 ? t.substring(0, 300) + "..." : t);
    }
    private void collect(AccessibilityNodeInfo n, StringBuilder sb, int depth) {
        if (n == null || depth > 25 || sb.length() > 400) return;
        CharSequence t = n.getText(), d = n.getContentDescription();
        if (t != null && t.length() > 0) sb.append(t).append(" | ");
        else if (d != null && d.length() > 0) sb.append(d).append(" | ");
        for (int i = 0; i < n.getChildCount(); i++) collect(n.getChild(i), sb, depth + 1);
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
