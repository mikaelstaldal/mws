#!/usr/bin/env bash
# Run on the operator host with an attached device, configured Android SDK and system Gradle.
set -euo pipefail

qualification_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$qualification_root"
if ! git diff --quiet || ! git diff --cached --quiet; then
    echo "Commit tracked source changes before collecting qualification evidence." >&2
    exit 1
fi
qualification_revision="$(git rev-parse HEAD)"
qualification_output="${1:-$qualification_root/provider-evidence-$(date -u +%Y%m%dT%H%M%SZ)}"
mkdir "$qualification_output"
# Make the path absolute before passing it to tools or reporting it.
qualification_output="$(cd -- "$qualification_output" && pwd)"
trap 'adb shell am force-stop nu.staldal.mws >/dev/null 2>&1 || true' EXIT

{
    echo "Source revision: $qualification_revision"
    echo "UTC date: $(date -u +%Y-%m-%dT%H:%M:%SZ)"
    echo "Build: DEBUG_PROVIDER_OVERRIDE=true gradle :app:assembleDebug :app:assembleDebugAndroidTest"
    adb shell getprop ro.product.model
    adb shell getprop ro.build.version.release
    adb shell getprop ro.build.version.sdk
    adb shell getprop ro.product.cpu.abilist
    adb shell dumpsys webviewupdate
} > "$qualification_output/metadata.txt"
DEBUG_PROVIDER_OVERRIDE=true gradle :app:assembleDebug :app:assembleDebugAndroidTest --console=plain \
    > "$qualification_output/build.txt" 2>&1
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk

run_qualification_phase() {
    local selection="$1" report="$2" expected="$3" output
    adb shell am force-stop nu.staldal.mws
    adb shell am instrument -w -r -e class "$selection" -e sourceRevision "$qualification_revision" \
        nu.staldal.mws.test/androidx.test.runner.AndroidJUnitRunner \
        > "$qualification_output/$report" 2>&1
    output="$(<"$qualification_output/$report")"
    if [[ "$output" == *"INSTRUMENTATION_STATUS_CODE: -3"* ||
          "$output" == *"INSTRUMENTATION_STATUS_CODE: -4"* ||
          "$output" == *"FAILURES!!!"* ]]; then
        echo "Failed or skipped required test: $qualification_output/$report" >&2
        exit 1
    fi
    if [[ "$output" != *"OK ($expected tests)"* &&
          ! ( "$expected" == 1 && "$output" == *"OK (1 test)"* ) ]]; then
        echo "Expected $expected passing tests; inspect $qualification_output/$report" >&2
        exit 1
    fi
    echo "Passed: $report ($expected tests)"
}

qualification_restart=nu.staldal.mws.RestartQualificationInstrumentedTest
run_qualification_phase "$qualification_restart#preparePersistedWorkerAndInterruptedMaintenance" provider-restart-prepare.txt 1
run_qualification_phase "$qualification_restart#interruptAfterProfileDeletionBeforeFinalCommit" provider-restart-delete.txt 1
run_qualification_phase "$qualification_restart#verifyPersistedWorkerAndResumeMaintenance" provider-restart-verify.txt 1
run_qualification_phase "$qualification_restart#cleanupRestartFixture" provider-restart-cleanup.txt 1
run_qualification_phase nu.staldal.mws.ProviderCompatibilitySuite provider-qualification.txt 37

echo "Device checks passed. Evidence retained in $qualification_output"
echo "A matrix PASS entry still requires review and immutable publication of this evidence."
