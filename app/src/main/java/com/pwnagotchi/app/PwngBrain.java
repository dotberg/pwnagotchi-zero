package com.pwnagotchi.app;

import java.io.*;
import java.util.*;
import java.util.Random;

/**
 * Pwnagotchi AI Brain — autonomous decision engine.
 * 
 * Learns which APs respond to attacks, adapts strategy over time.
 * v2.0: Sniff-native architecture. No Evil Twin, no WiFi toggling.
 * 
 * Phases:
 *   PHASE_OBSERVE (0-30s): Passive scan only, learn environment
 *   PHASE_HUNT (disabled): Falls through to attack
 *   PHASE_ATTACK (30s+): CSA deauth + passive EAPOL sniff
 * 
 * Scoring:
 *   +1  PMKID captured (passive)
 *   +5  Handshake captured (sniff_deauth)
 *   -1  Failed attempt (no EAPOL)
 */
public class PwngBrain {
    
    // Phases
    public static final int PHASE_OBSERVE = 0;
    public static final int PHASE_HUNT    = 1;
    public static final int PHASE_ATTACK  = 2;
    
    private int phase = PHASE_OBSERVE;
    private long sessionStart;
    private long lastAction;
    private int cooldown = 10000; // 10s initial (was 30s)
    
    // AP knowledge: BSSID -> {score, last_seen, flags, ssid, attempts, successes}
    private Map<String, ApKnowledge> apDB = new HashMap<>();
    
    // Global stats
    private int totalPmkids = 0;
    private int totalHandshakes = 0;
    private int totalScans = 0;
    private int currentApCount = 0;
    private int currentWpa2Count = 0;
    
    // Learning state
    private String lastActionType = "none";
    private String lastActionResult = "";
    private int successfulEvilTwins = 0;
    private int failedEvilTwins = 0;
    private List<String> blacklistedSsids = new ArrayList<>();
    
    // Display state (read by activity UI)
    public String currentFace = "(◕‿‿◕)";
    public String currentStatus = "initializing...";
    
    public static class ApKnowledge {
        String bssid, ssid, flags;
        int signal, freq;
        int score = 0;
        int attempts = 0;
        int pmkidSuccess = 0;
        int handshakeSuccess = 0;
        long lastSeen;
        long lastAttempt;
        boolean evilTwinWorked = false;
        boolean pmkidFound = false;
    }
    
    public static class Decision {
        public String action; // "scan", "sniff_deauth", "wait"
        public String targetBssid;
        public String targetSsid;
        public int targetFreq;
        public String reasoning;
        public String face;
        public String status;
        
        Decision(String a, String r) { action = a; reasoning = r; }
    }
    
    public PwngBrain() {
        sessionStart = System.currentTimeMillis();
        lastAction = sessionStart;
        loadMemory();
    }
    
    // ─── Main decision loop ───────────────────────────────────
    
    public Decision think(int apCount, int wpa2Count, java.util.List<String[]> scanResults) {
        currentApCount = apCount;
        currentWpa2Count = wpa2Count;
        totalScans++;
        
        long now = System.currentTimeMillis();
        long sessionAge = now - sessionStart;
        
        // Update AP database from scan results
        if (scanResults != null) {
            for (String[] ap : scanResults) {
                updateApKnowledge(ap);
            }
        }
        
        // Phase transition logic — EVIL TWIN ONLY (no PMKID hunting)
        if (phase == PHASE_OBSERVE && sessionAge > 30000) {  // 30s observe → straight to attack
            phase = PHASE_ATTACK;
        }
        
        // Cooldown check
        if (now - lastAction < cooldown) {
            return new Decision("wait", "cooldown " + ((cooldown - (now - lastAction))/1000) + "s");
        }
        
        // Phase-specific logic — EVIL TWIN ONLY
        switch (phase) {
            case PHASE_OBSERVE: return thinkObserve();
            case PHASE_HUNT:    // fall through — no PMKID, straight to attack
            case PHASE_ATTACK:  return thinkAttack();
            default:            return new Decision("scan", "default");
        }
    }

    // ─── Thompson Sampling (Bayesian Bandit) ──────────────────
    private Random rng = new Random();
    
