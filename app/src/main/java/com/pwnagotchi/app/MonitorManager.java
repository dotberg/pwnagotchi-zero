package com.pwnagotchi.app;

import java.io.*;

/**
 * Manages native WiFi monitor mode on Qualcomm adrastea (QCACLD-3.0).
 * Enables monitor mode via firmware reload, controls channels via iw.
 */
public class MonitorManager {
    
    private static final String MODULE = "adrastea";
    private static final String CON_MODE_PATH = "/sys/module/" + MODULE + "/parameters/con_mode";
    private static final String FW_PATH = "/sys/module/firmware_class/parameters/path";
    private static final String STAGING_DIR = "/data/local/tmp/fw";
    private static final String IFACE = "wlan0";
    private static final String IW_BIN = "/data/local/tmp/iw";
    private static final String IW_LIBS = "/data/local/tmp";
    
    private boolean monitorEnabled = false;
    
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
        
        if (!stageFirmware()) {
            System.err.println("[Monitor] Firmware staging failed");
            return false;
        }
        
        try {
            // Just bring wlan0 down directly — don't touch svc wifi
            // (svc wifi disable asynchronously kills the interface later)
            execSuMagisk("ip link set " + IFACE + " down 2>/dev/null");
            Thread.sleep(1500);
            
            // Set firmware path (needs root)
            execSu("echo -n '" + STAGING_DIR + "' > " + FW_PATH);
            
            // Bring interface down, set con_mode, bring up
            execSuMagisk("ip link set " + IFACE + " down 2>/dev/null");
            Thread.sleep(1000);
            
            // Set con_mode=4 (triggers firmware reload)
            execSu("echo 4 > " + CON_MODE_PATH);
            Thread.sleep(4000);
            
            // Bring interface up - needs NET_ADMIN from magisk context
            execSuMagisk("ip link set " + IFACE + " up 2>/dev/null");
            Thread.sleep(1500);
            
            // Verify interface is up
            String linkState = execSu("ip link show " + IFACE + " 2>/dev/null | grep -o 'state UP\\|state DOWN'").trim();
            if (!linkState.contains("UP")) {
                // Retry
                execSuMagisk("ip link set " + IFACE + " up 2>/dev/null");
                Thread.sleep(1000);
            }
            
            // Verify - read first line only
            String modeRaw = execSu("cat " + CON_MODE_PATH);
            String mode = modeRaw != null ? modeRaw.split("\\n")[0].trim() : "";
            monitorEnabled = "4".equals(mode);
            
            if (monitorEnabled) {
                System.out.println("[Monitor] Enabled (con_mode=" + mode + ")");
                // Copy iw to global location if needed
                execSu("cp /data/data/com.termux/files/usr/bin/iw " + IW_BIN + " 2>/dev/null; cp /data/data/com.termux/files/usr/lib/libnl* " + IW_LIBS + "/ 2>/dev/null");
                // Set initial channel
                setChannel(6);
            } else {
                System.err.println("[Monitor] Failed (con_mode=" + mode + ")");
            }
            
            return monitorEnabled;
        } catch (Exception e) {
            System.err.println("[Monitor] Error: " + e.getMessage());
            return false;
        }
    }
    
    /**
     * Disable monitor mode and restore normal WiFi.
     */
    public boolean disable() {
        if (!monitorEnabled) return true;
        
        try {
            execSuMagisk("ip link set " + IFACE + " down 2>/dev/null");
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
        String cmd = "export LD_LIBRARY_PATH=" + IW_LIBS + "; " + IW_BIN + " dev " + IFACE + " set channel " + channel;
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
        return execSuMagisk(cmd);
    }
    
    /**
     * Capture to a pcap file with optional BPF filter.
     */
    public String capture(String outputPath, int durationSeconds, String filter) {
        if (!monitorEnabled) return null;
        
        String filterPart = (filter != null && !filter.isEmpty()) ? " " + filter : "";
        String cmd = "timeout " + durationSeconds + " tcpdump -i " + IFACE 
                     + " -w " + outputPath + filterPart + " 2>/dev/null";
        execSu(cmd);
        
        File f = new File(outputPath);
        return (f.exists() && f.length() > 100) ? outputPath : null;
    }
    
    /**
     * Execute a command as root via su.
     */
    private String execSu(String cmd) {
        return exec(new String[]{"su", "-c", cmd + " 2>&1; exit"});
    }
    
    /**
     * Execute with magisk context (has CAP_NET_RAW for tcpdump).
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
            
            try { reader.join(8000); } catch (Exception e) {}
            
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
