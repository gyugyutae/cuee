#!/usr/bin/env python3
"""Run the CUEE journey through mock-korail on a dedicated Android emulator."""

from __future__ import annotations

import argparse
import re
import subprocess
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
APP_APK = ROOT / "app/build/outputs/apk/debug/app-debug.apk"
MOCK_APK = ROOT / "mock-korail/build/outputs/apk/debug/mock-korail-debug.apk"
SERVICE = "com.cuee/com.cuee.service.CueAccessibilityService"
MOCK_VERSION = "versionName=0.0.1-demo"


class DemoFailure(RuntimeError):
    pass


def adb(adb_path: str, serial: str, *args: str, check: bool = True) -> str:
    result = subprocess.run(
        [adb_path, "-s", serial, *args], text=True, capture_output=True, check=False
    )
    if check and result.returncode:
        raise DemoFailure(f"ADB command failed: {args[0]}")
    return result.stdout


def shell(adb_path: str, serial: str, *args: str, check: bool = True) -> str:
    return adb(adb_path, serial, "shell", *args, check=check)


def require_demo_emulator(adb_path: str, serial: str) -> None:
    if not serial.startswith("emulator-"):
        raise DemoFailure("Refusing to run: --serial must identify an Android emulator.")
    if shell(adb_path, serial, "getprop", "ro.kernel.qemu").strip() != "1":
        raise DemoFailure("Refusing to run: target does not report the Android emulator property.")
    model = shell(adb_path, serial, "getprop", "ro.product.model").strip()
    if model.startswith("SM-"):
        raise DemoFailure("Refusing to run on a Samsung device.")
    size = shell(adb_path, serial, "wm", "size")
    if not re.search(r"1080x2340", size):
        raise DemoFailure("Use the dedicated 1080x2340 CUEE AVD; this script uses its fixed test coordinates.")


def is_installed(adb_path: str, serial: str, package: str) -> bool:
    return shell(adb_path, serial, "pm", "path", package, check=False).strip().startswith("package:")


def ensure_mock_package(adb_path: str, serial: str) -> None:
    if is_installed(adb_path, serial, "com.korail.talk"):
        details = shell(adb_path, serial, "dumpsys", "package", "com.korail.talk")
        if MOCK_VERSION not in details or "MockKorailActivity" not in details:
            raise DemoFailure("Refusing to replace com.korail.talk: installed app is not the CUEE mock.")
    adb(adb_path, serial, "install", "-r", str(MOCK_APK))
    details = shell(adb_path, serial, "dumpsys", "package", "com.korail.talk")
    if MOCK_VERSION not in details or "MockKorailActivity" not in details:
        raise DemoFailure("Installed package did not identify itself as mock-korail.")


class LogMonitor:
    def __init__(self, adb_path: str, serial: str) -> None:
        self.adb_path = adb_path
        self.serial = serial
        self.cursor = 0
        self.last_result_gate: str | None = None
        self.last_target: str | None = None

    def _safe_log(self) -> str:
        raw = adb(self.adb_path, self.serial, "logcat", "-d", "-s", "CueAccessibilityService:D", "*:S")
        # Only resource IDs/stage markers are used; never print or save screen or voice text.
        return "\n".join(
            line for line in raw.splitlines()
            if "journey target=" in line or "journey confirmed by user" in line or
            "confirmed journey started" in line or "journey result gate=" in line
        )

    def wait_for(self, marker: str, timeout: float = 20.0) -> tuple[int, int, int, int] | None:
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            current = self._safe_log()
            gates = re.findall(r"journey result gate=([a-z_]+)", current)
            if gates:
                self.last_result_gate = gates[-1]
            targets = re.findall(r"journey target=(com\.korail\.talk:id/[A-Za-z0-9_]+)", current)
            if targets:
                self.last_target = targets[-1]
            if marker.startswith("journey target="):
                target_id = re.escape(marker.removeprefix("journey target="))
                pattern = rf"journey target={target_id} bounds=(-?\d+),(-?\d+),(-?\d+),(-?\d+)"
                bounds = [
                    match for match in re.finditer(pattern, current)
                    if match.start() >= self.cursor and
                    0 <= int(match.group(1)) < int(match.group(3)) <= 1080 and
                    0 <= int(match.group(2)) < int(match.group(4)) <= 2340
                ]
                if bounds:
                    self.cursor = bounds[-1].end()
                    return tuple(map(int, bounds[-1].groups()))
            if len(current) < self.cursor:
                self.cursor = 0
            index = current.find(marker, self.cursor)
            if not marker.startswith("journey target=") and index >= 0:
                self.cursor = index + len(marker)
                return None
            time.sleep(0.25)
        detail = f"; last safe result gate={self.last_result_gate}" if self.last_result_gate else ""
        if self.last_target:
            detail += f"; last safe target={self.last_target}"
        raise DemoFailure(f"Timed out waiting for CUEE target: {marker}{detail}")


def top_activity(adb_path: str, serial: str) -> str:
    state = shell(adb_path, serial, "dumpsys", "activity", "activities")
    return next((line for line in state.splitlines() if "topResumedActivity" in line), "")


def wait_for_activity(adb_path: str, serial: str, activity: str, timeout: float = 20.0) -> None:
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if activity in top_activity(adb_path, serial):
            return
        time.sleep(0.25)
    raise DemoFailure(f"Expected test activity did not open: {activity}")


