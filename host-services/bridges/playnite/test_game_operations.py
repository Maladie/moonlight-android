import json
import subprocess
import tempfile
import threading
import unittest
from pathlib import Path
from unittest import mock

from GameOperations import (
    EpicProvider, GameOperationsService, GenericPlayniteProvider,
    OperationProcessRegistry, SteamProvider, TrackedOperationProcess,
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
        self.assertIs(self.service.processes, self.epic.process_registry)
        self.assertIs(self.epic, self.service.provider_for({"source": "Epic"}))
        self.assertIs(self.generic, self.service.provider_for({"pluginName": "Steam Library"}))
        self.assertIs(self.steam, self.service.provider_for({"provider": "steam"}))
        self.assertIs(self.generic, self.service.provider_for({"source": "GOG"}))
        game = {"source": "Local Games", "pluginName": "Epic"}
        self.assertEqual("playnite", self.service.provider_label(game))
        self.assertIs(self.generic, self.service.provider_for(game))

    def test_nonsteam_preparation_precedes_dispatch_but_not_steam(self):
        for name, provider in (("epic", self.epic), ("playnite", self.generic), ("steam", self.steam)):
            calls = []
            provider.launch = mock.Mock(side_effect=lambda *args, **kwargs: calls.append("launch") or {"accepted": True})
            prepare = lambda: calls.append("prepare") or {"ready": True}
            result = self.service.launch({"provider": name}, prepare_nonsteam=prepare,
                                         launch_allowed=lambda: True)
            self.assertTrue(result["accepted"])
            self.assertEqual(["launch"] if name == "steam" else ["prepare", "launch"], calls)
        # Provider validation can reject after preparation; it is not an isolation ACK.
        self.epic.launch.return_value = {"accepted": False, "reason": "legendary_not_installed"}
        self.epic.launch.side_effect = None
        self.assertFalse(self.service.launch({"provider": "epic"}, prepare_nonsteam=prepare)["accepted"])

    def test_failed_preparation_or_cancel_after_it_never_dispatches(self):
        self.epic.launch = mock.Mock()
        for ready, allowed, reason in ((False, True, "steam_process_ambiguous"),
                                       (True, False, "launch_cancelled")):
            result = self.service.launch({"provider": "epic"},
                prepare_nonsteam=lambda: {"ready": ready, "reason": "steam_process_ambiguous"},
                launch_allowed=lambda: allowed)
            self.assertEqual(reason, result["reason"])
        self.epic.launch.assert_not_called()

    def test_cancel_during_epic_install_verification_prevents_actual_dispatch(self):
        self.epic._legendary = mock.Mock(return_value=Path("C:/legendary.exe"))
        self.epic._legendary_environment = mock.Mock(return_value={})
        self.epic._resolve_legendary_app = mock.Mock(return_value="App")
        self.epic.process_runner = mock.Mock()
        entered, resume = threading.Event(), threading.Event()
        allowed = True

        def verify(_app):
            entered.set()
            if not resume.wait(2):
                raise AssertionError("test did not release metadata query")
            return True, {"install_directory": "C:/Game", "executable": "game.exe"}

        self.epic._installed_legendary = verify
        results = []
        worker = threading.Thread(target=lambda: results.append(self.service.launch(
            {"id": "epic:App", "provider": "epic"},
            prepare_nonsteam=lambda: {"ready": True}, launch_allowed=lambda: allowed)))
        worker.start()
        self.assertTrue(entered.wait(2))
        allowed = False
        resume.set()
        worker.join(2)
        self.assertFalse(worker.is_alive())
        self.assertEqual("launch_cancelled", results[0]["reason"])
        self.epic.process_runner.assert_not_called()

    def test_epic_scan_requires_finished_egstore_state(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            manifests = root / "Manifests"; manifests.mkdir()
            install = root / "Game"; install.mkdir()
            executable = install / "game.exe"; executable.touch()
            egstore = install / ".egstore"; egstore.mkdir()
            manifest = manifests / "App.item"
            manifest.write_text(json.dumps({
                "AppName": "App", "DisplayName": "Game",
                "InstallLocation": str(install),
                "LaunchExecutable": executable.name,
                "bIsIncompleteInstall": False,
            }), encoding="utf-8")
            provider = EpicProvider(manifests=manifests)

            pending = egstore / "Pending"; pending.mkdir()
            self.assertFalse(provider.scan()["by_id"]["app"]["installed"])
            pending.rmdir()
            component = egstore / "App.mancpn"; component.touch()
            self.assertTrue(provider.scan()["by_id"]["app"]["installed"])
            component.unlink()

            self.assertTrue(provider.scan()["by_id"]["app"]["installed"])

    def test_only_playnite_provider_dispatches_connector_operations(self):
        calls = []

        def send(command, **payload):
            calls.append((command, payload))
            return {"accepted": True, "command": command}

        game = {"id": "game-id", "provider": "playnite",
                "providerGameId": "playnite-id"}
        self.service.dispatch_install(game, send)
        self.service.dispatch_uninstall(game, send)
        self.assertEqual([
            ("install", {"id": "playnite-id"}),
            ("uninstall", {"id": "playnite-id"}),
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

    @mock.patch("winreg.QueryValueEx")
    @mock.patch("winreg.OpenKey")
    def test_steam_root_falls_back_to_machine_install_path(self, open_key, query_value):
        import winreg
        machine_key = mock.MagicMock()
        machine_key.__enter__.return_value = machine_key
        open_key.side_effect = [OSError(), machine_key]
        query_value.return_value = (r"E:\Gry\Steam", winreg.REG_SZ)

        self.assertEqual(Path(r"E:\Gry\Steam"), SteamProvider._steam_root())
        self.assertEqual([
            mock.call(winreg.HKEY_CURRENT_USER, r"Software\Valve\Steam"),
            mock.call(winreg.HKEY_LOCAL_MACHINE, r"SOFTWARE\Valve\Steam"),
        ], open_key.call_args_list)
        query_value.assert_called_once_with(machine_key, "InstallPath")

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

    def test_invalid_app_id_or_missing_executable_never_uses_playnite(self):
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
                result = provider.dispatch_install(game, sender)
                self.assertFalse(result["accepted"])
                self.assertEqual("none", result["dispatch"])

            runner.assert_not_called()
            sender.assert_not_called()

    def test_aggregate_catalog_uses_exact_provider_ids_and_no_title_matching(self):
        self.steam.catalog = mock.Mock(return_value={
            "available": True, "complete": True, "reason": "", "games": [{
                "id": "steam:289070", "provider": "steam",
                "providerGameId": "289070", "name": "Civilization VI",
                "libraryKey": "steam", "libraryName": "Steam",
            }],
        })
        self.epic.catalog = mock.Mock(return_value={
            "available": True, "complete": True, "reason": "", "games": [{
                "id": "epic:CelesteApp", "provider": "epic",
                "providerGameId": "CelesteApp", "name": "Celeste",
                "libraryKey": "epic", "libraryName": "Epic",
            }],
        })
        steam_overlay = {
            "id": "11111111-1111-1111-1111-111111111111", "source": "Steam",
            "providerGameId": "289070", "name": "Other title", "cover": "steam.jpg",
        }
        epic_wrong_case = {
            "id": "22222222-2222-2222-2222-222222222222", "source": "Epic",
            "providerGameId": "celesteapp", "name": "Celeste", "cover": "wrong.jpg",
        }
        same_title_gog = {
            "id": "33333333-3333-3333-3333-333333333333", "source": "GOG",
            "providerGameId": "gog-id", "name": "Celeste",
        }

        result = self.service.aggregate_catalog(
            [steam_overlay, epic_wrong_case, same_title_gog])["library"]

        self.assertEqual(3, len(result))
        self.assertEqual(steam_overlay["id"], result["steam:289070"]["playniteGameId"])
        self.assertNotIn("cover", result["epic:CelesteApp"])
        self.assertEqual("playnite", result[same_title_gog["id"]]["provider"])
        self.assertEqual("gog", result[same_title_gog["id"]]["libraryKey"])

    def test_failed_steam_catalog_preserves_provider_without_reclassification(self):
        self.steam.catalog = mock.Mock(return_value={
            "available": False, "complete": False, "reason": "offline", "games": [],
        })
        self.epic.catalog = mock.Mock(return_value={
            "available": True, "complete": True, "reason": "", "games": [],
        })
        previous = {"steam:10": {
            "id": "steam:10", "provider": "steam", "providerGameId": "10",
            "name": "Owned", "libraryKey": "steam", "libraryName": "Steam",
        }}
        overlay = {
            "id": "44444444-4444-4444-4444-444444444444", "source": "Steam",
            "providerGameId": "10", "name": "Owned",
        }

        result = self.service.aggregate_catalog([overlay], previous)

        self.assertEqual("steam", result["library"]["steam:10"]["provider"])
        self.assertEqual("offline", result["providers"]["steam"]["reason"])

    def test_incomplete_provider_snapshot_preserves_last_authoritative_record(self):
        self.steam.catalog = mock.Mock(return_value={
            "available": True, "complete": False,
            "reason": "steam_manifest_scan_incomplete", "games": [{
                "id": "steam:10", "provider": "steam", "providerGameId": "10",
                "name": "Owned", "installed": False,
            }],
        })
        self.epic.catalog = mock.Mock(return_value={
            "available": True, "complete": True, "reason": "", "games": [],
        })
        previous = {"steam:10": {
            "id": "steam:10", "provider": "steam", "providerGameId": "10",
            "name": "Owned", "installed": True,
        }}

        result = self.service.aggregate_catalog([], previous)

        self.assertTrue(result["library"]["steam:10"]["installed"])
        self.assertFalse(result["providers"]["steam"]["complete"])

    def test_steam_usage_survives_playnite_overlay_and_offline_manifest_refresh(self):
        game = {"id": "steam:10", "provider": "steam", "providerGameId": "10",
                "playtimeMinutes": 120, "lastPlayed": "2026-08-31T10:00:00Z"}
        self.steam.catalog = mock.Mock(return_value={
            "available": True, "complete": True, "games": [game]})
        self.epic.catalog = mock.Mock(return_value={
            "available": True, "complete": True, "games": []})
        metadata = {"id": "44444444-4444-4444-4444-444444444444",
                    "source": "Steam", "providerGameId": "10", "playtimeMinutes": 30,
                    "lastPlayed": "2025-01-01", "cover": "cover.jpg"}
        library = self.service.aggregate_catalog([metadata])["library"]
        self.assertEqual(120, library["steam:10"]["playtimeMinutes"])
        self.assertEqual(game["lastPlayed"], library["steam:10"]["lastPlayed"])
        self.assertEqual("cover.jpg", library["steam:10"]["cover"])

        self.steam.catalog.return_value = {
            "available": True, "complete": False, "installationComplete": True,
            "games": [{"id": "steam:10", "provider": "steam", "providerGameId": "10"}]}
        offline = self.service.aggregate_catalog([metadata], library)["library"]
        self.assertEqual(120, offline["steam:10"]["playtimeMinutes"])
        # An authoritative zero must also win over Playnite and the cache.
        self.steam.catalog.return_value = {
            "available": True, "complete": True, "games": [{**game, "playtimeMinutes": 0}]}
        self.assertEqual(0, self.service.aggregate_catalog([metadata], library)["library"]
                         ["steam:10"]["playtimeMinutes"])

    def test_steam_launch_uses_authoritative_manifest_and_exact_process_contract(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            executable = root / "steam.exe"; executable.touch()
            steamapps = root / "steamapps"; steamapps.mkdir()
            install = steamapps / "common" / "Game"; install.mkdir(parents=True)
            (steamapps / "appmanifest_224760.acf").write_text(
                '"AppState"\n{\n"appid" "224760"\n"StateFlags" "4"\n'
                '"installdir" "Game"\n}', encoding="utf-8")
            process = mock.Mock(returncode=None)
            process.poll.return_value = None
            runner = mock.Mock(return_value=process)
            preflight = mock.Mock(return_value={"ready": True, "started": False})
            sender = mock.Mock()
            provider = SteamProvider(
                roots=[root], root_resolver=lambda: root, command_runner=runner,
                launch_preflight=preflight)

            result = provider.launch({
                "id": "steam:224760", "provider": "steam",
                "providerGameId": "224760",
            }, "launch-task", sender)

            self.assertTrue(result["accepted"])
            self.assertEqual([
                str(executable.resolve()), "steam://launch/224760/Dialog",
            ],
                             runner.call_args.args[0])
            self.assertEqual(str(root.resolve()), runner.call_args.kwargs["cwd"])
            self.assertFalse(runner.call_args.kwargs["shell"])
            preflight.assert_called_once_with(provider)
            sender.assert_not_called()

            # Busy is checked before probing the desktop; no second dispatch.
            self.assertEqual("operation_busy", provider.launch({
                "id": "steam:224760", "providerGameId": "224760"})["reason"])
            self.assertEqual(1, runner.call_count)
            preflight.assert_called_once_with(provider)
            provider.process_registry.remove(provider.process_registry.get("launch-task"))
            for reason in ("host_session_locked", "steam_launch_preflight_unavailable"):
                preflight.return_value = {"ready": False, "reason": reason}
                self.assertFalse(provider.launch({
                    "id": "steam:224760", "providerGameId": "224760"})["accepted"])
                self.assertEqual(1, runner.call_count)
            preflight.return_value = {"ready": True}
            self.assertFalse(provider.launch({"providerGameId": "invalid"})["accepted"])
            self.assertFalse(provider.launch({"providerGameId": "999"})["accepted"])
            executable.unlink()
            self.assertFalse(provider.launch({"providerGameId": "224760"})["accepted"])
            self.assertEqual(1, runner.call_count)

    def test_steam_console_log_is_not_launcher_surface_evidence(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            executable = root / "steam.exe"; executable.touch()
            steamapps = root / "steamapps"; steamapps.mkdir()
            install = steamapps / "common" / "Game"; install.mkdir(parents=True)
            (steamapps / "appmanifest_224760.acf").write_text(
                '"AppState"\n{\n"appid" "224760"\n"StateFlags" "4"\n'
                '"installdir" "Game"\n}', encoding="utf-8")
            logs = root / "logs"; logs.mkdir()
            console_log = logs / "console_log.txt"
            console_log.write_text(
                "GameAction [AppID 224760, ActionID 1] : ShowLaunchOption\n",
                encoding="utf-8")
            process = mock.Mock(returncode=0)
            process.poll.return_value = 0
            provider = SteamProvider(
                roots=[root], root_resolver=lambda: root,
                command_runner=mock.Mock(return_value=process),
                launch_preflight=mock.Mock(return_value={"ready": True}))

            provider.launch({
                "id": "steam:224760", "provider": "steam",
                "providerGameId": "224760",
            }, "launch-task")
            with console_log.open("a", encoding="utf-8") as output:
                output.write(
                    "GameAction [AppID 999, ActionID 2] : ShowLaunchOption\n"
                    "GameAction [AppID 224760, ActionID 3] : "
                    "LaunchApp changed task to ShowLaunchOption with \"\"\n")

            sample = provider.sample_launch({
                "id": "steam:224760", "provider": "steam",
                "providerGameId": "224760",
            }, "launch-task")

            self.assertNotIn("requires_attention", sample)
            self.assertTrue(sample["dispatched"])
            self.assertEqual("steam_launch_dispatched", sample["reason"])
            self.assertIsNotNone(provider.process_registry.get("launch-task"))

    def test_steam_catalog_joins_owned_games_with_local_manifest_truth(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            config = root / "config"; config.mkdir()
            (config / "loginusers.vdf").write_text(
                '"users"\n{\n"76561198000000000"\n{\n'
                '"MostRecent" "1"\n}\n}', encoding="utf-8")
            steamapps = root / "steamapps"; steamapps.mkdir()
            install = steamapps / "common" / "Owned"; install.mkdir(parents=True)
            (steamapps / "appmanifest_289070.acf").write_text(
                '"AppState"\n{\n"appid" "289070"\n"StateFlags" "4"\n'
                '"installdir" "Owned"\n}', encoding="utf-8")
            body = json.dumps({"response": {"games": [{
                "appid": 289070, "name": "Civilization VI",
                "playtime_forever": 120,
            }]}}).encode("utf-8")

            class Response:
                def __enter__(self): return self
                def __exit__(self, *_args): return False
                def read(self, _limit): return body

            opened = []
            provider = SteamProvider(
                roots=[root], root_resolver=lambda: root,
                api_key_path=root / "key.dpapi",
                secret_reader=lambda _path: "A" * 32,
                web_opener=lambda request, timeout: opened.append(
                    (request, timeout)) or Response())

            result = provider.catalog()

            self.assertTrue(result["available"])
            self.assertEqual(1, len(result["games"]))
            self.assertTrue(result["games"][0]["installed"])
            self.assertEqual(str(install), result["games"][0]["installDir"])
            self.assertEqual(8, opened[0][1])
            self.assertEqual("https", opened[0][0].full_url.split(":", 1)[0])

    def test_steam_catalog_without_key_exposes_installed_manifest_subset(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            steamapps = root / "steamapps"; steamapps.mkdir()
            install = steamapps / "common" / "Local"; install.mkdir(parents=True)
            (steamapps / "appmanifest_410110.acf").write_text(
                '"AppState"\n{\n"appid" "410110"\n"name" "Local Game"\n'
                '"StateFlags" "4"\n"installdir" "Local"\n}', encoding="utf-8")
            provider = SteamProvider(roots=[root], root_resolver=lambda: root)

            result = provider.catalog()

            self.assertTrue(result["available"])
            self.assertFalse(result["complete"])
            self.assertEqual("steam_api_key_missing", result["reason"])
            self.assertTrue(result["installationComplete"])
            self.assertEqual("steam:410110", result["games"][0]["id"])
            self.assertTrue(result["games"][0]["installed"])

    def test_steam_catalog_reports_incomplete_local_manifest_discovery(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            config = root / "config"; config.mkdir()
            (config / "loginusers.vdf").write_text(
                '"users"\n{\n"76561198000000000"\n{\n'
                '"MostRecent" "1"\n}\n}', encoding="utf-8")
            (root / "steamapps").mkdir()
            body = json.dumps({"response": {"games": [{
                "appid": 10, "name": "Counter-Strike",
            }]}}).encode("utf-8")

            class Response:
                def __enter__(self): return self
                def __exit__(self, *_args): return False
                def read(self, _limit): return body

            provider = SteamProvider(
                root_resolver=lambda: root, api_key_path=root / "key.dpapi",
                secret_reader=lambda _path: "A" * 32,
                web_opener=lambda _request, timeout: Response())

            result = provider.catalog()

            self.assertTrue(result["available"])
            self.assertFalse(result["complete"])
            self.assertEqual("steam_manifest_scan_incomplete", result["reason"])

    def test_epic_legendary_install_uses_exact_argv_env_and_no_playnite(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            executable = root / "legendary.exe"; executable.touch()
            state = root / "state" / "legendary"
            epic_root = root / "Epic Games"
            command = mock.Mock(side_effect=[
                mock.Mock(returncode=0, stdout="{}"),
                mock.Mock(returncode=0, stdout="[]"),
            ])
            process = mock.Mock(stdout=None)
            provider = EpicProvider(legendary_path=executable, legendary_state_path=state,
                epic_install_root=epic_root,
                command_runner=command, process_runner=mock.Mock(return_value=process))
            sender = mock.Mock()
            result = provider.dispatch_install({"id": "game", "providerGameId": "App_Name-1"}, sender)
            self.assertEqual("legendary", result["dispatch"])
            self.assertEqual([str(executable.resolve()), "-y", "install", "App_Name-1", "--platform", "Windows", "--skip-dlcs", "--skip-sdl", "--base-path", str(epic_root)], provider.process_runner.call_args.args[0])
            self.assertFalse(provider.process_runner.call_args.kwargs["shell"])
            self.assertEqual(str(state), provider.process_runner.call_args.kwargs["env"]["LEGENDARY_CONFIG_PATH"])
            sender.assert_not_called()

    def test_epic_legendary_install_uses_launcher_default_directory(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            executable = root / "legendary.exe"; executable.touch()
            epic_root = root / "Epic Library"
            settings = root / "EpicGamesLauncher" / "Saved" / "Config" / \
                "WindowsEditor" / "GameUserSettings.ini"
            settings.parent.mkdir(parents=True)
            settings.write_text(
                f"[Launcher]\nDefaultAppInstallLocation={epic_root}\n",
                encoding="utf-8")
            command = mock.Mock(side_effect=[
                mock.Mock(returncode=0, stdout="{}"),
                mock.Mock(returncode=0, stdout="[]"),
            ])
            process_runner = mock.Mock(return_value=mock.Mock(stdout=None))
            provider = EpicProvider(
                legendary_path=executable, legendary_state_path=root / "state",
                command_runner=command, process_runner=process_runner)

            with mock.patch.dict("GameOperations.os.environ",
                                 {"LOCALAPPDATA": str(root)}):
                provider.dispatch_install(
                    {"id": "game", "providerGameId": "App"}, mock.Mock())

            self.assertEqual(str(epic_root),
                             process_runner.call_args.args[0][-1])















    def test_epic_uninstall_both_authoritative_sources_absent_skips_repair(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary); executable = root / "legendary.exe"; executable.touch()
            manifests = root / "Manifests"; manifests.mkdir()
            command = mock.Mock(side_effect=[
                mock.Mock(returncode=0, stdout="{}"), mock.Mock(returncode=0, stdout="[]"),
            ])
            process_runner = mock.Mock()
            provider = EpicProvider(manifests=manifests, legendary_path=executable,
                legendary_state_path=root / "state", command_runner=command,
                process_runner=process_runner)
            sender = mock.Mock()

            result = provider.dispatch_uninstall({"id": "game", "providerGameId": "App"}, sender)

            self.assertEqual("already_absent", result["dispatch"])
            sender.assert_not_called(); process_runner.assert_not_called()
            self.assertFalse(any("egl-sync" in call.args[0] or "import" in call.args[0]
                                 for call in command.call_args_list))



    def test_epic_list_installed_failure_cannot_complete_uninstall(self):
        with tempfile.TemporaryDirectory() as temporary:
            executable = Path(temporary) / "legendary.exe"; executable.touch()
            process = mock.Mock(stdout=None); process.poll.return_value = 0; process.returncode = 0
            provider = EpicProvider(legendary_path=executable, legendary_state_path=Path(temporary) / "state",
                command_runner=mock.Mock(side_effect=[mock.Mock(returncode=0, stdout="{}"), mock.Mock(returncode=0, stdout='[{"app_name":"App"}]'), mock.Mock(returncode=1, stdout="")]),
                process_runner=mock.Mock(return_value=process))
            game = {"id": "game", "providerGameId": "App"}
            provider.dispatch_uninstall(game, mock.Mock())
            sample = provider.sample(game, "uninstall")
            self.assertFalse(sample.get("uninstalled", False))
            self.assertEqual("legendary_verification_failed", sample["reason"])





    def test_epic_orphan_cleanup_rejects_untrusted_manifest_paths(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary); manifests = root / "Manifests"; manifests.mkdir()
            install = root / "Installed"; (install / ".egstore").mkdir(parents=True)
            outside = root / "outside.item"
            content = json.dumps({"AppName": "App", "InstallLocation": str(install)})
            outside.write_text(content, encoding="utf-8")
            provider = EpicProvider(manifests=manifests, legendary_state_path=root / "state")
            game = {"providerGameId": "App"}

            for path in (outside, manifests, manifests / "missing.item"):
                self.assertEqual("epic_manifest_cleanup_unsafe", provider._quarantine_egl_orphan(
                    game, "App", str(path), str(install)))
            link = manifests / "link.item"; link.write_text(content, encoding="utf-8")
            original_resolve = Path.resolve
            def escaped(path, strict=False):
                return outside.resolve(strict=strict) if path == link.absolute() \
                    else original_resolve(path, strict=strict)
            with mock.patch.object(Path, "resolve", escaped):
                self.assertEqual("epic_manifest_cleanup_unsafe", provider._quarantine_egl_orphan(
                    game, "App", str(link), str(install)))
            self.assertTrue(outside.exists())
            self.assertTrue(link.exists())

    def test_epic_orphan_cleanup_rejects_changed_manifest_and_move_failure(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary); manifests = root / "Manifests"; manifests.mkdir()
            install = root / "Installed"; (install / ".egstore").mkdir(parents=True)
            manifest = manifests / "App.item"
            provider = EpicProvider(manifests=manifests, legendary_state_path=root / "state")
            game = {"providerGameId": "App"}
            for changed in ({"AppName": "Other", "InstallLocation": str(install)},
                            {"AppName": "App", "InstallLocation": str(root / "Other")}):
                manifest.write_text(json.dumps(changed), encoding="utf-8")
                self.assertEqual("epic_manifest_changed", provider._quarantine_egl_orphan(
                    game, "App", str(manifest), str(install)))
            manifest.write_text(json.dumps({"AppName": "App", "InstallLocation": str(install)}), encoding="utf-8")
            with mock.patch.object(Path, "replace", side_effect=OSError("move failed")):
                self.assertEqual("epic_manifest_cleanup_failed", provider._quarantine_egl_orphan(
                    game, "App", str(manifest), str(install)))
            self.assertTrue(manifest.exists())


    def test_epic_orphan_quarantine_is_unique_and_uses_no_global_egl_flags(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary); manifests = root / "Manifests"; manifests.mkdir()
            install = root / "Installed"; (install / ".egstore").mkdir(parents=True)
            manifest = manifests / "App.item"
            content = json.dumps({"AppName": "App", "InstallLocation": str(install)})
            provider = EpicProvider(manifests=manifests, legendary_state_path=root / "state")
            game = {"providerGameId": "App"}
            with mock.patch("GameOperations.time.time_ns", return_value=1), \
                    mock.patch("GameOperations.time.time", return_value=1):
                for _ in range(2):
                    manifest.write_text(content, encoding="utf-8")
                    self.assertEqual("", provider._quarantine_egl_orphan(
                        game, "App", str(manifest), str(install)))
            self.assertEqual(2, len(list((root / "state" / "egl-orphaned-manifests").glob("*.item"))))
            source = Path(__file__).with_name("GameOperations.py").read_text(encoding="utf-8")
            for forbidden in ("--unlink", "--migrate", "--disable-check"):
                self.assertNotIn(forbidden, source)

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

    def test_complete_installed_manifest_is_not_active_uninstall(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            steamapps = root / "steamapps"
            (steamapps / "common" / "FEZ").mkdir(parents=True)
            (steamapps / "appmanifest_224760.acf").write_text(
                self._steam_manifest(100, 100, 4), encoding="utf-8")
            provider = SteamProvider(roots=[root])
            game = {"source": "Steam", "providerGameId": "224760"}
            baseline = {"steam_baseline": provider.operation_baseline(game)}

            sample = provider.sample(game, "uninstall", baseline)

            self.assertFalse(sample["started"])
            self.assertEqual("not_started", sample["phase"])

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

        self.assertFalse(SteamProvider._started(
            snapshot, baseline, False, "install"))

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



    def test_epic_legendary_process_failure_returns_safe_excerpt(self):
        class InlineThread:
            def __init__(self, *, target, **_kwargs):
                self.target = target

            def start(self):
                self.target()

        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary); executable = root / "legendary.exe"; executable.touch()
            process = mock.Mock(stdout=["access_token=secret", "fatal failure\x01"])
            process.poll.return_value = 7; process.returncode = 7
            provider = EpicProvider(legendary_path=executable,
                legendary_state_path=root / "state",
                command_runner=mock.Mock(side_effect=[
                    mock.Mock(returncode=0, stdout="{}"),
                    mock.Mock(returncode=0, stdout="[]"),
                ]),
                process_runner=mock.Mock(return_value=process))
            with mock.patch("GameOperations.threading.Thread", InlineThread):
                provider.dispatch_install({"id": "game", "providerGameId": "App"}, mock.Mock())

            sample = provider.sample({"id": "game"}, "install")

            self.assertEqual("legendary_process_failed", sample["reason"])
            self.assertEqual(7, sample["exit_code"])
            self.assertEqual("fatal failure", sample["error_excerpt"])
            self.assertNotIn("secret", sample["error_excerpt"])

    def test_epic_legendary_zero_exit_without_verification_is_distinguished(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary); executable = root / "legendary.exe"; executable.touch()
            process = mock.Mock(stdout=None); process.poll.return_value = 0; process.returncode = 0
            provider = EpicProvider(legendary_path=executable,
                legendary_state_path=root / "state", command_runner=mock.Mock(side_effect=[
                    mock.Mock(returncode=0, stdout="{}"),
                    mock.Mock(returncode=0, stdout="[]"), mock.Mock(returncode=0, stdout="[]"),
                ]), process_runner=mock.Mock(return_value=process))
            provider.dispatch_install({"id": "game", "providerGameId": "App"}, mock.Mock())

            sample = provider.sample({"id": "game"}, "install")

            self.assertEqual("legendary_verification_failed", sample["reason"])
            self.assertEqual(0, sample["exit_code"])

    def test_legendary_install_and_uninstall_verify_files_without_epic_side_effects(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            legendary = root / "legendary.exe"; legendary.touch()
            install = root / "Game"; install.mkdir()
            game_exe = install / "game.exe"; game_exe.touch()
            installed = json.dumps([{
                "app_name": "App", "install_path": str(install),
                "executable": game_exe.name,
            }])
            process = mock.Mock(stdout=None, returncode=0)
            process.poll.return_value = 0
            command = mock.Mock(side_effect=[
                mock.Mock(returncode=0, stdout="{}"),
                mock.Mock(returncode=0, stdout="[]"),
                mock.Mock(returncode=0, stdout=installed),
            ])
            runner = mock.Mock(return_value=process)
            playnite = mock.Mock()
            provider = EpicProvider(
                legendary_path=legendary, legendary_state_path=root / "state",
                command_runner=command, process_runner=runner)
            game = {"id": "game", "providerGameId": "App"}

            self.assertTrue(provider.dispatch_install(game, playnite)["accepted"])
            self.assertTrue(provider.sample(game, "install")["installed"])
            self.assertEqual([
                str(legendary.resolve()), "-y", "install", "App", "--platform",
                "Windows", "--skip-dlcs", "--skip-sdl", "--base-path",
                str(provider._install_root()),
            ], runner.call_args.args[0])
            self.assertFalse(any("egl-sync" in call.args[0] for call in command.call_args_list))
            playnite.assert_not_called()

            command.reset_mock(); runner.reset_mock()
            command.side_effect = [
                mock.Mock(returncode=0, stdout="{}"),
                mock.Mock(returncode=0, stdout=installed),
                mock.Mock(returncode=0, stdout="[]"),
            ]
            self.assertTrue(provider.dispatch_uninstall(game, playnite)["accepted"])
            self.assertTrue(provider.sample(game, "uninstall")["uninstalled"])
            self.assertEqual(
                [str(legendary.resolve()), "-y", "uninstall", "App"],
                runner.call_args.args[0])
            self.assertFalse(any("egl-sync" in call.args[0] for call in command.call_args_list))
            playnite.assert_not_called()

    def test_legendary_launch_uses_exact_profile_context_and_requires_real_install(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            legendary = root / "legendary.exe"; legendary.touch()
            install = root / "Game"; install.mkdir()
            game_exe = install / "game.exe"; game_exe.touch()
            installed = json.dumps([{
                "app_name": "App", "install_path": str(install),
                "executable": game_exe.name,
            }])
            process = mock.Mock(stdout=None, returncode=0)
            process.poll.return_value = None
            runner = mock.Mock(return_value=process)
            command = mock.Mock(side_effect=[
                mock.Mock(returncode=0, stdout="{}"),
                mock.Mock(returncode=0, stdout=installed),
            ])
            state = root / "state"
            provider = EpicProvider(
                legendary_path=legendary, legendary_state_path=state,
                command_runner=command, process_runner=runner)

            result = provider.launch({"providerGameId": "App"})

            self.assertTrue(result["accepted"])
            self.assertEqual(str(game_exe), result["executable"])
            self.assertEqual([str(legendary.resolve()), "launch", "App"],
                             runner.call_args.args[0])
            kwargs = runner.call_args.kwargs
            self.assertFalse(kwargs["shell"])
            self.assertEqual(str(legendary.resolve().parent), kwargs["cwd"])
            self.assertEqual(str(state), kwargs["env"]["LEGENDARY_CONFIG_PATH"])

            process.poll.return_value = 0
            provider.sample_launch({"providerGameId": "App"}, result["task_id"])

            runner.reset_mock()
            provider.command_runner = mock.Mock(side_effect=[
                mock.Mock(returncode=0, stdout="{}"),
                mock.Mock(returncode=0, stdout="[]"),
            ])
            missing = provider.launch({"providerGameId": "App"})
            self.assertFalse(missing["accepted"])
            self.assertEqual("legendary_not_installed", missing["reason"])
            runner.assert_not_called()

            failed_process = mock.Mock(stdout=None, returncode=7)
            failed_process.poll.return_value = 7
            provider.process_runner = mock.Mock(return_value=failed_process)
            provider.command_runner = mock.Mock(side_effect=[
                mock.Mock(returncode=0, stdout="{}"),
                mock.Mock(returncode=0, stdout=installed),
            ])
            failed = provider.launch({"providerGameId": "App"})
            self.assertFalse(failed["accepted"])
            self.assertEqual("legendary_process_failed", failed["reason"])

    def test_legacy_install_imports_in_place_and_never_downloads_duplicate(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            legendary = root / "legendary.exe"; legendary.touch()
            manifests = root / "Manifests"; manifests.mkdir()
            install = root / "Existing"; install.mkdir()
            game_exe = install / "game.exe"; game_exe.touch()
            (manifests / "App.item").write_text(json.dumps({
                "AppName": "App", "InstallLocation": str(install),
                "LaunchExecutable": game_exe.name,
            }), encoding="utf-8")
            import_process = mock.Mock(stdout=None)
            import_process.poll.return_value = None
            repair_process = mock.Mock(stdout=None)
            repair_process.poll.return_value = None
            runner = mock.Mock(side_effect=[import_process, repair_process])
            provider = EpicProvider(
                manifests=manifests, legendary_path=legendary,
                legendary_state_path=root / "state",
                command_runner=mock.Mock(side_effect=[
                    mock.Mock(returncode=0, stdout="{}"),
                    mock.Mock(returncode=0, stdout="[]"),
                ]), process_runner=runner)

            result = provider.dispatch_install(
                {"id": "game", "providerGameId": "App"}, mock.Mock())

            self.assertTrue(result["accepted"])
            self.assertEqual("legendary_import", result["dispatch"])
            runner.assert_called_once_with(
                [str(legendary), "-y", "import", "App", str(install),
                 "--platform", "Windows", "--skip-dlcs"],
                shell=False, env=mock.ANY, cwd=str(legendary.parent),
                stdin=subprocess.DEVNULL, stdout=subprocess.PIPE,
                stderr=subprocess.STDOUT, text=True,
                creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))

            import_process.poll.return_value = 0
            import_process.returncode = 0
            verifying = provider.sample(
                {"id": "game", "providerGameId": "App"}, "install")
            self.assertEqual("legendary_repair_running", verifying["reason"])
            self.assertEqual(
                [str(legendary), "-y", "repair", "App", "--platform", "Windows",
                 "--skip-dlcs", "--skip-sdl"],
                runner.call_args_list[1].args[0])
            self.assertFalse(runner.call_args_list[1].kwargs["shell"])
            self.assertEqual(str(legendary.parent), runner.call_args_list[1].kwargs["cwd"])

            repair_process.poll.return_value = 0
            repair_process.returncode = 0
            provider.command_runner = mock.Mock(return_value=mock.Mock(
                returncode=0, stdout=json.dumps([{
                    "app_name": "App", "install_path": str(install),
                    "executable": game_exe.name, "needs_verification": False,
                }])))
            completed = provider.sample(
                {"id": "game", "providerGameId": "App"}, "install")
            self.assertTrue(completed["installed"])
            self.assertEqual(str(install), completed["install_directory"])

            runner.reset_mock()
            provider.command_runner = mock.Mock(side_effect=[
                mock.Mock(returncode=0, stdout="{}"),
                mock.Mock(returncode=0, stdout="[]"),
            ])
            launch = provider.launch({"id": "game", "providerGameId": "App"})
            self.assertFalse(launch["accepted"])
            self.assertEqual("legendary_import_required", launch["reason"])
            runner.assert_not_called()

    def test_legacy_install_with_stale_manifest_executable_imports_real_payload(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            legendary = root / "legendary.exe"; legendary.touch()
            manifests = root / "Manifests"; manifests.mkdir()
            install = root / "Celeste"; install.mkdir()
            (install / "Celeste.bin").write_bytes(b"payload")
            (manifests / "App.item").write_text(json.dumps({
                "AppName": "App", "DisplayName": "Celeste",
                "InstallLocation": str(install),
                "LaunchExecutable": "stale\\Celeste.exe",
                "bIsIncompleteInstall": False,
            }), encoding="utf-8")
            process = mock.Mock(stdout=None)
            process.poll.return_value = None
            runner = mock.Mock(return_value=process)
            provider = EpicProvider(
                manifests=manifests, legendary_path=legendary,
                legendary_state_path=root / "state",
                command_runner=mock.Mock(side_effect=[
                    mock.Mock(returncode=0, stdout="{}"),
                    mock.Mock(returncode=0, stdout="[]"),
                ]), process_runner=runner)

            result = provider.dispatch_install(
                {"id": "game", "name": "Celeste", "providerGameId": "App"},
                mock.Mock(), "task")

            self.assertEqual("legendary_import", result["dispatch"])
            self.assertEqual([
                str(legendary.resolve()), "-y", "import", "App", str(install),
                "--platform", "Windows", "--skip-dlcs",
            ], runner.call_args.args[0])
            self.assertNotIn("--base-path", runner.call_args.args[0])

    def test_legendary_verification_does_not_hold_process_registry_lock(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            legendary = root / "legendary.exe"; legendary.touch()
            install = root / "Game"; install.mkdir()
            game_exe = install / "game.exe"; game_exe.touch()
            process = mock.Mock(stdout=None, returncode=0)
            process.poll.return_value = 0
            registry = OperationProcessRegistry()
            tracked = TrackedOperationProcess(
                task_id="task", game_id="game", provider="epic",
                operation_type="install", process_type="install",
                phase="install", process=process, app_name="App",
                executable=legendary)
            self.assertTrue(registry.register(tracked))
            lock_observed = threading.Event()
            readers = []

            def list_installed(*_args, **_kwargs):
                reader = threading.Thread(
                    target=lambda: (registry.get("task"), lock_observed.set()))
                readers.append(reader)
                reader.start()
                self.assertTrue(lock_observed.wait(0.5))
                return mock.Mock(returncode=0, stdout=json.dumps([{
                    "app_name": "App", "install_path": str(install),
                    "executable": game_exe.name,
                }]))

            provider = EpicProvider(
                legendary_path=legendary, legendary_state_path=root / "state",
                command_runner=mock.Mock(side_effect=list_installed),
                process_registry=registry)

            result = provider.sample(
                {"id": "game", "providerGameId": "App"}, "install", "task")

            for reader in readers:
                reader.join(timeout=1)
            self.assertTrue(result["installed"])
            self.assertIsNone(registry.get("task"))

    def test_legendary_verification_discards_result_after_task_is_replaced(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            legendary = root / "legendary.exe"; legendary.touch()
            install = root / "Game"; install.mkdir()
            game_exe = install / "game.exe"; game_exe.touch()
            completed = mock.Mock(stdout=None, returncode=0)
            completed.poll.return_value = 0
            replacement_process = mock.Mock()
            replacement_process.poll.return_value = None
            registry = OperationProcessRegistry()
            tracked = TrackedOperationProcess(
                task_id="old", game_id="game", provider="epic",
                operation_type="install", process_type="install",
                phase="install", process=completed, app_name="App",
                executable=legendary)
            self.assertTrue(registry.register(tracked))
            replacement = TrackedOperationProcess(
                task_id="new", game_id="game", provider="epic",
                operation_type="install", process_type="install",
                phase="install", process=replacement_process, app_name="App",
                executable=legendary)

            def replace_during_verification(*_args, **_kwargs):
                registry.remove(tracked)
                self.assertTrue(registry.register(replacement))
                return mock.Mock(returncode=0, stdout=json.dumps([{
                    "app_name": "App", "install_path": str(install),
                    "executable": game_exe.name,
                }]))

            provider = EpicProvider(
                legendary_path=legendary, legendary_state_path=root / "state",
                command_runner=mock.Mock(side_effect=replace_during_verification),
                process_registry=registry)

            result = provider.sample(
                {"id": "game", "providerGameId": "App"}, "install", "old")

            self.assertEqual("legendary_operation_stale", result["reason"])
            self.assertIs(replacement, registry.get("new"))
            self.assertIs(replacement, registry.active("game"))

    def test_preinstalled_retry_does_not_download_again(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            legendary = root / "legendary.exe"; legendary.touch()
            install = root / "Game"; install.mkdir()
            game_exe = install / "game.exe"; game_exe.touch()
            installed = json.dumps([{
                "app_name": "App", "install_path": str(install),
                "executable": game_exe.name,
            }])
            runner = mock.Mock()
            provider = EpicProvider(
                legendary_path=legendary, legendary_state_path=root / "state",
                command_runner=mock.Mock(side_effect=[
                    mock.Mock(returncode=0, stdout="{}"),
                    mock.Mock(returncode=0, stdout=installed),
                ]), process_runner=runner)

            result = provider.dispatch_install(
                {"id": "game", "providerGameId": "App"}, mock.Mock())

            self.assertEqual("legendary_preinstalled", result["dispatch"])
            runner.assert_not_called()

    def test_unverified_legendary_record_fails_closed_without_duplicate_download(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            legendary = root / "legendary.exe"; legendary.touch()
            install = root / "Game"; install.mkdir()
            runner = mock.Mock()
            provider = EpicProvider(
                legendary_path=legendary, legendary_state_path=root / "state",
                command_runner=mock.Mock(side_effect=[
                    mock.Mock(returncode=0, stdout="{}"),
                    mock.Mock(returncode=0, stdout=json.dumps([{
                        "app_name": "App", "install_path": str(install),
                        "executable": "missing.exe",
                    }])),
                ]), process_runner=runner)

            result = provider.dispatch_install(
                {"id": "game", "providerGameId": "App"}, mock.Mock())

            self.assertFalse(result["accepted"])
            self.assertEqual("legendary_verification_failed", result["reason"])
            runner.assert_not_called()

    def test_needs_verification_record_fails_closed(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            legendary = root / "legendary.exe"; legendary.touch()
            install = root / "Game"; install.mkdir()
            game_exe = install / "game.exe"; game_exe.touch()
            runner = mock.Mock()
            provider = EpicProvider(
                legendary_path=legendary, legendary_state_path=root / "state",
                command_runner=mock.Mock(side_effect=[
                    mock.Mock(returncode=0, stdout="{}"),
                    mock.Mock(returncode=0, stdout=json.dumps([{
                        "app_name": "App", "install_path": str(install),
                        "executable": game_exe.name, "needs_verification": True,
                    }])),
                ]), process_runner=runner)

            result = provider.dispatch_install(
                {"id": "game", "providerGameId": "App"}, mock.Mock())

            self.assertFalse(result["accepted"])
            self.assertEqual("legendary_verification_failed", result["reason"])
            runner.assert_not_called()

    def test_typed_registry_replaces_finished_uninstall_but_not_live_task(self):
        registry = OperationProcessRegistry()
        finished = mock.Mock(returncode=0)
        finished.poll.return_value = 0
        old = TrackedOperationProcess(
            task_id="old", game_id="game", provider="epic",
            operation_type="uninstall", process_type="uninstall",
            phase="uninstall", process=finished)
        self.assertTrue(registry.register(old))

        running = mock.Mock()
        running.poll.return_value = None
        new = TrackedOperationProcess(
            task_id="new", game_id="game", provider="epic",
            operation_type="install", process_type="install",
            phase="install", process=running)
        self.assertTrue(registry.register(new))
        self.assertIsNone(registry.get("old"))
        self.assertIs(new, registry.active("game"))

        another = TrackedOperationProcess(
            task_id="another", game_id="game", provider="epic",
            operation_type="launch", process_type="launch",
            phase="launch", process=mock.Mock())
        self.assertFalse(registry.register(another))
        self.assertIs(new, registry.active("game"))

    def test_legendary_launch_reports_late_process_failure_for_exact_task(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            legendary = root / "legendary.exe"; legendary.touch()
            install = root / "Game"; install.mkdir()
            game_exe = install / "game.exe"; game_exe.touch()
            installed = json.dumps([{
                "app_name": "App", "install_path": str(install),
                "executable": game_exe.name,
            }])
            process = mock.Mock(stdout=None, returncode=None)
            process.poll.return_value = None
            provider = EpicProvider(
                legendary_path=legendary, legendary_state_path=root / "state",
                command_runner=mock.Mock(side_effect=[
                    mock.Mock(returncode=0, stdout="{}"),
                    mock.Mock(returncode=0, stdout=installed),
                ]), process_runner=mock.Mock(return_value=process))
            game = {"id": "game", "providerGameId": "App"}

            launched = provider.launch(game, "launch-task")
            self.assertTrue(launched["accepted"])
            tracked = provider.process_registry.get("launch-task")
            self.assertEqual("launch", tracked.operation_type)
            self.assertEqual("launch", tracked.process_type)

            tracked.error_excerpt = "game executable rejected"
            process.poll.return_value = 9
            process.returncode = 9
            failed = provider.sample_launch(game, "launch-task")

            self.assertEqual("legendary_process_failed", failed["reason"])
            self.assertEqual(9, failed["exit_code"])
            self.assertEqual("game executable rejected", failed["error_excerpt"])
            self.assertIsNone(provider.process_registry.get("launch-task"))


if __name__ == "__main__":
    unittest.main()
