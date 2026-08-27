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
        self.assertIs(self.steam, self.service.provider_for({"pluginName": "Steam Library"}))
        self.assertIs(self.generic, self.service.provider_for({"source": "GOG"}))
        game = {"source": "Local Games", "pluginName": "Epic"}
        self.assertEqual("local games", self.service.provider_label(game))
        self.assertIs(self.generic, self.service.provider_for(game))

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
