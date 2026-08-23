import json
import tempfile
import unittest
from pathlib import Path
from unittest import mock

from GameOperations import (
    EpicProvider, GameOperationsService, GenericPlayniteProvider, SteamProvider,
)
from OperationJournal import OperationJournal


class GameOperationsTest(unittest.TestCase):
    def setUp(self):
        self.generic = GenericPlayniteProvider()
        self.steam = SteamProvider()
        self.epic = EpicProvider()
        self.service = GameOperationsService(
            OperationJournal(None), self.generic, self.steam, self.epic)

    def test_provider_resolver_and_source_precedence(self):
        self.assertIs(self.epic, self.service.provider_for({"source": "Epic"}))
        self.assertIs(self.steam, self.service.provider_for({"pluginName": "Steam Library"}))
        self.assertIs(self.generic, self.service.provider_for({"source": "GOG"}))
        game = {"source": "Local Games", "pluginName": "Epic"}
        self.assertEqual("local games", self.service.provider_label(game))
        self.assertIs(self.generic, self.service.provider_for(game))

    def test_connector_dispatch_for_generic_and_steam(self):
        calls = []

        def send(command, **payload):
            calls.append((command, payload))
            return {"accepted": True, "command": command}

        for source in ("Playnite", "Steam"):
            game = {"id": "game-id", "source": source}
            self.service.dispatch_install(game, send)
            self.service.dispatch_uninstall(game, send)
        self.assertEqual([
            ("install", {"id": "game-id"}), ("uninstall", {"id": "game-id"}),
            ("install", {"id": "game-id"}), ("uninstall", {"id": "game-id"}),
        ], calls)

    def test_steam_install_dispatches_exact_direct_command_without_playnite(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            executable = root / "steam.exe"
            executable.touch()
            runner = mock.Mock()
            sender = mock.Mock()
            provider = SteamProvider(
                root_resolver=lambda: root, command_runner=runner)

            result = provider.dispatch_install({
                "id": "playnite-id", "providerGameId": "224760",
            }, sender)

            self.assertEqual("direct", result["dispatch"])
            self.assertEqual([
                str(executable.resolve()), "-silent", "+app_install", "224760",
            ], runner.call_args.args[0])
            self.assertFalse(runner.call_args.kwargs["shell"])
            sender.assert_not_called()

    def test_steam_uninstall_dispatches_exact_direct_command_without_playnite(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            executable = root / "steam.exe"
            executable.touch()
            runner = mock.Mock()
            sender = mock.Mock()
            provider = SteamProvider(
                root_resolver=lambda: root, command_runner=runner)

            result = provider.dispatch_uninstall({
                "id": "playnite-id", "providerGameId": "212680",
            }, sender)

            self.assertEqual("direct", result["dispatch"])
            self.assertEqual([
                str(executable.resolve()), "-silent", "+app_uninstall", "212680",
            ], runner.call_args.args[0])
            self.assertFalse(runner.call_args.kwargs["shell"])
            sender.assert_not_called()

    def test_invalid_app_id_or_missing_executable_uses_playnite_without_runner(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            runner = mock.Mock()
            sender = mock.Mock(return_value={"accepted": True, "command": "install"})
            provider = SteamProvider(
                root_resolver=lambda: root, command_runner=runner)
            games = [
                {"id": "first", "providerGameId": "+quit"},
                {"id": "second", "providerGameId": "224760"},
            ]

            for game in games:
                self.assertEqual(
                    "playnite", provider.dispatch_install(game, sender)["dispatch"])

            runner.assert_not_called()
            self.assertEqual([
                mock.call("install", id="first"),
                mock.call("install", id="second"),
            ], sender.call_args_list)

    def test_epic_install_uri_uninstall_and_manual_policy(self):
        game = {"id": "game-id", "source": "Epic", "providerGameId": "App_Name-1"}
        with mock.patch("GameOperations.os.name", "nt"), \
                mock.patch("GameOperations.os.startfile", create=True) as startfile:
            result = self.service.dispatch_install(game, mock.Mock())
        startfile.assert_called_once_with(
            "com.epicgames.launcher://apps/App_Name-1?action=install")
        self.assertEqual("epic", result["provider"])
        sender = mock.Mock(return_value={"accepted": True, "command": "uninstall"})
        self.service.dispatch_uninstall(game, sender)
        sender.assert_called_once_with("uninstall", id="game-id")
        self.assertEqual({"reason": "epic_manual", "launcher": "epicgameslauncher.exe"},
                         self.service.manual_attention(game, "install"))
        self.assertFalse(self.service.can_auto_confirm(
            game, {"image": "epicgameslauncher.exe"}))

    def test_steam_launcher_images_and_auto_confirmation_capability(self):
        game = {"source": "Steam"}
        self.assertEqual({"steam.exe", "steamwebhelper.exe"},
                         self.service.expected_launcher_images(game))
        self.assertTrue(self.service.can_auto_confirm(
            game, {"image": "SteamWebHelper.exe"}))

    def test_steam_manifest_progress_completion_uninstall_and_zero_bytes(self):
        with tempfile.TemporaryDirectory() as temporary:
            library = Path(temporary)
            steamapps = library / "steamapps"
            steamapps.mkdir()
            manifest = steamapps / "appmanifest_224760.acf"
            provider = SteamProvider(roots=[library])
            game = {"source": "Steam", "providerGameId": "224760"}
            manifest.write_text(self._steam_manifest(0, 100, 0), encoding="utf-8")
            baseline = {"steam_baseline": provider.operation_baseline(game)}
            self.assertEqual("not_started", provider.sample(
                game, "install", baseline)["phase"])
            manifest.write_text(self._steam_manifest(50, 100, 1026), encoding="utf-8")
            self.assertEqual(50, provider.sample(game, "install", baseline)["progress"])
            (steamapps / "common" / "FEZ").mkdir(parents=True)
            manifest.write_text(self._steam_manifest(100, 100, 4), encoding="utf-8")
            self.assertTrue(provider.sample(game, "install", baseline)["installed"])
            manifest.write_text(self._steam_manifest(50, 100, 1026), encoding="utf-8")
            unchanged = {"steam_baseline": provider.operation_baseline(game)}
            self.assertTrue(provider.sample(game, "install", unchanged)["started"])
            manifest.write_bytes(b"")
            unchanged = {"steam_baseline": provider.operation_baseline(game)}
            self.assertFalse(provider.sample(game, "install", unchanged)["started"])
            manifest.unlink()
            self.assertTrue(provider.sample(game, "uninstall")["uninstalled"])

    def test_empty_manifest_created_after_healthy_absent_baseline_is_not_started(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            steamapps = root / "steamapps"
            steamapps.mkdir()
            provider = SteamProvider(roots=[root])
            game = {"source": "Steam", "providerGameId": "224760"}
            baseline = {"steam_baseline": provider.operation_baseline(game)}
            (steamapps / "appmanifest_224760.acf").write_bytes(b"")

            sample = provider.sample(game, "install", baseline)

            self.assertFalse(sample["started"])
            self.assertEqual("not_started", sample["phase"])

    def test_zero_state_placeholder_manifest_is_not_started(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            steamapps = root / "steamapps"
            steamapps.mkdir()
            provider = SteamProvider(roots=[root])
            game = {"source": "Steam", "providerGameId": "224760"}
            baseline = {"steam_baseline": provider.operation_baseline(game)}
            (steamapps / "appmanifest_224760.acf").write_text(
                self._steam_manifest(0, 0, 0), encoding="utf-8")

            sample = provider.sample(game, "install", baseline)

            self.assertFalse(sample["started"])
            self.assertEqual("not_started", sample["phase"])

    def test_unhealthy_baseline_accepts_strong_current_steam_activity(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            steamapps = root / "steamapps"
            steamapps.mkdir()
            (steamapps / "appmanifest_224760.acf").write_text(
                self._steam_manifest(50, 100, 1026), encoding="utf-8")
            provider = SteamProvider(roots=[root])
            game = {"source": "Steam", "providerGameId": "224760"}
            baseline = {"steam_baseline": {
                "scan_available": False, "scan_complete": False,
                "manifest_present": False,
            }}

            sample = provider.sample(game, "install", baseline)

            self.assertTrue(sample["started"])
            self.assertEqual(50, sample["progress"])

    def test_manifest_signature_only_change_is_not_started(self):
        baseline = {
            "scan_available": True, "scan_complete": True,
            "manifest_present": True, "manifest_readable": True,
            "manifest_signature": ("manifest", 1, 20, 0, 0, 0, ""),
            "download_present": False, "bytes_downloaded": 0,
            "bytes_total": 0, "state_flags": 0,
        }
        snapshot = {**baseline,
                    "manifest_signature": ("manifest", 2, 20, 0, 0, 0, "")}

        self.assertFalse(SteamProvider._started(snapshot, baseline, False))

    def test_incomplete_steam_scan_cannot_report_uninstalled(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root / "steamapps").mkdir()
            inaccessible = root / "missing-secondary-library"
            provider = SteamProvider(roots=[root, inaccessible])

            sample = provider.sample({"providerGameId": "224760"}, "uninstall")

            self.assertFalse(sample["scan_complete"])
            self.assertFalse(sample.get("uninstalled", False))
            self.assertEqual("scan_incomplete", sample["phase"])

    @staticmethod
    def _steam_manifest(downloaded, total, state):
        return f'''"AppState"
{{
    "StateFlags" "{state}"
    "installdir" "FEZ"
    "BytesDownloaded" "{downloaded}"
    "BytesToDownload" "{total}"
}}'''

    def test_epic_stable_id_name_fallback_and_incomplete_scan_safeguards(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            manifests = root / "Manifests"
            install = root / "Installed"
            manifests.mkdir()
            install.mkdir()
            executable = install / "game.exe"
            executable.touch()
            (manifests / "broken.item").write_text("{broken", encoding="utf-8")
            (manifests / "valid.item").write_text(json.dumps({
                "AppName": "stable-app", "DisplayName": "Localized Name",
                "InstallLocation": str(install), "LaunchExecutable": executable.name,
            }), encoding="utf-8")
            provider = EpicProvider(manifests)
            snapshot = provider.scan()
            self.assertTrue(snapshot["available"])
            self.assertFalse(snapshot["complete"])
            self.assertTrue(provider.sample({
                "providerGameId": "stable-app", "name": "Wrong"}, "install")["installed"])
            self.assertTrue(provider.installed_from_snapshot({
                "name": "Localized Name"}, snapshot)["installed"])
            executable.unlink()
            self.assertIsNone(provider.sample({
                "providerGameId": "stable-app"}, "install"))
            self.assertIsNone(provider.sample({
                "providerGameId": "missing-app", "name": "Missing"}, "uninstall"))


if __name__ == "__main__":
    unittest.main()
