#!/system/bin/sh
# Pwnagotchi Monitor Mode Starter + WiFi Kill
# Disables Android WiFi service, enables monitor mode, sets channel.

MOD=adrastea
FW=/data/local/tmp/fw
IFACE=wlan0
IW=/data/local/tmp/iw

# 1. Kill Android WiFi so wpa_supplicant doesn't fight us
svc wifi disable 2>/dev/null
sleep 2

# 2. Stage firmware
mkdir -p $FW/$MOD 2>/dev/null
cp /vendor/firmware_mnt/image/$MOD/* $FW/$MOD/ 2>/dev/null

# 3. Copy iw + libs (one-time)
cp /data/data/com.termux/files/usr/bin/iw $IW 2>/dev/null
cp /data/data/com.termux/files/usr/lib/libnl* /data/local/tmp/ 2>/dev/null

# 4. Set firmware path
echo -n "$FW" > /sys/module/firmware_class/parameters/path 2>/dev/null

# 5. Down -> monitor mode -> up
/system/bin/ip link set $IFACE down 2>/dev/null
sleep 1
echo 4 > /sys/module/$MOD/parameters/con_mode
sleep 4
/system/bin/ip link set $IFACE up 2>/dev/null
sleep 2

# 6. Set channel
LD_LIBRARY_PATH=/data/local/tmp $IW dev $IFACE set channel 6 2>/dev/null

# 7. Status
echo "con_mode=$(cat /sys/module/$MOD/parameters/con_mode)"
echo "link=$(/system/bin/ip link show $IFACE | grep -o 'state [A-Z]*')"
echo "MONITOR_OK"
