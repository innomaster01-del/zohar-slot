#!/bin/bash
# CI smoke test: ARCore emulator (virtual scene) → install, launch in smoke mode, screenshots + logcat.
set -x
mkdir -p smoke
# The emulator draws 3D in software and can stop answering in a heavy scene. Then every adb call would only wait out its
# timeout until the step's time is up, and the tests after it would never be reported: so stop at once and say what the
# host shows (is the emulator process still there, how much memory is left).
alive() { timeout 12 adb shell true > /dev/null 2>&1; }
die() {
  { echo "=== EMULATOR STOPPED ANSWERING (smoke.sh line $1) ==="
    echo "adb: $(timeout 10 adb devices 2>&1 | tr '\n' ' ')"
    echo "emulator process: $(pgrep -fa 'qemu-system|emulator' | cut -c1-120 | head -3)"
    echo "host memory: $(free -m | sed -n 2p)"; echo "host load: $(uptime)"
    dmesg 2>/dev/null | grep -iE 'out of memory|oom-kill|killed process' | tail -3
  } >> smoke/summary.txt 2>&1
  pkill -f 'adb logcat' || true
  exit 0
}
URL=$(gh api repos/google-ar/arcore-android-sdk/releases/latest -q '.assets[] | select(.name | test("x86_for_emulator")) | .browser_download_url' | head -1)
echo "ARCore: $URL" > smoke/summary.txt
curl -fsSL -o /tmp/arcore.apk "$URL" && adb install -r /tmp/arcore.apk >> smoke/summary.txt 2>&1
adb install -r -g app/build/outputs/apk/slot/debug/app-slot-debug.apk >> smoke/summary.txt 2>&1
adb install -r -g app/build/outputs/apk/scratch/debug/app-scratch-debug.apk >> smoke/summary.txt 2>&1
adb install -r -g app/build/outputs/apk/blackjack/debug/app-blackjack-debug.apk >> smoke/summary.txt 2>&1
# the two modern machines' games on their real screen renderers (no 3D: light on the emulator)
for M in pharaoh dragon; do
  echo "--- screentest $M ---" >> smoke/summary.txt
  timeout 20 adb logcat -c
  timeout 30 adb shell am start -n com.zohar.slotar/.MainActivity --ez screentest true --es machine $M
  for n in $(seq 1 60); do sleep 4; alive || die $LINENO; timeout 60 adb logcat -d -s ZoharSmoke:I ZoharImg:I AndroidRuntime:E > smoke/${M}_st.txt 2>&1; grep -q "screentest $M done\|FATAL" smoke/${M}_st.txt && break; done
  sleep 3; timeout 60 adb logcat -d -s ZoharSmoke:I ZoharImg:I AndroidRuntime:E > smoke/${M}_st.txt 2>&1
  grep -v ZoharImg smoke/${M}_st.txt | cut -c1-260 | tail -30 >> smoke/summary.txt
  timeout 20 adb shell am force-stop com.zohar.slotar
done
# Higher or Lower self-test (no AR), before the heavy 3D cabinets that can take the emulator down: six rounds through the felt; the cards and the pot on the table are checked against the engine
adb install -r -g app/build/outputs/apk/hilo/debug/app-hilo-debug.apk >> smoke/summary.txt 2>&1
echo "--- hilo ---" >> smoke/summary.txt
timeout 20 adb logcat -c
timeout 30 adb shell am start -n com.zohar.hiloar/com.zohar.slotar.MainActivity --ez smoke true --ez hilotest true
for n in $(seq 1 36); do sleep 5; alive || die $LINENO
  timeout 30 adb logcat -d -s ZoharSmoke:V ZoharHL:V AndroidRuntime:E libc:F DEBUG:F > smoke/hilo_log.txt 2>&1
  grep -q "hilo done\|FATAL\|Abort" smoke/hilo_log.txt && break
  [ -z "$(timeout 10 adb shell pidof com.zohar.hiloar)" ] && { echo "process gone after $((n*5))s" >> smoke/summary.txt; break; }
