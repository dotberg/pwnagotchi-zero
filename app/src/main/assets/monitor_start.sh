#!/system/bin/sh
# Pwnagotchi Monitor Mode Starter
# Called by APK's MonitorManager via su -c

MOD=adrastea
FW=/data/local/tmp/fw
IFACE=wlan0
IW=/data/local/tmp/iw
IW_LIBS=/data/local/tmp

# Stage firmware
mkdir -p $FW/$MOD 2>/dev/null
cp /vendor/firmware_mnt/image/$MOD/* $FW/$MOD/ 2>/dev/null

# Copy iw + libs
cp /data/data/com.termux/files/usr/bin/iw $IW 2>/dev/null
cp /data/data/com.termux/files/usr/lib/libnl* $IW_LIBS/ 2>/dev/null

# Set firmware path
echo -n "$FW" > /sys/module/firmware_class/parameters/path 2>/dev/null

# Down -> set monitor mode -> up
/system/bin/ip link set $IFACE down 2>/dev/null
sleep 1
echo 4 > /sys/module/$MOD/parameters/con_mode
sleep 4
/system/bin/ip link set $IFACE up 2>/dev/null
sleep 1

# Set channel
LD_LIBRARY_PATH=$IW_LIBS $IW dev $IFACE set channel 6 2>/dev/null

# Output status
echo "con_mode=$(cat /sys/module/$MOD/parameters/con_mode)"
echo "link=$(/system/bin/ip link show $IFACE | grep -o 'state [A-Z]*')"
echo "MONITOR_OK"
