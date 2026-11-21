#!/usr/bin/env bash
# Runs scoped unit tests based on functional area:
#   scripts/run_tests.sh intervalometer  - Intervalometer core, service, overlay, session planner, calibration, settings mapping (incl. ARCore consent), network isolation, workflow hardening
#   scripts/run_tests.sh solar           - Sun & Moon trajectory, ephemeris, AR projection, terrain horizon, pose (ar.pose: ARCore consent, hand-over, camera ownership), domain.arframe
#   scripts/run_tests.sh nightsky        - Night sky AR, Meeus celestial math, FlatBuffers catalog, star renderer
#   scripts/run_tests.sh all             - All test suites and lint check

set -eu
. "$(dirname "${BASH_SOURCE[0]}")/env.sh"

TARGET="${1:-all}"
RESULTS_DIR="${PROJECT_DIR}/app/build/test-results/testDebugUnitTest"

die() {
    printf 'run_tests: FATAL: %s\n' "$1" >&2
    exit 1
}

info() {
    printf 'run_tests: %s\n' "$1"
}

check_xml_results() {
    python3 "${PROJECT_DIR}/scripts/check_test_results.py" "$RESULTS_DIR"
}

case "$TARGET" in
    caelis|intervalometer|core)
        info "Running Caelis core test suite (Service, Overlay, Session, Calibration, Plurals, Verify)..."
        (cd "$PROJECT_DIR" && JAVA_HOME="$JAVA_HOME" ./gradlew :app:testDebugUnitTest \
            --tests "com.dazix.caelis.service.*" \
            --tests "com.dazix.caelis.notify.*" \
            --tests "com.dazix.caelis.overlay.*" \
            --tests "com.dazix.caelis.verify.*" \
            --tests "com.dazix.caelis.domain.IntervalScheduleTest" \
            --tests "com.dazix.caelis.domain.SessionPlannerTest" \
            --tests "com.dazix.caelis.domain.stability.*" \
            --tests "com.dazix.caelis.domain.cameramode.*" \
            --tests "com.dazix.caelis.data.SettingsMappingTest" \
            --tests "com.dazix.caelis.res.*" \
            --tests "com.dazix.caelis.NetworkIsolationTest" \
            --tests "com.dazix.caelis.WorkflowHardeningTest" \
            --tests "com.dazix.caelis.ExportedComponentsTest" \
            --tests "com.dazix.caelis.LogPrivacyTest" \
            --tests "com.dazix.caelis.ReleaseBuildTest" \
            --tests "com.dazix.caelis.ui.AccessibilityDisclosureTest" \
            --tests "com.dazix.caelis.ui.CalibrationResolutionTest" \
            --tests "com.dazix.caelis.ui.CustomModeDraftTest" \
            --tests "com.dazix.caelis.ui.LaunchModeOptionsTest" \
            --tests "com.dazix.caelis.ui.MainViewModelNavigationTest" \
            --tests "com.dazix.caelis.ui.NavigationDestinationTest" \
            --tests "com.dazix.caelis.ui.PermissionsPlatformLevelTest" \
            --tests "com.dazix.caelis.ui.SetupUiStateTest" \
            --tests "com.dazix.caelis.ui.HomeScrollPolicyTest" \
            --tests "com.dazix.caelis.ui.WaitProgressTest" \
            --rerun-tasks)
        check_xml_results
        info "Caelis core test suite PASSED."
        ;;

    solar|sun)
        info "Running Sun Trajectory test suite (Solar & Lunar Ephemeris, Trajectory Planning, AR Projection, Camera FOV, Terrain Horizon, Pose & ARCore consent)..."
        (cd "$PROJECT_DIR" && JAVA_HOME="$JAVA_HOME" ./gradlew :app:testDebugUnitTest \
            --tests "com.dazix.caelis.domain.solar.*" \
            --tests "com.dazix.caelis.domain.horizon.*" \
            --tests "com.dazix.caelis.data.horizon.*" \
            --tests "com.dazix.caelis.ar.*" \
            --tests "com.dazix.caelis.domain.arframe.*" \
            --tests "com.dazix.caelis.domain.location.*" \
            --tests "com.dazix.caelis.data.location.*" \
            --rerun-tasks)
        # "ar.*" is a prefix wildcard: it also picks up ar.pose.* (ARCore session controller with a fake
        # platform, pose hand-over, camera ownership) and ar.scene.*. Only docs.* is in no suite.
        check_xml_results
        info "Sun Trajectory test suite PASSED."
        ;;

    nightsky|stars)
        info "Running Night Sky test suite (Celestial Math, FlatBuffers Catalog, Frustum Culling, Optics, Overlay & Hit Test, Sky Materials)..."
        (cd "$PROJECT_DIR" && JAVA_HOME="$JAVA_HOME" ./gradlew :app:testDebugUnitTest \
            --tests "com.dazix.caelis.domain.nightsky.*" \
            --tests "com.dazix.caelis.ui.nightsky.*" \
            --tests "com.dazix.caelis.domain.arframe.*" \
            --tests "com.dazix.caelis.ar.scene.*" \
            --tests "com.dazix.caelis.domain.location.*" \
            --tests "com.dazix.caelis.data.location.*" \
            --rerun-tasks)
        check_xml_results
        info "Night Sky test suite PASSED."
        ;;

    all)
        info "Running ALL test suites, assemble, and Android Lint..."
        (cd "$PROJECT_DIR" && JAVA_HOME="$JAVA_HOME" ./gradlew \
            :app:assembleDebug :app:testDebugUnitTest :app:assembleDebugAndroidTest :app:lintDebug \
            --rerun-tasks)
        check_xml_results
        info "Full validation PASSED."
        ;;

    *)
        die "Unknown test target: '$TARGET'. Supported targets: caelis (or intervalometer), solar, nightsky, all"
        ;;
esac
