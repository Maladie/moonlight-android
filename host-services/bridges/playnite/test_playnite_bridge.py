import unittest
import tempfile
import ctypes
import json
import os
import threading
import time
from pathlib import Path
from unittest import mock
from email.message import Message
from types import SimpleNamespace

import GameProviderBridge

from PatchPlayniteConnector import (
    ARTWORK_LOOKUP_ANCHOR, ARTWORK_PAYLOAD_ANCHOR, PATCH_MARKER, PATCH_MARKER_V8,
    INSTALL_EVENT_ANCHOR, INSTALL_READER_REPLACEMENT, INSTALL_UI_ANCHOR,
    INSTALL_UI_REPLACEMENT, INSTALLING_PAYLOAD_ANCHOR,
    READER_ANCHOR, SEND_BUILD_ANCHOR, SEND_PARAM_ANCHOR, SNAPSHOT_FUNCTION, STARTED_ANCHOR,
    SOURCE_PAYLOAD_ANCHOR, STATUS_OBJECT_ANCHOR, STATUS_PARAM_ANCHOR, patch_text,
)
from GameOperations import GameOperationsService, SteamProvider
from OperationJournal import OperationJournal
from GameProviderBridge import (
    BridgeState, GAME_START_TIMEOUT, GameProviderHandler, LAUNCHER_POSTCONDITION_TIMEOUT,
    ProviderDiagnostics,
    REQUIRED_GAME_STABLE_SAMPLES, REQUIRED_LAUNCHER_STABLE_SAMPLES,
    REQUIRED_STABLE_SAMPLES,
    STEAM_CANCELLATION_EVIDENCE_TIMEOUT,
    STEAM_PRIMARY_START_TIMEOUT,
    StreamDisplayResolver, WindowProbe, WindowsPipeClient,
    append_operation_audit,
)


GAME_ID = "840317c9-b9a4-4f72-be8e-807414e36a9b"
SECOND_GAME_ID = "65705ca9-b9c7-4ada-b4b7-f73ffb8ac64f"