def wait_for_service(adb_path: str, serial: str, timeout: float = 20.0) -> None:
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        state = shell(adb_path, serial, "dumpsys", "accessibility")
        if SERVICE in state and "Bound services:{Service[label=큐" in state:
            return
        time.sleep(0.25)
    raise DemoFailure("CUEE Accessibility did not bind on this AVD.")


def tap(adb_path: str, serial: str, x: int, y: int) -> None:
    shell(adb_path, serial, "input", "tap", str(x), str(y))


def tap_guided_target(adb_path: str, serial: str, monitor: LogMonitor, resource_id: str) -> None:
    bounds = monitor.wait_for(f"journey target={resource_id}")
    if bounds is None:
        raise DemoFailure(f"No safe bounds were logged for target {resource_id}.")
    left, top, right, bottom = bounds
    time.sleep(1.25)
    x, y = (left + right) // 2, (top + bottom) // 2
    tap(adb_path, serial, x, y)


def run_scenario(
    adb_path: str,
    serial: str,
    utterance: str,
    hour_target: str,
    passenger_adjustments: list[str],
) -> None:
    shell(adb_path, serial, "logcat", "-c")
    shell(adb_path, serial, "am", "start", "-W", "-n", "com.korail.talk/.MockKorailActivity")
    time.sleep(1.0)

    shell(
        adb_path, serial, "am", "broadcast", "-a", "com.cuee.DEBUG_COMMAND",
        "-p", "com.cuee", "--es", "utterance", utterance,
    )
    wait_for_activity(adb_path, serial, "com.cuee/.ui.JourneyActivity")
    monitor = LogMonitor(adb_path, serial)
    time.sleep(2.0)
    tap(adb_path, serial, 540, 1115)  # User-confirmed request; stops before any train choice.
    try:
        wait_for_activity(adb_path, serial, "com.korail.talk/.MockKorailActivity", timeout=6.0)
    except DemoFailure:
        if "com.cuee/.ui.JourneyActivity" not in top_activity(adb_path, serial):
            raise
        time.sleep(2.0)
        tap(adb_path, serial, 540, 1115)
        wait_for_activity(adb_path, serial, "com.korail.talk/.MockKorailActivity")
    monitor.wait_for("journey confirmed by user")
    steps = [
        "com.korail.talk:id/v_departure_station",
        "com.korail.talk:id/station_result",
        "com.korail.talk:id/v_arrival_station",
        "com.korail.talk:id/station_result",
        "com.korail.talk:id/rl_going_date",
        "com.korail.talk:id/date_cell_tomorrow",
        f"com.korail.talk:id/{hour_target}",
        "com.korail.talk:id/confirm_button",
        "com.korail.talk:id/tv_value_passenger",
    ]
    steps.extend(passenger_adjustments)
    steps.extend([
        "com.korail.talk:id/passenger_confirm",
        "com.korail.talk:id/search_trains",
    ])
    for resource_id in steps:
        tap_guided_target(adb_path, serial, monitor, resource_id)

    monitor.wait_for("journey result gate=verified")
    wait_for_activity(adb_path, serial, "com.korail.talk/.MockKorailActivity")


def main() -> int:
    parser = argparse.ArgumentParser(description="Emulator-only CUEE/mock-korail journey check")
    parser.add_argument("--serial", required=True, help="Dedicated emulator serial, e.g. emulator-5554")
    parser.add_argument("--adb", default=str(Path.home() / "Library/Android/sdk/platform-tools/adb"))
    args = parser.parse_args()

    if not Path(args.adb).exists():
        raise DemoFailure(f"adb not found: {args.adb}")
    missing = [path for path in (APP_APK, MOCK_APK) if not path.exists()]
    if missing:
        raise DemoFailure("Build APKs first: " + ", ".join(str(path) for path in missing))

    # All read-only target checks happen before installing or launching anything.
    require_demo_emulator(args.adb, args.serial)
    ensure_mock_package(args.adb, args.serial)
    adb(args.adb, args.serial, "install", "-r", str(APP_APK))

    enabled = shell(args.adb, args.serial, "settings", "get", "secure", "enabled_accessibility_services")
    if SERVICE not in enabled:
        raise DemoFailure("Enable CUEE Accessibility manually on this AVD, accept the disclosure, then rerun.")
    wait_for_service(args.adb, args.serial)

    scenarios = [
        (
            "부산에서대전가는열차내일오전9시어른2명어린이1명",
            "hourTxt09",
            ["com.korail.talk:id/adult_plus", "com.korail.talk:id/child_plus"],
        ),
        ("광주송정에서목포가는열차내일오후12시어른1명", "hourTxt12", []),
    ]
    for utterance, hour_target, passenger_adjustments in scenarios:
        run_scenario(args.adb, args.serial, utterance, hour_target, passenger_adjustments)
    print("PASS: 서로 다른 2개 역·시간·승객 조건이 mock 조회 결과와 일치했습니다. 열차 선택·예매·결제는 누르지 않았습니다.")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (subprocess.CalledProcessError, DemoFailure) as error:
        print(f"FAIL: {error}", file=sys.stderr)
        raise SystemExit(1)
