# Pwnagotchi Zero — Native Monitor Mode Edition

**Autonomous AI-driven WiFi security research tool for rooted Android. Now with native Qualcomm monitor mode — no external adapters, no custom ROM, no kernel patches.**

[![Monitor Mode](https://img.shields.io/badge/monitor%20mode-native-brightgreen)]()
[![API](https://img.shields.io/badge/API-34%2B-green)]()
[![Size](https://img.shields.io/badge/APK-33KB-brightgreen)]()
[![License](https://img.shields.io/badge/license-MIT-red)]()

## What It Does

Pwnagotchi Zero turns any rooted Android phone into an autonomous WiFi security testing platform.

**v1.6.2 introduces native monitor mode on Qualcomm QCACLD-3.0 chipsets (adrastea driver).** The internal WiFi chip now captures raw 802.11 frames with full radiotap headers — beacon frames, probe requests, data frames, everything. No external USB adapters, no custom kernels, no kernel module compilation hell.

The AI brain uses **Thompson Sampling** (Bayesian bandit) to learn which access points are vulnerable and adapts its attack strategy over time.

## Features

- **Native Monitor Mode** — Qualcomm QCACLD-3.0 monitor mode via firmware reload trick (see below)
- **AI Brain** — Thompson Sampling with Beta distributions per AP, learns from experience
- **Passive PMKID Capture** — Extracts PMKIDs from wpa_supplicant BSS info (RSN IE parsing)
- **Aggressive PMKID Hunting** — Fake-associate to trigger PMKID in AP response
- **Evil Twin** — Creates fake AP with target SSID, captures client handshakes
- **nl80211 Deauth** — Sends spoofed deauth frames via mgmt_tx
- **3-Phase Learning** — OBSERVE → HUNT → ATTACK
- **Big Notification** — High priority, expandable status display

---

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

## Requirements

- **Rooted Android 8+** with Magisk (tested on Magisk 30.7)
- **Termux** with `iw` and `libnl` (for channel control)
- **Qualcomm WiFi chipset** using qcacld-3.0/adrastea driver
- USB ADB (recommended — WiFi ADB is unstable for critical operations)

## Installation

### One-Time Setup (first time only)

```bash
# 1. Install prerequisites via Termux
pkg install tcpdump iw

# 2. Copy binaries to accessible location
adb shell su -c 'cp /data/data/com.termux/files/usr/bin/iw /data/local/tmp/iw'
adb shell su -c 'cp /data/data/com.termux/files/usr/lib/libnl* /data/local/tmp/'

# 3. Install the APK
adb install Pwnagotchi.apk
```

### Post-Install / Post-Rebuild Procedure (EVERY TIME)

**CRITICAL: After EVERY `adb install` (new UID), these steps are MANDATORY. Skipping any will result in \"0 APs\" or broken monitor mode.**

```bash
# STEP 1: Enable monitor mode (one-time per boot)
adb shell su -c 'sh /data/local/tmp/monitor_start.sh'
# Verify: cat /sys/module/adrastea/parameters/con_mode → must show "4"

# STEP 2: Grant root in Magisk MANUALLY
#    The APK's first `su` call triggers a Magisk root prompt.
#    Tap "GRANT" on the phone screen.
#    WHY: Every `adb install` gives the app a new UID.
#         Magisk policies are per-UID — the old grant doesn't carry over.
#         Without this, MonitorManager.enable() times out after 8s → no monitor mode.

# STEP 3: Fix Motorola notification block
adb shell su -c 'appops reset com.pwnagotchi.app'

# STEP 4: Start the service
adb shell am start-foreground-service -n com.pwnagotchi.app/.PwngService

# STEP 5: Verify it's working
sleep 10
adb shell su -c 'cat /data/data/com.pwnagotchi.app/files/brain.mem' | head -3
# Expected output:
#   phase=0
#   face=(◕‿‿◕)
#   status=monitor mode active      ← THIS is the critical line

# STEP 6: Verify AP visibility
sleep 30
adb shell su -c 'cat /data/data/com.pwnagotchi.app/files/brain.mem' | grep '^ap='
# Should show access points. If empty → root not granted (step 2).
```

### Quick Health Check Commands

```bash
# Is the service running?
adb shell su -c 'ps -A | grep pwn'

# Is monitor mode active?
adb shell su -c 'cat /sys/module/adrastea/parameters/con_mode'
# 0 = normal, 4 = monitor

# What's the brain thinking?
adb shell su -c 'cat /data/data/com.pwnagotchi.app/files/brain.mem' | head -5

# Check for captured handshakes (real ones have EAPOL!)
adb shell su -c 'ls -la /data/local/tmp/handshakes/hs_*'
adb shell su -c 'tcpdump -r /data/local/tmp/handshakes/hs_XXXX.pcap -n 2>/dev/null | grep -c EAPOL'
# Must return > 0 for a real handshake. 0 = beacons only, discard the file.
```

## Building from Source

```bash
# Requirements: Android SDK (build-tools 34), Eclipse ECJ
curl -sL "https://repo1.maven.org/maven2/org/eclipse/jdt/ecj/3.33.0/ecj-3.33.0.jar" -o /tmp/ecj2.jar
./build.sh          # Build APK (~33KB)
adb install -r Pwnagotchi.apk
```

## Architecture

```
Pwnagotchi.apk (33KB)
├── PwngBrain.java         — Thompson Sampling AI engine
├── PwngService.java       — Background scanning & attack service
├── MonitorManager.java    — Qualcomm monitor mode via firmware reload
├── MainActivity.java      — Matrix-green UI
├── wallpaper/
│   └── MatrixWallpaper.java — Matrix rain live wallpaper (bonus!)
└── deauth/                — nl80211 deauth injection (ARM64 native)
```

## Usage

1. Launch Pwnagotchi from app drawer
2. Service starts automatically — notification shows face and AP count
3. Pull down notification for expanded stats (APs, WPA2, handshakes, uptime)
4. Let the AI work autonomously — it cycles through OBSERVE → HUNT → ATTACK
5. Handshakes saved to `/sdcard/Android/data/com.pwnagotchi.app/files/handshakes/`
6. Tap notification to open status screen, tap Stop to exit

## Manual Monitor Mode (without the app)

```bash
# Enable
su -c 'mkdir -p /data/local/tmp/fw/adrastea'
su -c 'cp /vendor/firmware_mnt/image/adrastea/* /data/local/tmp/fw/adrastea/'
su -c 'echo -n "/data/local/tmp/fw" > /sys/module/firmware_class/parameters/path'
su -c 'ip link set wlan0 down; echo 4 > /sys/module/adrastea/parameters/con_mode'
sleep 4
su -c 'ip link set wlan0 up'
su -c 'export LD_LIBRARY_PATH=/data/local/tmp; /data/local/tmp/iw dev wlan0 set channel 6'
su -Z u:r:magisk:s0 -c 'tcpdump -i wlan0 -n -e'

# Disable (restore normal WiFi)
su -c 'ip link set wlan0 down; echo 0 > /sys/module/adrastea/parameters/con_mode'
sleep 2
su -c 'svc wifi enable'
```

## Creditz

Built by **dotberg** — 6 hours of kernel module hell, solved by a firmware reload trick.
Because the best tools come from being broke and having nothing but a rooted phone and determination.

*"Monitor mode was always there. Qualcomm just hid it behind a firmware reload."*