class WindowProbeTest(unittest.TestCase):
    @staticmethod
    def _steam_big_picture_window(root):
        return {
            "hwnd": 77, "image": "steamwebhelper.exe",
            "process_path": str(root / "bin" / "cef" / "steamwebhelper.exe"),
            "display": r"\\.\DISPLAY1", "bounds": [0, 0, 1920, 1080],
            "monitor_bounds": [0, 0, 1920, 1080],
        }

    def test_big_picture_already_active_does_not_relaunch(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root / "steam.exe").touch()
            runner = mock.Mock()
            provider = SteamProvider(
                root_resolver=lambda: root, command_runner=runner)
            probe = WindowProbe(mock.Mock())
            probe.is_session_locked = mock.Mock(return_value=False)
            window = self._steam_big_picture_window(root)
            probe.interactive_windows = mock.Mock(return_value=[window])

            result = probe.ensure_steam_big_picture(
                provider, r"\\.\DISPLAY1", timeout=.1)

            self.assertTrue(result["ready"])
            self.assertFalse(result["started"])
            probe.interactive_windows.assert_called_with(True)
            runner.assert_not_called()

    def test_big_picture_visible_before_display_resolution_is_accepted(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root / "steam.exe").touch()
            runner = mock.Mock()
            provider = SteamProvider(
                root_resolver=lambda: root, command_runner=runner)
            probe = WindowProbe(mock.Mock())
            probe.is_session_locked = mock.Mock(return_value=False)
            probe.interactive_windows = mock.Mock(return_value=[
                self._steam_big_picture_window(root)])

            result = probe.ensure_steam_big_picture(provider, "", timeout=.1)

            self.assertTrue(result["ready"])
            self.assertFalse(result["started"])
            runner.assert_not_called()

    def test_direct_steam_launch_does_not_wait_for_display_resolution(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            executable = root / "steam.exe"; executable.touch()
            steamapps = root / "steamapps"; steamapps.mkdir()
            (steamapps / "common" / "Game").mkdir(parents=True)
            (steamapps / "appmanifest_367520.acf").write_text(
                '"AppState"\n{\n"appid" "367520"\n"StateFlags" "4"\n'
                '"installdir" "Game"\n}', encoding="utf-8")
            process = mock.Mock(returncode=None)
            process.poll.return_value = None
            runner = mock.Mock(return_value=process)
            provider = SteamProvider(
                roots=[root], root_resolver=lambda: root, command_runner=runner)
            probe = WindowProbe(mock.Mock())
            probe.is_session_locked = mock.Mock(return_value=False)
            probe.interactive_windows = mock.Mock(return_value=[
                self._steam_big_picture_window(root)])
            provider.big_picture_preflight = lambda current: \
                probe.ensure_steam_big_picture(current, "", timeout=.1)

            result = provider.launch({
                "id": "steam:367520", "provider": "steam",
                "providerGameId": "367520",
            }, "launch-task")

            self.assertTrue(result["accepted"])
            self.assertEqual(1, runner.call_count)
            self.assertEqual([str(executable.resolve()),
                              "steam://launch/367520/Dialog"],
                             runner.call_args.args[0])

    def test_big_picture_can_open_before_display_resolution_and_confirm_postcondition(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            executable = root / "steam.exe"; executable.touch()
            runner = mock.Mock()
            provider = SteamProvider(
                root_resolver=lambda: root, command_runner=runner)
            probe = WindowProbe(mock.Mock())
            probe.is_session_locked = mock.Mock(return_value=False)
            window = self._steam_big_picture_window(root)
            probe.interactive_windows = mock.Mock(side_effect=[
                [], [], [], [], [window], [window], [window],
            ])

            with mock.patch("GameProviderBridge.time.sleep"):
                result = probe.ensure_steam_big_picture(provider, "", timeout=1)

            self.assertTrue(result["ready"])
            self.assertTrue(result["started"])
            self.assertEqual([str(executable.resolve()), "-gamepadui"],
                             runner.call_args.args[0])

    def test_game_readiness_still_rejects_an_unresolved_exact_display(self):
        source = Path(__file__).with_name("GameProviderBridge.py").read_text(
            encoding="utf-8-sig")
        sample = source[source.index("    def sample("):
                        source.index("\ndef compact_json")]

        self.assertIn('if not expected_display:', sample)
        self.assertIn('"reason": "stream_display_not_configured"', sample)

    def test_big_picture_is_opened_and_postcondition_is_confirmed(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            executable = root / "steam.exe"; executable.touch()
            runner = mock.Mock()
            provider = SteamProvider(
                root_resolver=lambda: root, command_runner=runner)
            probe = WindowProbe(mock.Mock())
            probe.is_session_locked = mock.Mock(return_value=False)
            window = self._steam_big_picture_window(root)
            probe.interactive_windows = mock.Mock(side_effect=[
                [], [], [], [], [window], [window], [window],
            ])

            with mock.patch("GameProviderBridge.time.sleep"):
                result = probe.ensure_steam_big_picture(
                    provider, r"\\.\DISPLAY1", timeout=1)

            self.assertTrue(result["ready"])
            self.assertTrue(result["started"])
            self.assertEqual([str(executable.resolve()), "-gamepadui"],
                             runner.call_args.args[0])
            self.assertEqual(str(root.resolve()), runner.call_args.kwargs["cwd"])
            self.assertFalse(runner.call_args.kwargs["shell"])

    def test_running_steam_opens_big_picture_without_restart(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            executable = root / "steam.exe"; executable.touch()
            runner = mock.Mock()
            provider = SteamProvider(
                root_resolver=lambda: root, command_runner=runner)
            probe = WindowProbe(mock.Mock())
            probe.is_session_locked = mock.Mock(return_value=False)
            probe._exact_process_running = mock.Mock(return_value=True)
            window = self._steam_big_picture_window(root)
            probe.interactive_windows = mock.Mock(side_effect=[
                [], [], [], [window], [window], [window],
            ])

            with mock.patch("GameProviderBridge.time.sleep"):
                result = probe.ensure_steam_big_picture(
                    provider, r"\\.\DISPLAY1", timeout=1)

            self.assertTrue(result["ready"])
            self.assertEqual([str(executable.resolve()), "steam://open/bigpicture"],
                             runner.call_args.args[0])

    def test_big_picture_missing_postcondition_is_explicit_error(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root / "steam.exe").touch()
            provider = SteamProvider(
                root_resolver=lambda: root, command_runner=mock.Mock())
            probe = WindowProbe(mock.Mock())
            probe.is_session_locked = mock.Mock(return_value=False)
            probe.interactive_windows = mock.Mock(return_value=[])

            with mock.patch("GameProviderBridge.time.sleep"), \
                    mock.patch("GameProviderBridge.time.monotonic", side_effect=[0, 1]):
                result = probe.ensure_steam_big_picture(
                    provider, r"\\.\DISPLAY1", timeout=.1)

            self.assertFalse(result["ready"])
            self.assertEqual("launcher_interaction_required", result["reason"])

    def test_locked_session_stops_big_picture_preflight_immediately(self):
        provider = mock.Mock()
        probe = WindowProbe(mock.Mock())
        probe.user32 = mock.Mock()
        probe.is_session_locked = mock.Mock(return_value=True)

        result = probe.ensure_steam_big_picture(
            provider, r"\\.\DISPLAY1", timeout=15)

        self.assertEqual({"ready": False, "reason": "host_session_locked"}, result)
        provider.executable.assert_not_called()

    def test_launcher_script_covers_uia_win32_and_guarded_visual_action(self):
        script = Path(__file__).with_name("Invoke-GameLauncher.ps1").read_text(
            encoding="utf-8-sig")

        for contract in (
                "InvokePattern", "QueryFullProcessImageName", "EnumChildWindows",
                "BM_CLICK", "AllowDefaultAction", "ClickPoint", "mouse_event",
                "PrintWindow", "Find-VisualPrimaryAction", "visual_primary",
                "launcher_input_failed", "Test-LauncherIdentity", "DetectOnly"):
            self.assertIn(contract, script)
        self.assertNotIn("LegacyIAccessiblePattern", script)
        self.assertNotIn("Get-Process", script)
        self.assertNotIn("PressEnter", script)
        self.assertNotIn("Civilization", script)
        self.assertNotIn("Carcassonne", script)
        self.assertLess(script.index("if ($AllowDefaultAction"),
                        script.index("$matches[0].GetCurrentPattern"))

    def test_game_launcher_invocation_uses_exact_window_identity(self):
        probe = WindowProbe(mock.Mock())
        completed = mock.Mock(stdout=(
            '{"recognized":true,"clicked":true,"method":"uia","action":"Graj"}\n'))
        candidate = {
            "hwnd": 77, "process_id": 1234,
            "process_path": r"E:\Games\Example\launcher.exe",
        }

        with mock.patch("GameProviderBridge.subprocess.run", return_value=completed) as run:
            result = probe.invoke_game_launcher(candidate)

        self.assertTrue(result["clicked"])
        command = run.call_args.args[0]
        self.assertIn("Invoke-GameLauncher.ps1", command[6])
        self.assertEqual("77", command[8])
        self.assertEqual("1234", command[10])
        self.assertEqual(candidate["process_path"], command[12])
        self.assertFalse(run.call_args.kwargs["check"])

    def test_guarded_default_action_is_explicitly_enabled(self):
        probe = WindowProbe(mock.Mock())
        completed = mock.Mock(stdout=(
            '{"recognized":true,"clicked":true,"method":"uia_pointer"}\n'))
        candidate = {
            "hwnd": 77, "process_id": 1234,
            "process_path": r"C:\Program Files (x86)\Steam\bin\cef\steamwebhelper.exe",
            "allow_default_action": True,
        }

        with mock.patch("GameProviderBridge.subprocess.run", return_value=completed) as run:
            result = probe.invoke_game_launcher(candidate)

        self.assertTrue(result["clicked"])
        self.assertIn("-AllowDefaultAction", run.call_args.args[0])

    def test_launcher_detection_mode_is_non_mutating(self):
        probe = WindowProbe(mock.Mock())
        completed = mock.Mock(stdout=(
            '{"recognized":true,"clicked":false,"reason":"launcher_action_detected"}\n'))
        candidate = {
            "hwnd": 77, "process_id": 1234,
            "process_path": r"C:\Program Files (x86)\Steam\steamwebhelper.exe",
        }

        with mock.patch("GameProviderBridge.subprocess.run", return_value=completed) as run:
            result = probe.invoke_game_launcher(candidate, detect_only=True)

        self.assertTrue(result["recognized"])
        self.assertFalse(result["clicked"])
        self.assertIn("-DetectOnly", run.call_args.args[0])

    def test_title_correlation_rejects_short_ambiguous_titles(self):
        self.assertFalse(WindowProbe.title_correlates(
            "V", "Sid Meier's Civilization V"))
        self.assertTrue(WindowProbe.title_correlates(
            "Sid Meier's Civilization V - DirectX",
            "Sid Meier's Civilization V"))

    def test_fresh_correlated_window_is_stabilized_then_invoked_once(self):
        class User32:
            @staticmethod
            def GetForegroundWindow():
                return 77

            @staticmethod
            def IsWindowVisible(_hwnd):
                return True

            @staticmethod
            def GetWindowThreadProcessId(_hwnd, process_id):
                process_id._obj.value = 1234
                return 1

            @staticmethod
            def GetWindowRect(_hwnd, rect):
                rect._obj.left, rect._obj.top = 100, 100
                rect._obj.right, rect._obj.bottom = 900, 700
                return True

            @staticmethod
            def EnumWindows(callback, _value):
                callback(77, 0)
                return True

        probe = object.__new__(WindowProbe)
        probe.user32 = User32()
        probe.dwmapi = None
        probe.is_session_locked = mock.Mock(return_value=False)
        probe._process_tree = mock.Mock(return_value={1234})
        probe._process_path = mock.Mock(
            return_value=r"E:\Games\Example\launcher.exe")
        probe._process_image = mock.Mock(return_value="launcher.exe")
        probe._window_title = mock.Mock(return_value="Example Launcher")
        probe._window_class = mock.Mock(return_value="ExampleWindow")
        probe._monitor_details = mock.Mock(
            return_value=(r"\\.\DISPLAY1", [0, 0, 1920, 1080]))
        probe.invoke_game_launcher = mock.Mock(side_effect=({
            "recognized": True, "clicked": True, "method": "uia"}, {
            "recognized": False, "clicked": False,
            "reason": "launcher_action_unavailable"}))
        baseline = {"windows": {}, "captured_at": time.time()}

        candidate = probe.sample("game", 1234, r"\\.\DISPLAY1",
                                 r"E:\Games\Example", baseline, False)
        invoked = probe.sample("game", 1234, r"\\.\DISPLAY1",
                               r"E:\Games\Example", baseline, True)
        required = probe.sample("game", 1234, r"\\.\DISPLAY1",
                                r"E:\Games\Example", baseline, False, True)
        unavailable = probe.sample("game", 1234, r"\\.\DISPLAY1",
                                   r"E:\Games\Example", baseline, True)

        self.assertEqual("launcher_candidate_detected", candidate["reason"])
        self.assertEqual("launcher_action_invoked", invoked["reason"])
        self.assertTrue(invoked["launcher_action_attempted"])
        self.assertEqual("launcher_interaction_required", required["reason"])
        self.assertEqual("launcher_interaction_required", unavailable["reason"])
        self.assertEqual("launcher_action_unavailable",
                         unavailable["launcher_detail"])
        self.assertEqual(2, probe.invoke_game_launcher.call_count)

    def test_missing_root_requires_certain_absence_of_exact_replacement(self):
        class User32:
            foreground = 77
            enumerate_window = True

            @classmethod
            def GetForegroundWindow(cls):
                return cls.foreground

            @staticmethod
            def IsWindowVisible(_hwnd):
                return True

            @staticmethod
            def GetWindowThreadProcessId(_hwnd, process_id):
                process_id._obj.value = 2222
                return 1

            @staticmethod
            def GetWindowRect(_hwnd, rect):
                rect._obj.left, rect._obj.top = 0, 0
                rect._obj.right, rect._obj.bottom = 1920, 1080
                return True

            @classmethod
            def EnumWindows(cls, callback, _value):
                if cls.enumerate_window:
                    callback(77, 0)
                return True

        probe = object.__new__(WindowProbe)
        probe.user32 = User32()
        probe.dwmapi = None
        probe.kernel32 = object()
        probe.is_session_locked = mock.Mock(return_value=False)
        probe._process_tree = mock.Mock(return_value=set())
        probe._process_path = mock.Mock(return_value=r"E:\Games\RE3\re3.exe")
        probe._process_image = mock.Mock(return_value="re3.exe")
        probe._window_title = mock.Mock(return_value="Resident Evil 3")
        probe._window_class = mock.Mock(return_value="RE Engine")
        probe._monitor_details = mock.Mock(
            return_value=(r"\\.\DISPLAY1", [0, 0, 1920, 1080]))
        probe.process_identities = mock.Mock(return_value=[])

        visible = probe.sample(
            "game", 1111, r"\\.\DISPLAY1", r"E:\Games\RE3",
            tracked_process_path=r"E:\Games\RE3\re3.exe")
        self.assertTrue(visible["replacement_process"])
        self.assertEqual(2222, visible["process_id"])
        self.assertNotEqual("game_process_exited", visible["reason"])

        User32.enumerate_window = False
        for identities, reason in (
                (None, "game_process_probe_unavailable"),
                ([{"process_id": 2222}], "game_replacement_headless"),
                ([{"process_id": 2222}, {"process_id": 3333}],
                 "game_process_identity_ambiguous"),
                ([], "game_process_exited")):
            with self.subTest(reason=reason):
                probe.process_identities.return_value = identities
                sample = probe.sample(
                    "game", 1111, r"\\.\DISPLAY1", r"E:\Games\RE3",
                    tracked_process_path=r"E:\Games\RE3\re3.exe")
                self.assertEqual(reason, sample["reason"])

        User32.enumerate_window = True
        probe._process_path.return_value = r"E:\Games\Other\other.exe"
        probe.process_identities.return_value = []
        outside = probe.sample(
            "game", 1111, r"\\.\DISPLAY1", r"E:\Games\RE3",
            tracked_process_path=r"E:\Games\RE3\re3.exe")
        self.assertEqual("game_process_exited", outside["reason"])

    def test_windowed_game_needs_independent_launcher_evidence(self):
        game = {
            "process_path": r"E:\Games\Example\game.exe",
            "image": "game.exe", "title": "Example", "window_class": "GameWindow",
        }
        separate_launcher = dict(
            game, process_path=r"E:\Games\Example\launcher.exe",
            image="launcher.exe", title="Example Launcher")
        native_dialog = dict(game, window_class="#32770")

        self.assertFalse(WindowProbe.has_launcher_evidence(
            game, r"E:\Games\Example\game.exe", r"E:\Games\Example"))
        self.assertTrue(WindowProbe.has_launcher_evidence(
            separate_launcher, r"E:\Games\Example\game.exe", r"E:\Games\Example"))
        self.assertTrue(WindowProbe.has_launcher_evidence(
            native_dialog, r"E:\Games\Example\game.exe", r"E:\Games\Example"))

    def test_benchmark_launchers_have_generic_independent_evidence(self):
        cases = (
            (
                "Carcassonne",
                {
                    "process_path": r"E:\Gry\Epic\Carcassonne\Carcassonne.exe",
                    "image": "carcassonne.exe",
                    "title": "Carcassonne Configuration",
                    "window_class": "#32770",
                },
                r"E:\Gry\Epic\Carcassonne\Carcassonne.exe",
                r"E:\Gry\Epic\Carcassonne",
                set(),
                "Carcassonne",
            ),
            (
                "House of Ashes",
                {
                    "process_path": r"C:\Program Files (x86)\Steam\bin\cef\steamwebhelper.exe",
                    "image": "steamwebhelper.exe",
                    "title": "The Dark Pictures Anthology: House of Ashes",
                    "window_class": "SDL_app",
                },
                "",
                r"E:\Gry\Steam\steamapps\common\House of Ashes",
                {"steam.exe", "steamwebhelper.exe"},
                "The Dark Pictures Anthology: House of Ashes",
            ),
            (
                "Civilization V",
                {
                    "process_path": r"C:\Program Files (x86)\Steam\bin\cef\steamwebhelper.exe",
                    "image": "steamwebhelper.exe",
                    "title": "Sid Meier's Civilization V - DirectX",
                    "window_class": "SDL_app",
                },
                "",
                r"E:\Gry\Steam\steamapps\common\Sid Meier's Civilization V",
                {"steam.exe", "steamwebhelper.exe"},
                "Sid Meier's Civilization V",
            ),
            (
                "Civilization VI native Steam dialog",
                {
                    "process_path": r"C:\Program Files (x86)\Steam\steamwebhelper.exe",
                    "image": "steamwebhelper.exe",
                    "title": "Sid Meier's Civilization VI - DirectX",
                    "window_class": "vguiPopupWindow",
                },
                "",
                r"E:\Gry\Steam\steamapps\common\Sid Meier's Civilization VI",
                {"steam.exe", "steamwebhelper.exe"},
                "Sid Meier's Civilization VI",
            ),
        )

        for name, candidate, executable, directory, images, title in cases:
            with self.subTest(name=name):
                self.assertTrue(WindowProbe.has_launcher_evidence(
                    candidate, executable, directory, images, title))

    def test_steam_cef_launch_dialog_is_correlated_outside_game_directory(self):
        class User32:
            @staticmethod
            def GetForegroundWindow():
                return 77

            @staticmethod
            def IsWindowVisible(_hwnd):
                return True

            @staticmethod
            def GetWindowThreadProcessId(_hwnd, process_id):
                process_id._obj.value = 4321
                return 1

            @staticmethod
            def GetWindowRect(_hwnd, rect):
                rect._obj.left, rect._obj.top = 200, 100
                rect._obj.right, rect._obj.bottom = 1100, 800
                return True

            @staticmethod
            def EnumWindows(callback, _value):
                callback(77, 0)
                return True

        probe = object.__new__(WindowProbe)
        probe.user32 = User32()
        probe.dwmapi = None
        probe.is_session_locked = mock.Mock(return_value=False)
        probe._process_tree = mock.Mock(return_value=set())
        probe._process_path = mock.Mock(return_value=(
            r"E:\Steam\bin\cef\cef.win64\steamwebhelper.exe"))
        probe._process_image = mock.Mock(return_value="steamwebhelper.exe")
        probe._window_title = mock.Mock(return_value=(
            "The Dark Pictures Anthology: House of Ashes"))
        probe._window_class = mock.Mock(return_value="SDL_app")
        probe._monitor_details = mock.Mock(
            return_value=(r"\\.\DISPLAY1", [0, 0, 1920, 1080]))
        probe.belongs_to_install_directory = mock.Mock(return_value=False)
        baseline = {"windows": {}, "captured_at": time.time()}

        sample = probe.sample(
            "game", 0, r"\\.\DISPLAY1",
            r"E:\Steam\steamapps\common\House of Ashes", baseline,
            False, False, "",
            "The Dark Pictures Anthology: House of Ashes",
            {"steam.exe", "steamwebhelper.exe"})

        self.assertEqual("launcher_candidate_detected", sample["reason"])
        self.assertTrue(sample["launcher_candidate"])
        self.assertTrue(sample["allow_default_action"])

    def test_unrelated_cef_window_is_not_a_launcher_candidate(self):
        candidate = {
            "process_path": r"E:\Steam\bin\cef\cef.win64\steamwebhelper.exe",
            "image": "steamwebhelper.exe", "title": "Steam Store",
            "window_class": "SDL_app",
        }

        self.assertFalse(WindowProbe.has_launcher_evidence(
            candidate, "", r"E:\Games\Civilization V",
            {"steam.exe", "steamwebhelper.exe"},
            "Sid Meier's Civilization V"))

    def test_foreground_steam_surface_after_launcher_action_requires_observation(self):
        class User32:
            @staticmethod
            def GetForegroundWindow():
                return 77

            @staticmethod
            def IsWindowVisible(_hwnd):
                return True

            @staticmethod
            def GetWindowThreadProcessId(_hwnd, process_id):
                process_id._obj.value = 4321
                return 1

            @staticmethod
            def GetWindowRect(_hwnd, rect):
                rect._obj.left, rect._obj.top = 200, 100
                rect._obj.right, rect._obj.bottom = 1100, 800
                return True

            @staticmethod
            def EnumWindows(callback, _value):
                callback(77, 0)
                return True

        probe = object.__new__(WindowProbe)
        probe.user32 = User32()
        probe.dwmapi = None
        probe.is_session_locked = mock.Mock(return_value=False)
        probe._process_tree = mock.Mock(return_value=set())
        probe._process_path = mock.Mock(return_value=(
            r"E:\Steam\bin\cef\cef.win64\steamwebhelper.exe"))
        probe._process_image = mock.Mock(return_value="steamwebhelper.exe")
        probe._window_title = mock.Mock(return_value="Steam")
        probe._window_class = mock.Mock(return_value="SDL_app")
        probe._monitor_details = mock.Mock(
            return_value=(r"\\.\DISPLAY1", [0, 0, 1920, 1080]))
        probe.belongs_to_install_directory = mock.Mock(return_value=False)
        probe.invoke_game_launcher = mock.Mock()
        baseline = {
            "foreground_hwnd": 11,
            "windows": {"77": {
                "process_id": 4321, "image": "steamwebhelper.exe",
                "title": "Steam",
            }},
        }

        sample = probe.sample(
            "game", 0, r"\\.\DISPLAY1",
            r"E:\Steam\steamapps\common\Civilization V", baseline,
            False, False, "", "Sid Meier's Civilization V",
            {"steam.exe", "steamwebhelper.exe"}, True)

        self.assertEqual("launcher_candidate_detected", sample["reason"])
        self.assertTrue(sample["launcher_candidate"])
        self.assertFalse(sample["allow_default_action"])
        probe.invoke_game_launcher.assert_not_called()

    def test_unchanged_fullscreen_provider_prompt_is_detected_without_clicking(self):
        class User32:
            @staticmethod
            def GetForegroundWindow():
                return 77

            @staticmethod
            def IsWindowVisible(_hwnd):
                return True

            @staticmethod
            def GetWindowThreadProcessId(_hwnd, process_id):
                process_id._obj.value = 4321
                return 1

            @staticmethod
            def GetWindowRect(_hwnd, rect):
                rect._obj.left, rect._obj.top = 0, 0
                rect._obj.right, rect._obj.bottom = 1920, 1080
                return True

            @staticmethod
            def EnumWindows(callback, _value):
                callback(77, 0)
                return True

        probe = object.__new__(WindowProbe)
        probe.user32 = User32()
        probe.dwmapi = None
        probe.is_session_locked = mock.Mock(return_value=False)
        probe._process_tree = mock.Mock(return_value=set())
        probe._process_path = mock.Mock(return_value=(
            r"E:\Steam\bin\cef\cef.win64\steamwebhelper.exe"))
        probe._process_image = mock.Mock(return_value="steamwebhelper.exe")
        probe._window_title = mock.Mock(return_value="Tryb Big Picture Steam")
        probe._window_class = mock.Mock(return_value="")
        probe._monitor_details = mock.Mock(
            return_value=(r"\\.\DISPLAY1", [0, 0, 1920, 1080]))
        probe.belongs_to_install_directory = mock.Mock(return_value=False)
        probe.invoke_game_launcher = mock.Mock(return_value={
            "recognized": True, "clicked": False,
            "reason": "launcher_action_detected",
        })
        baseline = {
            "foreground_hwnd": 77,
            "windows": {"77": {
                "process_id": 4321, "image": "steamwebhelper.exe",
                "title": "Tryb Big Picture Steam",
            }},
        }

        before_grace = probe.sample(
            "game", 0, r"\\.\DISPLAY1",
            r"E:\Steam\steamapps\common\Civilization VI", baseline,
            expected_title="Sid Meier's Civilization VI",
            expected_launcher_images={"steam.exe", "steamwebhelper.exe"})
        detected = probe.sample(
            "game", 0, r"\\.\DISPLAY1",
            r"E:\Steam\steamapps\common\Civilization VI", baseline,
            expected_title="Sid Meier's Civilization VI",
            expected_launcher_images={"steam.exe", "steamwebhelper.exe"},
            probe_provider_launcher=True)

        self.assertEqual("waiting_for_game_window", before_grace["reason"])
        self.assertEqual("launcher_interaction_required", detected["reason"])
        self.assertTrue(detected["launcher_candidate"])
        probe.invoke_game_launcher.assert_called_once()
        self.assertTrue(probe.invoke_game_launcher.call_args.kwargs["detect_only"])

    def test_operation_audit_appends_json_lines(self):
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "operation-audit.jsonl"
            append_operation_audit(path, "steam_direct_dispatched", {
                "game_id": GAME_ID, "requested_at": 12.5,
            })
            append_operation_audit(path, "steam_activity_observed", {
                "game_id": GAME_ID, "requested_at": 12.5,
            })

            records = [json.loads(line) for line in path.read_text(
                encoding="utf-8").splitlines()]

        self.assertEqual([
            "steam_direct_dispatched", "steam_activity_observed",
        ], [record["event"] for record in records])
        self.assertTrue(all(record["game_id"] == GAME_ID for record in records))
        self.assertTrue(all("timestamp" in record for record in records))

    def test_existing_steam_window_embedded_prompt_is_recognized(self):
        service = mock.Mock()
        probe = WindowProbe(service)
        probe.user32 = object()
        probe.is_session_locked = lambda: False
        probe.uac_consent_pending = lambda: False
        service.manual_attention.return_value = None
        service.sample.return_value = None
        service.expected_launcher_images.return_value = {
            "steam.exe", "steamwebhelper.exe"}
        window = {"hwnd": 77, "process_id": 123, "title": "Steam",
                  "image": "steamwebhelper.exe", "foreground": True,
                  "bounds": [0, 0, 1000, 700],
                  "monitor_bounds": [0, 0, 1920, 1080]}
        probe.interactive_windows = lambda: [window]
        service.confirm_operation.return_value = {
            "clicked": False, "recognized": True, "method": "uia"}

        sample = probe.installation_prompt({
            "game": {"source": "Steam", "name": "FEZ"},
            "operation": "install", "foreground_hwnd": 77,
            "windows": {"77": {"process_id": 123,
                                  "image": "steamwebhelper.exe",
                                  "title": "Steam"}},
        })

        self.assertTrue(sample["requires_attention"])
        self.assertEqual(77, sample["hwnd"])

    def test_legendary_failure_bypasses_window_confirmation(self):
        service = mock.Mock()
        probe = WindowProbe(service)
        probe.user32 = object()
        probe.is_session_locked = lambda: False
        probe.uac_consent_pending = lambda: False
        service.manual_attention.return_value = None
        service.sample.return_value = {
            "provider": "epic", "requires_attention": True,
            "reason": "legendary_verification_failed", "launcher": "legendary.exe",
        }
        probe.interactive_windows = mock.Mock()

        sample = probe.installation_prompt({
            "game": {"source": "Epic", "name": "Carcassonne"},
            "operation": "install",
        })

        self.assertEqual("legendary_verification_failed", sample["reason"])
        probe.interactive_windows.assert_not_called()
        service.confirm_operation.assert_not_called()


    def test_accepts_fullscreen_ui_owned_by_either_playnite_process(self):
        self.assertTrue(WindowProbe.is_playnite_ui_image("Playnite.FullscreenApp.exe"))
        self.assertTrue(WindowProbe.is_playnite_ui_image("PLAYNITE.DESKTOPAPP.EXE"))
        self.assertFalse(WindowProbe.is_playnite_ui_image("explorer.exe"))

    def test_headless_fallback_requires_window_to_fill_stream_monitor(self):
        monitor = [0, 0, 1920, 1080]
        self.assertTrue(WindowProbe.fills_monitor([0, 0, 1920, 1080], monitor))
        self.assertTrue(WindowProbe.fills_monitor([-8, -8, 1928, 1048], monitor))
        self.assertFalse(WindowProbe.fills_monitor([100, 100, 1380, 820], monitor))
        self.assertFalse(WindowProbe.fills_monitor([0, 0, 1920, 1080], []))

    def test_fullscreen_target_can_ignore_focus_on_another_monitor_only(self):
        bounds = [0, 0, 1920, 1080]
        monitor = [0, 0, 1920, 1080]
        self.assertTrue(WindowProbe.can_reveal_without_global_foreground(
            bounds, monitor, r"\\.\DISPLAY2", r"\\.\DISPLAY15"))
        self.assertTrue(WindowProbe.can_reveal_without_global_foreground(
            bounds, monitor, "", r"\\.\DISPLAY15"))
        self.assertFalse(WindowProbe.can_reveal_without_global_foreground(
            bounds, monitor, r"\\.\DISPLAY15", r"\\.\DISPLAY15"))
        self.assertFalse(WindowProbe.can_reveal_without_global_foreground(
            [100, 100, 1380, 820], monitor, r"\\.\DISPLAY2", r"\\.\DISPLAY15"))

    def test_game_process_may_be_resolved_within_exact_install_directory(self):
        install = r"E:\Games\Hollow Knight"
        self.assertTrue(WindowProbe.belongs_to_install_directory(
            r"E:\Games\Hollow Knight\hollow_knight.exe", install))
        self.assertFalse(WindowProbe.belongs_to_install_directory(
            r"E:\Games\Hollow Knight 2\not-the-game.exe", install))
        self.assertFalse(WindowProbe.belongs_to_install_directory(
            r"D:\Tools\unrelated.exe", install))
        self.assertFalse(WindowProbe.belongs_to_install_directory("", install))

    def test_install_prompt_scoring_requires_launcher_affinity(self):
        baseline = {
            "game": {"source": "Steam"},
            "foreground_hwnd": 10,
            "windows": {
                "20": {"title": "Steam", "image": "steam.exe"},
                "40": {"title": "Unrelated", "image": "unrelated.exe"},
            },
        }
        known = {"hwnd": 20, "image": "steam.exe", "title": "Steam",
                 "foreground": True, "bounds": [100, 100, 900, 700],
                 "monitor_bounds": [0, 0, 1920, 1080]}
        background_launcher = dict(known, foreground=False)
        generic = {"hwnd": 30, "image": "futurelauncher.exe", "title": "Choose folder",
                   "foreground": False, "bounds": [200, 160, 1000, 760],
                   "monitor_bounds": [0, 0, 1920, 1080]}
        unrelated = {"hwnd": 50, "image": "idea64.exe",
                     "title": "FieldDisplayNameOverrides.java [Default Changelist]",
                     "foreground": True, "bounds": [100, 100, 1500, 900],
                     "monitor_bounds": [0, 0, 1920, 1080]}
        unchanged = {"hwnd": 40, "image": "unrelated.exe", "title": "Unrelated",
                     "foreground": False, "bounds": [0, 0, 1920, 1080],
                     "monitor_bounds": [0, 0, 1920, 1080]}
        expected = {"steam.exe", "steamwebhelper.exe"}
        self.assertGreaterEqual(WindowProbe.installation_candidate_score(
            baseline, known, expected), 5)
        self.assertLess(WindowProbe.installation_candidate_score(
            baseline, background_launcher, expected), 5)
        self.assertEqual(0, WindowProbe.installation_candidate_score(
            baseline, generic, expected))
        self.assertEqual(0, WindowProbe.installation_candidate_score(
            baseline, unrelated, expected))
        self.assertEqual(0, WindowProbe.installation_candidate_score(
            baseline, unchanged, expected))

    def test_steam_web_helper_modal_is_a_known_launcher(self):
        baseline = {"foreground_hwnd": 1, "windows": {},
                    "game": {"source": "Steam"}}
        modal = {"hwnd": 2, "image": "steamwebhelper.exe", "title": "Odinstaluj",
                 "foreground": False, "bounds": [100, 100, 700, 400],
                 "monitor_bounds": [0, 0, 1920, 1080]}
        self.assertGreaterEqual(
            WindowProbe.installation_candidate_score(
                baseline, modal, {"steam.exe", "steamwebhelper.exe"}), 5)


    def _epic_probe(self, windows, sample=None):
        service = mock.Mock()
        service.epic = object()
        service.provider_for.return_value = service.epic
        service.manual_attention.return_value = None
        service.sample.return_value = sample
        service.expected_launcher_images.return_value = {"epicgameslauncher.exe"}
        probe = object.__new__(WindowProbe)
        probe.user32 = True
        probe.game_operations = service
        probe.is_session_locked = mock.Mock(return_value=False)
        probe.uac_consent_pending = mock.Mock(return_value=False)
        probe.interactive_windows = mock.Mock(return_value=windows)
        return probe, service



    def test_legendary_provider_failure_skips_unrelated_window_scan(self):
        sample = {"provider": "epic", "requires_attention": True,
                  "reason": "legendary_verification_failed",
                  "launcher": "legendary.exe"}
        probe, service = self._epic_probe([], sample)

        result = probe.installation_prompt({
            "game": {"source": "Epic", "name": "Alien: Isolation"},
            "operation": "uninstall", "foreground_hwnd": 0, "windows": {},
        })

        self.assertIs(sample, result)
        probe.interactive_windows.assert_not_called()
        service.confirm_operation.assert_not_called()


    def test_provider_started_sample_skips_generic_window_scan(self):
        window = {"hwnd": 10, "process_id": 100, "image": "epicgameslauncher.exe",
                  "title": "Epic Games Launcher", "foreground": True}
        provider_sample = {"provider": "epic", "started": True, "phase": "active_uninstall"}
        probe, service = self._epic_probe([window], provider_sample)

        result = probe.installation_prompt({
            "game": {"source": "Epic", "name": "Carcassonne"}, "operation": "uninstall"})

        self.assertIs(provider_sample, result)
        service.confirm_operation.assert_not_called()
        probe.interactive_windows.assert_not_called()



    def test_visible_uac_consent_is_reported_as_attention_required(self):
        probe = object.__new__(WindowProbe)
        probe.user32 = True
        probe.is_session_locked = mock.Mock(return_value=False)
        probe.uac_consent_pending = mock.Mock(return_value=True)
        probe.game_operations = mock.Mock()
        probe.game_operations.manual_attention.return_value = {
            "reason": "epic_manual", "launcher": "epicgameslauncher.exe"}

        result = probe.installation_prompt({
            "operation": "install", "game": {"source": "Epic"}})

        self.assertEqual("epic_manual", result["reason"])
        probe.game_operations.sample.assert_not_called()





    def test_large_playnite_messages_keep_windows_more_data_chunk(self):
        payload = b"x" * 8

        class PartialRead:
            @staticmethod
            def ReadFile(handle, buffer, size, read, overlapped):
                ctypes.memmove(buffer, payload, len(payload))
                read._obj.value = len(payload)
                return False

        client = WindowsPipeClient.__new__(WindowsPipeClient)
        client._kernel32 = lambda: PartialRead()
        with mock.patch("GameProviderBridge.ctypes.get_last_error", return_value=234,
                        create=True):
            self.assertEqual(payload, client._read(123, len(payload)))

    def test_pipe_availability_is_peeked_without_starting_a_read(self):
        class AvailableBytes:
            @staticmethod
            def PeekNamedPipe(handle, buffer, size, read, available, remaining):
                available._obj.value = 37
                return True

        client = WindowsPipeClient.__new__(WindowsPipeClient)
        client._kernel32 = lambda: AvailableBytes()
        self.assertEqual(37, client._available(123))


class BridgeStateTest(unittest.TestCase):
    def setUp(self):
        self.now = time.time()
        self.audit = []
        service = GameOperationsService(
            OperationJournal(None),
            steam=SteamProvider(root_resolver=lambda: None,
                                command_runner=mock.Mock()))
        self.state = BridgeState(
            game_operations=service, clock=lambda: self.now,
            operation_audit=lambda event, payload: self.audit.append((event, payload)))
        self.commands = []
        self.closed_processes = []
        self.fullscreen_calls = []
        self.focus_calls = []
        self.installation_focus_calls = []
        self.state.set_transport(True, self.commands.append)
        self.state.set_window_actions(
            lambda process_id: not self.closed_processes.append(process_id),
            lambda: self.fullscreen_calls.append(True) or {"started": False},
            lambda process_id, install_dir, display: self.focus_calls.append(
                (process_id, install_dir, display)) or {
                    "focused": True, "process_id": process_id, "display": display,
                },
            lambda: {"foreground_hwnd": 1, "windows": {}},
            None,
            lambda hwnd: self.installation_focus_calls.append(hwnd) or {
                "focused": True, "hwnd": hwnd,
            })

    @staticmethod
    def _active_trace(game_id="steam:289070", provider="steam",
                      provider_game_id="289070", playnite_guid="",
                      process_id=4242, process_path=r"C:\Games\Civ6\Civ6.exe",
                      started=133700000000000000):
        return {
            "version": 1, "game_id": game_id, "provider": provider,
            "provider_game_id": provider_game_id,
            "playnite_guid": playnite_guid, "process_id": process_id,
            "process_path": process_path,
            "process_started_filetime": started,
        }

    @staticmethod
    def _trace_game(trace):
        return {
            "id": trace["game_id"], "name": "Recovered game",
            "provider": trace["provider"],
            "providerGameId": trace["provider_game_id"],
            "playniteGameId": trace["playnite_guid"],
            "installDir": str(Path(trace["process_path"]).parent)
            if "\\" not in trace["process_path"] else
            trace["process_path"].rsplit("\\", 1)[0],
        }

    @staticmethod
    def _write_trace(path, trace):
        path.write_text(json.dumps(trace), encoding="utf-8")

    def _configure_reconciliation(self, state, trace, identities=None):
        identity = {
            "process_id": trace["process_id"],
            "process_path": trace["process_path"],
            "process_started_filetime": trace["process_started_filetime"],
        }
        state.set_reconciliation_actions(
            lambda process_id: identity if process_id == trace["process_id"] else None,
            lambda _path: list(identities) if identities is not None else [identity])

    def _put_native_game(self, game_id=GAME_ID):
        with self.state.lock:
            self.state.library[game_id] = {
                "id": game_id, "name": "Baba Is You", "provider": "playnite",
                "providerGameId": game_id, "playniteGameId": game_id,
            }
            self.state.playnite_library[game_id] = {
                "id": game_id, "name": "Baba Is You", "source": "GOG",
            }

    def test_unchanged_launcher_invocation_reaches_bounded_manual_fallback(self):
        sample = {
            "qualified": False, "reason": "launcher_action_invoked",
            "launcher_candidate": True, "launcher_action_attempted": True,
            "hwnd": 77, "process_id": 1234, "title": "Example Launcher",
        }
        self.state.apply_window_sample(sample)
        with self.state.lock:
            self.assertFalse(self.state._launcher_postcondition_failed_locked())

        # A launcher can update its caption after InvokePattern without ever
        # starting the game. HWND/PID identity must keep the postcondition open.
        self.state.apply_window_sample({
            **sample, "reason": "launcher_candidate_detected",
            "launcher_action_attempted": False, "title": "Starting...",
        })

        self.now += LAUNCHER_POSTCONDITION_TIMEOUT + .1

        with self.state.lock:
            self.assertTrue(self.state._launcher_postcondition_failed_locked())
        with mock.patch("builtins.print") as logged:
            self.state.apply_window_sample({
                **sample, "reason": "launcher_candidate_detected",
                "launcher_action_attempted": False,
                "launcher_detail": "launcher_input_failed",
            })
        with self.state.lock:
            self.assertFalse(self.state._launcher_postcondition_failed_locked())
            self.assertTrue(self.state._launcher_interaction_required)
            self.assertEqual(
                "launcher_input_failed", self.state.readiness["launcher_detail"])
        logged.assert_called_once()

    def test_closed_launcher_without_game_terminalizes_stale_start(self):
        with self.state.lock:
            self.state.current = {
                "state": "starting", "id": GAME_ID,
                "launchRequestedAt": self.now - GAME_START_TIMEOUT - 1,
            }
            self.state.readiness = {
                "ready": False, "reason": "waiting_for_game_window",
                "target_kind": "game", "stable_samples": 0,
            }
            self.state._launcher_automation_attempted = True

        self.state.apply_window_sample({
            "qualified": False, "reason": "waiting_for_game_window"})

        self.assertEqual("failed", self.state.current["state"])
        self.assertEqual("launcher_closed_without_game", self.state.current["reason"])
        self.assertEqual(
            "launcher_closed_without_game", self.state.readiness["reason"])
        self.assertEqual("game-launch-failed", self.state.events[-1]["event"])

        self.state.apply_window_sample({
            "qualified": False, "reason": "waiting_for_game_identity"})
        self.assertEqual(
            "launcher_closed_without_game", self.state.readiness["reason"])

    def test_exited_running_game_returns_to_idle_without_connector_event(self):
        with self.state.lock:
            self.state.current = {
                "state": "running", "id": GAME_ID, "processId": 4242,
            }
            self.state.readiness = {
                "ready": True, "reason": "target_window_ready",
                "target_kind": "game", "stable_samples": 4,
            }

        self.state.apply_window_sample({
            "qualified": False, "reason": "game_process_exited",
            "process_id": 4242,
        })

        self.assertEqual({"state": "idle"}, self.state.current)
        self.assertEqual("none", self.state.readiness["target_kind"])
        self.assertEqual("game-stopped", self.state.events[-1]["event"])
        self.assertEqual(4242, self.state.events[-1]["payload"]["processId"])

    def test_uncertain_reconciliation_never_accepts_window_readiness(self):
        for state_name in ("reconciling", "ambiguous"):
            with self.subTest(state=state_name), self.state.lock:
                self.state.current = {
                    "state": state_name, "id": GAME_ID,
                    "reason": "active_game_verification_pending",
                }
                self.state.readiness = {
                    "ready": False, "reason": "active_game_verification_pending",
                    "target_kind": "game", "stable_samples": 0,
                }
            for _ in range(REQUIRED_GAME_STABLE_SAMPLES + 1):
                self.state.apply_window_sample({
                    "qualified": True, "reason": "target_window_ready",
                    "process_id": 4242, "hwnd": 17,
                    "display": r"\\.\DISPLAY1", "bounds": [0, 0, 1920, 1080],
                })
            self.assertFalse(self.state.readiness["ready"])
            self.assertEqual(0, self.state.readiness["stable_samples"])

    def test_exited_starting_game_terminalizes_launch(self):
        with self.state.lock:
            self.state.current = {
                "state": "starting", "id": GAME_ID, "processId": 4242,
            }
            self.state.readiness = {
                "ready": False, "reason": "waiting_for_game_window",
                "target_kind": "game", "stable_samples": 0,
            }

        self.state.apply_window_sample({
            "qualified": False, "reason": "game_process_exited",
            "process_id": 4242,
        })

        self.assertEqual("failed", self.state.current["state"])
        self.assertEqual(
            "game_process_exited_before_window", self.state.readiness["reason"])
        self.assertEqual("game-launch-failed", self.state.events[-1]["event"])

    def test_disappeared_launcher_confirms_action_without_shortening_game_timeout(self):
        self.state.apply_window_sample({
            "qualified": False, "reason": "launcher_action_invoked",
            "launcher_candidate": True, "launcher_action_attempted": True,
            "hwnd": 77, "process_id": 1234,
        })

        self.state.apply_window_sample({
            "qualified": False, "reason": "waiting_for_game_window"})
        self.now += LAUNCHER_POSTCONDITION_TIMEOUT + .1
        self.state.apply_window_sample({
            "qualified": False, "reason": "waiting_for_game_window"})

        with self.state.lock:
            self.assertIsNone(self.state._launcher_invoked_signature)
            self.assertFalse(self.state._launcher_interaction_required)
            self.assertEqual("waiting_for_game_window", self.state.readiness["reason"])

    def test_secondary_provider_surface_after_autoclick_requests_manual_reveal(self):
        self.state.apply_window_sample({
            "qualified": False, "reason": "launcher_action_invoked",
            "launcher_candidate": True, "launcher_action_attempted": True,
            "hwnd": 77, "process_id": 1234,
        })
        self.state.apply_window_sample({
            "qualified": False, "reason": "waiting_for_game_window"})
        secondary = {
            "qualified": False, "reason": "launcher_candidate_detected",
            "launcher_candidate": True, "hwnd": 88, "process_id": 4321,
            "image": "steamwebhelper.exe", "title": "Steam",
        }

        for _ in range(REQUIRED_LAUNCHER_STABLE_SAMPLES):
            self.state.apply_window_sample(secondary)

        with self.state.lock:
            self.assertTrue(self.state._launcher_interaction_required)
            self.assertEqual(
                "launcher_interaction_required", self.state.readiness["reason"])
            self.assertEqual(
                "provider_interaction_required",
                self.state.readiness["launcher_detail"])
        self.assertEqual(
            "launcher-interaction-required", self.state.events[-1]["event"])

    def test_running_game_ignores_late_launcher_interaction_sample(self):
        with self.state.lock:
            self.state.current = {
                "state": "running", "id": GAME_ID, "processId": 4242,
            }
            self.state.readiness = {
                "ready": False, "reason": "waiting_for_game_window",
                "target_kind": "game", "stable_samples": 0,
            }
            before_events = list(self.state.events)

        self.state.apply_window_sample({
            "qualified": False, "reason": "launcher_interaction_required",
            "launcher_candidate": True, "launcher_action_attempted": True,
            "hwnd": 88, "process_id": 4321,
            "launcher_detail": "provider_interaction_required",
        })

        self.assertEqual("running", self.state.current["state"])
        self.assertEqual("waiting_for_game_window", self.state.readiness["reason"])
        self.assertFalse(self.state._launcher_interaction_required)
        self.assertEqual(before_events, list(self.state.events))

    def _start_direct_steam(self, installed=False, operation="install"):
        self._put_steam_game(self.state, GAME_ID, "224760", "FEZ", installed)
        return self._dispatch_direct_steam(GAME_ID, operation)

    @staticmethod
    def _put_steam_game(state, game_id, app_id, name, installed):
        with state.lock:
            state.library[game_id] = {
                "id": game_id, "name": name, "installed": installed,
                "provider": "steam", "providerGameId": app_id,
                "playniteGameId": game_id,
                "libraryKey": "steam", "libraryName": "Steam",
                "capabilities": {"launch": True, "install": True, "uninstall": True},
            }
            session = state.installations.get(game_id)
            if session is not None:
                session.setdefault("baseline", {})["game"] = dict(state.library[game_id])
            state._apply_installation_fields_locked(game_id, state.library[game_id])

    def _dispatch_direct_steam(self, game_id, operation):
        with mock.patch.object(
                self.state.game_operations.steam, "_direct_dispatch",
                return_value={
                    "accepted": True, "command": operation,
                    "provider": "steam", "dispatch": "direct",
                }):
            return self.state.install_game(game_id) if operation == "install" \
                else self.state.uninstall_game(game_id)

    def test_supplied_game_operations_service_owns_the_single_journal(self):
        service = GameOperationsService(OperationJournal(None))
        state = BridgeState(game_operations=service)
        self.assertIs(service, state.game_operations)
        self.assertIs(service.journal, state.operation_journal)

    def test_start_is_allowlisted_and_closes_privacy_gate(self):
        with self.state.lock:
            self.state.library[GAME_ID] = {
                "id": GAME_ID, "name": "Game", "provider": "playnite",
                "providerGameId": GAME_ID, "playniteGameId": GAME_ID,
            }
        result = self.state.start_game(GAME_ID.upper())

        self.assertTrue(result["accepted"])
        self.assertEqual({
            "type": "command", "command": "launch", "id": GAME_ID,
        }, self.commands[-1])
        self.assertFalse(self.state.readiness["ready"])
        self.assertEqual("game_starting", self.state.readiness["reason"])
        with self.assertRaises(ValueError):
            self.state.start_game("../../cmd.exe")

    def test_different_game_is_not_dispatched_while_current_game_is_active(self):
        other_id = "65705ca9-b9c7-4ada-b4b7-f73ffb8ac64f"
        with self.state.lock:
            self.state.library[other_id] = {
                "id": other_id, "name": "Other", "provider": "playnite",
                "providerGameId": other_id,
            }
            self.state.current = {
                "state": "running", "id": GAME_ID, "processId": 4242,
            }

        result = self.state.start_game(other_id)

        self.assertFalse(result["accepted"])
        self.assertEqual("another_game_running", result["reason"])
        self.assertEqual(GAME_ID, result["active_game_id"])
        self.assertEqual([], self.commands)
        self.assertEqual(GAME_ID, self.state.current["id"])

    def test_repeated_start_of_current_game_resumes_without_dispatch(self):
        with self.state.lock:
            self.state.library[GAME_ID] = {
                "id": GAME_ID, "name": "Game", "provider": "playnite",
                "providerGameId": GAME_ID,
            }
            self.state.current = {
                "state": "running", "id": GAME_ID, "processId": 4242,
            }

        result = self.state.start_game(GAME_ID)

        self.assertTrue(result["accepted"])
        self.assertTrue(result["already_running"])
        self.assertEqual("resume", result["command"])
        self.assertEqual([], self.commands)

    def test_stale_window_sample_cannot_claim_the_next_game(self):
        next_id = "65705ca9-b9c7-4ada-b4b7-f73ffb8ac64f"
        with self.state.lock:
            self.state.current = {"state": "starting", "id": next_id}
            self.state.readiness = {
                "ready": False, "reason": "game_starting",
                "target_kind": "game", "stable_samples": 0,
            }

        self.state.apply_window_sample({
            "qualified": True,
            "reason": "stabilizing_target_window",
            "observed_game_id": GAME_ID,
            "process_id": 4242,
        })

        self.assertEqual({"state": "starting", "id": next_id}, self.state.current)
        self.assertEqual("game_starting", self.state.readiness["reason"])

    def test_epic_record_resolution_requires_exact_legendary_app_name(self):
        with self.state.lock:
            self.state.library["epic:CelesteApp"] = {
                "id": "epic:CelesteApp", "name": "Celeste", "provider": "epic",
                "providerGameId": "CelesteApp",
            }

        self.assertEqual(
            "epic:CelesteApp", self.state.resolve_game_id("epic:CelesteApp"))
        self.assertEqual(
            "epic:celesteapp", self.state.resolve_game_id("epic:celesteapp"))
        with self.assertRaises(FileNotFoundError):
            self.state.start_game("epic:celesteapp")

    def test_playnite_status_is_advisory_for_direct_provider_launch_state(self):
        record_id = "steam:224760"
        with self.state.lock:
            self.state.library[record_id] = {
                "id": record_id, "name": "FEZ", "provider": "steam",
                "providerGameId": "224760", "playniteGameId": GAME_ID,
            }
            self.state.playnite_library[GAME_ID] = {
                "id": GAME_ID, "source": "Steam", "providerGameId": "224760",
            }
            self.state.current = {
                "state": "running", "id": record_id, "processId": 4242,
            }
            self.state.readiness = {
                "ready": True, "reason": "target_window_ready",
                "target_kind": "game", "stable_samples": 4,
            }

        self.state.handle_message({
            "type": "status", "status": {"name": "gameStopped", "id": GAME_ID},
        })

        self.assertEqual("running", self.state.current["state"])
        self.assertEqual(record_id, self.state.current["id"])
        self.assertTrue(self.state.readiness["ready"])
        self.assertEqual("playnite-status", self.state.events[-1]["event"])

    def test_steam_start_keeps_library_identity_before_game_started_event(self):
        install_dir = r"E:\Steam\steamapps\common\Sid Meier's Civilization V"
        self._put_steam_game(self.state, GAME_ID, "8930",
                             "Sid Meier's Civilization V", True)
        self.state.library[GAME_ID]["installDir"] = install_dir
        self.state.game_operations.launch = mock.Mock(return_value={
            "accepted": True, "provider": "steam", "dispatch": "direct",
            "install_directory": install_dir,
        })

        result = self.state.start_game(GAME_ID)

        self.assertTrue(result["accepted"])
        self.assertEqual("starting", self.state.current["state"])
        self.assertEqual(GAME_ID, self.state.current["id"])
        self.assertEqual(install_dir, self.state.current["installDir"])
        self.assertEqual("Sid Meier's Civilization V", self.state.current["title"])
        self.assertEqual("Steam", self.state.current["source"])

    def test_failed_start_dispatch_rolls_back_provisional_game_identity(self):
        self.state.handle_message({"type": "games", "payload": [{
            "id": GAME_ID, "name": "Sid Meier's Civilization V",
            "installed": True,
        }]})
        self.state.set_transport(False, None, "offline")

        with self.assertRaises(ConnectionError):
            self.state.start_game(GAME_ID)

        self.assertEqual({"state": "idle"}, self.state.current)
        self.assertEqual("waiting_for_game_identity", self.state.readiness["reason"])
        self.assertFalse(self.state._launcher_automation_attempted)

    def test_external_completion_does_not_leave_preparing_in_cached_game(self):
        self.state.handle_message({"type": "games", "payload": [{
            "id": GAME_ID, "name": "Defense Grid", "installed": False,
        }]})
        self.state.install_game(GAME_ID)

        self.state.apply_installation_probe(GAME_ID, {
            "installed": True, "provider": "epic", "progress": 100,
            "install_directory": r"E:\\Games\\DefenseGrid",
        })

        current = self.state.library_page("0", 10)["games"][0]
        self.assertFalse(current["installing"])
        self.assertEqual("", current["operationState"])



    def test_status_tracks_lifecycle_without_revealing_desktop(self):
        self._put_native_game()
        self.state.handle_message({
            "type": "status",
            "status": {"name": "gameStarted", "id": GAME_ID, "title": "Baba Is You",
                       "processId": 4242},
        })

        self.assertEqual("running", self.state.current["state"])
        self.assertFalse(self.state.readiness["ready"])
        self.assertEqual("waiting_for_game_window", self.state.readiness["reason"])
        self.assertEqual(4242, self.state.current["processId"])

        self.state.handle_message({
            "type": "status",
            "status": {"name": "gameStopped", "id": GAME_ID},
        })
        self.assertEqual("idle", self.state.current["state"])
        self.assertFalse(self.state.readiness["ready"])
        self.assertEqual("game_stopped", self.state.readiness["reason"])
        self.assertEqual("none", self.state.readiness["target_kind"])

    def test_library_batches_are_paged(self):
        self.state.handle_message({"type": "plugins", "payload": [{"id": "steam"}]})
        self.state.handle_message({
            "type": "games",
            "payload": [
                {"id": GAME_ID, "name": "Baba Is You", "installed": True},
                {"id": "65705ca9-b9c7-4ada-b4b7-f73ffb8ac64f",
                 "name": "Resident Evil 3", "installed": True,
                 "Playtime": 7500, "LastActivity": "2026-07-15T20:10:00Z",
                 "PlayCount": 14,
                 "Source": "GOG",
                 "Description": "<b>Escape the city.</b><br>Survive Nemesis."},
            ],
        })

        first = self.state.library_page("0", 1)
        second = self.state.library_page(first["next_cursor"], 1)
        self.assertEqual("Baba Is You", first["games"][0]["name"])
        self.assertEqual("Resident Evil 3", second["games"][0]["name"])
        self.assertEqual(125, second["games"][0]["playtimeMinutes"])
        self.assertEqual("2026-07-15T20:10:00Z", second["games"][0]["lastPlayed"])
        self.assertEqual("Escape the city.\nSurvive Nemesis.",
                         second["games"][0]["description"])
        self.assertEqual(14, second["games"][0]["playCount"])
        self.assertEqual("GOG", second["games"][0]["source"])
        self.assertEqual("", second["next_cursor"])

    def test_complete_snapshot_is_loaded_from_disk_after_restart(self):
        with tempfile.TemporaryDirectory() as temporary:
            cache_path = Path(temporary) / "library-cache.json"
            operations = GameOperationsService(
                OperationJournal(None), steam=SteamProvider(roots=[]))
            state = BridgeState(cache_path=cache_path, game_operations=operations)
            state.handle_message({"type": "snapshotStart"})
            state.handle_message({"type": "plugins", "payload": [{"id": "steam"}]})
            state.handle_message({"type": "categories", "payload": [{"id": "action"}]})
            state.handle_message({"type": "games", "payload": [{
                "id": GAME_ID, "name": "Baba Is You", "installed": True,
            }]})
            self.assertFalse(cache_path.exists())
            state.handle_message({"type": "snapshotComplete", "payload": {"games": 1}})
            for _ in range(100):
                with state.lock:
                    if not state.catalog_refresh_inflight:
                        break
                time.sleep(.005)

            restored = BridgeState(cache_path=cache_path)
            page = restored.library_page("0", 10)
            self.assertEqual(1, page["total"])
            self.assertEqual("Baba Is You", page["games"][0]["name"])
            self.assertEqual([{"id": "action"}], page["categories"])
            self.assertEqual([{"id": "steam"}], page["plugins"])

    def test_pending_installation_is_restored_from_operation_journal(self):
        with tempfile.TemporaryDirectory() as temporary:
            operation_path = Path(temporary) / "operations.sqlite3"
            state = BridgeState(operations_path=operation_path)
            state.set_transport(True, lambda _message: None)
            game = {"id": GAME_ID, "name": "FEZ", "installed": False,
                    "source": "Steam", "providerGameId": "224760"}
            self._put_steam_game(state, GAME_ID, "224760", "FEZ", False)
            with mock.patch.object(
                    state.game_operations.steam, "_direct_dispatch",
                    return_value={"accepted": True, "command": "install",
                                  "provider": "steam", "dispatch": "direct"}):
                state.install_game(GAME_ID)

            restored = BridgeState(operations_path=operation_path)
            self._put_steam_game(restored, GAME_ID, "224760", "FEZ", False)
            current = restored.library_page("0", 10)["games"][0]
            self.assertTrue(current["installing"])
            self.assertEqual("preparing", current["operationState"])



    def test_restored_steam_download_resumes_from_manifest_evidence(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            steamapps = root / "steamapps"
            steamapps.mkdir()
            (steamapps / "appmanifest_224760.acf").write_text('''"AppState"
{
    "StateFlags" "1026"
    "installdir" "FEZ"
    "BytesDownloaded" "50"
    "BytesToDownload" "100"
}''', encoding="utf-8")
            operation_path = root / "operations.sqlite3"
            journal = OperationJournal(operation_path)
            journal.begin(GAME_ID, "install", "steam", "FEZ")
            journal.update(
                GAME_ID, "attention_required", detail="launcher_prompt",
                window_handle=987, window_title="Install", launcher="steam.exe")
            runner = mock.Mock()
            service = GameOperationsService(
                OperationJournal(operation_path),
                steam=SteamProvider(
                    roots=[root], root_resolver=lambda: root,
                    command_runner=runner))
            restored = BridgeState(
                game_operations=service, clock=lambda: self.now)
            commands = []
            restored.set_transport(True, commands.append)
            restored.handle_message({"type": "games", "payload": [{
                "id": GAME_ID, "name": "FEZ", "installed": False,
                "source": "Steam", "providerGameId": "224760",
            }]})
            self._put_steam_game(restored, GAME_ID, "224760", "FEZ", False)
            session = restored.installations[GAME_ID]
            token = session["token"]

            restored.apply_installation_probe(
                GAME_ID, service.sample(session["baseline"]), token)

            operation = restored.operation_journal.get(GAME_ID)
            self.assertEqual("downloading", operation["state"])
            self.assertEqual(50, operation["progress"])
            self.assertEqual(0, operation["window_handle"])
            self.assertNotEqual("confirmation_window_expired", operation["detail"])
            self.assertFalse(restored.library[GAME_ID]["installRequiresAttention"])
            runner.assert_not_called()
            self.assertEqual([], commands)

    def test_restored_inactive_steam_reissues_direct_command_once(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            executable = root / "steam.exe"
            executable.touch()
            (root / "steamapps").mkdir()
            operation_path = root / "operations.sqlite3"
            journal = OperationJournal(operation_path)
            journal.begin(GAME_ID, "install", "steam", "FEZ")
            runner = mock.Mock()
            service = GameOperationsService(
                OperationJournal(operation_path),
                steam=SteamProvider(
                    roots=[root], root_resolver=lambda: root,
                    command_runner=runner))
            restored = BridgeState(
                game_operations=service, clock=lambda: self.now)
            commands = []
            restored.set_transport(True, commands.append)
            restored.handle_message({"type": "games", "payload": [{
                "id": GAME_ID, "name": "FEZ", "installed": False,
                "source": "Steam", "providerGameId": "224760",
            }]})
            self._put_steam_game(restored, GAME_ID, "224760", "FEZ", False)
            session = restored.installations[GAME_ID]
            token = session["token"]

            restored.apply_installation_probe(
                GAME_ID, service.sample(session["baseline"]), token)
            current = restored.installations[GAME_ID]
            restored.apply_installation_probe(
                GAME_ID, service.sample(current["baseline"]), token)

            runner.assert_called_once()
            self.assertEqual([
                str(executable.resolve()), "-silent", "+app_install", "224760",
            ], runner.call_args.args[0])
            self.assertEqual([], commands)

    def test_restored_complete_steam_install_reissues_uninstall(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            executable = root / "steam.exe"
            executable.touch()
            steamapps = root / "steamapps"
            (steamapps / "common" / "FEZ").mkdir(parents=True)
            (steamapps / "appmanifest_224760.acf").write_text('''"AppState"
{
    "StateFlags" "4"
    "installdir" "FEZ"
    "BytesDownloaded" "100"
    "BytesToDownload" "100"
}''', encoding="utf-8")
            operation_path = root / "operations.sqlite3"
            journal = OperationJournal(operation_path)
            journal.begin(GAME_ID, "uninstall", "steam", "FEZ")
            journal.update(GAME_ID, "uninstalling")
            runner = mock.Mock()
            service = GameOperationsService(
                OperationJournal(operation_path),
                steam=SteamProvider(
                    roots=[root], root_resolver=lambda: root,
                    command_runner=runner))
            restored = BridgeState(
                game_operations=service, clock=lambda: self.now)
            restored.set_transport(True, mock.Mock())
            restored.handle_message({"type": "games", "payload": [{
                "id": GAME_ID, "name": "FEZ", "installed": True,
                "source": "Steam", "providerGameId": "224760",
            }]})
            self._put_steam_game(restored, GAME_ID, "224760", "FEZ", True)
            session = restored.installations[GAME_ID]
            token = session["token"]

            sample = service.sample(session["baseline"])
            restored.apply_installation_probe(GAME_ID, sample, token)

            self.assertFalse(sample["started"])
            self.assertFalse(restored.installations[GAME_ID]["primary_started"])
            runner.assert_called_once()
            self.assertEqual([
                str(executable.resolve()), "-silent", "+app_uninstall", "224760",
            ], runner.call_args.args[0])


    def test_bracketed_snapshot_keeps_previous_library_until_complete(self):
        self.state.handle_message({"type": "games", "payload": [{
            "id": GAME_ID, "name": "Old game", "installed": True,
        }]})
        previous_revision = self.state.library_page("0", 10)["revision"]
        self.state.handle_message({"type": "snapshotStart"})
        self.state.handle_message({"type": "plugins", "payload": []})
        self.state.handle_message({"type": "games", "payload": [{
            "id": "65705ca9-b9c7-4ada-b4b7-f73ffb8ac64f",
            "name": "New game", "installed": True,
        }]})
        before_complete = self.state.library_page("0", 10)
        self.assertEqual(["Old game"], [game["name"] for game in before_complete["games"]])
        self.assertEqual(previous_revision, before_complete["revision"])

        self.state.handle_message({"type": "snapshotComplete"})
        completed = self.state.library_page("0", 10)
        self.assertEqual(["New game"], [game["name"] for game in completed["games"]])
        self.assertNotEqual(previous_revision, completed["revision"])

    def test_refresh_requests_new_snapshot_without_clearing_library(self):
        self.state.handle_message({"type": "games", "payload": [{
            "id": GAME_ID, "name": "Baba Is You", "installed": True,
        }]})
        result = self.state.refresh_library()
        self.assertTrue(result["accepted"])
        self.assertEqual(self.state.library_page("0", 10)["revision"],
                         result["previous_revision"])
        self.assertEqual({"type": "command", "command": "snapshot"}, self.commands[-1])

    def test_installation_prompt_is_stabilized_published_and_focusable(self):
        self.state.handle_message({"type": "games", "payload": [{
            "id": GAME_ID, "name": "Baba Is You", "installed": False,
        }]})
        self._dispatch_direct_steam(GAME_ID, "install")
        sample = {"requires_attention": True, "reason": "launcher_prompt",
                  "hwnd": 77, "process_id": 123, "title": "Choose install location",
                  "image": "futurelauncher.exe"}
        for _ in range(3):
            self.state.apply_installation_probe(GAME_ID, sample)
        game = self.state.library_page("0", 10)["games"][0]
        self.assertTrue(game["installRequiresAttention"])
        self.assertEqual("Choose install location", game["installWindowTitle"])
        self.assertIn("game-installation-attention-required",
                      [event["event"] for event in self.state.events])
        self.state.handle_message({"type": "games", "payload": [{
            "id": GAME_ID, "name": "Baba Is You", "installed": False,
            "installing": False,
        }]})
        refreshed = self.state.library_page("0", 10)["games"][0]
        self.assertTrue(refreshed["installing"])
        self.assertTrue(refreshed["installRequiresAttention"])
        result = self.state.focus_installation(GAME_ID)
        self.assertTrue(result["accepted"])
        self.assertEqual([77], self.installation_focus_calls)

        # "Done" is an explicit acknowledgement. Some launchers (notably Epic)
        # keep the same top-level HWND after their embedded prompt is confirmed.
        self.state.installation_probe_action = lambda baseline: {
            "requires_attention": True, "reason": "launcher_prompt",
            "hwnd": 77, "process_id": 123, "title": "Choose install location",
            "image": "futurelauncher.exe",
        }
        verified = self.state.verify_installation(GAME_ID)
        self.assertFalse(verified["requires_attention"])
        self.assertEqual("installing", verified["status"])
        resumed = self.state.library_page("0", 10)["games"][0]
        self.assertFalse(resumed["installRequiresAttention"])

        for _ in range(3):
            self.state.apply_installation_probe(
                GAME_ID, self.state.installation_probe_action({}))
        self.assertFalse(self.state.library_page(
            "0", 10)["games"][0]["installRequiresAttention"])

        self.state.handle_message({"type": "status", "status": {
            "name": "gameInstalled", "id": GAME_ID,
        }})
        completed = self.state.library_page("0", 10)["games"][0]
        self.assertTrue(completed["installed"])
        self.assertFalse(completed["installRequiresAttention"])

    def test_known_steam_dialog_is_auto_confirmed_before_attention_fallback(self):
        confirmations = []
        self.state.game_operations.confirm_operation = \
            lambda _game, hwnd, operation, name, _visual: (
            confirmations.append((hwnd, operation, name)) or {"clicked": True})
        self._put_steam_game(self.state, GAME_ID, "224760", "FEZ", False)
        self._dispatch_direct_steam(GAME_ID, "install")
        sample = {"requires_attention": True, "reason": "launcher_prompt",
                  "hwnd": 77, "process_id": 123, "title": "Install - FEZ",
                  "image": "steam.exe"}
        for _ in range(3):
            self.state.apply_installation_probe(GAME_ID, sample)
        for _ in range(20):
            if confirmations and self.state.events[-1]["event"] == \
                    "game-installation-auto-confirmed":
                break
            time.sleep(.01)
        self.assertEqual([(77, "install", "FEZ")], confirmations)
        self.assertFalse(self.state.library_page(
            "0", 10)["games"][0]["installRequiresAttention"])
        self.assertEqual("game-installation-auto-confirmed", self.state.events[-1]["event"])
        self.assertIn("steam_automation_succeeded",
                      [event for event, _payload in self.audit])
        self.assertNotIn("steam_playnite_fallback_dispatched",
                         [event for event, _payload in self.audit])

    def test_steam_activity_prevents_primary_timeout_fallback(self):
        self._start_direct_steam()
        token = self.state.installations[GAME_ID]["token"]
        self.state.apply_installation_probe(GAME_ID, {
            "provider": "steam", "started": True, "phase": "active_install",
            "requires_attention": False, "progress": 10,
        }, token)
        self.now += STEAM_PRIMARY_START_TIMEOUT + 1

        self.state.apply_installation_probe(GAME_ID, {
            "provider": "steam", "started": False, "phase": "not_started",
            "requires_attention": False,
        }, token)

        self.assertEqual("downloading", self.state.operation_journal.get(GAME_ID)["state"])
        self.assertFalse(any(command.get("command") == "install"
                             for command in self.commands))

    def test_steam_audit_proves_direct_activity_and_authoritative_completion(self):
        self._start_direct_steam()
        token = self.state.installations[GAME_ID]["token"]
        self.state.apply_installation_probe(GAME_ID, {
            "provider": "steam", "started": True, "phase": "active_install",
            "requires_attention": False, "progress": 15,
            "manifest_present": True, "scan_complete": True,
        }, token)
        self.state.apply_installation_probe(GAME_ID, {
            "provider": "steam", "installed": True,
            "phase": "completed_install", "progress": 100,
            "install_directory": r"E:\Games\FEZ",
        }, token)

        self.assertEqual([
            "steam_direct_dispatched",
            "steam_activity_observed",
            "steam_authoritative_completion",
        ], [event for event, _payload in self.audit])
        self.assertEqual("direct", self.audit[1][1]["dispatch_path"])
        self.assertEqual("installed", self.audit[2][1]["outcome"])
        self.assertTrue(all(payload["requested_at"] == token[2]
                            for _event, payload in self.audit))

    def test_primary_timeout_requires_attention_without_playnite_fallback(self):
        baselines = mock.Mock(return_value={"capture": "primary"})
        self.state.installation_baseline_action = baselines
        self._start_direct_steam()
        token = self.state.installations[GAME_ID]["token"]
        no_evidence = {
            "provider": "steam", "started": False, "phase": "not_started",
            "requires_attention": False,
        }
        self.now += STEAM_PRIMARY_START_TIMEOUT + 1

        self.state.apply_installation_probe(GAME_ID, no_evidence, token)

        fallbacks = [command for command in self.commands
                     if command.get("command") == "install"]
        self.assertEqual([], fallbacks)
        self.assertEqual(1, baselines.call_count)
        self.assertNotIn("steam_playnite_fallback_dispatched",
                         [event for event, _payload in self.audit])
        current = self.state.library_page("0", 10)["games"][0]
        self.assertTrue(current["installRequiresAttention"])
        self.assertEqual("steam_operation_not_started",
                         current["installAttentionReason"])
        self.assertEqual("steam.exe", current["installLauncher"])

    def test_failed_steam_automation_exposes_manual_fallback(self):
        self.state.game_operations.confirm_operation = mock.Mock(return_value={
            "clicked": False, "reason": "automation_failed",
        })
        self._put_steam_game(self.state, GAME_ID, "224760", "FEZ", False)
        self._dispatch_direct_steam(GAME_ID, "install")
        sample = {
            "requires_attention": True, "reason": "launcher_prompt",
            "hwnd": 77, "process_id": 123, "title": "Install",
            "image": "steam.exe",
        }
        for _ in range(3):
            self.state.apply_installation_probe(GAME_ID, sample)
        for _ in range(100):
            if self.state.installations[GAME_ID].get("automation_failure"):
                break
            time.sleep(.005)
        for _ in range(3):
            self.state.apply_installation_probe(GAME_ID, sample)

        operation = self.state.operation_journal.get(GAME_ID)
        self.assertEqual("attention_required", operation["state"])
        self.assertIn("automation=automation_failed", operation["detail"])
        self.assertEqual("steam.exe", operation["launcher"])
        self.assertIn("steam_automation_failed",
                      [event for event, _payload in self.audit])
        self.assertIn("steam_attention_required",
                      [event for event, _payload in self.audit])

    def test_stale_steam_auto_confirmation_result_is_ignored(self):
        release = threading.Event()
        started = threading.Event()

        def confirm(_game, _hwnd, _operation, _name, _visual):
            started.set()
            release.wait(1)
            return {"clicked": True}

        self.state.game_operations.confirm_operation = confirm
        self._put_steam_game(self.state, GAME_ID, "224760", "FEZ", False)
        self._dispatch_direct_steam(GAME_ID, "install")
        sample = {
            "requires_attention": True, "reason": "launcher_prompt",
            "hwnd": 77, "process_id": 123, "title": "Install",
            "image": "steam.exe",
        }
        for _ in range(3):
            self.state.apply_installation_probe(GAME_ID, sample)
        self.assertTrue(started.wait(1))
        old_token = self.state.installations[GAME_ID]["token"]
        self.state.operation_journal.update(GAME_ID, "completed")
        time.sleep(.002)
        newer = self.state.operation_journal.begin(
            GAME_ID, "install", "steam", "FEZ")
        new_token = self.state.operation_token(newer)
        self.assertNotEqual(old_token, new_token)
        self.state.installations[GAME_ID]["token"] = new_token
        release.set()
        for _ in range(100):
            if not any(thread.name == "LauncherOperationConfirmation"
                       for thread in threading.enumerate()):
                break
            time.sleep(.005)

        self.assertEqual("preparing", self.state.operation_journal.get(GAME_ID)["state"])
        self.assertFalse(any(event["event"] == "game-installation-auto-confirmed"
                             for event in self.state.events))
        self.assertNotIn("steam_automation_succeeded",
                         [event for event, _payload in self.audit])

    def test_steam_attention_is_hidden_while_automation_is_running(self):
        release = threading.Event()
        started = threading.Event()
        def confirm(_game, _hwnd, _operation, _name, _visual):
            started.set()
            release.wait(1)
            return {"clicked": True}
        self.state.game_operations.confirm_operation = confirm
        self._put_steam_game(self.state, GAME_ID, "212680", "FTL", False)
        self._dispatch_direct_steam(GAME_ID, "install")
        sample = {"requires_attention": True, "reason": "launcher_prompt",
                  "hwnd": 77, "process_id": 123, "title": "Steam",
                  "image": "steamwebhelper.exe"}
        for _ in range(3):
            self.state.apply_installation_probe(GAME_ID, sample)
        self.assertTrue(started.wait(1))
        for _ in range(5):
            self.state.apply_installation_probe(GAME_ID, sample)
        self.assertFalse(self.state.library_page(
            "0", 10)["games"][0]["installRequiresAttention"])
        release.set()

    def test_installed_steam_game_uninstall_dialog_is_auto_confirmed(self):
        confirmations = []
        self.state.game_operations.confirm_operation = \
            lambda _game, hwnd, operation, name, _visual: (
            confirmations.append((hwnd, operation, name)) or {"clicked": True})
        self._put_steam_game(self.state, GAME_ID, "212680", "FTL", True)
        self._dispatch_direct_steam(GAME_ID, "uninstall")
        sample = {"requires_attention": True, "reason": "launcher_prompt",
                  "hwnd": 88, "process_id": 456, "title": "Odinstaluj",
                  "image": "steamwebhelper.exe"}
        for _ in range(3):
            self.state.apply_installation_probe(GAME_ID, sample)
        for _ in range(100):
            if confirmations and self.state.events[-1]["event"] == \
                    "game-installation-auto-confirmed":
                break
            time.sleep(.005)
        self.assertEqual([(88, "uninstall", "FTL")], confirmations)
        self.assertEqual("game-installation-auto-confirmed", self.state.events[-1]["event"])

    def test_steam_uninstall_requires_stable_healthy_manifest_absence(self):
        self._start_direct_steam(installed=True, operation="uninstall")
        token = self.state.installations[GAME_ID]["token"]
        absent = {
            "uninstalled": True, "provider": "steam",
            "scan_available": True, "scan_complete": True,
        }
        for _ in range(REQUIRED_STABLE_SAMPLES - 1):
            self.state.apply_installation_probe(GAME_ID, absent, token)
        self.assertTrue(self.state.library[GAME_ID]["installed"])

        self.state.apply_installation_probe(GAME_ID, absent, token)

        self.assertFalse(self.state.library[GAME_ID]["installed"])
        self.assertEqual("completed", self.state.operation_journal.get(GAME_ID)["state"])
        completions = [payload for event, payload in self.audit
                       if event == "steam_authoritative_completion"]
        self.assertEqual(1, len(completions))
        self.assertEqual("uninstalled", completions[0]["outcome"])
        self.assertEqual(REQUIRED_STABLE_SAMPLES,
                         completions[0]["stable_absence_samples"])

    def test_incomplete_steam_scan_does_not_complete_uninstall(self):
        self._start_direct_steam(installed=True, operation="uninstall")
        token = self.state.installations[GAME_ID]["token"]

        self.state.apply_installation_probe(GAME_ID, {
            "provider": "steam", "started": False, "phase": "scan_incomplete",
            "scan_available": True, "scan_complete": False,
            "requires_attention": False,
        }, token)

        self.assertTrue(self.state.library[GAME_ID]["installed"])
        self.assertEqual("uninstalling", self.state.operation_journal.get(GAME_ID)["state"])
        self.assertNotIn("steam_authoritative_completion",
                         [event for event, _payload in self.audit])

    def test_delayed_game_installed_cannot_reverse_authoritative_steam_uninstall(self):
        self._start_direct_steam(installed=True, operation="uninstall")
        token = self.state.installations[GAME_ID]["token"]
        absent = {
            "uninstalled": True, "provider": "steam",
            "scan_available": True, "scan_complete": True,
        }
        for _ in range(REQUIRED_STABLE_SAMPLES):
            self.state.apply_installation_probe(GAME_ID, absent, token)

        self.state.handle_message({
            "type": "status",
            "status": {"name": "gameInstalled", "id": GAME_ID},
        })

        self.assertFalse(self.state.library[GAME_ID]["installed"])
        operation = self.state.operation_journal.get(GAME_ID)
        self.assertEqual("uninstall", operation["kind"])
        self.assertEqual("completed", operation["state"])

    def test_delayed_cancellation_cannot_reverse_authoritative_steam_install(self):
        self._start_direct_steam()
        token = self.state.installations[GAME_ID]["token"]
        self.state.apply_installation_probe(GAME_ID, {
            "installed": True, "provider": "steam", "phase": "completed_install",
            "install_directory": r"E:\Games\FEZ",
        }, token)

        self.state.handle_message({
            "type": "status",
            "status": {"name": "gameInstallationCancelled", "id": GAME_ID},
        })

        self.assertTrue(self.state.library[GAME_ID]["installed"])
        operation = self.state.operation_journal.get(GAME_ID)
        self.assertEqual("install", operation["kind"])
        self.assertEqual("completed", operation["state"])

    def test_delayed_old_playnite_event_cannot_mutate_newer_steam_generation(self):
        self._start_direct_steam()
        old_token = self.state.installations[GAME_ID]["token"]
        self.state.apply_installation_probe(GAME_ID, {
            "installed": True, "provider": "steam", "phase": "completed_install",
            "install_directory": r"E:\Games\FEZ",
        }, old_token)
        self._dispatch_direct_steam(GAME_ID, "uninstall")
        new_token = self.state.installations[GAME_ID]["token"]
        self.assertNotEqual(old_token, new_token)

        self.state.handle_message({
            "type": "status",
            "status": {"name": "gameInstalled", "id": GAME_ID},
        })
        self.state.handle_message({
            "type": "status",
            "status": {"name": "gameInstallationCancelled", "id": GAME_ID},
        })

        operation = self.state.operation_journal.get(GAME_ID)
        self.assertEqual("uninstall", operation["kind"])
        self.assertEqual(new_token, self.state.installations[GAME_ID]["token"])
        self.assertNotIn("cancellation_token", self.state.installations[GAME_ID])

    def test_generic_connector_event_mutates_truth_but_epic_event_is_advisory(self):
        self.state.handle_message({"type": "games", "payload": [
            {"id": GAME_ID, "name": "Baba Is You", "installed": False},
            {"id": SECOND_GAME_ID, "name": "Celeste", "installed": False,
             "source": "Epic", "providerGameId": "celeste-app"},
        ]})

        self.state.handle_message({
            "type": "status",
            "status": {"name": "gameInstalled", "id": GAME_ID},
        })
        self.state.handle_message({
            "type": "status",
            "status": {"name": "gameInstalled", "id": SECOND_GAME_ID},
        })

        self.assertTrue(self.state.library[GAME_ID]["installed"])
        self.assertNotIn(SECOND_GAME_ID, self.state.library)

    def test_prestart_steam_uninstall_blocks_another_launcher_operation(self):
        self._put_steam_game(self.state, GAME_ID, "224760", "FEZ", True)
        self._put_steam_game(self.state, SECOND_GAME_ID, "212680", "FTL", False)
        self._dispatch_direct_steam(GAME_ID, "uninstall")

        with self.assertRaisesRegex(RuntimeError, "awaiting confirmation"):
            self._dispatch_direct_steam(SECOND_GAME_ID, "install")

    def test_started_steam_operation_allows_another_launcher_operation(self):
        self._put_steam_game(self.state, GAME_ID, "224760", "FEZ", True)
        self._put_steam_game(self.state, SECOND_GAME_ID, "212680", "FTL", False)
        self._dispatch_direct_steam(GAME_ID, "uninstall")
        token = self.state.installations[GAME_ID]["token"]
        self.state.apply_installation_probe(GAME_ID, {
            "provider": "steam", "started": True, "phase": "active_uninstall",
            "scan_available": True, "scan_complete": True,
            "requires_attention": False,
        }, token)

        result = self._dispatch_direct_steam(SECOND_GAME_ID, "install")

        self.assertEqual("direct", result["dispatch"])

    def test_started_steam_install_cancels_after_stable_healthy_inactivity(self):
        self._start_direct_steam()
        token = self.state.installations[GAME_ID]["token"]
        self.state.apply_installation_probe(GAME_ID, {
            "provider": "steam", "started": True, "phase": "active_install",
            "scan_available": True, "scan_complete": True,
            "requires_attention": False, "progress": 25,
        }, token)
        self.state.handle_message({
            "type": "status",
            "status": {"name": "gameInstallationCancelled", "id": GAME_ID},
        })
        self.state.apply_installation_probe(GAME_ID, {
            "provider": "steam", "started": True, "phase": "active_install",
            "scan_available": True, "scan_complete": True,
            "requires_attention": False, "progress": 25,
        }, token)
        inactive = {
            "provider": "steam", "started": False, "phase": "not_started",
            "scan_available": True, "scan_complete": True,
            "manifest_present": False, "requires_attention": False,
        }
        for _ in range(REQUIRED_STABLE_SAMPLES):
            self.state.apply_installation_probe(GAME_ID, inactive, token)

        self.assertEqual("cancelled", self.state.operation_journal.get(GAME_ID)["state"])
        self.assertNotIn(GAME_ID, self.state.installations)
        self.assertFalse(self.state.library[GAME_ID]["installing"])

    def test_started_steam_uninstall_cancels_when_game_remains_installed(self):
        self._start_direct_steam(installed=True, operation="uninstall")
        token = self.state.installations[GAME_ID]["token"]
        self.state.apply_installation_probe(GAME_ID, {
            "provider": "steam", "started": True, "phase": "active_uninstall",
            "scan_available": True, "scan_complete": True,
            "manifest_present": True, "requires_attention": False,
        }, token)
        self.state.handle_message({
            "type": "status",
            "status": {"name": "gameInstallationCancelled", "id": GAME_ID},
        })
        inactive = {
            "provider": "steam", "started": False, "phase": "not_started",
            "scan_available": True, "scan_complete": True,
            "manifest_present": True, "requires_attention": False,
        }
        for _ in range(REQUIRED_STABLE_SAMPLES):
            self.state.apply_installation_probe(GAME_ID, inactive, token)

        self.assertEqual("cancelled", self.state.operation_journal.get(GAME_ID)["state"])
        self.assertTrue(self.state.library[GAME_ID]["installed"])
        self.assertFalse(self.state.library[GAME_ID]["uninstalling"])

    def test_steam_cancellation_racing_authoritative_completion_completes(self):
        self._start_direct_steam()
        token = self.state.installations[GAME_ID]["token"]
        self.state.apply_installation_probe(GAME_ID, {
            "provider": "steam", "started": True, "phase": "active_install",
            "scan_available": True, "scan_complete": True,
            "requires_attention": False, "progress": 90,
        }, token)
        self.state.handle_message({
            "type": "status",
            "status": {"name": "gameInstallationCancelled", "id": GAME_ID},
        })

        self.state.apply_installation_probe(GAME_ID, {
            "installed": True, "provider": "steam", "phase": "completed_install",
            "install_directory": r"E:\Games\FEZ",
        }, token)

        self.assertEqual("completed", self.state.operation_journal.get(GAME_ID)["state"])
        self.assertTrue(self.state.library[GAME_ID]["installed"])

    def test_stale_steam_cancellation_evidence_is_ignored_for_newer_token(self):
        self._start_direct_steam()
        old_token = self.state.installations[GAME_ID]["token"]
        self.state.apply_installation_probe(GAME_ID, {
            "provider": "steam", "started": True, "phase": "active_install",
            "scan_available": True, "scan_complete": True,
            "requires_attention": False, "progress": 25,
        }, old_token)
        self.state.handle_message({
            "type": "status",
            "status": {"name": "gameInstallationCancelled", "id": GAME_ID},
        })
        self.state.operation_journal.update(GAME_ID, "completed")
        newer = self.state.operation_journal.begin(GAME_ID, "install", "steam", "FEZ")
        new_token = self.state.operation_token(newer)
        self.state.installations[GAME_ID]["token"] = new_token

        for _ in range(REQUIRED_STABLE_SAMPLES):
            self.state.apply_installation_probe(GAME_ID, {
                "provider": "steam", "started": False, "phase": "not_started",
                "scan_available": True, "scan_complete": True,
                "requires_attention": False,
            }, old_token)

        self.assertEqual("preparing", self.state.operation_journal.get(GAME_ID)["state"])
        self.assertEqual(new_token, self.state.installations[GAME_ID]["token"])

    def test_inconclusive_cancelled_steam_scan_reaches_bounded_attention(self):
        self._start_direct_steam()
        token = self.state.installations[GAME_ID]["token"]
        self.state.apply_installation_probe(GAME_ID, {
            "provider": "steam", "started": True, "phase": "active_install",
            "scan_available": True, "scan_complete": True,
            "requires_attention": False, "progress": 25,
        }, token)
        self.state.handle_message({
            "type": "status",
            "status": {"name": "gameInstallationCancelled", "id": GAME_ID},
        })
        inconclusive = {
            "provider": "steam", "started": False, "phase": "scan_incomplete",
            "scan_available": True, "scan_complete": False,
            "requires_attention": False,
        }
        self.state.apply_installation_probe(GAME_ID, inconclusive, token)
        self.assertEqual("downloading", self.state.operation_journal.get(GAME_ID)["state"])
        self.now += STEAM_CANCELLATION_EVIDENCE_TIMEOUT + 1

        self.state.apply_installation_probe(GAME_ID, inconclusive, token)

        operation = self.state.operation_journal.get(GAME_ID)
        self.assertEqual("attention_required", operation["state"])
        self.assertEqual("steam_cancellation_inconclusive", operation["detail"])





    def test_malformed_library_cache_is_ignored(self):
        with tempfile.TemporaryDirectory() as temporary:
            cache_path = Path(temporary) / "library-cache.json"
            cache_path.write_text("{not json", encoding="utf-8")
            state = BridgeState(cache_path=cache_path)
            self.assertEqual(0, state.library_page("0", 10)["total"])

    def test_running_game_trace_is_written_atomically_with_exact_identity(self):
        with tempfile.TemporaryDirectory() as temporary:
            trace_path = Path(temporary) / "active-game.json"
            trace = self._active_trace()
            state = BridgeState(active_game_path=trace_path)
            state.library[trace["game_id"]] = self._trace_game(trace)
            self._configure_reconciliation(state, trace)
            with state.lock:
                state.current = {"state": "starting", "id": trace["game_id"]}
                state.readiness = {
                    "ready": False, "reason": "game_starting",
                    "target_kind": "game", "stable_samples": 0,
                }
            state.apply_window_sample({
                "qualified": True, "reason": "stabilizing_target_window",
                "observed_game_id": trace["game_id"],
                "process_id": trace["process_id"],
            })

            saved = json.loads(trace_path.read_text(encoding="utf-8"))
            self.assertEqual(trace["process_path"].casefold(), saved["process_path"])
            self.assertEqual(
                {key: value for key, value in trace.items() if key != "process_path"},
                {key: value for key, value in saved.items() if key != "process_path"})
            self.assertFalse(trace_path.with_name("active-game.json.tmp").exists())

    def test_bridge_restart_restores_exact_running_game_not_ready(self):
        with tempfile.TemporaryDirectory() as temporary:
            trace_path = Path(temporary) / "active-game.json"
            trace = self._active_trace()
            self._write_trace(trace_path, trace)
            state = BridgeState(active_game_path=trace_path)
            self.assertEqual("reconciling", state.current["state"])
            state.library[trace["game_id"]] = self._trace_game(trace)

            self._configure_reconciliation(state, trace)

            self.assertEqual("running", state.current["state"])
            self.assertTrue(state.current["reconciled"])
            self.assertEqual(trace["process_id"], state.current["processId"])
            self.assertFalse(state.readiness["ready"])
            self.assertEqual("waiting_for_game_window", state.readiness["reason"])
            reconciled_events = len([
                event for event in state.events if event["event"] == "game-reconciled"])

            state.set_transport(False, None, "connector unavailable")
            state._attempt_active_game_reconciliation_locked()

            self.assertEqual(reconciled_events, len([
                event for event in state.events if event["event"] == "game-reconciled"]))

    def test_missing_or_reused_pid_rejects_and_removes_trace(self):
        cases = (
            (lambda _pid: None, "missing"),
            (lambda pid: {"process_id": pid,
                          "process_path": r"C:\Other\Civ6.exe",
                          "process_started_filetime": 133700000000000000}, "path"),
            (lambda pid: {"process_id": pid,
                          "process_path": r"C:\Games\Civ6\Civ6.exe",
                          "process_started_filetime": 133700000000000001}, "time"),
        )
        for identity_action, label in cases:
            with self.subTest(label=label), tempfile.TemporaryDirectory() as temporary:
                trace_path = Path(temporary) / "active-game.json"
                trace = self._active_trace()
                self._write_trace(trace_path, trace)
                state = BridgeState(active_game_path=trace_path)
                state.library[trace["game_id"]] = self._trace_game(trace)
                state.set_reconciliation_actions(identity_action, lambda _path: [])

                self.assertEqual({"state": "idle"}, state.current)
                self.assertFalse(trace_path.exists())

    def test_process_enumeration_failure_keeps_reconciliation_pending(self):
        with tempfile.TemporaryDirectory() as temporary:
            trace_path = Path(temporary) / "active-game.json"
            trace = self._active_trace()
            self._write_trace(trace_path, trace)
            state = BridgeState(active_game_path=trace_path)
            state.library[trace["game_id"]] = self._trace_game(trace)
            identity = {
                "process_id": trace["process_id"],
                "process_path": trace["process_path"],
                "process_started_filetime": trace["process_started_filetime"],
            }

            state.set_reconciliation_actions(lambda _pid: identity, lambda _path: None)

            self.assertEqual("reconciling", state.current["state"])
            self.assertTrue(trace_path.exists())

    def test_enumeration_filetime_mismatch_rejects_toctou_pid_reuse(self):
        with tempfile.TemporaryDirectory() as temporary:
            trace_path = Path(temporary) / "active-game.json"
            trace = self._active_trace()
            self._write_trace(trace_path, trace)
            state = BridgeState(active_game_path=trace_path)
            state.library[trace["game_id"]] = self._trace_game(trace)
            identity = {
                "process_id": trace["process_id"],
                "process_path": trace["process_path"],
                "process_started_filetime": trace["process_started_filetime"],
            }
            state.set_reconciliation_actions(
                lambda _pid: identity,
                lambda _path: [{
                    **identity,
                    "process_started_filetime": trace["process_started_filetime"] + 1,
                }])

            self.assertEqual({"state": "idle"}, state.current)
            self.assertFalse(trace_path.exists())

    def test_malformed_or_oversized_active_trace_is_removed(self):
        for content in ('{"version":1,"process_id":-1}', "x" * (16 * 1024 + 1)):
            with self.subTest(size=len(content)), tempfile.TemporaryDirectory() as temporary:
                trace_path = Path(temporary) / "active-game.json"
                trace_path.write_text(content, encoding="utf-8")

                state = BridgeState(active_game_path=trace_path)

                self.assertEqual({"state": "idle"}, state.current)
                self.assertFalse(trace_path.exists())

    def test_different_game_id_rejects_trace(self):
        with tempfile.TemporaryDirectory() as temporary:
            trace_path = Path(temporary) / "active-game.json"
            trace = self._active_trace(game_id="steam:289070")
            self._write_trace(trace_path, trace)
            state = BridgeState(active_game_path=trace_path)
            game = self._trace_game(trace)
            game["id"] = "steam:289071"
            state.library[game["id"]] = game

            self._configure_reconciliation(state, trace)

            self.assertEqual({"state": "idle"}, state.current)
            self.assertFalse(trace_path.exists())

    def test_multiple_library_or_process_matches_are_ambiguous(self):
        for duplicate_kind in ("library", "process"):
            with self.subTest(kind=duplicate_kind), tempfile.TemporaryDirectory() as temporary:
                trace_path = Path(temporary) / "active-game.json"
                trace = self._active_trace()
                self._write_trace(trace_path, trace)
                state = BridgeState(active_game_path=trace_path)
                state.library[trace["game_id"]] = self._trace_game(trace)
                identities = None
                if duplicate_kind == "library":
                    duplicate = self._trace_game(trace)
                    duplicate["id"] = "steam:289071"
                    state.library[duplicate["id"]] = duplicate
                else:
                    identity = {
                        "process_id": trace["process_id"],
                        "process_path": trace["process_path"],
                        "process_started_filetime": trace["process_started_filetime"],
                    }
                    identities = [identity, {**identity, "process_id": 5150}]

                self._configure_reconciliation(state, trace, identities)

                self.assertEqual("ambiguous", state.current["state"])
                self.assertTrue(trace_path.exists())
                self.assertFalse(state.start_game(trace["game_id"])["accepted"])
                self.assertFalse(state.stop_game(trace["game_id"])["accepted"])

    def test_native_restore_waits_for_exact_connector_guid_and_pid(self):
        with tempfile.TemporaryDirectory() as temporary:
            trace_path = Path(temporary) / "active-game.json"
            trace = self._active_trace(
                game_id=GAME_ID, provider="playnite",
                provider_game_id=GAME_ID, playnite_guid=GAME_ID)
            self._write_trace(trace_path, trace)
            state = BridgeState(active_game_path=trace_path)
            state.library[GAME_ID] = self._trace_game(trace)
            state.playnite_library[GAME_ID] = {
                "id": GAME_ID, "name": "Recovered game", "source": "GOG"}
            state.set_transport(True, lambda _command: None)
            self._configure_reconciliation(state, trace)
            self.assertEqual("reconciling", state.current["state"])

            state.handle_message({
                "type": "status", "status": {
                    "name": "gameStarted", "id": GAME_ID,
                    "processId": trace["process_id"],
                }})

            self.assertEqual("running", state.current["state"])
            self.assertTrue(state.current["reconciled"])
            self.assertFalse(state.readiness["ready"])

    def test_native_connector_mismatch_is_ambiguous(self):
        with tempfile.TemporaryDirectory() as temporary:
            trace_path = Path(temporary) / "active-game.json"
            trace = self._active_trace(
                game_id=GAME_ID, provider="playnite",
                provider_game_id=GAME_ID, playnite_guid=GAME_ID)
            self._write_trace(trace_path, trace)
            state = BridgeState(active_game_path=trace_path)
            state.library[GAME_ID] = self._trace_game(trace)
            state.playnite_library[GAME_ID] = {"id": GAME_ID, "source": "GOG"}
            state.set_transport(True, lambda _command: None)
            self._configure_reconciliation(state, trace)

            state.handle_message({
                "type": "status", "status": {
                    "name": "gameStarted", "id": GAME_ID, "processId": 5150,
                }})

            self.assertEqual("ambiguous", state.current["state"])
            self.assertEqual("active_game_connector_mismatch", state.current["reason"])

    def test_native_reconcile_stop_requires_exact_guid_and_pid(self):
        with tempfile.TemporaryDirectory() as temporary:
            trace_path = Path(temporary) / "active-game.json"
            trace = self._active_trace(
                game_id=GAME_ID, provider="playnite",
                provider_game_id=GAME_ID, playnite_guid=GAME_ID)
            self._write_trace(trace_path, trace)
            state = BridgeState(active_game_path=trace_path)
            state.library[GAME_ID] = self._trace_game(trace)
            state.playnite_library[GAME_ID] = {"id": GAME_ID, "source": "GOG"}
            state.set_transport(True, lambda _command: None)
            self._configure_reconciliation(state, trace)

            for process_id in (None, trace["process_id"] + 1):
                status = {"name": "gameStopped", "id": GAME_ID}
                if process_id is not None:
                    status["processId"] = process_id
                state.handle_message({"type": "status", "status": status})
                self.assertEqual("reconciling", state.current["state"])
                self.assertTrue(trace_path.exists())

            state.handle_message({"type": "status", "status": {
                "name": "gameStopped", "id": GAME_ID,
                "processId": trace["process_id"],
            }})
            self.assertEqual({"state": "idle"}, state.current)
            self.assertFalse(trace_path.exists())

    def test_late_connector_event_cannot_replace_newer_current_game(self):
        newer_id = SECOND_GAME_ID
        with self.state.lock:
            self.state.library[GAME_ID] = {
                "id": GAME_ID, "provider": "playnite",
                "providerGameId": GAME_ID, "playniteGameId": GAME_ID,
            }
            self.state.library[newer_id] = {
                "id": newer_id, "provider": "playnite",
                "providerGameId": newer_id, "playniteGameId": newer_id,
            }
            self.state.playnite_library[GAME_ID] = {"id": GAME_ID, "source": "GOG"}
            self.state.current = {
                "state": "running", "id": newer_id, "processId": 5150,
            }

        self.state.handle_message({
            "type": "status", "status": {
                "name": "gameStarted", "id": GAME_ID, "processId": 4242,
            }})

        self.assertEqual(newer_id, self.state.current["id"])
        self.assertEqual(5150, self.state.current["processId"])
        self.assertEqual("playnite-status", self.state.events[-1]["event"])

    def test_uncorrelated_connector_events_cannot_create_or_stop_current_game(self):
        unknown_id = "11111111-1111-4111-8111-111111111111"
        self._put_native_game()
        self.state.handle_message({
            "type": "status", "status": {
                "name": "gameStarted", "id": unknown_id, "processId": 4242,
            }})
        self.assertEqual({"state": "idle"}, self.state.current)
        with self.state.lock:
            self.state.current = {
                "state": "running", "id": SECOND_GAME_ID, "processId": 5150,
            }
            self.state.readiness = {
                "ready": False, "reason": "waiting_for_game_window",
                "target_kind": "game", "stable_samples": 0,
            }

        self.state.handle_message({
            "type": "status", "status": {
                "name": "gameStopped", "id": GAME_ID, "processId": 4242,
            }})

        self.assertEqual(SECOND_GAME_ID, self.state.current["id"])
        self.assertEqual(5150, self.state.current["processId"])
        self.assertEqual("playnite-status", self.state.events[-1]["event"])

    def test_native_restore_uses_process_evidence_only_after_connector_failure(self):
        with tempfile.TemporaryDirectory() as temporary:
            trace_path = Path(temporary) / "active-game.json"
            trace = self._active_trace(
                game_id=GAME_ID, provider="playnite",
                provider_game_id=GAME_ID, playnite_guid=GAME_ID)
            self._write_trace(trace_path, trace)
            state = BridgeState(active_game_path=trace_path)
            state.library[GAME_ID] = self._trace_game(trace)
            self._configure_reconciliation(state, trace)
            self.assertEqual("reconciling", state.current["state"])

            state.set_transport(False, None, "connector unavailable")

            self.assertEqual("running", state.current["state"])
            self.assertFalse(state.readiness["ready"])

    def test_stale_trace_cannot_stop_a_newer_game(self):
        with tempfile.TemporaryDirectory() as temporary:
            trace_path = Path(temporary) / "active-game.json"
            trace = self._active_trace()
            self._write_trace(trace_path, trace)
            state = BridgeState(active_game_path=trace_path)
            state.library[trace["game_id"]] = self._trace_game(trace)
            state.set_reconciliation_actions(lambda _pid: None, lambda _path: [])
            newer_id = "epic:ExactAppName"
            state.library[newer_id] = {
                "id": newer_id, "provider": "epic",
                "providerGameId": "ExactAppName", "playniteGameId": "",
                "name": "Newer", "installDir": r"C:\Games\Newer",
            }
            with state.lock:
                state.current = {"state": "running", "id": newer_id, "processId": 5150}
            state.graceful_close = mock.Mock(return_value=True)

            with self.assertRaisesRegex(ValueError, "not the current provider game"):
                state.stop_game(trace["game_id"])
            state._attempt_active_game_reconciliation_locked()

            state.graceful_close.assert_not_called()
            self.assertEqual(newer_id, state.current["id"])

    def test_artwork_is_resolved_only_from_library_metadata(self):
        with tempfile.NamedTemporaryFile(suffix=".png", delete=False) as artwork:
            artwork.write(b"\x89PNG\r\n\x1a\nimage")
            artwork_path = artwork.name
        try:
            self.state.handle_message({"type": "games", "payload": [{
                "id": GAME_ID, "name": "Baba Is You", "boxArtPath": artwork_path,
            }]})
            body, content_type = self.state.artwork(GAME_ID, "cover")
            self.assertTrue(body.startswith(b"\x89PNG"))
            self.assertEqual("image/png", content_type)
            with self.assertRaises(ValueError):
                self.state.artwork(GAME_ID, "../../secret")
        finally:
            Path(artwork_path).unlink(missing_ok=True)

    def test_forced_stop_is_never_generated(self):
        self._put_native_game()
        self.state.handle_message({
            "type": "status",
            "status": {"name": "gameStarted", "id": GAME_ID, "processId": 4242},
        })
        close_requested = threading.Event()
        self.state.graceful_close = lambda process_id: (
            self.closed_processes.append(process_id), close_requested.set(), True)[-1]
        result_holder = {}
        stopping = threading.Thread(target=lambda: result_holder.update(
            self.state.stop_game(GAME_ID)))
        stopping.start()
        self.assertTrue(close_requested.wait(1))
        self.state.apply_window_sample({
            "qualified": False, "reason": "game_process_exited",
            "process_id": 4242, "observed_game_id": GAME_ID,
        })
        stopping.join(1)

        self.assertFalse(stopping.is_alive())
        result = result_holder
        self.assertTrue(result["accepted"])
        self.assertEqual(False, result["force"])
        self.assertEqual([4242], self.closed_processes)
        self.assertFalse(self.state.readiness["ready"])
        self.assertEqual("idle", self.state.current["state"])

    def test_stop_waits_for_authoritative_exit_for_every_provider(self):
        records = (
            ("steam:289070", "steam", "289070"),
            ("epic:ExactAppName", "epic", "ExactAppName"),
            (f"playnite:{GAME_ID}", "playnite", GAME_ID),
        )
        for game_id, provider, provider_game_id in records:
            with self.subTest(provider=provider):
                closed = threading.Event()
                self.closed_processes.clear()
                with self.state.lock:
                    self.state.library[game_id] = {
                        "id": game_id, "name": provider, "provider": provider,
                        "providerGameId": provider_game_id,
                    }
                    self.state.current = {
                        "state": "running", "id": game_id, "processId": 4242,
                    }
                    self.state.readiness = {
                        "ready": True, "reason": "target_window_ready",
                        "target_kind": "game", "stable_samples": 4,
                    }
                self.state.graceful_close = lambda process_id: (
                    self.closed_processes.append(process_id), closed.set(), True)[-1]
                result_holder = {}
                stopping = threading.Thread(target=lambda: result_holder.update(
                    self.state.stop_game(game_id)))
                stopping.start()
                self.assertTrue(closed.wait(1))
                self.assertTrue(stopping.is_alive())
                self.state.apply_window_sample({
                    "qualified": False, "reason": "game_process_exited",
                    "process_id": 4242, "observed_game_id": game_id,
                })
                stopping.join(1)

                self.assertEqual(True, result_holder["accepted"])
                self.assertEqual([4242], self.closed_processes)
                self.assertEqual([], self.commands)

    def test_cross_provider_start_waits_for_visible_replacement_to_exit(self):
        game_id = "steam:952060"
        with self.state.lock:
            self.state.library[game_id] = {
                "id": game_id, "name": "Resident Evil 3", "provider": "steam",
                "providerGameId": "952060", "installDir": r"E:\Games\RE3",
            }
            self.state.current = {
                "state": "running", "id": game_id, "processId": 1111,
                "processPath": r"e:\games\re3\re3.exe",
            }
            self.state.readiness = {
                "ready": True, "reason": "target_window_ready",
                "target_kind": "game", "stable_samples": 4,
            }
        closed = []
        first_close = threading.Event()
        self.state.graceful_close = lambda process_id: (
            closed.append(process_id), first_close.set(), True)[-1]
        next_provider_starts = []
        result = {}

        def stop_then_start():
            result.update(self.state.stop_game(game_id))
            if result.get("accepted"):
                next_provider_starts.append("epic:Salt")

        stopping = threading.Thread(target=stop_then_start)
        stopping.start()
        self.assertTrue(first_close.wait(1))
        self.state.apply_window_sample({
            "qualified": False, "reason": "target_not_fullscreen",
            "replacement_process": True, "process_id": 2222,
            "process_path": r"E:\Games\RE3\re3.exe",
            "observed_game_id": game_id,
        })

        self.assertTrue(stopping.is_alive())
        self.assertEqual([], next_provider_starts)
        self.assertEqual([1111, 2222], closed)
        self.assertEqual(2222, self.state.current["processId"])

        self.state.apply_window_sample({
            "qualified": False, "reason": "game_process_exited",
            "process_id": 2222, "observed_game_id": game_id,
        })
        stopping.join(1)

        self.assertFalse(stopping.is_alive())
        self.assertTrue(result["accepted"])
        self.assertEqual(["epic:Salt"], next_provider_starts)

    def test_stop_during_start_closes_late_process_before_accepting(self):
        with self.state.lock:
            self.state.library["steam:289070"] = {
                "id": "steam:289070", "name": "Civilization VI",
                "provider": "steam", "providerGameId": "289070",
            }
            self.state.current = {
                "state": "starting", "id": "steam:289070",
                "installDir": r"E:\\Games\\Civ6",
            }
            self.state.readiness = {
                "ready": False, "reason": "game_starting",
                "target_kind": "game", "stable_samples": 0,
            }
        closed = threading.Event()
        self.state.graceful_close = lambda process_id: (
            self.closed_processes.append(process_id), closed.set(), True)[-1]
        result_holder = {}
        stopping = threading.Thread(target=lambda: result_holder.update(
            self.state.stop_game("steam:289070")))
        stopping.start()
        for _ in range(100):
            if self.state.current.get("state") == "stopping":
                break
            time.sleep(.01)
        self.assertEqual("stopping", self.state.current["state"])

        self.state.apply_window_sample({
            "qualified": True, "reason": "target_window_ready",
            "process_id": 5150, "observed_game_id": "steam:289070",
        })
        self.assertTrue(closed.wait(1))
        self.state.apply_window_sample({
            "qualified": False, "reason": "game_process_exited",
            "process_id": 5150, "observed_game_id": "steam:289070",
        })
        stopping.join(1)

        self.assertEqual({"accepted": True, "command": "stop", "force": False},
                         result_holder)
        self.assertEqual([5150], self.closed_processes)

    def test_direct_provider_is_stoppable_while_dispatch_is_blocked(self):
        game_id = "steam:289070"
        with self.state.lock:
            self.state.library[game_id] = {
                "id": game_id, "name": "Civilization VI", "provider": "steam",
                "providerGameId": "289070", "installed": True,
            }
        dispatch_entered = threading.Event()
        release_dispatch = threading.Event()

        def blocked_launch(_game, _task_id):
            dispatch_entered.set()
            release_dispatch.wait(1)
            return {"accepted": False, "command": "launch", "provider": "steam",
                    "reason": "launch_cancelled"}

        self.state.game_operations.steam.launch = blocked_launch
        launch_result = {}
        launching = threading.Thread(target=lambda: launch_result.update(
            self.state.start_game(game_id)))
        launching.start()
        self.assertTrue(dispatch_entered.wait(1))
        self.assertEqual("starting", self.state.current["state"])

        stop_result = {}
        stopping = threading.Thread(target=lambda: stop_result.update(
            self.state.stop_game(game_id)))
        stopping.start()
        for _ in range(100):
            if self.state.current.get("state") == "stopping":
                break
            time.sleep(.01)
        self.assertEqual("stopping", self.state.current["state"])
        release_dispatch.set()
        launching.join(1)
        stopping.join(1)

        self.assertFalse(launching.is_alive())
        self.assertFalse(stopping.is_alive())
        self.assertFalse(launch_result["accepted"])
        self.assertTrue(stop_result["accepted"])
        self.assertEqual("idle", self.state.current["state"])

    def test_unconfirmed_stop_is_rejected_and_remains_stopping(self):
        with self.state.lock:
            self.state.library["epic:ExactAppName"] = {
                "id": "epic:ExactAppName", "name": "Celeste",
                "provider": "epic", "providerGameId": "ExactAppName",
            }
            self.state.current = {
                "state": "running", "id": "epic:ExactAppName", "processId": 4242,
            }
            self.state.readiness = {
                "ready": True, "reason": "target_window_ready",
                "target_kind": "game", "stable_samples": 4,
            }
        self.state.graceful_close = lambda _process_id: False

        result = self.state.stop_game("epic:ExactAppName")

        self.assertFalse(result["accepted"])
        self.assertEqual("game_stop_close_rejected", result["reason"])
        self.assertEqual("stopping", self.state.current["state"])

    def test_close_message_without_process_exit_times_out(self):
        with self.state.lock:
            self.state.library["steam:289070"] = {
                "id": "steam:289070", "name": "Civilization VI",
                "provider": "steam", "providerGameId": "289070",
            }
            self.state.current = {
                "state": "running", "id": "steam:289070", "processId": 4242,
            }
            self.state.readiness = {
                "ready": True, "reason": "target_window_ready",
                "target_kind": "game", "stable_samples": 4,
            }
        self.state.stop_timeout = .01
        self.state.graceful_close = lambda _process_id: True

        result = self.state.stop_game("steam:289070")

        self.assertFalse(result["accepted"])
        self.assertEqual("game_stop_timeout", result["reason"])
        self.assertEqual("stopping", self.state.current["state"])

    def test_stop_without_an_observed_process_clears_stale_start_after_timeout(self):
        with self.state.lock:
            self.state.library["steam:289070"] = {
                "id": "steam:289070", "name": "Civilization VI",
                "provider": "steam", "providerGameId": "289070",
            }
            self.state.current = {
                "state": "starting", "id": "steam:289070",
            }
            self.state.readiness = {
                "ready": False, "reason": "waiting_for_game_window",
                "target_kind": "game", "stable_samples": 0,
            }
        self.state.stop_timeout = .01

        result = self.state.stop_game("steam:289070")

        self.assertTrue(result["accepted"])
        self.assertTrue(result["already_stopped"])
        self.assertEqual({"state": "idle"}, self.state.current)
        self.assertEqual("game-stopped", self.state.events[-1]["event"])

    def test_stop_never_targets_a_different_provider_record(self):
        with self.state.lock:
            self.state.library["epic:ExactAppName"] = {
                "id": "epic:ExactAppName", "name": "Celeste",
                "provider": "epic", "providerGameId": "ExactAppName",
            }
            self.state.current = {
                "state": "running", "id": "steam:289070", "processId": 4242,
            }
            self.state.readiness = {
                "ready": True, "reason": "target_window_ready",
                "target_kind": "game", "stable_samples": 4,
            }

        with self.assertRaisesRegex(ValueError, "not the current provider game"):
            self.state.stop_game("epic:ExactAppName")

        self.assertEqual([], self.closed_processes)
        self.assertEqual("running", self.state.current["state"])

    def test_show_fullscreen_closes_gate_before_activation(self):
        result = self.state.show_fullscreen()
        self.assertEqual("show-fullscreen", result["command"])
        self.assertEqual([True], self.fullscreen_calls)
        self.assertFalse(self.state.readiness["ready"])
        self.assertEqual("waiting_for_playnite_window", self.state.readiness["reason"])

    def test_install_is_non_blocking_and_completion_updates_library(self):
        self.state.handle_message({
            "type": "games",
            "payload": [{"id": GAME_ID, "name": "Baba Is You", "installed": False}],
        })

        result = self.state.install_game(GAME_ID.upper())

        self.assertTrue(result["accepted"])
        self.assertEqual({
            "type": "command", "command": "install", "id": GAME_ID,
        }, self.commands[-1])
        self.assertTrue(self.state.library[GAME_ID]["installing"])
        self.state.handle_message({
            "type": "status",
            "status": {"name": "gameInstalled", "id": GAME_ID},
        })
        self.assertTrue(self.state.library[GAME_ID]["installed"])
        self.assertFalse(self.state.library[GAME_ID]["installing"])
        self.assertEqual("game-installed", self.state.events[-1]["event"])
        self.assertEqual("Baba Is You", self.state.events[-1]["payload"]["name"])

    def test_uninstall_dispatches_only_for_installed_game(self):
        self.state.handle_message({
            "type": "games",
            "payload": [{"id": GAME_ID, "name": "Baba Is You", "installed": True}],
        })

        result = self.state.uninstall_game(GAME_ID)

        self.assertTrue(result["accepted"])
        self.assertEqual({
            "type": "command", "command": "uninstall", "id": GAME_ID,
        }, self.commands[-1])











    def test_second_operation_waits_until_first_prompt_is_resolved(self):
        other = "65705ca9-b9c7-4ada-b4b7-f73ffb8ac64f"
        self.state.handle_message({"type": "games", "payload": [
            {"id": GAME_ID, "name": "FTL", "installed": False},
            {"id": other, "name": "FEZ", "installed": True},
        ]})
        self.state.install_game(GAME_ID)
        with self.assertRaisesRegex(RuntimeError, "awaiting confirmation"):
            self.state.uninstall_game(other)
        self.state.operation_journal.update(GAME_ID, "installing")
        self.assertTrue(self.state.uninstall_game(other)["accepted"])

    def test_install_rejects_unknown_or_already_installed_game(self):
        with self.assertRaises(FileNotFoundError):
            self.state.install_game(GAME_ID)
        self.state.handle_message({
            "type": "games",
            "payload": [{"id": GAME_ID, "name": "Baba Is You", "installed": True}],
        })
        with self.assertRaises(ValueError):
            self.state.install_game(GAME_ID)

    def test_focus_current_game_closes_gate_and_uses_install_identity(self):
        self.state.set_expected_display(r"\\.\DISPLAY15")
        self._put_native_game()
        self.state.handle_message({
            "type": "status",
            "status": {
                "name": "gameStarted", "id": GAME_ID, "processId": 4242,
                "installDir": r"E:\Games\Baba Is You",
            },
        })
        result = self.state.focus_game()
        self.assertTrue(result["focused"])
        self.assertEqual(
            [(4242, r"E:\Games\Baba Is You", r"\\.\DISPLAY15")],
            self.focus_calls)
        self.assertFalse(self.state.readiness["ready"])
        self.assertEqual("game_focus_requested", self.state.readiness["reason"])

    def test_stream_display_is_resolved_only_from_named_fields(self):
        payload = {
            "sources": {
                "session": {"value": {"display_name": "DISPLAY15"}},
                "clients": {"value": {"name": "DISPLAY99", "output_name_override": ""}},
            }
        }
        self.assertEqual(
            [r"\\.\DISPLAY15"], StreamDisplayResolver.displays_from_payload(payload))

    def test_connector_patch_is_guarded_and_idempotent(self):
        fixture = "\n".join([
            INSTALL_UI_ANCHOR,
            READER_ANCHOR, STATUS_PARAM_ANCHOR, STATUS_OBJECT_ANCHOR,
            SEND_PARAM_ANCHOR, SEND_BUILD_ANCHOR, STARTED_ANCHOR,
            ARTWORK_LOOKUP_ANCHOR, ARTWORK_PAYLOAD_ANCHOR,
            INSTALLING_PAYLOAD_ANCHOR, INSTALL_EVENT_ANCHOR,
            "function Start-ConnectorLoop {", "}",
        ])
        patched, changed = patch_text(fixture)
        self.assertTrue(changed)
        self.assertIn(PATCH_MARKER, patched)
        self.assertIn("Send-WakePlaySnapshotToLauncher", patched)
        self.assertIn("type = 'snapshotStart'", patched)
        self.assertIn("StartedProcessId", patched)
        self.assertIn("backgroundImagePath", patched)
        self.assertIn("description     = [string]$g.Description", patched)
        self.assertIn("playCount       = [int]$g.PlayCount", patched)
        self.assertIn("$PlayniteApi.Database.Sources.Get($g.SourceId).Name", patched)
        self.assertIn("source          =", patched)
        self.assertIn("providerGameId  = [string]$g.GameId", patched)
        self.assertIn("InstallGameByGuidStringOnUIThread", patched)
        self.assertIn("UninstallGameByGuidStringOnUIThread", patched)
        self.assertIn("$obj.command -eq 'uninstall'", patched)
        self.assertIn("$obj.command -eq 'mark-uninstalled'", patched)
        self.assertIn("gameInstallationCancelled", patched)
        self.assertIn("installing      = [bool]$g.IsInstalling", patched)
        self.assertGreaterEqual(patched.count("installing.SetValue(game, false)"), 2)
        self.assertNotIn("/game/prepare", patched)
        second, changed_again = patch_text(patched)
        self.assertFalse(changed_again)
        self.assertEqual(patched, second)

    def test_vibepollo_rebuilds_existing_direct_provider_target_from_allowlist(self):
        script = Path(__file__).parents[1] / "vibepollo" / "VibepolloBridge.ps1"
        source = script.read_text(encoding="utf-8-sig")

        provider_branch = source.index("if ($isProviderRecord) {")
        managed_branch = source.index('elseif ($managed -in @("manual", "auto"))')
        self.assertLess(provider_branch, managed_branch)
        self.assertIn("Rebuild every direct-provider target from a command-free allowlist",
                      source)

    def test_vibepollo_ensures_one_command_free_neutral_stream_additively(self):
        script = Path(__file__).parents[1] / "vibepollo" / "VibepolloBridge.ps1"
        source = script.read_text(encoding="utf-8-sig")
        start = source.index("function Ensure-MoonWakerStream {")
        end = source.index("\nfunction Find-AppsByPlayniteId", start)
        ensure = source[start:end]

        self.assertIn('$name = "MoonWaker Stream"', ensure)
        self.assertIn('$uuid = "6d6f6f6e-7761-4b65-9273-747265616d00"', ensure)
        self.assertIn('$payload["moonwaker-managed"] = "stream"', ensure)
        self.assertIn('$payload["uuid"] = $uuid', ensure)
        self.assertIn('$payload["auto-detach"] = $true', ensure)
        self.assertIn("Test-MoonWakerStreamCommandFree", ensure)
        command_guard = source[source.index("function Test-MoonWakerStreamCommandFree {"):start]
        for field in ("cmd", "prep-cmd", "state-cmd", "detached",
                      "playnite-id", "playnite_id", "playnite-managed",
                      "playnite-source"):
            self.assertIn(f'"{field}"', command_guard)
        self.assertIn("Multiple managed MoonWaker Stream applications", ensure)
        self.assertIn("Multiple applications already use the MoonWaker Stream name", ensure)
        self.assertIn("An unmarked application already uses the MoonWaker Stream name", ensure)
        self.assertIn("An unmanaged application already uses the reserved MoonWaker Stream UUID", ensure)
        self.assertGreaterEqual(ensure.count('Get-PropertyValue $matches[0].app'), 3)
        self.assertIn("Test-MoonWakerStreamCommandFree $matches[0].app", ensure)
        self.assertIn("return New-MoonWakerStreamResult $match $false $false", ensure)
        self.assertNotIn("Remove-DuplicatePlayniteApps", ensure)
        self.assertNotIn(" DELETE", ensure)
        self.assertNotIn("Ensure-PlayniteApp", ensure)

    def test_vibepollo_neutral_stream_ensure_is_wired_for_startup_and_retry(self):
        bridge = (Path(__file__).parents[1] / "vibepollo" / "VibepolloBridge.ps1") \
            .read_text(encoding="utf-8-sig")
        installer = (Path(__file__).parents[2] / "install" /
                     "Install-WakePlayProfile.ps1").read_text(encoding="utf-8-sig")
        smoke_test = (Path(__file__).parents[1] / "vibepollo" /
                      "Test-VibepolloBridge.ps1").read_text(encoding="utf-8-sig")

        self.assertGreaterEqual(bridge.count("Ensure-MoonWakerStream"), 3)
        self.assertIn("^/apps/stream/ensure$", bridge)
        self.assertIn("Ensure-MoonWakerStreamTarget $VibepolloPort", installer)
        self.assertIn("/apps/stream/ensure", installer)
        self.assertEqual(2, smoke_test.count("/apps/stream/ensure"))
        self.assertIn("$stream.uuid -ne [string]$streamAgain.uuid", smoke_test)
        self.assertIn("$stream.app_id -ne [long]$streamAgain.app_id", smoke_test)
        self.assertIn("[bool]$streamAgain.created", smoke_test)

    def test_connector_v8_is_upgraded_with_library_source(self):
        patched, changed = patch_text(PATCH_MARKER_V8 + "\n"
                                      + INSTALL_UI_REPLACEMENT + "\n"
                                      + INSTALL_READER_REPLACEMENT + "\n"
                                      + SOURCE_PAYLOAD_ANCHOR + "\n"
                                      + SNAPSHOT_FUNCTION)
        self.assertTrue(changed)
        self.assertIn(PATCH_MARKER, patched)
        self.assertIn("source          =", patched)

    def test_game_readiness_requires_sustained_stability_and_closes_immediately(self):
        self._put_native_game()
        self.state.handle_message({
            "type": "status",
            "status": {"name": "gameStarted", "id": GAME_ID, "processId": 4242},
        })
        sample = {"qualified": True, "reason": "stabilizing_target_window",
                  "process_id": 4242, "hwnd": 17, "display": r"\\.\DISPLAY15",
                  "bounds": [0, 0, 1920, 1080]}
        for _ in range(REQUIRED_GAME_STABLE_SAMPLES - 1):
            self.state.apply_window_sample(sample)
        self.assertFalse(self.state.readiness["ready"])
        self.state.apply_window_sample(sample)
        self.assertTrue(self.state.readiness["ready"])
        self.state.apply_window_sample({"qualified": False, "reason": "target_not_foreground"})
        self.assertFalse(self.state.readiness["ready"])


    def _start_epic_runtime_uninstall(self):
        self.state.game_operations.dispatch_uninstall = lambda _game, _sender: {
            "accepted": True, "command": "uninstall", "provider": "epic",
            "dispatch": "legendary",
        }
        self.state.handle_message({"type": "games", "payload": [{
            "id": GAME_ID, "name": "Alien: Isolation", "installed": True,
            "source": "Epic", "providerGameId": "Alien",
        }]})
        self.state.uninstall_game(GAME_ID)
        return self.state.installations[GAME_ID]["token"]














    def _start_epic_runtime_install(self):
        self.state.game_operations.dispatch_install = lambda _game, _sender: {
            "accepted": True, "command": "install", "provider": "epic",
            "dispatch": "legendary",
        }
        self.state.handle_message({"type": "games", "payload": [{
            "id": GAME_ID, "name": "Alien: Isolation", "installed": False,
            "source": "Epic", "providerGameId": "Alien",
        }]})
        self.state.install_game(GAME_ID)
        return self.state.installations[GAME_ID]["token"]




    def test_epic_start_uses_legendary_and_window_readiness_identity(self):
        with self.state.lock:
            self.state.library[GAME_ID] = {
                "id": GAME_ID, "name": "Carcassonne", "source": "Epic",
                "providerGameId": "Thrush", "installed": True,
            }
        launch = mock.Mock(return_value={
            "accepted": True, "command": "launch", "provider": "epic",
            "dispatch": "legendary", "app_name": "Thrush",
            "install_directory": r"E:\Games\Carcassonne",
        })
        self.state.game_operations.launch = launch

        result = self.state.start_game(GAME_ID)

        self.assertTrue(result["accepted"])
        launch.assert_called_once()
        self.assertEqual([], self.commands)
        self.assertEqual("starting", self.state.current["state"])
        self.assertEqual(r"E:\Games\Carcassonne", self.state.current["installDir"])
        self.assertFalse(self.state.readiness["ready"])
        self.assertEqual("game_starting", self.state.readiness["reason"])

        self.state.apply_window_sample({
            "qualified": False, "reason": "target_not_fullscreen",
            "process_id": 4242,
        })
        self.assertEqual("running", self.state.current["state"])
        self.assertEqual(4242, self.state.current["processId"])
        self.assertFalse(self.state.readiness["ready"])
        self.assertEqual("game-running", self.state.events[-1]["event"])

    def test_failed_epic_start_never_sends_playnite_or_opens_readiness(self):
        with self.state.lock:
            self.state.library[GAME_ID] = {
                "id": GAME_ID, "name": "Carcassonne", "source": "Epic",
                "providerGameId": "Thrush", "installed": False,
            }
            before_readiness = dict(self.state.readiness)
            before_events = list(self.state.events)
        self.state.game_operations.launch = mock.Mock(return_value={
            "accepted": False, "command": "launch", "provider": "epic",
            "requires_attention": False, "reason": "legendary_not_installed",
        })

        result = self.state.start_game(GAME_ID)

        self.assertFalse(result["accepted"])
        self.assertEqual("legendary_not_installed", result["reason"])
        self.assertEqual([], self.commands)
        self.assertEqual(before_readiness, self.state.readiness)
        self.assertEqual(before_events, list(self.state.events))

    def test_late_legendary_launch_failure_closes_starting_state(self):
        with self.state.lock:
            self.state.current = {
                "state": "starting", "id": GAME_ID,
                "launchTaskId": "launch-task",
            }
            self.state.readiness = {
                "ready": False, "reason": "game_starting",
                "target_kind": "game", "stable_samples": 0,
            }

        self.state.apply_launch_process_sample({
            "provider": "epic", "requires_attention": True,
            "reason": "legendary_process_failed", "exit_code": 7,
            "error_excerpt": "launch rejected",
        }, "launch-task")

        self.assertEqual("failed", self.state.current["state"])
        self.assertEqual("legendary_process_failed", self.state.readiness["reason"])
        self.assertEqual("game-launch-failed", self.state.events[-1]["event"])
        self.assertEqual("launch rejected",
                         self.state.events[-1]["payload"]["error_excerpt"])

    def test_steam_dispatch_hint_does_not_request_reveal_without_window(self):
        with self.state.lock:
            self.state.current = {
                "state": "starting", "id": GAME_ID,
                "launchTaskId": "launch-task",
            }
            self.state.readiness = {
                "ready": False, "reason": "game_starting",
                "target_kind": "game", "stable_samples": 0,
            }

        self.state.apply_launch_process_sample({
            "provider": "steam", "dispatched": True,
            "reason": "steam_launch_dispatched",
        }, "launch-task")
        self.state.apply_window_sample({
            "qualified": False, "reason": "waiting_for_game_window",
        })

        self.assertEqual("starting", self.state.current["state"])
        self.assertEqual("waiting_for_game_window", self.state.readiness["reason"])
        self.assertNotIn("launcher-interaction-required", [
            event["event"] for event in self.state.events])

    def test_legendary_overlay_overrides_both_playnite_true_and_false(self):
        with self.state.lock:
            self.state.library[GAME_ID] = {
                "id": GAME_ID, "name": "Carcassonne", "provider": "epic",
                "source": "Epic",
                "providerGameId": "Thrush", "installed": False,
                "installing": True,
            }
        snapshots = [{
            "available": True, "complete": True,
            "by_id": {"Thrush": {
                "installed": True, "provider": "epic",
                "install_directory": r"E:\Games\Carcassonne",
            }},
        }, {"available": True, "complete": True, "by_id": {}}]
        self.state.game_operations.external_snapshot = mock.Mock(side_effect=snapshots)
        self.state.game_operations.migration_candidate = mock.Mock(return_value={
            "reason": "legendary_import_required",
            "install_directory": r"D:\Epic\Carcassonne",
        })

        self.state._reconcile_external_installs()
        self.assertTrue(self.state.library[GAME_ID]["installed"])
        self.assertFalse(self.state.library[GAME_ID]["installing"])
        self.assertEqual(r"E:\Games\Carcassonne",
                         self.state.library[GAME_ID]["installDir"])

        self.state._reconcile_external_installs()
        self.assertFalse(self.state.library[GAME_ID]["installed"])
        self.assertFalse(self.state.library[GAME_ID]["installing"])
        self.assertEqual("", self.state.library[GAME_ID]["installDir"])
        self.assertTrue(self.state.library[GAME_ID]["legendaryImportRequired"])
        self.assertEqual(r"D:\Epic\Carcassonne",
                         self.state.library[GAME_ID]["legendaryImportDirectory"])

    def test_epic_connector_completion_and_cancellation_are_advisory(self):
        with self.state.lock:
            self.state.library[GAME_ID] = {
                "id": GAME_ID, "name": "Carcassonne", "source": "Epic",
                "providerGameId": "Thrush", "installed": False,
            }
            operation = self.state.operation_journal.begin(
                GAME_ID, "install", "epic", "Carcassonne")
            token = self.state.operation_token(operation)
            self.state.installations[GAME_ID] = {
                "baseline": {"game": dict(self.state.library[GAME_ID])},
                "requested_at": operation["requested_at"], "operation": "install",
                "token": token, "requires_attention": False,
            }

        for name in ("gameInstalled", "gameInstallationCancelled"):
            self.state.handle_message({
                "type": "status", "status": {"name": name, "id": GAME_ID},
            })

        self.assertEqual("preparing", self.state.operation_journal.get(GAME_ID)["state"])
        self.assertIn(GAME_ID, self.state.installations)
        self.assertFalse(self.state.library[GAME_ID]["installed"])

    def test_epic_authoritative_completion_never_marks_playnite(self):
        with self.state.lock:
            self.state.library[GAME_ID] = {
                "id": GAME_ID, "name": "Carcassonne", "source": "Epic",
                "providerGameId": "Thrush", "installed": False,
            }
            operation = self.state.operation_journal.begin(
                GAME_ID, "install", "epic", "Carcassonne")
            token = self.state.operation_token(operation)
            self.state.installations[GAME_ID] = {
                "baseline": {"game": dict(self.state.library[GAME_ID])},
                "requested_at": operation["requested_at"], "operation": "install",
                "token": token, "requires_attention": False,
            }

        self.state.apply_installation_probe(GAME_ID, {
            "provider": "epic", "installed": True,
            "install_directory": r"E:\Games\Carcassonne",
        }, token)

        self.assertEqual("completed", self.state.operation_journal.get(GAME_ID)["state"])
        self.assertTrue(self.state.library[GAME_ID]["installed"])
        self.assertEqual([], self.commands)

    def test_epic_existing_payload_starts_explicit_import_operation(self):
        with self.state.lock:
            self.state.library[GAME_ID] = {
                "id": GAME_ID, "name": "Carcassonne", "source": "Epic",
                "providerGameId": "Thrush", "installed": True,
                "installDir": r"D:\Epic\Carcassonne",
            }
        self.state.game_operations.external_snapshot = mock.Mock(return_value={
            "available": True, "complete": True, "by_id": {},
        })
        self.state.game_operations.migration_candidate = mock.Mock(return_value={
            "accepted": False, "provider": "epic", "requires_attention": False,
            "reason": "legendary_import_required",
            "install_directory": r"D:\Epic\Carcassonne",
        })
        dispatch = mock.Mock(return_value={
            "accepted": True, "command": "install", "provider": "epic",
            "dispatch": "legendary_import",
        })
        self.state.game_operations.dispatch_install = dispatch

        result = self.state.install_game(GAME_ID)

        self.assertTrue(result["accepted"])
        self.assertEqual("legendary_import", result["dispatch"])
        self.assertEqual("preparing", self.state.operation_journal.get(GAME_ID)["state"])
        self.assertFalse(self.state.library[GAME_ID]["installed"])
        self.assertTrue(self.state.library[GAME_ID]["legendaryImportRequired"])
        dispatch.assert_called_once()

class ProviderDiagnosticsTest(unittest.TestCase):
    def test_publish_projection_preserves_domain_event_and_omits_traps(self):
        with tempfile.TemporaryDirectory() as temporary:
            diagnostics = ProviderDiagnostics()
            log_dir = Path(temporary) / "logs"
            diagnostics.start(log_dir)
            state = BridgeState(profile_id="living-room")
            state.request_context.request_id = "request:42"
            payload = {
                "id": GAME_ID, "state": "running", "previous_state": "starting",
                "reason": "target_window_ready", "kind": "launch", "ready": True,
                "accepted": True, "requires_attention": False,
                "name": "Secret title", "title": "Secret", "display": "DISPLAY1",
                "path": r"C:\Private\game.exe", "process_id": 42, "hwnd": 77,
                "nested": {"token": "secret"},
            }
            with mock.patch.object(GameProviderBridge, "DIAGNOSTICS", diagnostics), state.lock:
                sequence = state.next_sequence
                state._publish_locked("game-running", payload)
            diagnostics.close()

            self.assertEqual({"sequence": sequence, "event": "game-running",
                              "timestamp": state.events[-1]["timestamp"], "payload": payload},
                             state.events[-1])
            entry = json.loads(next(log_dir.glob("provider-diagnostics.jsonl"))
                               .read_text(encoding="utf-8").strip())
            self.assertEqual(GAME_ID, entry["game_id"])
            self.assertEqual("request:42", entry["request_id"])
            self.assertEqual("target_window_ready", entry["reason"])
            for forbidden in ("name", "title", "display", "path", "process_id",
                              "hwnd", "nested"):
                self.assertNotIn(forbidden, entry)

    def test_request_context_is_inherited_then_cleared_and_invalid_ids_are_omitted(self):
        state = BridgeState(profile_id="living-room")
        handler = object.__new__(GameProviderHandler)
        handler.server = SimpleNamespace(state=state)
        handler.path = "/game/start?secret=value"
        handler.headers = Message()
        handler.headers["X-Request-Id"] = " request:42 "
        recorder = mock.Mock()
        with mock.patch.object(GameProviderBridge, "DIAGNOSTICS", recorder):
            handler._begin_diagnostics("POST")
            with state.lock:
                state._publish_locked("game-running", {"id": GAME_ID})
            self.assertEqual("request:42", recorder.lifecycle.call_args.args[3])
            handler._response_status = 202
            handler._finish_diagnostics()
            self.assertFalse(hasattr(state.request_context, "request_id"))
            for invalid in ("Bearer secret", None):
                state.request_context.request_id = invalid
                with state.lock:
                    state._publish_locked("game-stopped", {"id": GAME_ID})
                self.assertIn(recorder.lifecycle.call_args.args[3], ("Bearer secret", None))

        with tempfile.TemporaryDirectory() as temporary:
            diagnostics = ProviderDiagnostics()
            diagnostics.start(Path(temporary))
            diagnostics.lifecycle("game-stopped", 1, "default", None, {"id": GAME_ID})
            diagnostics.lifecycle("game-stopped", 2, "default", "Bearer secret", {"id": GAME_ID})
            diagnostics.close()
            entries = [json.loads(line) for line in
                       (Path(temporary) / "provider-diagnostics.jsonl").read_text(
                           encoding="utf-8").splitlines()]
            self.assertTrue(all("request_id" not in entry for entry in entries))

    def test_writer_rotates_and_fails_open(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            log_dir = root / "logs"
            log_dir.mkdir()
            old = time.time() - GameProviderBridge.DIAGNOSTIC_RETENTION_SECONDS - 1
            for suffix in ("", ".1", ".secret", ".bak"):
                path = log_dir / f"provider-diagnostics.jsonl{suffix}"
                path.write_text("old", encoding="utf-8")
                os.utime(path, (old, old))
            retention = ProviderDiagnostics()
            retention.start(log_dir)
            retention.close()
            self.assertFalse((log_dir / "provider-diagnostics.jsonl").exists())
            self.assertFalse((log_dir / "provider-diagnostics.jsonl.1").exists())
            self.assertTrue((log_dir / "provider-diagnostics.jsonl.secret").exists())
            self.assertTrue((log_dir / "provider-diagnostics.jsonl.bak").exists())
            (log_dir / "provider-diagnostics.jsonl.secret").unlink()
            (log_dir / "provider-diagnostics.jsonl.bak").unlink()

            with mock.patch.object(GameProviderBridge, "DIAGNOSTIC_MAX_BYTES", 1024):
                diagnostics = ProviderDiagnostics()
                diagnostics.start(log_dir)
                for sequence in range(80):
                    diagnostics.lifecycle("game-running", sequence, "p" * 256,
                                          "request:42", {"id": GAME_ID, "state": "running"})
                diagnostics.close()
            files = list((root / "logs").glob("provider-diagnostics.jsonl*"))
            self.assertGreater(len(files), 1)
            self.assertLessEqual(len(files), 10)
            occupied = root / "occupied"
            occupied.write_text("x", encoding="utf-8")
            diagnostics = ProviderDiagnostics()
            diagnostics.start(occupied / "logs")
            diagnostics.record("game-running")
            diagnostics.close()
            self.assertGreater(diagnostics.dropped, 0)


if __name__ == "__main__":
    unittest.main()
