import unittest
import tempfile
import ctypes
import json
import threading
import time
from pathlib import Path
from unittest import mock

from PatchPlayniteConnector import (
    ARTWORK_LOOKUP_ANCHOR, ARTWORK_PAYLOAD_ANCHOR, PATCH_MARKER, PATCH_MARKER_V8,
    INSTALL_EVENT_ANCHOR, INSTALL_READER_REPLACEMENT, INSTALL_UI_ANCHOR,
    INSTALL_UI_REPLACEMENT, INSTALLING_PAYLOAD_ANCHOR,
    READER_ANCHOR, SEND_BUILD_ANCHOR, SEND_PARAM_ANCHOR, SNAPSHOT_FUNCTION, STARTED_ANCHOR,
    SOURCE_PAYLOAD_ANCHOR, STATUS_OBJECT_ANCHOR, STATUS_PARAM_ANCHOR, patch_text,
)
from GameOperations import GameOperationsService, SteamProvider
from OperationJournal import OperationJournal
from PlayniteBridge import (
    BridgeState, GAME_START_TIMEOUT, LAUNCHER_POSTCONDITION_TIMEOUT,
    REQUIRED_GAME_STABLE_SAMPLES, REQUIRED_LAUNCHER_STABLE_SAMPLES,
    REQUIRED_STABLE_SAMPLES,
    STEAM_CANCELLATION_EVIDENCE_TIMEOUT,
    STEAM_FALLBACK_START_TIMEOUT, STEAM_PRIMARY_START_TIMEOUT,
    StreamDisplayResolver, WindowProbe, WindowsPipeClient,
    append_operation_audit,
)


GAME_ID = "840317c9-b9a4-4f72-be8e-807414e36a9b"
SECOND_GAME_ID = "65705ca9-b9c7-4ada-b4b7-f73ffb8ac64f"


