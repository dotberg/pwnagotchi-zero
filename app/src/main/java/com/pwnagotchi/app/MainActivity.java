package com.pwnagotchi.app;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
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

    private BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            if ("com.pwnagotchi.app.STATS_UPDATE".equals(i.getAction())) updateUI(i);
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
                toggleButton.setText("▶ START"); toggleButton.setBackgroundColor(0xFF2E7D32);
                faceView.setText("(⇀‿‿↼)"); statusView.setText("sleeping...");
            } else {
                startForegroundService(new Intent(this, PwngService.class));
                serviceRunning = true;
                toggleButton.setText("⏹ STOP"); toggleButton.setBackgroundColor(0xFFC62828);
            }
        });

        startForegroundService(new Intent(this, PwngService.class));
        serviceRunning = true;
        toggleButton.setText("⏹ STOP"); toggleButton.setBackgroundColor(0xFFC62828);
    }

    @Override protected void onResume() {
        super.onResume();
        if (android.os.Build.VERSION.SDK_INT >= 34)
            registerReceiver(receiver, new IntentFilter("com.pwnagotchi.app.STATS_UPDATE"), Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(receiver, new IntentFilter("com.pwnagotchi.app.STATS_UPDATE"));
    }
    @Override protected void onPause() { super.onPause(); try { unregisterReceiver(receiver); } catch (Exception e) {} }

    private void updateUI(Intent i) {
        String face = i.getStringExtra("face"), status = i.getStringExtra("status"),
               phase = i.getStringExtra("phase");
        int ap = i.getIntExtra("apCount", 0), wpa2 = i.getIntExtra("wpa2Count", 0),
            pk = i.getIntExtra("totalPmkids", 0), hs = i.getIntExtra("totalHandshakes", 0),
            sc = i.getIntExtra("totalScans", 0);
        long ss = i.getLongExtra("sessionStart", System.currentTimeMillis());
        long up = (System.currentTimeMillis() - ss) / 1000;

        faceView.setText(face != null ? face : "(◕‿‿◕)");
        statusView.setText(status != null ? status : "thinking...");
        phaseView.setText("Phase: " + (phase != null ? phase : "BOOT") + " | Uptime: " + (up/60) + "m " + (up%60) + "s");
        statsView.setText(String.format("APs: %d | WPA2: %d | Scans: %d\nPMKIDs: %d | Handshakes: %d",
            ap, wpa2, sc, pk, hs));
    }

    private LinearLayout buildLayout() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(48, 48, 48, 48);
        root.setBackgroundColor(0xFF1A1A2E);

        LinearLayout.LayoutParams m = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);

        TextView t = new TextView(this); t.setText("P W N A G O T C H I");
        t.setTextColor(0xFF00D4FF); t.setTextSize(18); t.setTypeface(Typeface.MONOSPACE);
        t.setGravity(Gravity.CENTER); t.setPadding(0,0,0,16); root.addView(t, m);

        TextView ai = new TextView(this); ai.setText("(AI-driven, autonomous)");
        ai.setTextColor(0xFF546E7A); ai.setTextSize(10); ai.setTypeface(Typeface.MONOSPACE);
        ai.setGravity(Gravity.CENTER); ai.setPadding(0,0,0,24); root.addView(ai, m);

        faceView = new TextView(this); faceView.setId(1);
        faceView.setText("(◕‿‿◕)"); faceView.setTextColor(0xFFFFD740);
        faceView.setTextSize(36); faceView.setGravity(Gravity.CENTER);
        faceView.setTypeface(Typeface.MONOSPACE); faceView.setPadding(0,0,0,16);
        root.addView(faceView, m);

        statusView = new TextView(this); statusView.setId(2);
        statusView.setText("initializing..."); statusView.setTextColor(0xFFB0BEC5);
        statusView.setTextSize(14); statusView.setGravity(Gravity.CENTER);
        statusView.setTypeface(Typeface.MONOSPACE); statusView.setPadding(0,0,0,16);
        root.addView(statusView, m);

        phaseView = new TextView(this); phaseView.setId(4);
        phaseView.setText("Phase: BOOT"); phaseView.setTextColor(0xFF78909C);
        phaseView.setTextSize(12); phaseView.setGravity(Gravity.CENTER);
        phaseView.setTypeface(Typeface.MONOSPACE); phaseView.setPadding(0,0,0,16);
        root.addView(phaseView, m);

        statsView = new TextView(this); statsView.setId(3);
        statsView.setText("APs: 0 | WPA2: 0\nPMKIDs: 0 | Handshakes: 0");
        statsView.setTextColor(0xFF90A4AE); statsView.setTextSize(13);
        statsView.setTypeface(Typeface.MONOSPACE); statsView.setGravity(Gravity.CENTER);
        statsView.setPadding(0,0,0,32);
        root.addView(statsView, m);

        // Export button
        Button exp = new Button(this);
        exp.setText("📤 EXPORT");
        exp.setTextColor(0xFFFFFFFF); exp.setBackgroundColor(0xFF546E7A);
        exp.setTextSize(14); exp.setTypeface(Typeface.MONOSPACE);
        exp.setPadding(0,12,0,12);
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
        toggleButton.setText("⏹ STOP"); toggleButton.setTextColor(0xFFFFFFFF);
        toggleButton.setBackgroundColor(0xFFC62828);
        toggleButton.setTextSize(16); toggleButton.setTypeface(Typeface.MONOSPACE);
        root.addView(toggleButton, m);

        TextView br = new TextView(this); br.setText("[ pwnagotchi.ai ]");
        br.setTextColor(0xFF37474F); br.setTextSize(10); br.setTypeface(Typeface.MONOSPACE);
        br.setGravity(Gravity.CENTER); br.setPadding(0,24,0,0);
        root.addView(br, m);

        return root;
    }

    private void startServiceCompat(Intent i) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O)
            startServiceCompat(i); else startService(i);
    }
}
