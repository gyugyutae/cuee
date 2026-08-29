#!/usr/bin/env python3
"""Run the real CUEE accessibility service against the deterministic Korail demo app."""

from __future__ import annotations

import argparse
import subprocess
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
APP_APK = ROOT / "app/build/outputs/apk/debug/app-debug.apk"
MOCK_APK = ROOT / "mock-korail/build/outputs/apk/debug/mock-korail-debug.apk"
SERVICE = "com.cuee/com.cuee.service.CueAccessibilityService"
LOG_TAG = "CueAccessibilityService"


class DemoFailure(RuntimeError):
    pass


def command(adb: str, *args: str, capture: bool = True, check: bool = True) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        [adb, *args],
        check=check,
        text=True,
        capture_output=capture,
    )


def shell(adb: str, *args: str, capture: bool = True, check: bool = True) -> subprocess.CompletedProcess[str]:
    return command(adb, "shell", *args, capture=capture, check=check)


def tap_at(adb: str, x: int, y: int) -> None:
    shell(adb, "input", "tap", str(x), str(y))


def logs(adb: str) -> str:
    return command(adb, "logcat", "-d", "-s", f"{LOG_TAG}:D", "*:S").stdout


def wait_log(adb: str, expected: str, timeout: float = 20.0) -> None:
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        current = logs(adb)
        if expected in current:
            return
        fatal = [
            line for line in current.splitlines()
            if "station input failed" in line or "state=FAILED" in line or "stop=NO_TARGET" in line
        ]
        if fatal:
            raise DemoFailure("CUEE failed before reaching the expected step:\n" + "\n".join(fatal[-8:]))
        time.sleep(0.25)
    raise DemoFailure(f"Timed out waiting for log: {expected}\n\nRecent logs:\n{logs(adb)[-5000:]}")


def screenshot(adb: str, destination: Path) -> None:
    destination.parent.mkdir(parents=True, exist_ok=True)
    with destination.open("wb") as output:
        subprocess.run([adb, "exec-out", "screencap", "-p"], check=True, stdout=output)


def ensure_clean_runtime(adb: str) -> None:
    for package in ("com.cuee", "com.korail.talk"):
        shell(adb, "am", "force-stop", package, check=False)
        shell(adb, "pm", "clear", package, check=False)
    command(adb, "install", "-r", str(APP_APK))
    command(adb, "install", "-r", str(MOCK_APK))
    shell(adb, "pm", "grant", "com.cuee", "android.permission.RECORD_AUDIO", check=False)
    shell(adb, "am", "start", "-W", "-n", "com.cuee/.ui.MainActivity")
    shell(adb, "settings", "delete", "secure", "enabled_accessibility_services", check=False)
    shell(adb, "settings", "put", "secure", "enabled_accessibility_services", SERVICE)
    shell(adb, "settings", "put", "secure", "accessibility_enabled", "1")
    time.sleep(1.0)
    command(adb, "logcat", "-c")
    shell(adb, "am", "start", "-W", "-n", "com.korail.talk/.MockKorailActivity")
    time.sleep(1.0)


def start_recording(adb: str) -> subprocess.Popen[bytes]:
    shell(adb, "unlink", "/sdcard/cuee-demo-raw.mp4", check=False)
    process = subprocess.Popen(
        [
            adb,
            "shell",
            "screenrecord",
            "--size", "720x1560",
            "--bit-rate", "6000000",
            "--time-limit", "60",
            "/sdcard/cuee-demo-raw.mp4",
        ],
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL,
    )
    time.sleep(0.5)
    return process


def stop_recording(adb: str, process: subprocess.Popen[bytes], destination: Path) -> None:
    # Keep the verified payment-stop screen visible and let the encoder flush recent frames.
    time.sleep(2.5)
    shell(adb, "pkill", "-INT", "screenrecord", check=False)
    try:
        process.wait(timeout=8)
    except subprocess.TimeoutExpired:
        process.terminate()
        process.wait(timeout=3)
    destination.parent.mkdir(parents=True, exist_ok=True)
    command(adb, "pull", "/sdcard/cuee-demo-raw.mp4", str(destination))
    shell(adb, "unlink", "/sdcard/cuee-demo-raw.mp4", check=False)


