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

    def test_epic_missing_legendary_only_uses_egl_for_install(self):
        game = {"id": "game-id", "source": "Epic", "providerGameId": "App_Name-1"}
        with mock.patch("GameOperations.os.name", "nt"), \
                mock.patch("GameOperations.os.startfile", create=True) as startfile:
            result = self.service.dispatch_install(game, mock.Mock())
        startfile.assert_called_once_with(
            "com.epicgames.launcher://apps/App_Name-1?action=install")
        self.assertEqual("epic", result["provider"])
        self.assertEqual("egl_auto", result["dispatch"])
        self.assertEqual("legendary_unavailable", result["fallback_reason"])
        sender = mock.Mock()
        uninstall = self.service.dispatch_uninstall(game, sender)
        sender.assert_not_called()
        self.assertFalse(uninstall["accepted"])
        self.assertEqual("none", uninstall["dispatch"])
        self.assertTrue(uninstall["requires_attention"])
        self.assertEqual("legendary_unavailable", uninstall["reason"])
        self.assertIsNone(self.service.manual_attention(game, "install"))
        self.assertTrue(self.service.can_auto_confirm(game, {"image": "epicgameslauncher.exe"}))
    def test_epic_legendary_install_uses_exact_argv_env_and_no_playnite(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            executable = root / "legendary.exe"; executable.touch()
            state = root / "state" / "legendary"
            epic_root = root / "Epic Games"
            command = mock.Mock(return_value=mock.Mock(returncode=0, stdout="{}"))
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
    def test_epic_legendary_uninstall_uses_exact_argv(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary); executable = root / "legendary.exe"; executable.touch()
            command = mock.Mock(side_effect=[
                mock.Mock(returncode=0, stdout="{}"),
                mock.Mock(returncode=0, stdout='[{"app_name":"App"}]'),
            ])
            provider = EpicProvider(legendary_path=executable, legendary_state_path=root / "state",
                command_runner=command, process_runner=mock.Mock(return_value=mock.Mock(stdout=None)))
            provider.dispatch_uninstall({"id": "game", "providerGameId": "App"}, mock.Mock())
            self.assertEqual([str(executable.resolve()), "-y", "uninstall", "App"], provider.process_runner.call_args.args[0])
            self.assertFalse(provider.process_runner.call_args.kwargs["shell"])
    def test_epic_uninstall_already_tracked_skips_egl_import(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary); executable = root / "legendary.exe"; executable.touch()
            command = mock.Mock(side_effect=[
                mock.Mock(returncode=0, stdout="{}"),
                mock.Mock(returncode=0, stdout='[{"app_name":"App"}]'),
            ])
            process_runner = mock.Mock(return_value=mock.Mock(stdout=None))
            provider = EpicProvider(legendary_path=executable, legendary_state_path=root / "state",
                command_runner=command, process_runner=process_runner)

            result = provider.dispatch_uninstall({"id": "game", "providerGameId": "App"}, mock.Mock())

            self.assertEqual("legendary", result["dispatch"])
            self.assertEqual(2, command.call_count)
            self.assertEqual([str(executable.resolve()), "-y", "uninstall", "App"],
                             process_runner.call_args.args[0])

    def test_epic_uninstall_sync_failure_imports_trusted_egl_install(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary); executable = root / "legendary.exe"; executable.touch()
            state = root / "state"
            manifests = root / "Manifests"; manifests.mkdir()
            install = root / "Installed"; install.mkdir()
            (install / "game.bin").touch()
            (manifests / "App.item").write_text(json.dumps({
                "AppName": "App", "InstallLocation": str(install),
            }), encoding="utf-8")
            command = mock.Mock(side_effect=[
                mock.Mock(returncode=0, stdout="{}"),
                mock.Mock(returncode=0, stdout="[]"),
                mock.Mock(returncode=1, stdout="sync failed"),
                mock.Mock(returncode=0, stdout="imported"),
                mock.Mock(returncode=0, stdout='[{"app_name":"App"}]'),
            ])
            process_runner = mock.Mock(return_value=mock.Mock(stdout=None))
            provider = EpicProvider(manifests=manifests, legendary_path=executable, legendary_state_path=state,
                command_runner=command, process_runner=process_runner)

            sender = mock.Mock()
            result = provider.dispatch_uninstall({"id": "game", "providerGameId": "App"}, sender)

            self.assertEqual("legendary", result["dispatch"])
            self.assertEqual([
                str(executable.resolve()), "-y", "egl-sync", "--one-shot", "--import-only",
            ], command.call_args_list[2].args[0])
            import_call = command.call_args_list[3]
            self.assertEqual([
                str(executable.resolve()), "-y", "import", "App", str(install),
                "--platform", "Windows", "--skip-dlcs",
            ], import_call.args[0])
            self.assertEqual(str(state), import_call.kwargs["env"]["LEGENDARY_CONFIG_PATH"])
            self.assertEqual(str(executable.resolve().parent), import_call.kwargs["cwd"])
            self.assertFalse(import_call.kwargs["shell"])
            self.assertNotIn("--disable-check", import_call.args[0])
            self.assertEqual([str(executable.resolve()), "-y", "uninstall", "App"],
                             process_runner.call_args.args[0])
            sender.assert_not_called()

    def test_epic_uninstall_sync_success_but_absent_imports(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary); executable = root / "legendary.exe"; executable.touch()
            manifests = root / "Manifests"; manifests.mkdir()
            install = root / "Installed"; install.mkdir()
            (install / "game.bin").touch()
            (manifests / "App.item").write_text(json.dumps({
                "AppName": "App", "InstallLocation": str(install),
            }), encoding="utf-8")
            command = mock.Mock(side_effect=[
                mock.Mock(returncode=0, stdout="{}"),
                mock.Mock(returncode=0, stdout="[]"),
                mock.Mock(returncode=0, stdout="synced"),
                mock.Mock(returncode=0, stdout="[]"),
                mock.Mock(returncode=0, stdout="imported"),
                mock.Mock(returncode=0, stdout='[{"app_name":"App"}]'),
            ])
            process_runner = mock.Mock(return_value=mock.Mock(stdout=None))
            provider = EpicProvider(manifests=manifests, legendary_path=executable,
                legendary_state_path=root / "state", command_runner=command,
                process_runner=process_runner)

            result = provider.dispatch_uninstall(
                {"id": "game", "providerGameId": "App"}, mock.Mock())

            self.assertEqual("legendary", result["dispatch"])
            self.assertEqual("import", command.call_args_list[4].args[0][2])
            self.assertEqual([str(executable.resolve()), "-y", "uninstall", "App"],
                             process_runner.call_args.args[0])

    def test_epic_uninstall_import_problems_never_use_playnite(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary); executable = root / "legendary.exe"; executable.touch()
            manifests = root / "Manifests"; manifests.mkdir()
            install = root / "Installed"; install.mkdir()
            (install / "game.bin").touch()
            (manifests / "App.item").write_text(json.dumps({
                "AppName": "App", "InstallLocation": str(install),
            }), encoding="utf-8")
            cases = [
                ([mock.Mock(returncode=0, stdout="{}"), mock.Mock(returncode=0, stdout="[]"),
                  mock.Mock(returncode=1, stdout=""), mock.Mock(returncode=1, stdout="")],
                 "legendary_import_failed"),
                ([mock.Mock(returncode=0, stdout="{}"), mock.Mock(returncode=0, stdout="[]"),
                  mock.Mock(returncode=1, stdout=""), mock.Mock(returncode=0, stdout=""),
                  mock.Mock(returncode=1, stdout="")], "legendary_post_import_query_failed"),
                ([mock.Mock(returncode=0, stdout="{}"), mock.Mock(returncode=0, stdout="[]"),
                  mock.Mock(returncode=1, stdout=""), mock.Mock(returncode=0, stdout=""),
                  mock.Mock(returncode=0, stdout="[]")], "legendary_post_import_still_absent"),
            ]
            for responses, reason in cases:
                sender = mock.Mock()
                process_runner = mock.Mock()
                provider = EpicProvider(manifests=manifests, legendary_path=executable,
                    legendary_state_path=root / reason, command_runner=mock.Mock(side_effect=responses),
                    process_runner=process_runner)

                result = provider.dispatch_uninstall(
                    {"id": "game", "providerGameId": "App"}, sender)

                sender.assert_not_called()
                process_runner.assert_not_called()
                self.assertEqual({"accepted": False, "command": "uninstall", "provider": "epic",
                                  "dispatch": "none", "requires_attention": True,
                                  "reason": reason}, result)

    def test_epic_uninstall_rejects_missing_or_unsafe_egl_install(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary); executable = root / "legendary.exe"; executable.touch()
            manifests = root / "Manifests"; manifests.mkdir()
            cases = [None, Path(root.anchor)]
            for directory in cases:
                for path in manifests.glob("*.item"):
                    path.unlink()
                if directory is not None:
                    (manifests / "App.item").write_text(json.dumps({
                        "AppName": "App", "InstallLocation": str(directory),
                    }), encoding="utf-8")
                sender = mock.Mock()
                process_runner = mock.Mock()
                provider = EpicProvider(manifests=manifests, legendary_path=executable,
                    legendary_state_path=root / ("state" + str(len(sender.mock_calls))),
                    command_runner=mock.Mock(side_effect=[
                        mock.Mock(returncode=0, stdout="{}"), mock.Mock(returncode=0, stdout="[]"),
                        mock.Mock(returncode=1, stdout="")]), process_runner=process_runner)

                result = provider.dispatch_uninstall(
                    {"id": "game", "providerGameId": "App"}, sender)

                sender.assert_not_called()
                process_runner.assert_not_called()
                self.assertEqual("legendary_egl_install_missing_or_unsafe" if directory is None
                                 else "epic_manifest_cleanup_unsafe", result["reason"])

    def test_epic_legendary_completion_requires_list_installed_verification(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary); executable = root / "legendary.exe"; executable.touch()
            process = mock.Mock(stdout=None); process.poll.return_value = 0; process.returncode = 0
            command = mock.Mock(side_effect=[
                mock.Mock(returncode=0, stdout="{}"),
                mock.Mock(returncode=0, stdout='[{"app_name":"App","install_path":"D:/Games/App"}]'),
                mock.Mock(returncode=0, stdout="{}")])
            provider = EpicProvider(legendary_path=executable, legendary_state_path=root / "state",
                command_runner=command, process_runner=mock.Mock(return_value=process))
            game = {"id": "game", "providerGameId": "App"}
            provider.dispatch_install(game, mock.Mock())
            sample = provider.sample(game, "install")
            self.assertEqual("D:/Games/App", sample["install_directory"])
            self.assertEqual("egl_sync_complete", sample["reconciliation"])
            self.assertEqual([str(executable.resolve()), "-y", "egl-sync", "--one-shot", "--export-only"],
                             command.call_args_list[-1].args[0])

    def test_epic_auth_and_unresolved_legendary_fall_back_with_distinct_reasons(self):
        with tempfile.TemporaryDirectory() as temporary:
            executable = Path(temporary) / "legendary.exe"; executable.touch()
            game = {"id": "game", "providerGameId": "App", "name": "Exact Name"}
            cases = [
                ([mock.Mock(returncode=1, stdout=""), mock.Mock(returncode=1, stdout="")], "legendary_auth_required"),
                ([mock.Mock(returncode=1, stdout=""), mock.Mock(returncode=0, stdout="[]")], "legendary_unresolved"),
            ]
            for responses, reason in cases:
                provider = EpicProvider(legendary_path=executable, legendary_state_path=Path(temporary) / reason,
                    command_runner=mock.Mock(side_effect=responses))
                with mock.patch("GameOperations.os.name", "nt"), \
                        mock.patch("GameOperations.os.startfile", create=True) as startfile:
                    result = provider.dispatch_install(game, mock.Mock())
                startfile.assert_called_once()
                self.assertEqual("egl_auto", result["dispatch"])
                self.assertEqual(reason, result["fallback_reason"])

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

    def test_epic_uninstall_quarantines_imported_orphan_only_after_export(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary); executable = root / "legendary.exe"; executable.touch()
            manifests = root / "Manifests"; manifests.mkdir()
            install = root / "Installed"; (install / ".egstore").mkdir(parents=True)
            payload = install / "game.bin"; payload.touch()
            manifest = manifests / "App.item"
            content = json.dumps({"AppName": "App", "InstallLocation": str(install)})
            manifest.write_text(content, encoding="utf-8")
            process = mock.Mock(stdout=None); process.poll.return_value = 0; process.returncode = 0
            provider = EpicProvider(manifests=manifests, legendary_path=executable,
                legendary_state_path=root / "state", command_runner=mock.Mock(side_effect=[
                    mock.Mock(returncode=0, stdout="{}"),
                    mock.Mock(returncode=0, stdout="[]"),
                    mock.Mock(returncode=1, stdout="sync failed"),
                    mock.Mock(returncode=0, stdout="imported"),
                    mock.Mock(returncode=0, stdout='[{"app_name":"App"}]'),
                    mock.Mock(returncode=0, stdout="[]"),
                    mock.Mock(returncode=0, stdout="{}"),
                ]), process_runner=mock.Mock(return_value=process))
            game = {"id": "game", "providerGameId": "App"}

            provider.dispatch_uninstall(game, mock.Mock())
            payload.unlink()
            sample = provider.sample(game, "uninstall")

            self.assertTrue(sample["uninstalled"])
            self.assertFalse(manifest.exists())
            quarantined = list((root / "state" / "egl-orphaned-manifests").glob("App-*.item"))
            self.assertEqual(1, len(quarantined))
            self.assertEqual(content, quarantined[0].read_text(encoding="utf-8"))

    def test_epic_sync_import_tracks_manifest_for_orphan_cleanup(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary); executable = root / "legendary.exe"; executable.touch()
            manifests = root / "Manifests"; manifests.mkdir()
            install = root / "Installed"; (install / ".egstore").mkdir(parents=True)
            payload = install / "game.bin"; payload.touch()
            manifest = manifests / "App.item"
            manifest.write_text(json.dumps({"AppName": "App", "InstallLocation": str(install)}), encoding="utf-8")
            process = mock.Mock(stdout=None); process.poll.return_value = 0; process.returncode = 0
            command = mock.Mock(side_effect=[
                mock.Mock(returncode=0, stdout="{}"), mock.Mock(returncode=0, stdout="[]"),
                mock.Mock(returncode=0, stdout="synced"),
                mock.Mock(returncode=0, stdout='[{"app_name":"App"}]'),
                mock.Mock(returncode=0, stdout="[]"), mock.Mock(returncode=0, stdout="{}"),
            ])
            provider = EpicProvider(manifests=manifests, legendary_path=executable,
                legendary_state_path=root / "state", command_runner=command,
                process_runner=mock.Mock(return_value=process))
            game = {"id": "game", "providerGameId": "App"}

            provider.dispatch_uninstall(game, mock.Mock())
            payload.unlink()
            sample = provider.sample(game, "uninstall")

            self.assertTrue(sample["uninstalled"])
            self.assertFalse(manifest.exists())
            self.assertEqual([str(executable.resolve()), "-y", "egl-sync", "--one-shot", "--import-only"],
                             command.call_args_list[2].args[0])
            self.assertEqual([str(executable.resolve()), "-y", "uninstall", "App"],
                             provider.process_runner.call_args.args[0])
            self.assertEqual(1, len(list((root / "state" / "egl-orphaned-manifests").glob("*.item"))))

    def test_epic_uninstall_keeps_manifest_when_payload_remains(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary); executable = root / "legendary.exe"; executable.touch()
            manifests = root / "Manifests"; manifests.mkdir()
            install = root / "Installed"; install.mkdir(); (install / "game.bin").touch()
            manifest = manifests / "App.item"
            manifest.write_text(json.dumps({"AppName": "App", "InstallLocation": str(install)}), encoding="utf-8")
            process = mock.Mock(stdout=None); process.poll.return_value = 0; process.returncode = 0
            provider = EpicProvider(manifests=manifests, legendary_path=executable,
                legendary_state_path=root / "state", command_runner=mock.Mock(side_effect=[
                    mock.Mock(returncode=0, stdout="{}"),
                    mock.Mock(returncode=0, stdout='[{"app_name":"App"}]'),
                    mock.Mock(returncode=0, stdout="[]"),
                    mock.Mock(returncode=0, stdout="{}"),
                ]), process_runner=mock.Mock(return_value=process))
            game = {"id": "game", "providerGameId": "App"}

            provider.dispatch_uninstall(game, mock.Mock())
            sample = provider.sample(game, "uninstall")

            self.assertEqual("epic_manifest_cleanup_unsafe", sample["reason"])
            self.assertTrue(manifest.exists())
            self.assertFalse((root / "state" / "egl-orphaned-manifests").exists())

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

    def test_epic_uninstall_recovers_live_orphan_without_import_or_process(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary); executable = root / "legendary.exe"; executable.touch()
            manifests = root / "Manifests"; manifests.mkdir()
            install = root / "Installed"; (install / ".egstore").mkdir(parents=True)
            manifest = manifests / "App.item"
            manifest.write_text(json.dumps({"AppName": "App", "InstallLocation": str(install)}), encoding="utf-8")
            command = mock.Mock(side_effect=[
                mock.Mock(returncode=0, stdout="{}"), mock.Mock(returncode=0, stdout="[]"),
            ])
            process_runner = mock.Mock()
            provider = EpicProvider(manifests=manifests, legendary_path=executable,
                legendary_state_path=root / "state", command_runner=command,
                process_runner=process_runner)
            game = {"id": "game", "providerGameId": "App"}

            result = provider.dispatch_uninstall(game, mock.Mock())

            self.assertEqual("manifest_cleanup", result["dispatch"])
            self.assertFalse(manifest.exists())
            process_runner.assert_not_called()
            commands = [call.args[0] for call in command.call_args_list]
            self.assertFalse(any(argv[2] in {"egl-sync", "import", "uninstall"}
                                 for argv in commands))
            self.assertTrue(provider.sample(game, "uninstall")["uninstalled"])

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
                command_runner=mock.Mock(return_value=mock.Mock(returncode=0, stdout="{}")),
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
                    mock.Mock(returncode=0, stdout="[]"),
                ]), process_runner=mock.Mock(return_value=process))
            provider.dispatch_install({"id": "game", "providerGameId": "App"}, mock.Mock())

            sample = provider.sample({"id": "game"}, "install")

            self.assertEqual("legendary_verification_failed", sample["reason"])
            self.assertEqual(0, sample["exit_code"])


if __name__ == "__main__":
    unittest.main()