    private double thompsonSample(ApKnowledge ap) {
        int successes = ap.pmkidSuccess + ap.handshakeSuccess;
        int failures = Math.max(0, ap.attempts - successes);
        double alpha = successes + 1.0;
        double betaParam = failures + 1.0;
        double x = gammaSample(alpha);
        double y = gammaSample(betaParam);
        return x / (x + y + 0.0001);
    }
    
    private double gammaSample(double shape) {
        if (shape < 1) shape = 1;
        double d = shape - 1.0/3.0;
        double c = 1.0 / Math.sqrt(9.0 * d);
        double x, v;
        do {
            x = rng.nextGaussian();
            v = (1 + c * x) * (1 + c * x) * (1 + c * x);
        } while (v <= 0);
        return d * v;
    }
    
    private Decision thinkObserve() {
        long age = (System.currentTimeMillis() - sessionStart) / 1000;
        Decision d = new Decision("scan", "learning environment");
        d.face = "(✜‿‿✜)";
        d.status = "observing... (" + age + "s) | " + currentApCount + " APs, " + currentWpa2Count + " WPA2";
        return d;
    }
    
    private Decision thinkHunt() {
        // Find the best WPA2-PSK AP using Thompson Sampling
        ApKnowledge best = null;
        double bestSample = -999;
        for (ApKnowledge ap : apDB.values()) {
            if (!ap.flags.contains("WPA2-PSK") && !ap.flags.contains("WPA-PSK")) continue;
            if (ap.attempts >= 3) continue; // Don't retry too many times
            if (blacklistedSsids.contains(ap.ssid)) continue;
            
            // Thompson Sampling: pick AP with highest sampled reward
            double sample = thompsonSample(ap) * 100 + ap.signal * 0.01;
            if (ap.freq > 5000) sample -= 50;
            if (ap.freq < 2500) sample += 20;
            sample += ssidBonus(ap.ssid) * 0.1;
            if (best == null || sample > bestSample) {
                best = ap;
                bestSample = sample;
            }
        }
        
        if (best != null) {
            Decision d = new Decision("aggressive", "promising target: " + best.ssid + " (" + best.signal + "dBm)");
            d.targetBssid = best.bssid;
            d.targetSsid = best.ssid;
            d.targetFreq = best.freq;
            d.face = "(☼‿‿☼)";
            d.status = "hunting: " + best.ssid + " | " + currentApCount + " APs";
            best.attempts++;
            best.lastAttempt = System.currentTimeMillis();
            return d;
        }
        
        // No targets left to try — escalate to attack phase
        phase = PHASE_ATTACK;
        return thinkAttack();
    }
    
    private Decision thinkAttack() {
        // Only attack if at least 1 WPA2 AP visible
        if (currentWpa2Count < 1) {
            Decision d = new Decision("scan", "too few targets for attack");
            d.face = "(≖__≖)";
            d.status = "waiting for more APs...";
            return d;
        }
        
        // Find best target using Thompson Sampling + signal strength bias
        // Sniff-deauth works best on close APs where we can hear both AP and client
        ApKnowledge best = null;
        double bestSample = -999;
        for (ApKnowledge ap : apDB.values()) {
            if (!ap.flags.contains("WPA2-PSK") && !ap.flags.contains("WPA-PSK")) continue;
            // Skip SAE/WPA3 — deauth doesn't work on PMF-required APs
            if (ap.flags.contains("SAE")) continue;
            if (blacklistedSsids.contains(ap.ssid)) continue;
            if (ap.handshakeSuccess > 0) continue;  // already got this one
            
            // Signal-weighted Thompson sample
            // Strong signal = can hear both AP and client clearly
            double signalWeight = (ap.signal + 95) * 0.5;  // -45→25, -60→17.5, -75→10, -85→5, -95→0
            if (signalWeight < 0) signalWeight = 0;
            
            double sample = thompsonSample(ap) * 20 + signalWeight * 4.0;
            if (ap.freq > 5000) sample -= 30;     // 5GHz penalty (shorter range)
            if (ap.freq < 2500) sample += 15;     // 2.4GHz bonus (better range)
            sample += ssidBonus(ap.ssid) * 0.1;
            
            // Hard filter: too far = can't hear client clearly, penalize but don't skip
            if (ap.signal < -85) sample -= 50;
            // Wi-Fi Direct printers have NO clients — massive penalty
            if (ap.ssid != null && ap.ssid.toUpperCase().contains("DIRECT-")) sample -= 500;
            
            if (best == null || sample > bestSample) {
                best = ap;
                bestSample = sample;
            }
        }
        
        if (best == null) {
            Decision d = new Decision("scan", "no viable sniff targets");
            d.face = "(╥☁╥ )";
            return d;
        }
        
        // Always use sniff_deauth — passive EAPOL capture after CSA deauth flood
        // 30% deauth probability: only attack 30% of cycles to avoid DoS-ing the target
        if (Math.random() > 0.30) {
            Decision d = new Decision("scan", "deauth flood protection — skipping attack cycle");
            d.face = "(◕‿‿◕)";
            d.status = "pacing... (" + currentApCount + " APs, " + currentWpa2Count + " WPA2)";
            return d;
        }
        
        Decision d = new Decision("sniff_deauth", "CSA + sniff: " + best.ssid);
        d.targetBssid = best.bssid;
        d.targetSsid = best.ssid;
        d.targetFreq = best.freq;
        d.face = "(⌐■_■)";
        d.status = "👃 sniff: " + best.ssid + " (" + best.signal + "dBm)";
        
        best.attempts++;
        best.lastAttempt = System.currentTimeMillis();
        failedEvilTwins++;
        
        return d;
    }
    