done
timeout 25 adb exec-out screencap -p > smoke/hilo.png
grep -vE "^-|^\s+at |hilo alive|step texture|step chip" smoke/hilo_log.txt | cut -c1-300 | head -60 >> smoke/summary.txt
grep "hilo alive" smoke/hilo_log.txt | tail -1 >> smoke/summary.txt
timeout 20 adb shell am force-stop com.zohar.hiloar; sleep 2
# blackjack self-test (no AR): the dealer plays real rounds through the player's buttons; cards on the table are checked against the engine.
# Run it with growing scene parts so a renderer problem on the emulator shows which part causes it.
(adb logcat -v time > smoke/bj_stream.txt 2>&1 &) ; sleep 1
bjrun() {  # $1 = parts, $2 = seconds max
  echo "--- blackjack parts=$1 ---" >> smoke/summary.txt
  timeout 20 adb logcat -c
  timeout 30 adb shell am start -n com.zohar.blackjackar/com.zohar.slotar.MainActivity --ez smoke true --ez bjtest true --es bjparts "$1"
  for n in $(seq 1 $(( $2 / 5 ))); do sleep 5; alive || die $LINENO
    timeout 30 adb logcat -d -s ZoharSmoke:V ZoharBJ:V AndroidRuntime:E Filament:V libc:F DEBUG:F > smoke/bj_log_$1.txt 2>&1
    grep -q "bj done\|FATAL\|Abort" smoke/bj_log_$1.txt && break
    [ -z "$(timeout 10 adb shell pidof com.zohar.blackjackar)" ] && { echo "process gone after $((n*5))s" >> smoke/summary.txt; break; }
  done
  grep -vE "^-|^\s+at |ZoharImg|bj alive" smoke/bj_log_$1.txt | cut -c1-300 | head -40 >> smoke/summary.txt
  grep "bj alive" smoke/bj_log_$1.txt | tail -1 >> smoke/summary.txt
  timeout 20 adb shell am force-stop com.zohar.blackjackar; sleep 2
}
timeout 25 adb exec-out screencap -p > smoke/bj.png
timeout 60 adb logcat -d -s ZoharImg:I ZoharImg:W > smoke/bj_img.txt 2>&1
# scratch cards self-test (non-AR): buy, scratch every region through the real screen-ray path
adb logcat -c
adb shell am start -n com.zohar.scratchar/com.zohar.slotar.MainActivity --ez smoke true --ez scratchtest true
for n in $(seq 1 20); do sleep 4; alive || die $LINENO; adb logcat -d -s ZoharSmoke:I ZoharCard:I ZoharImg:I AndroidRuntime:E > smoke/scratch_log.txt 2>&1; grep -q "card_done.png end\|FATAL" smoke/scratch_log.txt && break; done
timeout 25 adb exec-out screencap -p > smoke/scratch.png
timeout 60 adb logcat -d -s ZoharImg:I > smoke/card_img.txt 2>&1
echo "--- scratch ---" >> smoke/summary.txt; grep -E "scratch|card|FATAL|Exception" smoke/scratch_log.txt | head -40 >> smoke/summary.txt
echo "step: stop scratch preview" >> smoke/summary.txt
timeout 20 adb shell am force-stop com.zohar.scratchar
# scratch cards in AR (emulator virtual scene): auto-place, then scratch through the real touch dispatch
echo "step: AR scratch" >> smoke/summary.txt
timeout 20 adb shell pm grant com.zohar.scratchar android.permission.CAMERA
timeout 20 adb logcat -c
timeout 30 adb shell am start -n com.zohar.scratchar/com.zohar.slotar.MainActivity --ez smoke true
echo "step: started" >> smoke/summary.txt
for n in $(seq 1 30); do sleep 4; alive || die $LINENO; timeout 30 adb logcat -d -s ZoharSmoke:I ZoharCard:I ZoharPerf:I AndroidRuntime:E > smoke/scratch_ar_log.txt 2>&1; grep -q "scratch AR done\|FATAL" smoke/scratch_ar_log.txt && break; done
echo "--- scratch AR ---" >> smoke/summary.txt; grep -E "scratch|card|FATAL|Exception|Perf" smoke/scratch_ar_log.txt | head -40 >> smoke/summary.txt
timeout 20 adb shell am force-stop com.zohar.scratchar
adb shell pm grant com.zohar.slotar android.permission.CAMERA
adb logcat -c
(adb logcat -v time > smoke/logcat.txt 2>&1 &) ; sleep 1
adb shell am start -n com.zohar.slotar/.MainActivity --ez smoke true
for i in 1 2 3; do sleep 6; alive || die $LINENO; timeout 25 adb exec-out screencap -p > smoke/ar_$i.png; done
timeout 20 adb shell am force-stop com.zohar.slotar
adb shell am start -n com.zohar.slotar/.MainActivity --ez smoke true --ez preview true
for i in 01 02 03 04; do sleep 3; alive || die $LINENO; timeout 25 adb exec-out screencap -p > smoke/preview_$i.png; done
# tap every machine button where the app says it is drawn, then check which part the app resolved
for n in 1 2 3 4 5 6 7 8 9 10; do grep -q "btn LeverKnob" smoke/logcat.txt && break; sleep 3; done
grep "ZoharSmoke.*btn " smoke/logcat.txt | sed 's/.*btn //' | while read name x y; do
  echo "TAP $name at $x,$y" >> smoke/summary.txt
  timeout 20 adb shell input tap $x $y; sleep 2
