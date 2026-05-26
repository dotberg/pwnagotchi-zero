package com.pwnagotchi.app;

import java.io.*;
import java.util.*;

/**
 * Pwnagotchi AI Brain — autonomous decision engine.
 * 
 * Learns which APs respond to which attacks, adapts strategy over time.
 * 
 * Phases:
 *   PHASE_OBSERVE (0-120s): Passive scan only, learn environment
 *   PHASE_HUNT (120-600s): Aggressive PMKID hunting on promising APs
 *   PHASE_ATTACK (600s+): Evil Twin + deauth on learned targets
 * 
 * Scoring:
 *   +1  PMKID captured (passive/aggressive)
 *   +3  Handshake captured (evil twin)
 *   -1  Failed attempt (no response)
 *   +5  Deauth success (AP went down briefly)
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
        public String action; // "scan", "aggressive", "evil_twin", "deauth_twin", "wait"
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
        
        // Phase transition logic (aggressive timing)
        if (phase == PHASE_OBSERVE && sessionAge > 30000) {  // 30s observe
            phase = PHASE_HUNT;
        }
        if (phase == PHASE_HUNT && sessionAge > 90000) {     // 90s total -> attack
            phase = PHASE_ATTACK;
        }
        // Fast-track: go straight to attack after 60s if we have targets
        if (wpa2Count >= 3 && sessionAge > 60000) {
            phase = PHASE_ATTACK;
        }
        
        // Cooldown check
        if (now - lastAction < cooldown) {
            return new Decision("wait", "cooldown " + ((cooldown - (now - lastAction))/1000) + "s");
        }
        
        // Phase-specific logic
        switch (phase) {
            case PHASE_OBSERVE:
                return thinkObserve();
            case PHASE_HUNT:
                return thinkHunt();
            case PHASE_ATTACK:
                return thinkAttack();
            default:
                return new Decision("scan", "default");
        }
    }
    
    private Decision thinkObserve() {
        long age = (System.currentTimeMillis() - sessionStart) / 1000;
        Decision d = new Decision("scan", "learning environment");
        d.face = "(✜‿‿✜)";
        d.status = "observing... (" + age + "s) | " + currentApCount + " APs, " + currentWpa2Count + " WPA2";
        return d;
    }
    
    private Decision thinkHunt() {
        // Find the best WPA2-PSK AP we haven't tried aggressive on yet
        ApKnowledge best = null;
        for (ApKnowledge ap : apDB.values()) {
            if (!ap.flags.contains("WPA2-PSK") && !ap.flags.contains("WPA-PSK")) continue;
            if (ap.attempts >= 3) continue; // Don't retry too many times
            if (blacklistedSsids.contains(ap.ssid)) continue;
            
            if (best == null || ap.signal > best.signal) {
                best = ap;
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
        // Only attack if enough APs around (stealth)
        if (currentWpa2Count < 2) {
            Decision d = new Decision("scan", "too few targets for attack");
            d.face = "(≖__≖)";
            d.status = "waiting for more APs...";
            return d;
        }
        
        // Find best target for Evil Twin - prefer 2.4GHz (our AP can't do 5GHz)
        ApKnowledge best = null;
        int bestScore = -999;
        for (ApKnowledge ap : apDB.values()) {
            if (!ap.flags.contains("WPA2-PSK") && !ap.flags.contains("WPA-PSK")) continue;
            if (blacklistedSsids.contains(ap.ssid)) continue;
            if (ap.evilTwinWorked) continue;
            
            // 5GHz penalty: our hotspot only works on 2.4GHz!
            int apScore = ap.signal + ap.pmkidSuccess * 10 + ssidBonus(ap.ssid);
            if (ap.freq > 5000) apScore -= 50;  // Heavy penalty for 5GHz
            if (ap.freq < 2500) apScore += 20;  // Bonus for 2.4GHz
            
            if (best == null || apScore > bestScore) {
                best = ap;
                bestScore = apScore;
            }
        }
        
        if (best == null) {
            Decision d = new Decision("scan", "no viable attack targets");
            d.face = "(╥☁╥ )";
            return d;
        }
        
        // Decide: Evil Twin only or Deauth + Evil Twin
        // Use deauth if: we've had success before AND AP is on 2.4GHz (better range)
        boolean useDeauth = (successfulEvilTwins >= 1 || failedEvilTwins < 2) && best.freq < 5000;
        
        Decision d = new Decision(
            useDeauth ? "deauth_twin" : "evil_twin",
            useDeauth ? "DEAUTH + Evil Twin: " + best.ssid : "Evil Twin: " + best.ssid
        );
        d.targetBssid = best.bssid;
        d.targetSsid = best.ssid;
        d.targetFreq = best.freq;
        d.face = "(⌐■_■)";
        d.status = "attacking: " + best.ssid + " (" + best.signal + "dBm)" + (useDeauth ? " [DEAUTH]" : "");
        
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
                if (action.equals("evil_twin") || action.equals("deauth_twin")) {
                    ap.evilTwinWorked = true;
                    ap.handshakeSuccess++;
                    totalHandshakes++;
                    successfulEvilTwins++;
                    failedEvilTwins--; // Undo the pre-increment
                }
                if (action.equals("aggressive")) {
                    ap.pmkidFound = true;
                    ap.pmkidSuccess++;
                    totalPmkids++;
                }
            } else {
                // Only blacklist for technical failures, not for "no client"
                // "No client" is normal - try again later
                if (action.equals("evil_twin") || action.equals("deauth_twin")) {
                    // Evil Twin failed technically (WiFi restore issue etc) - penalize hard
                    ap.score -= 3;
                    if (ap.attempts >= 5 && ap.score < -10) {
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
            cooldown = Math.min(60000, cooldown + 5000);
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
                    ap.bssid, ap.ssid, ap.flags, ap.signal, ap.freq,
                    ap.score, ap.attempts, ap.pmkidSuccess, ap.evilTwinWorked, ap.pmkidFound);
            }
            pw.close();
        } catch (Exception e) {}
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
