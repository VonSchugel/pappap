#!/usr/bin/env bash
set -euo pipefail
package=ma.marqicci.daybook
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
api_level=$(adb shell getprop ro.build.version.sdk | tr -d '\r')
if [ "$api_level" -ge 33 ]; then
  adb shell pm grant "$package" android.permission.POST_NOTIFICATIONS
fi
failed=0
run_case() {
  local output
  echo "Running $1"
  output=$(adb shell am instrument -w -e class "$package.DeviceChecks#$1" "${@:2}" "$package.test/androidx.test.runner.AndroidJUnitRunner")
  echo "$output"
  if ! grep -Fq 'OK (1 test)' <<< "$output"; then
    failed=1
  fi
}
# Use the system airplane-mode broadcast on the isolated Oreo emulator. Its
# legacy svc wifi process can exit before notifying ConnectivityService.
if [ "$api_level" -eq 26 ]; then
  adb root
  adb wait-for-device
  adb shell settings put global airplane_mode_radios cell,bluetooth,wifi,nfc,wimax
  adb shell settings put global airplane_mode_on 1
  adb shell am broadcast -a android.intent.action.AIRPLANE_MODE --ez state true
  adb emu gsm data unregistered
  for interface in wlan0 eth0 rmnet0; do
    if adb shell ip link show "$interface" >/dev/null 2>&1; then
      adb shell ip link set "$interface" down
    fi
  done
fi
for radio in wifi data; do
  echo "Disabling $radio on the test emulator"
  for attempt in 1 2 3; do
    if adb shell svc "$radio" disable; then break; fi
    sleep 2
  done
done
run_case verifyOfflineTransports
if ((failed)); then
  adb shell dumpsys connectivity | head -100 || true
  adb shell ip link show || true
fi
mkdir -p build/ui-review
run_case workflowStudioRoundTrip
run_case workflowChainingAndValidation
if [ "$api_level" -eq 35 ]; then
  # The host stages the same pinned weights as the app's setup screen.
  # Inference and the reviewed two-step run both execute on the offline Android guest.
  weights=build/smollm2-360m.gguf
  curl -fsSL --retry 3 --max-time 300 -o "$weights" 'https://huggingface.co/bartowski/SmolLM2-360M-Instruct-GGUF/resolve/ddde4293f6050e8b81cca2ea2438c2fe444a0d5b/SmolLM2-360M-Instruct-Q4_K_M.gguf'
  echo "2fa3f013dcdd7b99f9b237717fa0b12d75bbb89984cc1274be1471a465bac9c2  $weights" | sha256sum -c -
  adb push "$weights" /data/local/tmp/pappap-smollm2.gguf
  adb shell run-as "$package" mkdir -p no_backup
  adb shell "run-as $package sh -c 'cat /data/local/tmp/pappap-smollm2.gguf > no_backup/smollm2-360m-q4km.gguf'"
  adb shell am force-stop "$package"
  run_case realPhoneModelWorksOffline
fi
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
adb shell am force-stop "$package"
run_case reopenBriefAfterProcessStop
run_case datedBookmarksAndBeachObservations
run_case archiveFindsEssayOnlyDate
run_case passageExportRetainsReadingAndQuotedLines
run_case malformedEventDeadlineCannotCreateAnAlarm
run_case rapidOfflineEditsStayOnTheirDate
run_case deadlineConversionHandlesMidnightAndPartialDates
run_case badLegacyReminderLeavesAgendaAndOtherAlarmsAvailable
run_case pausingFromScheduleUpdatesToday
adb install -r app/build/outputs/apk/preview/app-preview.apk
adb shell pm path "$package.preview"
adb shell pm path "$package"
adb shell am start -W -n "$package.preview/$package.MainActivity"
# ADB forwards loopback even while the emulator's Wi-Fi and mobile data are off.
export APP_TOKEN
APP_TOKEN=$(python -c 'import secrets; print(secrets.token_hex(18))')
export DB_PATH="$PWD/build/ui-review/connected-test.sqlite"
PYTHONPATH=server python -m uvicorn main:app --host 127.0.0.1 --port 18731 >build/connected-api.log 2>&1 &
api_pid=$!
trap 'kill "$api_pid" 2>/dev/null || true; adb reverse --remove tcp:18731 || true' EXIT
curl --retry 20 --retry-delay 1 --retry-connrefused --fail --silent -H "Authorization: Bearer $APP_TOKEN" http://127.0.0.1:18731/v1/preferences >/dev/null
adb reverse tcp:18731 tcp:18731
run_case realServerRecoversPendingChanges -e apiToken "$APP_TOKEN"
exit "$failed"

