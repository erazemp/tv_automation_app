package com.erazem.streamtest;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;

public class MainActivity extends Activity {
    private TextView status, log;
    private EditText minutes, yt, tw, nf;
    private final Handler h = new Handler(Looper.getMainLooper());
    private final Runnable tick = new Runnable() { public void run() { refresh(); h.postDelayed(this, 2000); } };

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        Config c = Config.load(this);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.HORIZONTAL);
        root.setPadding(48, 32, 48, 32);
        root.setBackgroundColor(Color.rgb(16, 24, 40));

        LinearLayout left = column();
        TextView title = text("Stream Test", 30); title.setTypeface(Typeface.DEFAULT_BOLD);
        left.addView(title);
        left.addView(text("YouTube → Twitch → Netflix → Home", 16));
        status = text("", 16); status.setPadding(0, 16, 0, 16);
        left.addView(status);

        minutes = field(left, "Minutes per service", String.valueOf(c.minutes), true);
        yt = field(left, "YouTube video id or link", c.yt, false);
        tw = field(left, "Twitch channel (empty = featured)", c.tw, false);
        nf = field(left, "Netflix title id", c.nf, false);

        LinearLayout buttons = new LinearLayout(this);
        Button start = new Button(this); start.setText("Start");
        Button stop = new Button(this); stop.setText("Stop");
        buttons.addView(start); buttons.addView(stop);
        left.addView(buttons);
        start.setOnClickListener(v -> startSession());
        stop.setOnClickListener(v -> { EngineService.stop(); toast("Stopping..."); });

        LinearLayout right = column();
        right.addView(text("Log (newest first) - files in Android/data/com.erazem.streamtest/files", 14));
        ScrollView sv = new ScrollView(this);
        log = text("", 13); log.setTypeface(Typeface.MONOSPACE);
        sv.addView(log);
        right.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        root.addView(left, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        root.addView(right, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.4f));
        setContentView(root);
        start.requestFocus();

        if (getIntent().getBooleanExtra("autostart", false)) h.postDelayed(this::startSession, 1500);
    }

    private void startSession() {
        Config c = new Config();
        try { c.minutes = Float.parseFloat(minutes.getText().toString().trim()); } catch (Exception e) { c.minutes = 60f; }
        c.yt = yt.getText().toString().trim();
        c.tw = tw.getText().toString().trim();
        c.nf = nf.getText().toString().trim();
        c.save(this);
        EngineService e = EngineService.instance;
        if (e == null) { toast("Engine not enabled - run setup from the laptop once (app.bat setup)"); return; }
        if (!e.start(c)) toast("Already running"); else toast("Starting...");
    }

    private void refresh() {
        boolean eng = EngineService.instance != null, media = EngineService.hasMediaAccess(this);
        status.setText("Engine (accessibility): " + (eng ? "ON" : "OFF - run setup") +
                "\nMedia access: " + (media ? "ON" : "OFF - run setup") +
                "\nStatus: " + (EngineService.running ? "RUNNING " + EngineService.current : "idle"));
        log.setText(Logbook.tail(this, 40));
    }

    @Override protected void onResume() { super.onResume(); h.post(tick); }
    @Override protected void onPause() { super.onPause(); h.removeCallbacks(tick); }

    private LinearLayout column() {
        LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.VERTICAL); l.setPadding(16, 0, 16, 0); return l;
    }
    private TextView text(String s, int sp) {
        TextView t = new TextView(this); t.setText(s); t.setTextSize(sp); t.setTextColor(Color.WHITE); return t;
    }
    private EditText field(LinearLayout parent, String label, String value, boolean number) {
        parent.addView(text(label, 14));
        EditText e = new EditText(this);
        e.setText(value); e.setSingleLine(true); e.setTextColor(Color.WHITE);
        if (number) e.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        parent.addView(e);
        return e;
    }
    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }
}
