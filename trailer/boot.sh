#!/bin/bash
# (Re)boots the foldable emulator unfolded and ready for recording.
source ~/tools/env.sh
adb devices | grep -q emulator && exit 0
(nohup emulator -avd dezfold36 -no-window -gpu host -no-audio -no-snapshot-load >/tmp/emu-trailer.log 2>&1 &)
sleep 5; adb wait-for-device
until [ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; do sleep 3; done
adb shell wm dismiss-keyguard; adb emu unfold >/dev/null; sleep 3
adb shell pm list packages | grep -q com.dez.sports || adb install ~/projects/dez-sports/app/build/outputs/apk/release/app-release.apk >/dev/null
echo booted
