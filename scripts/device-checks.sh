#!/usr/bin/env bash
set -euo pipefail
package=ma.marqicci.daybook
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
if [ "$(adb shell getprop ro.build.version.sdk | tr -d '\r')" -ge 33 ]; then
  adb shell pm grant "$package" android.permission.POST_NOTIFICATIONS
fi
run_case() {
  local output
  output=$(adb shell am instrument -w -e class "$package.DeviceChecks#$1" "$package.test/androidx.test.runner.AndroidJUnitRunner")
  echo "$output"
  grep -Fq 'OK (1 test)' <<< "$output"
}
adb shell svc wifi disable
adb shell svc data disable
trap 'adb shell svc wifi enable; adb shell svc data enable; adb shell cmd uimode night no; adb shell settings put system font_scale 1.0' EXIT
mkdir -p build/ui-review
run_case captureScreens
for screen in today schedule brief journal focus; do
  adb exec-out run-as "$package" cat "cache/ui-$screen.png" > "build/ui-review/$screen.png"
done
adb shell cmd uimode night yes
adb shell settings put system font_scale 1.5
adb shell am force-stop "$package"
run_case largerDarkToday
adb exec-out run-as "$package" cat cache/ui-dark-large.png > build/ui-review/dark-large.png
adb shell cmd uimode night no
adb shell settings put system font_scale 1.0
adb shell am force-stop "$package"
run_case scheduleAndHourlyDisclosure
run_case writeOffline
adb shell am force-stop "$package"
run_case readAfterForceStop
run_case editScheduleAndRetryBell
run_case import28Days
run_case createProjectSessionOffline
adb shell am force-stop "$package"
run_case resumeAndCompleteAfterRestart
run_case nextWeeklyRunAndLegacyDaily
run_case focusedJournalUsesDraft
run_case resumeEditionAfterRestart
adb install -r app/build/outputs/apk/preview/app-preview.apk
adb shell pm path "$package.preview"
adb shell pm path "$package"
adb shell am start -W -n "$package.preview/$package.MainActivity"

