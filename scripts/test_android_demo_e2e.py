import unittest
from unittest.mock import patch

from run_android_demo_e2e import DemoFailure, LogMonitor, run_scenario


class LogMonitorTest(unittest.TestCase):
    def test_returns_current_target_bounds(self):
        monitor = LogMonitor("unused", "emulator-5554")
        with patch.object(monitor, "_safe_log", return_value="journey target=com.korail.talk:id/child_plus bounds=794,742,992,918"):
            self.assertEqual((794, 742, 992, 918), monitor.wait_for("journey target=com.korail.talk:id/child_plus"))

    def test_rejects_historical_target_after_guidance_changes(self):
        self.assert_target_rejected(
            "journey target=com.korail.talk:id/child_plus bounds=794,742,992,918\n"
            "journey target=none"
        )

    def test_rejects_bounds_outside_emulator_screen(self):
        self.assert_target_rejected("journey target=com.korail.talk:id/child_plus bounds=1269,742,1487,918")

    def assert_target_rejected(self, log):
        monitor = LogMonitor("unused", "emulator-5554")
        with patch.object(monitor, "_safe_log", return_value=log), patch("run_android_demo_e2e.time.monotonic", side_effect=[0, 0, 21]), patch("run_android_demo_e2e.time.sleep"):
            with self.assertRaises(DemoFailure):
                monitor.wait_for("journey target=com.korail.talk:id/child_plus")


class ScenarioTest(unittest.TestCase):
    def test_default_passengers_skip_picker_but_adjustments_require_it(self):
        for adjustments in ([], ["com.korail.talk:id/child_plus"]):
            with self.subTest(adjustments=adjustments), patch("run_android_demo_e2e.shell") as shell, patch("run_android_demo_e2e.wait_for_activity"), patch("run_android_demo_e2e.tap"), patch("run_android_demo_e2e.time.sleep"), patch("run_android_demo_e2e.LogMonitor"), patch("run_android_demo_e2e.tap_guided_target") as guided:
                run_scenario("unused", "emulator-5554", "test request", "hourTxt09", adjustments)
                targets = [call.args[-1] for call in guided.call_args_list]
                self.assertEqual(bool(adjustments), "com.korail.talk:id/tv_value_passenger" in targets)
                self.assertEqual(bool(adjustments), "com.korail.talk:id/passenger_confirm" in targets)
                self.assertEqual("com.korail.talk:id/search_trains", targets[-1])
                broadcast = next(call.args for call in shell.call_args_list if "broadcast" in call.args)
                self.assertEqual(("--ez", "newJourney", "true"), broadcast[-3:])
