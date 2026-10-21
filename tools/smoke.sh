#!/bin/bash
# CI smoke test: ARCore emulator (virtual scene) → install, launch in smoke mode, screenshots + logcat.
set -x
mkdir -p smoke
URL=$(gh api repos/google-ar/arcore-android-sdk/releases/latest -q '.assets[] | select(.name | test("x86_for_emulator")) | .browser_download_url' | head -1)
echo "ARCore: $URL" > smoke/summary.txt
curl -fsSL -o /tmp/arcore.apk "$URL" && adb install -r /tmp/arcore.apk >> smoke/summary.txt 2>&1
adb install -r -g app/build/outputs/apk/debug/app-debug.apk >> smoke/summary.txt 2>&1
adb shell pm grant com.zohar.slotar android.permission.CAMERA
adb logcat -c
(adb logcat -v time > smoke/logcat.txt 2>&1 &) ; sleep 1
adb shell am start -n com.zohar.slotar/.MainActivity --ez smoke true
for i in 1 2 3; do sleep 6; timeout 25 adb exec-out screencap -p > smoke/ar_$i.png; done
timeout 20 adb shell am force-stop com.zohar.slotar
adb shell am start -n com.zohar.slotar/.MainActivity --ez smoke true --ez preview true
for i in 01 02 03 04; do sleep 3; timeout 25 adb exec-out screencap -p > smoke/preview_$i.png; done
# tap every machine button where the app says it is drawn, then check which part the app resolved
for n in 1 2 3 4 5 6 7 8 9 10; do grep -q "btn LeverKnob" smoke/logcat.txt && break; sleep 3; done
grep "ZoharSmoke.*btn " smoke/logcat.txt | sed 's/.*btn //' | while read name x y; do
  echo "TAP $name at $x,$y" >> smoke/summary.txt
  timeout 20 adb shell input tap $x $y; sleep 2
done
sleep 10
grep -E "ZoharTap|selftap|ZoharTouch" smoke/logcat.txt >> smoke/summary.txt
for i in 05 06; do sleep 3; timeout 25 adb exec-out screencap -p > smoke/preview_$i.png; done
sleep 2; pkill -f 'adb logcat' || true
grep -E "FATAL|AndroidRuntime|Zohar|ARCore-|SceneView" smoke/logcat.txt | grep -vE "^.{0,40}V " | head -400 >> smoke/summary.txt
exit 0
