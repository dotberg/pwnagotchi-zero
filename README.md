# Pwnagotchi Zero

**Autonomous AI-driven WiFi security research tool for rooted Android. Zero external hardware.**

[![Phase](https://img.shields.io/badge/AI-Thompson%20Sampling-blue)]()
[![API](https://img.shields.io/badge/API-34%2B-green)]()
[![Size](https://img.shields.io/badge/APK-25KB-brightgreen)]()
[![License](https://img.shields.io/badge/license-MIT-red)]()

## What It Does

Pwnagotchi Zero turns any rooted Android phone into an autonomous WiFi security testing platform. **No monitor mode, no external adapters, no Raspberry Pi required.**

The AI brain uses **Thompson Sampling** (Bayesian bandit) to learn which access points are vulnerable and adapts its attack strategy over time.

## Features

- **AI Brain** — Thompson Sampling with Beta distributions per AP, learns from experience
- **Passive PMKID Capture** — Extracts PMKIDs from wpa_supplicant BSS info (RSN IE parsing)
- **Aggressive PMKID Hunting** — Fake-associate to trigger PMKID in AP response
- **Evil Twin** — Creates fake AP with target SSID, captures client handshakes
- **nl80211 Deauth** — Sends spoofed deauth frames via mgmt_tx (no monitor mode!)
- **3-Phase Learning** — OBSERVE (30s) → HUNT (aggressive) → ATTACK (evil twin)

## Technical Deep Dive

### PMKID Extraction (No Monitor Mode)
Uses `wpa_cli -p /data/vendor/wifi/wpa/sockets -i wlan0 bss <BSSID>` to get raw RSN IE hex data. Parses the TLV structure looking for tag `0x30` (RSN), extracts PMKID list field. Outputs hashcat `-m 22000` format.

### nl80211 Deauth Injection
Sends raw deauthentication frames through Linux's nl80211 netlink interface using `NL80211_CMD_FRAME`. The `MGMT_TX_RANDOM_TA` driver capability allows spoofed transmitter addresses — no monitor mode needed.

### Evil Twin via hostapd
Uses Android's `cmd wifi start-softap` to create a WPA2-PSK access point with the target SSID. Monitors hostapd logs for EAPOL handshake events from connecting clients.

### AI: Thompson Sampling
Each AP is modeled as a Beta distribution: `Beta(α=successes+1, β=failures+1)`. The brain samples from each distribution and selects the highest value — naturally balancing exploration vs exploitation. No neural networks, no TensorFlow — pure Bayesian statistics.

## Requirements

- **Rooted Android 8+** with Magisk
- That's it. No external WiFi adapters, no monitor mode hardware.

## Installation

```bash
# Download latest APK from Releases
adb install Pwnagotchi.apk

# Push deauth binary (compiled from deauth/deauth.c)
adb push deauth /data/data/com.pwnagotchi.app/deauth
adb shell su -c 'chmod 755 /data/data/com.pwnagotchi.app/deauth'
```

## Building from Source

```bash
# Requirements: Android SDK, Eclipse ECJ, NDK r27+
./build.sh          # Build APK
./deauth/build.sh   # Build deauth binary (ARM64)
```

## Usage

1. Launch Pwnagotchi
2. Tap `[ STOP ]` to start
3. Let the AI work autonomously
4. Export hashes: tap `[ EXPORT ]`
5. Pull and crack: `adb pull /sdcard/pmkid_hashes.22000 . && hashcat -m 22000 pmkid_hashes.22000 rockyou.txt`

## Architecture

```
Pwnagotchi.apk (25KB)
├── PwngBrain.java      — Thompson Sampling AI engine
├── PwngService.java    — Background scanning & attack service
├── MainActivity.java   — Matrix-green UI
└── deauth.c            — nl80211 deauth injection (ARM64 native)
```

## Cracking Hashes

The app saves hashes in hashcat `-m 22000` format:
```
WPA*02*PMKID*BSSID*STAMAC*SSID***
```

Crack with:
```bash
hashcat -m 22000 pmkid_hashes.22000 rockyou.txt
```

## Legal

For authorized security research and penetration testing only. Use only on networks you own or have explicit permission to test.

## Credits

Built by **dotberg** — because the best tools come from being broke and having nothing but a rooted phone and determination.

