#!/usr/bin/env bash
# TigrLauncher: сборка без Gradle и без единой зависимости.
# aapt2 compile/link -> javac -> d8 -> zipalign -> apksigner
#
# Пути берутся из окружения, значения по умолчанию — под Windows + Android Studio:
#   ANDROID_HOME (или ANDROID_SDK_ROOT) — корень SDK, по умолчанию /c/AndroidSDK
#   BUILD_TOOLS_VERSION                 — версия build-tools, по умолчанию 36.0.0
#   PLATFORM_VERSION                    — версия android.jar, по умолчанию android-37.0
#   JAVA_HOME                           — JDK (нужен javac/keytool/java), иначе JBR из Android Studio
set -euo pipefail

SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-/c/AndroidSDK}}"
BT="$SDK/build-tools/${BUILD_TOOLS_VERSION:-36.0.0}"
PLATFORM="$SDK/platforms/${PLATFORM_VERSION:-android-37.0}/android.jar"
JBR_BIN="${JAVA_HOME:+$JAVA_HOME/bin}"
if [ -z "$JBR_BIN" ]; then
    JBR_BIN="/c/Program Files/Android/Android Studio/jbr/bin"
fi

for tool in "$BT/aapt2.exe" "$BT/aapt.exe" "$BT/zipalign.exe" "$PLATFORM" "$JBR_BIN/javac.exe"; do
    if [ ! -e "$tool" ]; then
        echo "Не найдено: $tool" >&2
        echo "Укажи ANDROID_HOME / BUILD_TOOLS_VERSION / PLATFORM_VERSION / JAVA_HOME." >&2
        exit 1
    fi
done

cd "$(dirname "$0")"
rm -rf build
mkdir -p build/gen build/classes build/dex

echo "[1/6] aapt2 compile"
"$BT/aapt2.exe" compile --dir res -o build/res.zip

echo "[2/6] aapt2 link"
# versionCode/versionName берутся из AndroidManifest.xml
"$BT/aapt2.exe" link \
    -o build/app.unsigned.apk \
    -I "$PLATFORM" \
    --manifest AndroidManifest.xml \
    --java build/gen \
    --min-sdk-version 26 --target-sdk-version 28 \
    build/res.zip

echo "[3/6] javac"
find src build/gen -name '*.java' > build/sources.txt
"$JBR_BIN/javac.exe" -nowarn -encoding UTF-8 -source 8 -target 8 \
    -bootclasspath "$PLATFORM" -d build/classes @build/sources.txt

echo "[4/6] d8"
find build/classes -name '*.class' > build/classlist.txt
# shellcheck disable=SC2046
"$JBR_BIN/java.exe" -cp "$BT/lib/d8.jar" com.android.tools.r8.D8 \
    --release --lib "$PLATFORM" --output build/dex $(cat build/classlist.txt)

echo "[5/6] dex -> apk + zipalign"
# aapt add кладёт classes.dex в корень APK; отдельный zip/python не нужны.
(cd build/dex && "$BT/aapt.exe" add "../../build/app.unsigned.apk" classes.dex > /dev/null)
"$BT/zipalign.exe" -f 4 build/app.unsigned.apk build/app.aligned.apk

echo "[6/6] sign"
if [ ! -f tigrlauncher.keystore ]; then
    "$JBR_BIN/keytool.exe" -genkeypair -v -keystore tigrlauncher.keystore -alias tigrlauncher \
        -keyalg RSA -keysize 2048 -validity 10000 \
        -storepass tigrlauncher -keypass tigrlauncher \
        -dname "CN=TigrLauncher, O=Tigran, C=RU" > /dev/null
fi
"$JBR_BIN/java.exe" -cp "$BT/lib/apksigner.jar" com.android.apksigner.ApkSignerTool sign \
    --ks tigrlauncher.keystore --ks-key-alias tigrlauncher \
    --ks-pass pass:tigrlauncher --key-pass pass:tigrlauncher \
    --out TigrLauncher.apk build/app.aligned.apk
"$JBR_BIN/java.exe" -cp "$BT/lib/apksigner.jar" com.android.apksigner.ApkSignerTool verify TigrLauncher.apk
ls -la TigrLauncher.apk