def run_scenario(adb: str, screenshot_dir: Path, capture_screenshots: bool = True) -> None:
    shell(
        adb,
        "am", "broadcast",
        "-a", "com.cuee.DEBUG_COMMAND",
        "--es", "utterance", "진주에서서울가는표예매해줘",
        "-p", "com.cuee",
    )

    # Coordinates target stable controls in the dedicated 1080 x 2340 emulator profile.
    # Assertions come from CUEE's own analyzed target logs, not a competing UIAutomation service.
    steps = [
        ("target=departure:com.korail.talk:id/v_departure_station", "v_departure_station", 280, 560),
        ("target=진주 result:com.korail.talk:id/station_result", "station_result", 540, 750),
        ("target=arrival:com.korail.talk:id/v_arrival_station", "v_arrival_station", 780, 560),
        ("target=서울 result:com.korail.talk:id/station_result", "station_result", 540, 750),
        ("target=date field:com.korail.talk:id/rl_going_date", "rl_going_date", 540, 950),
        ("target=date:com.korail.talk:id/date_cell_tomorrow", "date_cell_tomorrow", 540, 650),
        ("target=06:00 time:com.korail.talk:id/hourTxt06", "hourTxt06", 210, 1000),
        ("target=confirm:com.korail.talk:id/confirm_button", "confirm_button", 540, 1250),
        ("target=passenger:com.korail.talk:id/tv_value_passenger", "tv_value_passenger", 540, 1240),
        ("target=어른 plus:com.korail.talk:id/adult_plus", "adult_plus", 900, 560),
        ("target=어린이 plus:com.korail.talk:id/child_plus", "child_plus", 900, 800),
        ("target=confirm:com.korail.talk:id/passenger_confirm", "passenger_confirm", 540, 1010),
        ("target=train search:com.korail.talk:id/search_trains", "search_trains", 540, 1510),
        ("target=KTX standard:com.korail.talk:id/reserveButton206", "reserveButton206", 850, 1000),
        ("target=예매:com.korail.talk:id/booking_button", "booking_button", 540, 1150),
    ]

    for index, (expected_log, resource_id, x, y) in enumerate(steps, start=1):
        wait_log(adb, expected_log)
        if capture_screenshots and index in {1, 5, 9, 14}:
            screenshot(adb, screenshot_dir / f"{index:02d}-{resource_id}.png")
        tap_at(adb, x, y)

    wait_log(adb, "message=결제하기 버튼이에요. 결제는 직접 확인해 주세요.")
    if capture_screenshots:
        screenshot(adb, screenshot_dir / "16-payment-safety-stop.png")

    current_logs = logs(adb)
    forbidden = ["station input failed", "state=FAILED", "stop=NO_TARGET"]
    found = [value for value in forbidden if value in current_logs]
    if found:
        raise DemoFailure(f"Failure signals remained in log: {found}")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--adb", default="adb", help="Path to adb")
    parser.add_argument(
        "--screenshots",
        type=Path,
        default=ROOT / "artifacts/screenshots/cuee-emulator-e2e",
    )
    parser.add_argument("--record", type=Path, help="Optional raw screen-recording destination")
    args = parser.parse_args()

    missing = [path for path in (APP_APK, MOCK_APK) if not path.exists()]
    if missing:
        raise DemoFailure("Build APKs first: " + ", ".join(str(path) for path in missing))

    ensure_clean_runtime(args.adb)
    recorder = start_recording(args.adb) if args.record else None
    try:
        run_scenario(args.adb, args.screenshots, capture_screenshots=args.record is None)
    finally:
        if recorder is not None:
            stop_recording(args.adb, recorder, args.record)
    print("PASS: CUEE completed the deterministic Jinju-to-Seoul flow and stopped before payment.")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (subprocess.CalledProcessError, DemoFailure) as error:
        print(f"FAIL: {error}", file=sys.stderr)
        raise SystemExit(1)
