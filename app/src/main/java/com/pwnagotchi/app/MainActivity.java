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
    private Handler pollHandler = new Handler();
    
    private Runnable pollTask = new Runnable() {
        @Override
        public void run() {
            refreshUI();
            pollHandler.postDelayed(this, 3000); // poll every 3s
        }
    };

    @Override
    protected void onCreate(Bundle s) {
        super.onCreate(s);
        setContentView(buildLayout());
        faceView = findViewById(1); statusView = findViewById(2);
        statsView = findViewById(3); phaseView = findViewById(4);
        toggleButton = findViewById(5);

        toggleButton.setOnClickListener(v -> {
            if (serviceRunning) {
                stopService(new Intent(this, PwngService.class).setAction("STOP"));
                serviceRunning = false;
                toggleButton.setText("[ START ]");
                faceView.setText("(⇀‿‿↼)"); statusView.setText("sleeping...");
            } else {
                startForegroundService(new Intent(this, PwngService.class));
                serviceRunning = true;
                toggleButton.setText("[ STOP ]");
            }
        });

        startForegroundService(new Intent(this, PwngService.class));
        serviceRunning = true;
        toggleButton.setText("⏹ STOP"); toggleButton.setBackgroundColor(0xFFC62828);
    }

    @Override protected void onResume() {
        super.onResume();
        pollHandler.post(pollTask);
    }
    @Override protected void onPause() {
        super.onPause();
        pollHandler.removeCallbacks(pollTask);
    }

    private void refreshUI() {
        // Read brain file on background thread to not block UI
        new Thread(() -> {
            try {
                java.io.File f = new java.io.File("/data/data/com.pwnagotchi.app/files/brain.mem");
                if (!f.exists()) {
                    runOnUiThread(() -> phaseView.setText("Phase: BOOT"));
                    return;
                }
                java.io.BufferedReader br = new java.io.BufferedReader(new java.io.FileReader(f));
                String line, phaseStr = "BOOT";
                int pk = 0, hs = 0, cd = 0;
                while ((line = br.readLine()) != null) {
                    if (line.startsWith("phase=")) { int p = Integer.parseInt(line.substring(6)); phaseStr = p==0?"OBSERVE":p==1?"HUNT":"ATTACK"; }
                    else if (line.startsWith("total_pmkids=")) pk = Integer.parseInt(line.substring(13));
                    else if (line.startsWith("total_handshakes=")) hs = Integer.parseInt(line.substring(17));
                    else if (line.startsWith("cooldown=")) cd = Integer.parseInt(line.substring(9));
                }
                br.close();
                final String ps = phaseStr; final int cp = cd, fp = pk, fh = hs;
                runOnUiThread(() -> {
                    phaseView.setText("PHASE: " + ps + " | CD: " + (cp/1000) + "s");
                    statsView.setText(String.format("PMKIDs: %d | Handshakes: %d", fp, fh));
                });
            } catch (Exception e) {
                runOnUiThread(() -> phaseView.setText("Phase: ..."));
            }
        }).start();
    }

    private void updateUI(Intent i) {} // unused, kept for compat

    private LinearLayout buildLayout() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(32, 32, 32, 32);
        root.setBackgroundColor(0xFF0A0A0A);

        LinearLayout.LayoutParams m = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);

        // ASCII banner
        TextView t = new TextView(this); 
        t.setText("╔══════════════════════╗\n║   P W N A G O T C H I   ║\n╚══════════════════════╝");
        t.setTextColor(0xFF00FF41); t.setTextSize(12); t.setTypeface(Typeface.MONOSPACE);
        t.setGravity(Gravity.CENTER); t.setPadding(0,0,0,8); root.addView(t, m);

        TextView ai = new TextView(this); ai.setText("[ autonomous AI ]");
        ai.setTextColor(0xFF005500); ai.setTextSize(9); ai.setTypeface(Typeface.MONOSPACE);
        ai.setGravity(Gravity.CENTER); ai.setPadding(0,0,0,20); root.addView(ai, m);

        faceView = new TextView(this); faceView.setId(1);
        faceView.setText("(◕‿‿◕)"); faceView.setTextColor(0xFF00FF41);
        faceView.setTextSize(32); faceView.setGravity(Gravity.CENTER);
        faceView.setTypeface(Typeface.MONOSPACE); faceView.setPadding(0,0,0,12);
        root.addView(faceView, m);

        statusView = new TextView(this); statusView.setId(2);
        statusView.setText("initializing..."); statusView.setTextColor(0xFF00CC00);
        statusView.setTextSize(12); statusView.setGravity(Gravity.CENTER);
        statusView.setTypeface(Typeface.MONOSPACE); statusView.setPadding(0,0,0,12);
        root.addView(statusView, m);

        phaseView = new TextView(this); phaseView.setId(4);
        phaseView.setText("PHASE: BOOT"); phaseView.setTextColor(0xFF007700);
        phaseView.setTextSize(11); phaseView.setGravity(Gravity.CENTER);
        phaseView.setTypeface(Typeface.MONOSPACE); phaseView.setPadding(0,0,0,12);
        root.addView(phaseView, m);

        statsView = new TextView(this); statsView.setId(3);
        statsView.setText("PMKIDs: 0 | Handshakes: 0");
        statsView.setTextColor(0xFF005500); statsView.setTextSize(11);
        statsView.setTypeface(Typeface.MONOSPACE); statsView.setGravity(Gravity.CENTER);
        statsView.setPadding(0,0,0,24);
        root.addView(statsView, m);

        // Export button
        Button exp = new Button(this);
        exp.setText("[ EXPORT ]");
        exp.setTextColor(0xFF00FF41); exp.setBackgroundColor(0xFF111111);
        exp.setTextSize(12); exp.setTypeface(Typeface.MONOSPACE);
        exp.setPadding(0,10,0,10);
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

        // Small gap
        TextView gap = new TextView(this); gap.setText(""); gap.setPadding(0,8,0,8); root.addView(gap, m);

        // Toggle
        toggleButton = new Button(this); toggleButton.setId(5);
        toggleButton.setText("[ STOP ]"); toggleButton.setTextColor(0xFF00FF41);
        toggleButton.setBackgroundColor(0xFF1A3300);
        toggleButton.setTextSize(14); toggleButton.setTypeface(Typeface.MONOSPACE);
        root.addView(toggleButton, m);

        TextView br = new TextView(this); br.setText("[ dotberg/pwnagotchi-zero ]");
        br.setTextColor(0xFF003300); br.setTextSize(9); br.setTypeface(Typeface.MONOSPACE);
        br.setGravity(Gravity.CENTER); br.setPadding(0,16,0,0);
        root.addView(br, m);

        return root;
    }

    private void startServiceCompat(Intent i) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O)
            startServiceCompat(i); else startService(i);
    }
}
