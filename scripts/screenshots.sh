#!/usr/bin/env bash
# Runs inside the emulator job: installs the APK and captures the three main screens.
set -x
APP=com.gshotwell.hr4x4
ACT=$APP/.MainActivity
OUT=docs/screenshots
mkdir -p $OUT
shot() { sleep "$2"; adb exec-out screencap -p > "$OUT/$1.png"; }

adb install -g hr4x4-coach.apk
for s in window_animation_scale transition_animation_scale animator_duration_scale; do adb shell settings put global $s 0; done
# Clean status bar
adb shell settings put global sysui_demo_allowed 1
adb shell am broadcast -a com.android.systemui.demo -e command enter || true
adb shell am broadcast -a com.android.systemui.demo -e command clock -e hhmm 0712 || true
adb shell am broadcast -a com.android.systemui.demo -e command battery -e level 86 -e plugged false || true
adb shell am broadcast -a com.android.systemui.demo -e command notifications -e visible false || true
adb shell am broadcast -a com.android.systemui.demo -e command network -e wifi show -e level 4 || true

adb shell am start -n $ACT --es demo setup
shot setup 10
adb shell input swipe 540 1900 540 700 400
shot setup-2 3

adb shell am start -n $ACT --es demo summary
shot summary 6
adb shell input swipe 540 1900 540 600 400
shot summary-2 3

adb shell am start -n $ACT --es demo workout
shot workout 14
