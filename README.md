# Pwnagotchi Zero

Autonomous AI-driven WiFi security research tool for rooted Android. **No external hardware required.**

## Features
- **AI Brain**: Learns which APs respond to attacks, adapts over time
- **Passive PMKID capture**: Through wpa_supplicant control socket (no monitor mode)
- **Aggressive PMKID hunting**: Fake-associate to trigger PMKID in AP response
- **Evil Twin**: Creates fake AP to capture client handshakes
- **nl80211 Deauth**: Send spoofed deauth frames through mgmt_tx (no monitor mode)
- **25KB APK**: Minimal, no dependencies

## How It Works

### Phases
1. **OBSERVE (0-2min)**: Passive scanning, learning the environment
2. **HUNT (2-10min)**: Aggressive PMKID hunting on promising WPA2-PSK APs
3. **ATTACK (10min+)**: Evil Twin + Deauth on learned targets

### Technical Stack
- `wpa_cli` for BSS scanning and RSN IE extraction
- `hostapd` for Evil Twin AP creation
- Raw `nl80211` netlink for deauth frame injection
- PBKDF2-HMAC-SHA1 for PMKID parsing
- Reinforcement learning for autonomous decision making

## Requirements
- Rooted Android 8+ with Magisk
- That's it. No external WiFi adapters, no monitor mode.

## Build
```bash
# Requires: Android SDK (build-tools 34), Eclipse ECJ, aapt2, d8, apksigner
./build.sh
```

## Usage
1. Install `Pwnagotchi.apk`
2. Open app → tap START
3. Let the AI work autonomously
4. Export hashes: tap EXPORT → `adb pull /sdcard/pmkid_hashes.22000`
5. Crack: `hashcat -m 22000 pmkid_hashes.22000 rockyou.txt`

## Legal
For authorized security research only. Use on networks you own or have permission to test.