    // ─── Learning ─────────────────────────────────────────────
    
    public void reportResult(String action, boolean success, String targetBssid, String details) {
        lastActionType = action;
        lastActionResult = success ? "success" : "failed";
        
        if (targetBssid != null && apDB.containsKey(targetBssid)) {
            ApKnowledge ap = apDB.get(targetBssid);
            
            if (success) {
                ap.score += 5;
                if (action.equals("sniff_deauth")) {
                    ap.handshakeSuccess++;
                    totalHandshakes++;
                    successfulEvilTwins++;
                    if (failedEvilTwins > 0) failedEvilTwins--;
                }
                if (action.equals("aggressive")) {
                    ap.pmkidFound = true;
                    ap.pmkidSuccess++;
                    totalPmkids++;
                }
            } else {
                // Penalize only technical failures, not "no client nearby"
                if (action.equals("sniff_deauth")) {
                    ap.score -= 2;
                    if (ap.attempts >= 6 && ap.score < -10) {
                        blacklistedSsids.add(ap.ssid);
                    }
                } else {
                    ap.score -= 1;
                    if (ap.attempts >= 8 && ap.score < -5) {
                        blacklistedSsids.add(ap.ssid);
                    }
                }
            }
        }
        
        // Adjust cooldown based on success rate
        if (success) {
            cooldown = Math.max(5000, cooldown - 5000);
        } else if (!action.equals("wait") && !action.equals("scan")) {
            cooldown = Math.min(60000, cooldown + 10000);
        }
        
        // Only reset timer for real actions (not idle scans/waits)
        if (!action.equals("wait") && !action.equals("scan")) {
            lastAction = System.currentTimeMillis();
        }
        saveMemory();
    }
    
    // ─── AP Database ─────────────────────────────────────────
    
    // Heuristic: score SSID for likelihood of having clients
    private int ssidBonus(String ssid) {
        if (ssid == null) return 0;
        String s = ssid.toLowerCase();
        // Penalize guest/hotspot/iot networks (fewer clients)
        if (s.contains("guest") || s.contains("-guest")) return -25;
        if (s.contains("iot") || s.contains("fridge") || s.contains("tv") || s.contains("printer")) return -15;
        if (s.contains("direct-") || s.contains("hotspot") || s.contains("mobile")) return -15;
        // Bonus for networks that look like home/business networks
        if (s.contains("funbox") || s.contains("netia") || s.contains("orange") || s.contains("play-")) return 10;
        // Hidden SSIDs might be important
        if (s.equals("<hidden>")) return 5;
        return 0;
    }
    
    private void updateApKnowledge(String[] ap) {
        // ap = {bssid, ssid, freq, signal, flags}
        if (ap.length < 5) return;
        String bssid = ap[0];
        
        ApKnowledge ak = apDB.get(bssid);
        if (ak == null) {
            ak = new ApKnowledge();
            ak.bssid = bssid;
            ak.ssid = ap[1];
            apDB.put(bssid, ak);
        }
        
        try {
            ak.freq = Integer.parseInt(ap[2]);
            ak.signal = Integer.parseInt(ap[3]);
        } catch (Exception e) {}
        ak.flags = ap[4];
        ak.lastSeen = System.currentTimeMillis();
    }
    
