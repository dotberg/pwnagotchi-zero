# Pwnagotchi Zero — Autonomous WiFi Security for Rooted Android

**Turn any rooted Android phone into an AI-driven WiFi security testing platform. Uses native Qualcomm monitor mode — zero external hardware, zero kernel patches.**

[![Monitor Mode](https://img.shields.io/badge/monitor%20mode-native-brightgreen)]()
[![API](https://img.shields.io/badge/API-34%2B-green)]()
[![APK](https://img.shields.io/badge/APK-37KB-brightgreen)]()
[![License](https://img.shields.io/badge/license-MIT-red)]()

## It Works. Here's Proof.

v1.7 is battle-tested on Motorola Edge 50 Fusion (cuscoi, Android 14). Monitor mode via firmware reload. Deauth + passive EAPOL capture. Thompson Sampling AI brain. RESTART button in notification — no ADB needed after initial deploy.

```
scan: 5 APs, 5 WPA2  →  sniff_deauth: Target-2.4G ch1  →  PRESCAN: 47 beacons, 10 client frames  →  CSA flood 20s  →  waiting reconnect  →  sniffing EAPOL
```

## What's New in v1.7

- **Aggressive deauth**: 85% attack gate, 20s burst, 200ms intervals — ~2000 deauth+CSA frames per burst (was: 30% gate, 10s, 600ms = ~320 frames)
- **Client pre-scan**: 5-second scan checks for actual client activity before committing to attack. Empty APs get blacklisted for 10 cycles — saves 85 seconds per miss.
- **Stale AP pruning**: Brain filters APs not seen in 60s, prunes DB entries older than 120s. No more chasing APs from 5km ago.
- **RESTART button**: Notification action + in-app button. Kills process, `START_STICKY` revives with fresh state. Zero cable needed after first deploy.
- **Clean targets only**: Skips SAE/WPA3, Wi-Fi Direct, hotspots, and APs with 3+ consecutive "no clients" results.

## Features

- **Native Monitor Mode** — Qualcomm QCACLD-3.0 firmware reload trick. Raw 802.11 radiotap frames from internal WiFi.
- **Thompson Sampling AI** — Bayesian bandit per AP. Learns which targets respond. Persists across sessions.
- **CSA + Deauth Flood** — Channel Switch Announcement beacons bypass PMF (802.11w). Alternating deauth + CSA spoofed from target AP.
- **Passive EAPOL Capture** — tcpdump with `wlan addr3` filter (radiotap-safe). Post-verified with EAPOL count. No false positives.
- **Channel Hopping** — Rotates channels 1→6→11 each scan cycle. 15-20 APs visible instead of 2-3.
- **Self-contained** — MonitorManager.enable() auto-starts firmware reload from within the app. No external scripts needed at runtime.

## Quick Deploy

```bash
# Build
curl -sL "https://repo1.maven.org/maven2/org/eclipse/jdt/ecj/3.33.0/ecj-3.33.0.jar" -o /tmp/ecj2.jar
./build.sh

# Install & run (do this EVERY TIME after adb install — UID changes!)
adb install -r Pwnagotchi.apk
# >>> TAP "GRANT" IN MAGISK ON PHONE <<<
adb shell su -c 'appops reset com.pwnagotchi.app'
adb shell am start-foreground-service -n com.pwnagotchi.app/.PwngService

# Verify (after 20s)
adb shell su -c 'cat /sys/module/adrastea/parameters/con_mode'  # must show "4"
adb logcat -s PwngService:PwngBrain | grep 'sniff_deauth'
```

After initial deploy, use the **RESTART button in the notification** to restart — no ADB needed.

## Health Check

```bash
# Monitor mode active?
adb shell su -c 'cat /sys/module/adrastea/parameters/con_mode'  # 4 = monitor

# Brain state
adb shell su -c 'cat /data/data/com.pwnagotchi.app/files/brain.mem' | head -5

# Real handshakes? (EAPOL count must be > 0!)
adb shell su -c 'ls -la /data/local/tmp/handshakes/hs_*'
adb shell su -c 'tcpdump -r /data/local/tmp/handshakes/hs_XXXX.pcap -n 2>/dev/null | grep -c EAPOL'
```

## Architecture

```
Pwnagotchi.apk (37KB)
├── PwngBrain.java         — Thompson Sampling AI, stale pruning, noClients tracking
├── PwngService.java       — Scan/attack loop, client pre-scan, RESTART handler
├── MonitorManager.java    — Firmware reload, beacon_flood deauth, tcpdump capture
├── MainActivity.java      — Matrix-green status UI with live stats
└── wallpaper/
    └── MatrixWallpaper.java — Matrix rain live wallpaper
```

## The Qualcomm Monitor Mode Trick

TL;DR: `con_mode=4` is read-only normally. But it becomes **writable during firmware reload**. So we stage firmware, point `firmware_class/path` at our staging dir, bring wlan0 down, write `4`, bring it up — monitor mode.

```bash
MOD=adrastea; FW=/data/local/tmp/fw
mkdir -p $FW/$MOD && cp /vendor/firmware_mnt/image/$MOD/* $FW/$MOD/
echo -n "$FW" > /sys/module/firmware_class/parameters/path
ip link set wlan0 down
echo 4 > /sys/module/$MOD/parameters/con_mode
sleep 4; ip link set wlan0 up
# wlan0 is now IEEE802_11_RADIO — full monitor mode
```

This is handled automatically by the APK's `MonitorManager.enable()`. No manual steps at runtime.

dmesg confirmation:
```
adrastea: Monitor mode is enabled
device wlan0 entered promiscuous mode
```

## Critical Pitfalls (Read Before Debugging)

| Problem | Cause | Fix |
|---------|-------|-----|
| 0 APs in scan | tcpdump without `su -Z u:r:magisk:s0` | Use `execSuMagisk()` for all tcpdump/iw/beacon_flood calls |
| 24-byte pcap (empty) | `ether proto 0x888e` BPF broken on radiotap | Use `wlan addr3 <BSSID>` filter |
| Deauth never transmitted | Channel number passed as MHz to beacon_flood | Pass frequency in MHz (e.g. 2437, not 6) |
| Monitor mode dies after enable | `svc wifi disable` called after `ip link up` | NEVER touch svc wifi during monitor mode |
| Motorola notification hidden | POST_NOTIFICATION blocked at UID level | `adb shell su -c 'appops reset com.pwnagotchi.app'` |
| Service won't start from ADB | `exported="false"` blocks external start | Use `am start-foreground-service` (works with exported=false) |

## Requirements

- **Rooted Android 8+** with Magisk (tested on 30.7)
- **Qualcomm WiFi** using qcacld-3.0/adrastea driver
- **Termux** with `tcpdump`, `iw`, `libnl` (one-time setup)
- ADB for initial deployment only

## 🔬 The Qualcomm Monitor Mode Breakthrough

*This is the part that took 6 hours of kernel module compilation hell to discover. Future researchers: skip the kernel modules, here's the trick.*

### The Problem

Qualcomm QCACLD-3.0 (adrastea driver on Motorola Edge 50 Fusion / cuscoi, kernel 5.10.198) has a `con_mode` parameter at `/sys/module/adrastea/parameters/con_mode`:

- `0` = normal station mode  
- `4` = monitor mode  

But writing to it fails with `Permission denied` even as root. The `module_param` is declared with `S_IRUGO` (0444) — read-only. SELinux also blocks it. `CONFIG_MODULE_FORCE_LOAD=n` prevents forced loading. Kernel module compilation fails because `CONFIG_MODVERSIONS=y` enforces symbol CRC matching (and the vendor kernel's CRCs aren't in AOSP sources).

### The Solution: Firmware Reload Trick

The `con_mode` parameter becomes **writable during firmware reload**. The trick:

1. Stage a copy of the WiFi firmware to a writable location  
2. Point the kernel's firmware loader at the staging directory  
3. Bring `wlan0` down  
4. Write `4` to `con_mode` — this triggers a firmware reload because the path changed  
5. During the reload, the parameter IS writable  
6. Bring `wlan0` back up — it's now in monitor mode with `link/ieee802.11/radiotap`

### The Code

```bash
# Stage firmware
MOD=adrastea
FW=/data/local/tmp/fw
mkdir -p $FW/$MOD
cp /vendor/firmware_mnt/image/$MOD/* $FW/$MOD/

# Set firmware path (forces reload on con_mode write)
echo -n "$FW" > /sys/module/firmware_class/parameters/path

# Bring down, set monitor mode, bring up
ip link set wlan0 down
echo 4 > /sys/module/$MOD/parameters/con_mode
sleep 4
ip link set wlan0 up

# Verify
cat /sys/module/$MOD/parameters/con_mode  # should show "4"
iw dev wlan0 info                            # should show "type monitor"
tcpdump -i wlan0 -n -e                       # beacons should appear
```

### Key Requirements

| Requirement | Why |
|-------------|-----|
| Root (Magisk) | Writing to sysfs, firmware path, ip link |
| SELinux permissive | `setenforce 0` is usually needed (USB ADB helps) |
| `tcpdump` + `su -Z u:r:magisk:s0` | `CAP_NET_RAW` needed for packet capture from app context |
| `iw` with `libnl` | Channel setting (Termux provides these) |
| Firmware staging directory | Must contain ALL firmware files from the vendor partition |

### dmesg Confirmation

```
adrastea: [9:I:HTT] htt_h2t_rx_ring_cfg_msg_ll : Monitor mode is enabled
adrastea: [16394:I:HDD] __hdd_driver_mode_change: Acquire wakelock for monitor mode
device wlan0 entered promiscuous mode
```

### Why This Works

The Qualcomm WiFi firmware (bdwlan_cuscoi_ipa.bin) has monitor mode support compiled in — it's just disabled by the kernel config (`CONFIG_FEATURE_MONITOR_MODE_SUPPORT := n` in qcacld-3.0). The firmware itself supports it. The `con_mode=4` write during firmware reload tells the firmware to boot in monitor mode. The kernel's module_param permission check is bypassed because the firmware reload path takes a different code path.

### Related Research

- [kimocoder/qualcomm_android_monitor_mode](https://github.com/kimocoder/qualcomm_android_monitor_mode) — Original research on QCACLD monitor mode
- [spiral009/aviumui-wlan-tools](https://github.com/spiral009/aviumui-wlan-tools) — OnePlus monitor mode scripts (inspired the firmware reload approach)
- Qualcomm CodeLinaro `qcacld-3.0` — `CONFIG_FEATURE_MONITOR_MODE_SUPPORT` flag exists but is off by default

---

## Manual Monitor Mode (Without the App)

```bash
# Enable
su -c 'mkdir -p /data/local/tmp/fw/adrastea'
su -c 'cp /vendor/firmware_mnt/image/adrastea/* /data/local/tmp/fw/adrastea/'
su -c 'echo -n "/data/local/tmp/fw" > /sys/module/firmware_class/parameters/path'
su -c 'ip link set wlan0 down; echo 4 > /sys/module/adrastea/parameters/con_mode'
sleep 4; su -c 'ip link set wlan0 up'
su -c 'export LD_LIBRARY_PATH=/data/local/tmp; /data/local/tmp/iw dev wlan0 set channel 6'
su -Z u:r:magisk:s0 -c 'tcpdump -i wlan0 -n -e'

# Disable (restore normal WiFi)
su -c 'ip link set wlan0 down; echo 0 > /sys/module/adrastea/parameters/con_mode'
sleep 2; su -c 'svc wifi enable'
```

## Creditz

Built by **dotberg** — 6 hours of kernel module hell, solved by a firmware reload trick. Because the best tools come from being broke and having nothing but a rooted phone and determination.

*"Monitor mode was always there. Qualcomm just hid it behind a firmware reload."*