class WindowProbeTest(unittest.TestCase):
    def test_launcher_script_covers_uia_win32_and_guarded_visual_action(self):
        script = Path(__file__).with_name("Invoke-GameLauncher.ps1").read_text(
            encoding="utf-8-sig")

        for contract in (
                "InvokePattern", "QueryFullProcessImageName", "EnumChildWindows",
                "BM_CLICK", "AllowDefaultAction", "ClickPoint", "mouse_event",
                "PrintWindow", "Find-VisualPrimaryAction", "visual_primary",
                "launcher_input_failed", "Test-LauncherIdentity"):
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

        with mock.patch("PlayniteBridge.subprocess.run", return_value=completed) as run:
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

        with mock.patch("PlayniteBridge.subprocess.run", return_value=completed) as run:
            result = probe.invoke_game_launcher(candidate)

        self.assertTrue(result["clicked"])
        self.assertIn("-AllowDefaultAction", run.call_args.args[0])

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
        with mock.patch("PlayniteBridge.ctypes.get_last_error", return_value=234,
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
        self.assertEqual("playnite", self.state.readiness["target_kind"])
        self.assertEqual("game-stopped", self.state.events[-1]["event"])
        self.assertEqual(4242, self.state.events[-1]["payload"]["processId"])

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
        self.state.handle_message({"type": "games", "payload": [{
            "id": GAME_ID, "name": "FEZ", "installed": installed,
            "source": "Steam", "providerGameId": "224760",
        }]})
        return self._dispatch_direct_steam(GAME_ID, operation)

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
        result = self.state.start_game(GAME_ID.upper())

        self.assertTrue(result["accepted"])
        self.assertEqual({
            "type": "command", "command": "launch", "id": GAME_ID,
        }, self.commands[-1])
        self.assertFalse(self.state.readiness["ready"])
        self.assertEqual("game_starting", self.state.readiness["reason"])
        with self.assertRaises(ValueError):
            self.state.start_game("../../cmd.exe")

    def test_steam_start_keeps_library_identity_before_game_started_event(self):
        install_dir = r"E:\Steam\steamapps\common\Sid Meier's Civilization V"
        self.state.handle_message({"type": "games", "payload": [{
            "id": GAME_ID, "name": "Sid Meier's Civilization V",
            "installed": True, "source": "Steam", "providerGameId": "8930",
            "installDir": install_dir, "exe": "",
        }]})

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
            "installed": True, "source": "Steam", "providerGameId": "8930",
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
        self.assertEqual("waiting_for_playnite_window", self.state.readiness["reason"])

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
                 "Source": "Steam",
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
        self.assertEqual("Steam", second["games"][0]["source"])
        self.assertEqual("", second["next_cursor"])

    def test_complete_snapshot_is_loaded_from_disk_after_restart(self):
        with tempfile.TemporaryDirectory() as temporary:
            cache_path = Path(temporary) / "library-cache.json"
            state = BridgeState(cache_path=cache_path)
            state.handle_message({"type": "plugins", "payload": [{"id": "steam"}]})
            state.handle_message({"type": "categories", "payload": [{"id": "action"}]})
            state.handle_message({"type": "games", "payload": [{
                "id": GAME_ID, "name": "Baba Is You", "installed": True,
            }]})
            self.assertFalse(cache_path.exists())
            state.handle_message({"type": "snapshotComplete", "payload": {"games": 1}})

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
            state.handle_message({"type": "games", "payload": [game]})
            state.install_game(GAME_ID)

            restored = BridgeState(operations_path=operation_path)
            restored.handle_message({"type": "games", "payload": [game]})
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
        self.state.install_game(GAME_ID)
        sample = {"requires_attention": True, "reason": "launcher_prompt",
                  "hwnd": 77, "process_id": 123, "title": "Choose install location",
                  "image": "futurelauncher.exe"}
        for _ in range(3):
            self.state.apply_installation_probe(GAME_ID, sample)
        game = self.state.library_page("0", 10)["games"][0]
        self.assertTrue(game["installRequiresAttention"])
        self.assertEqual("Choose install location", game["installWindowTitle"])
        self.assertEqual("game-installation-attention-required",
                         self.state.events[-1]["event"])
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
        self.state.handle_message({"type": "games", "payload": [{
            "id": GAME_ID, "name": "FEZ", "installed": False,
            "source": "Steam", "providerGameId": "224760",
        }]})
        self.state.install_game(GAME_ID)
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
        fallback = [payload for event, payload in self.audit
                    if event == "steam_playnite_fallback_dispatched"]
        self.assertEqual("direct_unavailable", fallback[0]["trigger"])

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

    def test_primary_timeout_dispatches_fresh_playnite_fallback_once(self):
        baselines = mock.Mock(side_effect=[
            {"capture": "primary"}, {"capture": "fallback"},
        ])
        self.state.installation_baseline_action = baselines
        self._start_direct_steam()
        token = self.state.installations[GAME_ID]["token"]
        no_evidence = {
            "provider": "steam", "started": False, "phase": "not_started",
            "requires_attention": False,
        }
        self.now += STEAM_PRIMARY_START_TIMEOUT + 1

        self.state.apply_installation_probe(GAME_ID, no_evidence, token)
        self.state.apply_installation_probe(GAME_ID, no_evidence, token)

        fallbacks = [command for command in self.commands
                     if command.get("command") == "install"]
        self.assertEqual(1, len(fallbacks))
        self.assertEqual("fallback",
                         self.state.installations[GAME_ID]["baseline"]["capture"])
        self.assertEqual(2, baselines.call_count)
        fallback_audits = [payload for event, payload in self.audit
                           if event == "steam_playnite_fallback_dispatched"]
        self.assertEqual(1, len(fallback_audits))
        self.assertEqual("primary_no_activity_timeout",
                         fallback_audits[0]["trigger"])

        self.now += STEAM_FALLBACK_START_TIMEOUT + 1
        self.state.apply_installation_probe(GAME_ID, no_evidence, token)
        current = self.state.library_page("0", 10)["games"][0]
        self.assertTrue(current["installRequiresAttention"])
        self.assertEqual("steam_fallback_not_started",
                         current["installAttentionReason"])
        self.assertEqual("steam.exe", current["installLauncher"])

    def test_failed_steam_automation_exposes_manual_fallback(self):
        self.state.game_operations.confirm_operation = mock.Mock(return_value={
            "clicked": False, "reason": "automation_failed",
        })
        self.state.handle_message({"type": "games", "payload": [{
            "id": GAME_ID, "name": "FEZ", "installed": False,
            "source": "Steam", "providerGameId": "224760",
        }]})
        self.state.install_game(GAME_ID)
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
        self.state.handle_message({"type": "games", "payload": [{
            "id": GAME_ID, "name": "FEZ", "installed": False,
            "source": "Steam", "providerGameId": "224760",
        }]})
        self.state.install_game(GAME_ID)
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
        self.state.handle_message({"type": "games", "payload": [{
            "id": GAME_ID, "name": "FTL", "installed": False,
            "source": "Steam", "providerGameId": "212680",
        }]})
        self.state.install_game(GAME_ID)
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
        self.state.handle_message({"type": "games", "payload": [{
            "id": GAME_ID, "name": "FTL", "installed": True,
            "source": "Steam", "providerGameId": "212680",
        }]})
        self.state.uninstall_game(GAME_ID)
        sample = {"requires_attention": True, "reason": "launcher_prompt",
                  "hwnd": 88, "process_id": 456, "title": "Odinstaluj",
                  "image": "steamwebhelper.exe"}
        for _ in range(3):
            self.state.apply_installation_probe(GAME_ID, sample)
        for _ in range(20):
            if confirmations and self.state.events[-1]["event"] == \
                    "game-installation-auto-confirmed":
                break
            time.sleep(.01)
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
        self.assertFalse(self.state.library[SECOND_GAME_ID]["installed"])

    def test_prestart_steam_uninstall_blocks_another_launcher_operation(self):
        self.state.handle_message({"type": "games", "payload": [
            {"id": GAME_ID, "name": "FEZ", "installed": True,
             "source": "Steam", "providerGameId": "224760"},
            {"id": SECOND_GAME_ID, "name": "FTL", "installed": False,
             "source": "Steam", "providerGameId": "212680"},
        ]})
        self._dispatch_direct_steam(GAME_ID, "uninstall")

        with self.assertRaisesRegex(RuntimeError, "awaiting confirmation"):
            self._dispatch_direct_steam(SECOND_GAME_ID, "install")

    def test_started_steam_operation_allows_another_launcher_operation(self):
        self.state.handle_message({"type": "games", "payload": [
            {"id": GAME_ID, "name": "FEZ", "installed": True,
             "source": "Steam", "providerGameId": "224760"},
            {"id": SECOND_GAME_ID, "name": "FTL", "installed": False,
             "source": "Steam", "providerGameId": "212680"},
        ]})
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
        self.state.handle_message({
            "type": "status",
            "status": {"name": "gameStarted", "id": GAME_ID, "processId": 4242},
        })
        result = self.state.stop_game(GAME_ID)
        self.assertEqual(False, result["force"])
        self.assertEqual([4242], self.closed_processes)
        self.assertFalse(self.state.readiness["ready"])
        self.assertEqual("game_stopping", self.state.readiness["reason"])

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

    def test_legendary_overlay_overrides_both_playnite_true_and_false(self):
        with self.state.lock:
            self.state.library[GAME_ID] = {
                "id": GAME_ID, "name": "Carcassonne", "source": "Epic",
                "providerGameId": "Thrush", "installed": False,
                "installing": True,
            }
        snapshots = [{
            "available": True, "complete": True,
            "by_id": {"thrush": {
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

if __name__ == "__main__":
    unittest.main()
