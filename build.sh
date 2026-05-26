#!/bin/bash
# Build Pwnagotchi APK from source
# Requires: Android SDK (build-tools 34), Eclipse ECJ

ANDROID_HOME="${ANDROID_HOME:-$HOME/android-sdk}"
ANDROID_JAR="$ANDROID_HOME/platforms/android-34/android.jar"
BUILD_TOOLS="$ANDROID_HOME/build-tools/34.0.0"
ECJ="${ECJ:-/tmp/ecj2.jar}"

mkdir -p obj/classes obj/gen

# Compile resources
$BUILD_TOOLS/aapt2 compile -o obj/res.flata --dir app/src/main/res/
$BUILD_TOOLS/aapt2 link -o obj/base.apk -I $ANDROID_JAR \
    --manifest app/src/main/AndroidManifest.xml \
    -R obj/res.flata --java obj/gen --auto-add-overlay

# Compile Java
java -jar $ECJ -source 8 -target 8 -cp $ANDROID_JAR:obj/gen \
    -d obj/classes -warn:none \
    app/src/main/java/com/pwnagotchi/app/*.java obj/gen/com/pwnagotchi/app/R.java

# Dex
$BUILD_TOOLS/d8 --lib $ANDROID_JAR --min-api 31 \
    --output obj/ $(find obj/classes -name '*.class')

# Package
cd obj && cp base.apk unsigned.apk && zip -j unsigned.apk classes.dex
$BUILD_TOOLS/zipalign -f -p 4 unsigned.apk aligned.apk

# Sign (generate keystore first time)
if [ ! -f ../pwnagotchi.keystore ]; then
    keytool -genkey -v -keystore ../pwnagotchi.keystore \
        -alias pwnagotchi -keyalg RSA -keysize 2048 -validity 10000 \
        -storepass pwnagotchi -keypass pwnagotchi \
        -dname "CN=Pwnagotchi"
fi
$BUILD_TOOLS/apksigner sign --ks ../pwnagotchi.keystore \
    --ks-pass pass:pwnagotchi --out ../Pwnagotchi.apk aligned.apk
echo "Built: Pwnagotchi.apk"