    // ─── Persistence ─────────────────────────────────────────
    
    public void saveMemory() {
        try {
            File f = new File("/data/data/com.pwnagotchi.app/files/brain.mem");
            f.getParentFile().mkdirs();
            PrintWriter pw = new PrintWriter(new FileWriter(f));
            pw.println("phase=" + phase);
            pw.println("successful_et=" + successfulEvilTwins);
            pw.println("failed_et=" + failedEvilTwins);
            pw.println("cooldown=" + cooldown);
            pw.println("total_pmkids=" + totalPmkids);
            pw.println("total_handshakes=" + totalHandshakes);
            pw.println("face=" + currentFace);
            pw.println("status=" + currentStatus);
            for (String s : blacklistedSsids) pw.println("blacklist=" + s);
            for (ApKnowledge ap : apDB.values()) {
                pw.printf("ap=%s|%s|%s|%d|%d|%d|%d|%d|%b|%b\n",
                    ap.bssid != null ? ap.bssid : "null",
                    ap.ssid != null ? ap.ssid : "null",
                    ap.flags != null ? ap.flags : "null",
                    ap.signal, ap.freq,
                    ap.score, ap.attempts, ap.pmkidSuccess, ap.evilTwinWorked, ap.pmkidFound);
            }
            pw.close();
        } catch (Exception e) {
            android.util.Log.e("PwngBrain", "saveMemory failed: " + e.getMessage(), e);
        }
    }
    
    private void loadMemory() {
        try {
            File f = new File("/data/data/com.pwnagotchi.app/files/brain.mem");
            if (!f.exists()) return;
            BufferedReader br = new BufferedReader(new FileReader(f));
            String line;
            while ((line = br.readLine()) != null) {
                if (line.startsWith("phase=")) phase = Integer.parseInt(line.substring(6));
                else if (line.startsWith("successful_et=")) successfulEvilTwins = Integer.parseInt(line.substring(14));
                else if (line.startsWith("failed_et=")) failedEvilTwins = Integer.parseInt(line.substring(10));
                else if (line.startsWith("cooldown=")) cooldown = Integer.parseInt(line.substring(9));
                else if (line.startsWith("total_pmkids=")) totalPmkids = Integer.parseInt(line.substring(13));
                else if (line.startsWith("total_handshakes=")) totalHandshakes = Integer.parseInt(line.substring(17));
                else if (line.startsWith("face=")) currentFace = line.substring(5);
                else if (line.startsWith("status=")) currentStatus = line.substring(7);
                else if (line.startsWith("blacklist=")) blacklistedSsids.add(line.substring(10));
                else if (line.startsWith("ap=")) {
                    String[] p = line.substring(3).split("\\|");
                    if (p.length >= 10) {
                        ApKnowledge ak = new ApKnowledge();
                        ak.bssid = p[0]; ak.ssid = p[1]; ak.flags = p[2];
                        ak.signal = Integer.parseInt(p[3]); ak.freq = Integer.parseInt(p[4]);
                        ak.score = Integer.parseInt(p[5]); ak.attempts = Integer.parseInt(p[6]);
                        ak.pmkidSuccess = Integer.parseInt(p[7]);
                        ak.evilTwinWorked = Boolean.parseBoolean(p[8]);
                        ak.pmkidFound = Boolean.parseBoolean(p[9]);
                        apDB.put(ak.bssid, ak);
                    }
                }
            }
            br.close();
        } catch (Exception e) {}
    }
    
    public int getPhase() { return phase; }
    public int getTotalPmkids() { return totalPmkids; }
    public int getTotalHandshakes() { return totalHandshakes; }
    public int getTotalScans() { return totalScans; }
    public String getPhaseName() {
        switch (phase) {
            case PHASE_OBSERVE: return "OBSERVE";
            case PHASE_HUNT: return "HUNT";
            case PHASE_ATTACK: return "ATTACK";
            default: return "UNKNOWN";
        }
    }
}
