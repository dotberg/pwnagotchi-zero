package com.pwnagotchi.app;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.os.Handler;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.LinearLayout;
import android.graphics.Color;
import android.graphics.Typeface;
import android.view.Gravity;

public class MainActivity extends Activity {

    private TextView faceView, statusView, statsView, phaseView;
    private Button toggleButton;
    private boolean serviceRunning = false;
    
    private String liveFace = "(◕‿‿◕)", liveStatus = "booting...", livePhase = "BOOT";
    private int liveAps = 0, liveWpa2 = 0, liveHs = 0, livePmkid = 0, liveUptime = 0;

    private BroadcastReceiver statsReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context ctx, Intent i) {
            liveFace = i.getStringExtra("face"); if (liveFace == null) liveFace = "(◕‿‿◕)";
            liveStatus = i.getStringExtra("status"); if (liveStatus == null) liveStatus = "...";
            int p = i.getIntExtra("phase", -1);
            livePhase = p == 0 ? "OBSERVE" : p == 1 ? "HUNT" : p == 2 ? "ATTACK" : "MONITOR";
            liveAps = i.getIntExtra("apCount", 0);
            liveWpa2 = i.getIntExtra("wpa2Count", 0);
            liveHs = i.getIntExtra("totalHandshakes", 0);
            livePmkid = i.getIntExtra("totalPmkids", 0);
            long ss = i.getLongExtra("sessionStart", 0);
            liveUptime = (int)((ss > 0) ? Math.max(0, (System.currentTimeMillis() - ss) / 1000) : 0);
            refreshViews();
        }
    };
    
    private void refreshViews() {
        faceView.setText(liveFace);
        statusView.setText(liveStatus);
        phaseView.setText("[" + livePhase + "]  UP: " + formatTime(liveUptime));
        statsView.setText("APs:" + liveAps + "  WPA2:" + liveWpa2 + "  HS:" + liveHs + "  PMKID:" + livePmkid);
    }
    
    private String formatTime(int sec) {
        if (sec < 60) return sec + "s";
        int m = sec / 60; int s = sec % 60;
        return m + "m" + s + "s";
    }

    private static final String STOP_FILE = "/data/data/com.pwnagotchi.app/files/.stopped";

    @Override
    protected void onCreate(Bundle s) {
        super.onCreate(s);
        setContentView(buildLayout());
        
        faceView = findViewById(1); statusView = findViewById(2);
        statsView = findViewById(3); phaseView = findViewById(4);
        toggleButton = findViewById(5);

        toggleButton.setOnClickListener(v -> {
            if (serviceRunning) {
                Intent si = new Intent(this, PwngService.class);
                si.setAction("STOP");
                startService(si);
                serviceRunning = false;
                toggleButton.setText("[ START ]");
                faceView.setText("(⇀‿‿↼)"); statusView.setText("sleeping...");
            } else {
                new java.io.File(STOP_FILE).delete();
                startServiceCompat(new Intent(this, PwngService.class));
                serviceRunning = true;
                toggleButton.setText("[ STOP ]");
            }
        });

        if (new java.io.File(STOP_FILE).exists()) {
            serviceRunning = false;
            toggleButton.setText("[ START ]");
            faceView.setText("(⇀‿‿↼)"); statusView.setText("sleeping...");
        } else {
            startServiceCompat(new Intent(this, PwngService.class));
            serviceRunning = true;
            toggleButton.setText("[ STOP ]");
        }
    }

    @Override protected void onResume() {
        super.onResume();
        registerReceiver(statsReceiver, new IntentFilter("com.pwnagotchi.app.STATS_UPDATE"), Context.RECEIVER_NOT_EXPORTED);
    }
    @Override protected void onPause() {
        super.onPause();
        try { unregisterReceiver(statsReceiver); } catch (Exception e) {}
    }

    private LinearLayout buildLayout() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(24, 40, 24, 24);
        root.setBackgroundColor(0xFF0A0A0A);

        LinearLayout.LayoutParams m = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);

        // Face — BIG and centered
        faceView = new TextView(this); faceView.setId(1);
        faceView.setText("(◕‿‿◕)"); faceView.setTextColor(0xFF00FF41);
        faceView.setTextSize(48); faceView.setGravity(Gravity.CENTER);
        faceView.setTypeface(Typeface.MONOSPACE); faceView.setPadding(0,20,0,16);
        root.addView(faceView, m);

        // Status — live action text
        statusView = new TextView(this); statusView.setId(2);
        statusView.setText("initializing..."); statusView.setTextColor(0xFF00CC00);
        statusView.setTextSize(18); statusView.setGravity(Gravity.CENTER);
        statusView.setTypeface(Typeface.MONOSPACE); statusView.setPadding(0,0,0,12);
        root.addView(statusView, m);

        // Phase + uptime
        phaseView = new TextView(this); phaseView.setId(4);
        phaseView.setText("[BOOT]  UP: 0s"); phaseView.setTextColor(0xFF00AA00);
        phaseView.setTextSize(16); phaseView.setGravity(Gravity.CENTER);
        phaseView.setTypeface(Typeface.MONOSPACE); phaseView.setPadding(0,0,0,8);
        root.addView(phaseView, m);

        // Stats — APs, WPA2, Handshakes, PMKID
        statsView = new TextView(this); statsView.setId(3);
        statsView.setText("APs:0  WPA2:0  HS:0  PMKID:0");
        statsView.setTextColor(0xFF008800); statsView.setTextSize(14);
        statsView.setTypeface(Typeface.MONOSPACE); statsView.setGravity(Gravity.CENTER);
        statsView.setPadding(0,0,0,20);
        root.addView(statsView, m);

        // Export button
        Button exp = new Button(this);
        exp.setText("[ EXPORT HASHCAT ]");
        exp.setTextColor(0xFF00FF41); exp.setBackgroundColor(0xFF111111);
        exp.setTextSize(16); exp.setTypeface(Typeface.MONOSPACE);
        exp.setPadding(0,14,0,14);
        exp.setOnClickListener(v -> {
            String src = "/sdcard/Android/data/com.pwnagotchi.app/files/handshakes/pmkid_hashes.22000";
            if (!new java.io.File(src).exists())
                src = "/data/data/com.pwnagotchi.app/files/handshakes/pmkid_hashes.22000";
            if (!new java.io.File(src).exists()) { statusView.setText("No hashes yet"); return; }
            try {
                java.io.File dest = new java.io.File("/sdcard/pmkid_hashes.22000");
                java.io.FileInputStream fis = new java.io.FileInputStream(src);
                java.io.FileOutputStream fos = new java.io.FileOutputStream(dest);
                byte[] b = new byte[8192]; int n;
                while ((n = fis.read(b)) > 0) fos.write(b, 0, n);
                fis.close(); fos.close();
                statusView.setText("Exported!\nadb pull /sdcard/pmkid_hashes.22000");
            } catch (Exception e) { statusView.setText("Export failed"); }
        });
        root.addView(exp, m);

        TextView gap = new TextView(this); gap.setText(""); gap.setPadding(0,14,0,14); root.addView(gap, m);

        // Start/Stop button
        toggleButton = new Button(this); toggleButton.setId(5);
        toggleButton.setText("[ STOP ]"); toggleButton.setTextColor(0xFF00FF41);
        toggleButton.setBackgroundColor(0xFF1A3300);
        toggleButton.setTextSize(20); toggleButton.setTypeface(Typeface.MONOSPACE);
        toggleButton.setPadding(0,16,0,16);
        root.addView(toggleButton, m);

        TextView br = new TextView(this); br.setText("dotberg/pwnagotchi-zero  v1.6.2");
        br.setTextColor(0xFF004400); br.setTextSize(11); br.setTypeface(Typeface.MONOSPACE);
        br.setGravity(Gravity.CENTER); br.setPadding(0,20,0,0);
        root.addView(br, m);

        return root;
    }

    private void startServiceCompat(Intent i) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O)
            startForegroundService(i); else startService(i);
    }
}
