import unittest
import tempfile
import ctypes
import json
import os
import threading
import time
from pathlib import Path
from unittest import mock

from PatchPlayniteConnector import (
    ARTWORK_LOOKUP_ANCHOR, ARTWORK_PAYLOAD_ANCHOR, PATCH_MARKER, PATCH_MARKER_V8,
    INSTALL_EVENT_ANCHOR, INSTALL_READER_REPLACEMENT, INSTALL_UI_ANCHOR,
    INSTALL_UI_REPLACEMENT, INSTALLING_PAYLOAD_ANCHOR,
    READER_ANCHOR, SEND_BUILD_ANCHOR, SEND_PARAM_ANCHOR, STARTED_ANCHOR,
    SOURCE_PAYLOAD_ANCHOR, STATUS_OBJECT_ANCHOR, STATUS_PARAM_ANCHOR, patch_text,
)
from PlayniteBridge import (
    BridgeState, REQUIRED_GAME_STABLE_SAMPLES, SteamLibraryProbe,
    StreamDisplayResolver, WindowProbe, WindowsPipeClient,
)


GAME_ID = "840317c9-b9a4-4f72-be8e-807414e36a9b"


class WindowProbeTest(unittest.TestCase):
    def test_steam_manifest_reports_progress_completion_and_uninstall(self):
        with tempfile.TemporaryDirectory() as temporary:
            library = Path(temporary)
            steamapps = library / "steamapps"
            steamapps.mkdir()
            manifest = steamapps / "appmanifest_224760.acf"
            manifest.write_text('''"AppState"
{
    "appid" "224760"
    "StateFlags" "1026"
    "installdir" "FEZ"
    "BytesDownloaded" "50"
    "BytesToDownload" "100"
}''', encoding="utf-8")
            probe = SteamLibraryProbe([library])
            game = {"source": "Steam", "providerGameId": "224760"}
            self.assertEqual(50, probe.sample(game, "install")["progress"])

            (steamapps / "common" / "FEZ").mkdir(parents=True)
            manifest.write_text(manifest.read_text(encoding="utf-8").replace(
                '"1026"', '"4"').replace('"50"', '"100"'), encoding="utf-8")
            self.assertTrue(probe.sample(game, "install")["installed"])

            manifest.unlink()
            self.assertTrue(probe.sample(game, "uninstall")["uninstalled"])

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
        self.assertGreaterEqual(WindowProbe.installation_candidate_score(baseline, known), 5)
        self.assertLess(WindowProbe.installation_candidate_score(
            baseline, background_launcher), 5)
        self.assertGreaterEqual(WindowProbe.installation_candidate_score(baseline, generic), 5)
        self.assertEqual(0, WindowProbe.installation_candidate_score(baseline, unrelated))
        self.assertEqual(0, WindowProbe.installation_candidate_score(baseline, unchanged))

    def test_steam_web_helper_modal_is_a_known_launcher(self):
        baseline = {"foreground_hwnd": 1, "windows": {}}
        modal = {"hwnd": 2, "image": "steamwebhelper.exe", "title": "Odinstaluj",
                 "foreground": False, "bounds": [100, 100, 700, 400],
                 "monitor_bounds": [0, 0, 1920, 1080]}
        self.assertGreaterEqual(
            WindowProbe.installation_candidate_score(baseline, modal), 5)

    def test_epic_manifest_confirms_completed_install(self):
        with tempfile.TemporaryDirectory() as temporary:
            manifests = Path(temporary) / "Epic" / "EpicGamesLauncher" / "Data" / "Manifests"
            install = Path(temporary) / "Games" / "IntoTheBreach"
            manifests.mkdir(parents=True)
            install.mkdir(parents=True)
            (manifests / "game.item").write_text(json.dumps({
                "DisplayName": "Into The Breach", "AppName": "Blobfish",
                "InstallLocation": str(install),
            }), encoding="utf-8")
            with mock.patch.dict(os.environ, {"PROGRAMDATA": temporary}):
                result = WindowProbe.external_installation_completion({
                    "pluginName": "Epic", "pluginId": "Blobfish",
                    "name": "Into The Breach",
                })
            self.assertEqual(str(install), result["install_directory"])
            self.assertEqual("epic", result["provider"])

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
        with mock.patch("PlayniteBridge.ctypes.get_last_error", return_value=234):
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
        self.state = BridgeState()
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
        self.state.confirm_installation_action = lambda hwnd, operation: (
            confirmations.append((hwnd, operation)) or {"clicked": True})
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
        self.assertEqual([(77, "install")], confirmations)
        self.assertFalse(self.state.library_page(
            "0", 10)["games"][0]["installRequiresAttention"])
        self.assertEqual("game-installation-auto-confirmed", self.state.events[-1]["event"])

    def test_steam_attention_is_hidden_while_automation_is_running(self):
        release = threading.Event()
        started = threading.Event()
        def confirm(_hwnd, _operation):
            started.set()
            release.wait(1)
            return {"clicked": True}
        self.state.confirm_installation_action = confirm
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
        self.state.confirm_installation_action = lambda hwnd, operation: (
            confirmations.append((hwnd, operation)) or {"clicked": True})
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
        self.assertEqual([(88, "uninstall")], confirmations)
        self.assertEqual("game-installation-auto-confirmed", self.state.events[-1]["event"])

    def test_external_completion_finishes_session_and_syncs_connector(self):
        self.state.handle_message({"type": "games", "payload": [{
            "id": GAME_ID, "name": "Into The Breach", "installed": False,
            "pluginName": "Epic", "pluginId": "Blobfish",
        }]})
        self.state.install_game(GAME_ID)
        self.state.apply_installation_probe(GAME_ID, {
            "installed": True, "requires_attention": False,
            "provider": "epic", "install_directory": r"E:\Games\IntoTheBreach",
        })
        game = self.state.library_page("0", 10)["games"][0]
        self.assertTrue(game["installed"])
        self.assertFalse(game["installing"])
        self.assertEqual("game-installed", self.state.events[-1]["event"])
        self.assertEqual({
            "type": "command", "command": "mark-installed", "id": GAME_ID,
            "install_directory": r"E:\Games\IntoTheBreach",
        }, self.commands[-1])

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
        self.assertIn("gameInstallationCancelled", patched)
        self.assertIn("installing      = [bool]$g.IsInstalling", patched)
        self.assertNotIn("/game/prepare", patched)
        second, changed_again = patch_text(patched)
        self.assertFalse(changed_again)
        self.assertEqual(patched, second)

    def test_connector_v8_is_upgraded_with_library_source(self):
        patched, changed = patch_text(PATCH_MARKER_V8 + "\n"
                                      + INSTALL_UI_REPLACEMENT + "\n"
                                      + INSTALL_READER_REPLACEMENT + "\n"
                                      + SOURCE_PAYLOAD_ANCHOR)
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

if __name__ == "__main__":
    unittest.main()
