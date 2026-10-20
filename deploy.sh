#!/usr/bin/env bash
# Сборка + установка на ТВ + назначение домашним экраном.
set -euo pipefail
cd "$(dirname "$0")"

TV="${TV:-192.168.40.138:5555}"
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-/c/AndroidSDK}}"
ADB="${ADB:-$SDK/platform-tools/adb.exe}"
if [ ! -e "$ADB" ]; then
    # Linux/macOS или adb в PATH
    ADB="$(command -v adb || echo "$ADB")"
fi

bash build.sh
"$ADB" connect "$TV" > /dev/null 2>&1 || true
"$ADB" -s "$TV" install -r TigrLauncher.apk
# На Android 9 cmd package set-home-activity надо повторять после каждой перезагрузки ТВ,
# иначе система снова показывает выбор домашнего приложения.
"$ADB" -s "$TV" shell cmd package set-home-activity \
    com.tigran.tigrlauncher/com.tigran.tigrlauncher.MainActivity
"$ADB" -s "$TV" shell am force-stop com.tigran.tigrlauncher
echo "OK: TigrLauncher установлен и назначен домашним экраном. Нажми Домой на пульте."
