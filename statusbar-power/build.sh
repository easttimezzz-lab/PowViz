#!/bin/sh
set -e
cd "$(dirname "$0")"

SDK="${ANDROID_HOME:-$HOME/android/sdk}"
BT="$SDK/build-tools/35.0.0"
ANDROID_JAR="$SDK/platforms/android-34/android.jar"

rm -rf build
mkdir -p build/classes build/gen build/dex build/stubs

echo "[1/5] aapt2 compile"
"$BT/aapt2" compile --dir res -o build/res.zip

echo "[2/5] aapt2 link"
"$BT/aapt2" link \
    -o build/app-unsigned.apk \
    -I "$ANDROID_JAR" \
    --manifest AndroidManifest.xml \
    -R build/res.zip \
    -A assets \
    --java build/gen \
    --auto-add-overlay \
    --min-sdk-version 26 \
    --target-sdk-version 34

echo "[3/5] javac"
find xposed-api -name '*.java' > build/stub-sources.txt
javac -source 8 -target 8 -classpath "$ANDROID_JAR" -d build/stubs @build/stub-sources.txt
find src build/gen -name '*.java' > build/sources.txt
javac -source 8 -target 8 -classpath "$ANDROID_JAR:build/stubs" -d build/classes @build/sources.txt

echo "[4/5] d8"
"$BT/d8" --lib "$ANDROID_JAR" --min-api 26 --output build/dex \
    $(find build/classes -name '*.class')

(cd build/dex && zip -q -X ../app-unsigned.apk classes.dex)

echo "[5/5] zipalign + sign"
"$BT/zipalign" -f 4 build/app-unsigned.apk build/app-aligned.apk

if [ ! -f debug.keystore ]; then
    keytool -genkeypair -keystore debug.keystore -alias androiddebugkey \
        -storepass android -keypass android \
        -dname "CN=Android Debug,O=Android,C=US" \
        -keyalg RSA -keysize 2048 -validity 10000 >/dev/null 2>&1
fi

"$BT/apksigner" sign --ks debug.keystore --ks-pass pass:android --key-pass pass:android \
    --out build/statusbar-power.apk build/app-aligned.apk

echo "OK: $(pwd)/build/statusbar-power.apk"
ls -l build/statusbar-power.apk
