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
            manifest.write_text(self._steam_manifest(50, 100, 1026), encoding="utf-8")
            provider = SteamProvider(roots=[library])
            game = {"source": "Steam", "providerGameId": "224760"}
            self.assertEqual(50, provider.sample(game, "install")["progress"])
            (steamapps / "common" / "FEZ").mkdir(parents=True)
            manifest.write_text(self._steam_manifest(100, 100, 4), encoding="utf-8")
            self.assertTrue(provider.sample(game, "install")["installed"])
            manifest.write_text(self._steam_manifest(0, 100, 1026), encoding="utf-8")
            self.assertIsNone(provider.sample(game, "install"))
            manifest.write_bytes(b"")
            self.assertIsNone(provider.sample(game, "install"))
            manifest.unlink()
            self.assertTrue(provider.sample(game, "uninstall")["uninstalled"])

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