done
sleep 10
grep -E "ZoharTap|selftap|ZoharTouch|virtual hand|preview done|fx " smoke/logcat.txt >> smoke/summary.txt
for i in 05 06; do sleep 3; alive || die $LINENO; timeout 25 adb exec-out screencap -p > smoke/preview_$i.png; done
sleep 30; pkill -f 'adb logcat' || true
grep -E "virtual hand|preview done|ZoharSmoke" smoke/logcat.txt >> smoke/summary.txt
grep -E "FATAL|AndroidRuntime|Zohar|ARCore-|SceneView" smoke/logcat.txt | grep -vE "^.{0,40}V " | head -400 >> smoke/summary.txt
adb shell pm grant com.zohar.slotar android.permission.CAMERA
# the two modern cabinets in 3D (no AR), after every lighter test — build 67 lost the emulator here, 10 s into the dragon cabinet: spins through the real buttons, screen + 3D view captured to logcat.
# The full log streams to a file so a crash that takes the emulator down is still on record.
(adb logcat -v time > smoke/machines_stream.txt 2>&1 &) ; sleep 1
for M in dragon pharaoh; do
  echo "--- machine $M ---" >> smoke/summary.txt
  timeout 20 adb shell am force-stop com.zohar.slotar; timeout 20 adb logcat -c
  timeout 30 adb shell am start -n com.zohar.slotar/.MainActivity --ez smoke true --ez preview true --es machine $M
  for n in $(seq 1 25); do sleep 4; alive || die $LINENO; timeout 60 adb logcat -d -s ZoharSmoke:I ZoharMachine:I ZoharTap:I ZoharImg:I ZoharImg:W AndroidRuntime:E > smoke/${M}_log.txt 2>&1; grep -q "preview done\|FATAL" smoke/${M}_log.txt && break; done
  timeout 25 adb exec-out screencap -p > smoke/${M}.png
  grep -vE "ZoharImg|^\s+at " smoke/${M}_log.txt | cut -c1-300 | head -40 >> smoke/summary.txt
  grep "ZoharImg" smoke/${M}_log.txt > smoke/${M}_img.txt
done
timeout 20 adb shell am force-stop com.zohar.slotar
# blackjack last: the emulator's software GPU tends to die on it, which must not cost the other tests
bjrun table,dealer 240
exit 0
