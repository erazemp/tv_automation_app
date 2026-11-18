package com.erazem.streamtest;

import android.content.Context;
import java.io.*;
import java.text.SimpleDateFormat;
import java.util.*;

/** Writes session.log and streams.csv to /sdcard/Android/data/com.erazem.streamtest/files/ */
final class Logbook {
    static final String CSV_HEADER =
        "session,service,package,content,launched,start,end,duration_s,playback_confirmed,hb_playing,hb_not_playing";

    static File dir(Context c) {
        File d = c.getExternalFilesDir(null);
        if (d == null) d = c.getFilesDir();
        d.mkdirs();
        return d;
    }
    static String ts() { return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date()); }
    static String stamp() { return new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()); }

    static synchronized void line(Context c, String msg) {
        append(new File(dir(c), "session.log"), ts() + "  " + msg);
        android.util.Log.i("StreamTest", msg);
    }
    static synchronized void csv(Context c, String row) {
        File f = new File(dir(c), "streams.csv");
        if (!f.exists()) append(f, CSV_HEADER);
        append(f, row);
    }
    private static void append(File f, String s) {
        try (FileWriter w = new FileWriter(f, true)) { w.write(s + "\n"); } catch (IOException ignored) { }
    }
    static synchronized String tail(Context c, int n) {
        File f = new File(dir(c), "session.log");
        if (!f.exists()) return "(no log yet)";
        ArrayDeque<String> q = new ArrayDeque<>();
        try (BufferedReader r = new BufferedReader(new FileReader(f))) {
            String l;
            while ((l = r.readLine()) != null) { q.addLast(l); if (q.size() > n) q.removeFirst(); }
        } catch (IOException e) { return "(cannot read log)"; }
        StringBuilder sb = new StringBuilder();
        Iterator<String> it = q.descendingIterator();          // newest first
        while (it.hasNext()) sb.append(it.next()).append('\n');
        return sb.toString();
    }
}
