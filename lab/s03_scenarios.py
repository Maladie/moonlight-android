#!/usr/bin/env python3
"""Bound, deterministic external-controller scenarios for S03."""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import time
import uuid
import xml.etree.ElementTree as ET

import lab_runner


CORRELATION_ASSERTIONS = [
    'fresh_action_attempt', 'exact_terminal', 'single_terminal',
    'settled_no_late_effects',
]
SCENARIO_ASSERTIONS = {
    'success': ['console_ui_only', 'locale_pinned', *CORRELATION_ASSERTIONS,
                'gateway_before_streamer', 'requested_identity_ready', 'streamer_ready',
                'remote_sign_in_granted', 'single_session_attempt', 'single_preparation',
                'single_session', 'single_transport', 'single_launch'],
    'warmup': ['console_ui_only', 'locale_pinned', *CORRELATION_ASSERTIONS,
               'gateway_before_streamer', 'requested_identity_ready', 'streamer_ready',
               'remote_sign_in_granted', 'single_session_attempt', 'single_preparation',
               'single_session', 'single_transport', 'warmup_without_game'],
    'denied': ['console_ui_only', 'locale_pinned', *CORRELATION_ASSERTIONS,
               'remote_sign_in_denied', 'no_session_attempt', 'no_session_effect', 'no_launch'],
    'wrong_identity': ['console_ui_only', 'locale_pinned', *CORRELATION_ASSERTIONS,
                       'wrong_identity_blocked', 'no_takeover', 'no_session_attempt',
                       'no_session_effect', 'no_launch'],
    'timeout_late_ready': ['controlled_pending_fixture_bound', 'timeout_observed',
                           'late_ready_rejected', 'no_launch'],
}
FACT_KEYS = {
    'complete', 'gateway_before_streamer', 'requested_identity_ready', 'streamer_ready',
    'warmup_observed', 'remote_sign_in_granted', 'wrong_identity_active',
    'settled', 'late_effects_observed', 'terminal_outcome', 'terminal_count',
    'action_attempt_count', 'session_attempt_count', 'game_launch_count',
    'logoff_observed', 'switch_observed', 'preparation_count',
    'stream_session_count', 'transport_count',
}
COUNT_KEYS = {key for key in FACT_KEYS if key.endswith('_count')}
KEY_EVENTS = {
    'KEYCODE_DPAD_UP', 'KEYCODE_DPAD_DOWN', 'KEYCODE_DPAD_LEFT',
    'KEYCODE_DPAD_RIGHT', 'KEYCODE_DPAD_CENTER', 'KEYCODE_BACK',
}
ALIAS = re.compile(r'LAB_[A-Z0-9_]{3,80}')
SHA256 = re.compile(r'sha256:[0-9a-f]{64}')
IDENTITY = re.compile(r'[A-Za-z0-9_.:-]{1,120}')


def exact_keys(value: dict, required: set[str], label: str) -> None:
    if not isinstance(value, dict) or set(value) != required:
        raise ValueError(label + ' keys do not match the scenario contract')


def safe_environment() -> dict[str, str]:
    return {key: value for key, value in os.environ.items() if key.upper() in lab_runner.SAFE_ENV}


def bind_command(argv: list[str], cwd: Path, repo: Path, env: dict[str, str]) -> list[str]:
    if not isinstance(argv, list) or not argv or any(not isinstance(x, str) or not x for x in argv):
        raise ValueError('Controller command must be a nonempty argv array')
    bound = [sys.executable if item == '{python}' else item for item in argv]
    script_index = lab_runner.reviewed_script_index(bound)
    if script_index is not None:
        script = Path(bound[script_index])
        script = script.resolve() if script.is_absolute() else (cwd / script).resolve()
        if repo not in script.parents or not script.is_file():
            raise ValueError('Reviewed controller script must exist below the repository')
        bound[script_index] = str(script)
    executable = Path(bound[0])
    if executable.is_absolute() or executable.parent != Path('.'):
        executable = executable.resolve() if executable.is_absolute() else (cwd / executable).resolve()
        if not executable.is_file():
            raise ValueError('Controller executable does not exist')
    else:
        found = shutil.which(bound[0], path=env.get('PATH'))
        if found is None:
            raise ValueError('Controller executable is not on the sanitized PATH')
        executable = Path(found).resolve()
    bound[0] = str(executable)
    for index, item in enumerate(bound):
        lab_runner.reject_secret('', item, f'controller.argv[{index}]')
    return bound


def load_config(path: Path, repo: Path) -> dict:
    config = json.loads(path.read_text(encoding='utf-8'))
    exact_keys(config, {'schema_version', 'binding_status', 'locale', 'android_package',
                        'console_component', 'adb_argv', 'observer_argv', 'cleanup_argv',
                        'cleanup_fixture_alias', 'cleanup_bound', 'poll_seconds',
                        'timeout_seconds', 'scenarios'}, 'config')
    if config['schema_version'] != 1 or config['binding_status'] not in {'BOUND', 'UNBOUND'}:
        raise ValueError('Unsupported scenario config')
    if (not isinstance(config['android_package'], str) or
            not re.fullmatch(r'com\.limelight(?:\.[A-Za-z0-9_.]+)?', config['android_package'])):
        raise ValueError('Android package must be explicitly bound to a MoonWaker variant')
    if config['console_component'] != (config['android_package'] +
                                       '/com.limelight.console.ConsoleActivity'):
        raise ValueError('Only the full production ConsoleActivity component is permitted')
    if not re.fullmatch(r'[a-z]{2}-[A-Z]{2}', config['locale']):
        raise ValueError('A fixed language-region locale is required')
    for key in ('poll_seconds', 'timeout_seconds'):
        value = config[key]
        if isinstance(value, bool) or not isinstance(value, (int, float)) or not 0 < value <= 120:
            raise ValueError(key + ' is outside its bounded range')
    scenarios = config['scenarios']
    if not isinstance(scenarios, dict) or set(scenarios) != set(SCENARIO_ASSERTIONS):
        raise ValueError('All S03 scenario contracts are required')
    aliases: list[str] = []
    for name, scenario in scenarios.items():
        exact_keys(scenario, {'fixture_alias', 'navigation', 'outcome_text', 'fixture_bound'}, name)
        alias = scenario['fixture_alias']
        if not isinstance(alias, str) or not ALIAS.fullmatch(alias):
            raise ValueError(name + ' needs a synthetic LAB_ fixture alias')
        aliases.append(alias)
        if not isinstance(scenario['fixture_bound'], bool):
            raise ValueError(name + '.fixture_bound must be boolean')
        outcome = scenario['outcome_text']
        if outcome is not None and not isinstance(outcome, str):
            raise ValueError(name + '.outcome_text must be string or null')
        if name in {'denied', 'wrong_identity'} and not outcome:
            raise ValueError(name + '.outcome_text is required')
        navigation = scenario['navigation']
        if not isinstance(navigation, list) or not navigation:
            raise ValueError(name + ' needs bounded selector navigation')
        key_count = 0
        for entry in navigation:
            exact_keys(entry, {'selector', 'keys', 'activate'}, name + '.navigation')
            if not isinstance(entry['selector'], str) or not ALIAS.fullmatch(entry['selector']):
                raise ValueError('Navigation selectors must be synthetic LAB_ names')
            aliases.append(entry['selector'])
            if not isinstance(entry['keys'], list) or any(key not in KEY_EVENTS for key in entry['keys']):
                raise ValueError('Navigation permits only named DPAD/back key events')
            if entry['activate'] is not True:
                raise ValueError('Every selected fixture must be activated explicitly')
            key_count += len(entry['keys']) + 1
        if key_count > 32:
            raise ValueError(name + ' navigation exceeds 32 bounded key events')
    if len(aliases) != len(set(aliases)):
        raise ValueError('Synthetic fixture selectors must be globally unique')
    if config['scenarios']['timeout_late_ready']['fixture_bound']:
        raise ValueError('No legitimate controlled pending-attempt fixture is currently bound')
    if (not isinstance(config['cleanup_bound'], bool) or
            not isinstance(config['cleanup_fixture_alias'], str) or
            not ALIAS.fullmatch(config['cleanup_fixture_alias'])):
        raise ValueError('Cleanup needs an explicit synthetic fixture binding')
    adb_argv = config['adb_argv']
    python_adapter = (isinstance(adb_argv, list) and len(adb_argv) >= 2 and
                      Path(adb_argv[0]).name.lower() in
                      {'{python}', 'python', 'python.exe', 'python3', 'python3.exe'})
    if not isinstance(adb_argv, list) or len(adb_argv) != (2 if python_adapter else 1):
        raise ValueError('adb_argv permits only one direct executable or reviewed Python adapter')
    for key in ('observer_argv', 'cleanup_argv'):
        if (not isinstance(config[key], list) or not config[key] or
                any(not isinstance(item, str) for item in config[key])):
            raise ValueError(key + ' must be a direct argv array')
    observer_text = ' '.join(config['observer_argv']).lower()
    if 'hostcontrol' in observer_text and re.search(r'(^|\s)status($|\s)', observer_text):
        raise ValueError('Host Control Status is not a read-only observer')
    if config['binding_status'] == 'BOUND':
        if 'UNBOUND' in json.dumps(config, sort_keys=True):
            raise ValueError('BOUND scenario config contains an UNBOUND placeholder')
        if not config['cleanup_bound']:
            raise ValueError('BOUND scenario config requires reviewed fixture cleanup')
    lab_runner.reject_output_secrets(config, 'config')
    env = safe_environment()
    cwd = path.parent.resolve()
    config['_adb'] = bind_command(config['adb_argv'], cwd, repo, env)
    config['_observer'] = bind_command(config['observer_argv'], cwd, repo, env)
    config['_cleanup'] = bind_command(config['cleanup_argv'], cwd, repo, env)
    config['_cwd'] = cwd
    config['_env'] = env
    config['_sha256'] = 'sha256:' + hashlib.sha256(path.read_bytes()).hexdigest()
    return config


def bind_invocation(config: dict, binding: dict[str, str], scenario: str, step_id: str) -> None:
    exact_keys(binding, {'plan_sha256', 'apk_sha256', 'controller_id',
                         'android_serial', 'host_id'}, 'plan binding')
    if not SHA256.fullmatch(binding['plan_sha256']) or not SHA256.fullmatch(binding['apk_sha256']):
        raise ValueError('Plan and APK SHA-256 bindings are required')
    if any(not IDENTITY.fullmatch(binding[key])
           for key in ('controller_id', 'android_serial', 'host_id')):
        raise ValueError('Plan controller/device/host bindings are invalid')
    config['_binding'] = dict(binding)
    config['_scope_id'] = 'LAB_SCOPE_' + uuid.uuid4().hex.upper()
    config['_scenario'] = scenario
    config['_step_id'] = step_id


def run_direct(argv: list[str], config: dict) -> bytes:
    proc, owner = lab_runner.start_owned_process(argv, config['_cwd'], config['_env'])
    stdout, timed_out = lab_runner.capture_bounded(
        proc, owner, float(config['timeout_seconds']))
    if timed_out or proc.returncode != 0:
        raise ValueError('Controller command failed or exceeded bounded output')
    return stdout


def adb(config: dict, *args: str) -> bytes:
    return run_direct(config['_adb'] + ['-s', config['_binding']['android_serial'], *args], config)


def verify_locale(config: dict) -> None:
    actual = adb(config, 'shell', 'getprop', 'persist.sys.locale').decode('utf-8').strip()
    if actual != config['locale']:
        raise ValueError('Android locale does not match the pinned scenario locale')


def normalize_component(value: str) -> str:
    if value.count('/') != 1:
        raise ValueError('Resolved Android component is malformed')
    package, activity = value.split('/')
    if activity.startswith('.'):
        activity = package + activity
    return package + '/' + activity


def verify_target(config: dict) -> dict[str, bool]:
    verify_locale(config)
    raw_component = adb(config, 'shell', 'cmd', 'package', 'resolve-activity', '--brief',
                        config['console_component']).decode('utf-8')
    lines = [line.strip() for line in raw_component.splitlines() if line.strip()]
    if len(lines) != 1 or normalize_component(lines[0]) != config['console_component']:
        raise ValueError('Resolved Android package/component does not match the bound target')
    raw_paths = adb(config, 'shell', 'pm', 'path', config['android_package']).decode('utf-8')
    paths = [line[8:] for line in raw_paths.splitlines() if line.startswith('package:')]
    if (len(paths) != 1 or len([line for line in raw_paths.splitlines() if line.strip()]) != 1 or
            not paths[0].startswith('/') or any(char.isspace() for char in paths[0])):
        raise ValueError('Installed Android package path is missing or ambiguous')
    raw_digest = adb(config, 'shell', 'toybox', 'sha256sum', paths[0]).decode('utf-8').strip()
    parts = raw_digest.split()
    actual_digest = 'sha256:' + parts[0] if len(parts) == 2 and parts[1] == paths[0] else ''
    if actual_digest != config['_binding']['apk_sha256']:
        raise ValueError('Installed Android artifact SHA-256 does not match the bound APK')
    return {'config_bound': True, 'locale_pinned': True,
            'console_resolvable': True, 'installed_apk_matches': True}


def hierarchy(config: dict) -> ET.Element:
    raw = adb(config, 'shell', 'uiautomator', 'dump', '/dev/tty')
    start = raw.find(b'<?xml')
    if start < 0:
        start = raw.find(b'<hierarchy')
    if start < 0:
        raise ValueError('Android UI hierarchy is missing')
    end = raw.find(b'</hierarchy>', start)
    if end < 0:
        raise ValueError('Android UI hierarchy is incomplete')
    try:
        return ET.fromstring(raw[start:end + len(b'</hierarchy>')])
    except ET.ParseError as exc:
        raise ValueError('Android UI hierarchy is malformed') from exc


def package_nodes(root: ET.Element, package: str) -> list[ET.Element]:
    return [node for node in root.iter() if node.attrib.get('package') == package]


def outcome_nodes(root: ET.Element, text: str, package: str) -> list[ET.Element]:
    return [node for node in package_nodes(root, package)
            if node.attrib.get('text') == text or node.attrib.get('content-desc') == text]


def selector_targets(root: ET.Element, text: str, package: str) -> list[ET.Element]:
    parents = {child: parent for parent in root.iter() for child in parent}
    targets: list[ET.Element] = []
    for node in package_nodes(root, package):
        exact = node.attrib.get('text') == text or node.attrib.get('content-desc') == text
        if not exact:
            continue
        current = node
        while current is not None and current.attrib.get('package') == package:
            if current.attrib.get('focusable') == 'true' or current.attrib.get('clickable') == 'true':
                if current not in targets:
                    targets.append(current)
                break
            current = parents.get(current)
    return targets


def await_node(config: dict, text: str, *, target: bool, focused: bool | None = None) -> None:
    deadline = time.monotonic() + float(config['timeout_seconds'])
    while True:
        root = hierarchy(config)
        matches = (selector_targets(root, text, config['android_package']) if target else
                   outcome_nodes(root, text, config['android_package']))
        if len(matches) > 1:
            raise ValueError('Android selector is ambiguous')
        if len(matches) == 1 and (focused is None or
                                  (matches[0].attrib.get('focused') == 'true') == focused):
            return
        if time.monotonic() >= deadline:
            raise ValueError('Android selector was not observed before the bounded deadline')
        time.sleep(float(config['poll_seconds']))


def open_console(config: dict) -> None:
    adb(config, 'shell', 'am', 'start', '-W', '-n', config['console_component'])


def navigate(config: dict, entries: list[dict]) -> None:
    for entry in entries:
        await_node(config, entry['selector'], target=True)
        for key in entry['keys']:
            adb(config, 'shell', 'input', 'keyevent', key)
        await_node(config, entry['selector'], target=True, focused=True)
        adb(config, 'shell', 'input', 'keyevent', 'KEYCODE_DPAD_CENTER')


def context_args(config: dict, scenario: str, alias: str) -> list[str]:
    binding = config['_binding']
    return ['--scenario', scenario, '--fixture-alias', alias,
            '--scope-id', config['_scope_id'], '--config-sha256', config['_sha256'],
            '--controller-id', binding['controller_id'],
            '--android-serial', binding['android_serial'], '--host-id', binding['host_id']]


def check_context(value: dict, config: dict, scenario: str, alias: str) -> None:
    binding = config['_binding']
    expected = {'scenario': scenario, 'fixture_alias': alias,
                'scope_id': config['_scope_id'], 'config_sha256': config['_sha256'],
                'controller_id': binding['controller_id'],
                'android_serial': binding['android_serial'], 'host_id': binding['host_id']}
    if any(value.get(key) != item for key, item in expected.items()):
        raise ValueError('Controller response correlation does not match the bound invocation')


def observer_snapshot(config: dict, scenario: str, alias: str,
                      after_cursor: int | None = None) -> tuple[int, dict]:
    argv = config['_observer'] + context_args(config, scenario, alias)
    if after_cursor is not None:
        argv += ['--after-cursor', str(after_cursor)]
    raw = run_direct(argv, config)
    try:
        snapshot = json.loads(raw.decode('utf-8'))
    except (UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise ValueError('Host observer output is not one JSON object') from exc
    exact_keys(snapshot, {'schema_version', 'scenario', 'fixture_alias', 'scope_id',
                          'config_sha256', 'controller_id', 'android_serial', 'host_id',
                          'cursor', 'facts'}, 'observer')
    if snapshot['schema_version'] != 1:
        raise ValueError('Unsupported host observer response')
    check_context(snapshot, config, scenario, alias)
    cursor = snapshot['cursor']
    if isinstance(cursor, bool) or not isinstance(cursor, int) or cursor < 0:
        raise ValueError('Host observer cursor is invalid')
    facts = snapshot['facts']
    exact_keys(facts, FACT_KEYS, 'observer.facts')
    for key, value in facts.items():
        if key in COUNT_KEYS:
            if isinstance(value, bool) or not isinstance(value, int) or value < 0:
                raise ValueError('Host observer count is invalid')
        elif key == 'terminal_outcome':
            if value is not None and value not in {'READY', 'REMOTE_SIGN_IN_NOT_GRANTED',
                                                   'OTHER_PROFILE_ACTIVE'}:
                raise ValueError('Host observer terminal outcome is invalid')
        elif not isinstance(value, bool):
            raise ValueError('Host observer fact has the wrong type')
    lab_runner.reject_output_secrets(snapshot, 'observer')
    return cursor, facts


def await_observer(config: dict, scenario: str, alias: str, after_cursor: int) -> dict:
    deadline = time.monotonic() + float(config['timeout_seconds'])
    highest_cursor = after_cursor
    while True:
        cursor, facts = observer_snapshot(config, scenario, alias, after_cursor)
        if cursor <= highest_cursor:
            raise ValueError('Host observer cursor did not advance monotonically')
        highest_cursor = cursor
        if facts['late_effects_observed']:
            raise ValueError('Host observer reported a forbidden late effect')
        if scenario in {'denied', 'wrong_identity'} and (
                facts['logoff_observed'] or facts['switch_observed'] or
                any(facts[key] for key in ('session_attempt_count', 'preparation_count',
                                           'stream_session_count', 'transport_count',
                                           'game_launch_count'))):
            raise ValueError('Host observer reported a forbidden negative-scenario effect')
        if facts['complete']:
            return facts
        if time.monotonic() >= deadline:
            raise ValueError('Host observation did not complete before the bounded deadline')
        time.sleep(float(config['poll_seconds']))


def assertions_for(scenario: str, facts: dict) -> dict[str, bool]:
    terminal = {'success': 'READY', 'warmup': 'READY',
                'denied': 'REMOTE_SIGN_IN_NOT_GRANTED',
                'wrong_identity': 'OTHER_PROFILE_ACTIVE'}.get(scenario)
    common = {'console_ui_only': True, 'locale_pinned': True,
              'fresh_action_attempt': facts.get('action_attempt_count') == 1,
              'exact_terminal': facts.get('terminal_outcome') == terminal,
              'single_terminal': facts.get('terminal_count') == 1,
              'settled_no_late_effects': facts.get('settled') is True and
                                         facts.get('late_effects_observed') is False}
    if scenario == 'success':
        return {**common, 'gateway_before_streamer': facts['gateway_before_streamer'],
                'requested_identity_ready': facts['requested_identity_ready'],
                'streamer_ready': facts['streamer_ready'],
                'remote_sign_in_granted': facts['remote_sign_in_granted'],
                'single_session_attempt': facts['session_attempt_count'] == 1,
                'single_preparation': facts['preparation_count'] == 1,
                'single_session': facts['stream_session_count'] == 1,
                'single_transport': facts['transport_count'] == 1,
                'single_launch': facts['game_launch_count'] == 1}
    if scenario == 'warmup':
        return {**common, 'gateway_before_streamer': facts['gateway_before_streamer'],
                'requested_identity_ready': facts['requested_identity_ready'],
                'streamer_ready': facts['streamer_ready'],
                'remote_sign_in_granted': facts['remote_sign_in_granted'],
                'single_session_attempt': facts['session_attempt_count'] == 1,
                'single_preparation': facts['preparation_count'] == 1,
                'single_session': facts['stream_session_count'] == 1,
                'single_transport': facts['transport_count'] == 1,
                'warmup_without_game': facts['warmup_observed'] and facts['game_launch_count'] == 0}
    if scenario == 'denied':
        return {**common, 'remote_sign_in_denied': not facts['remote_sign_in_granted'],
                'no_session_attempt': facts['session_attempt_count'] == 0,
                'no_session_effect': facts['preparation_count'] == 0 and
                                     facts['stream_session_count'] == 0 and
                                     facts['transport_count'] == 0,
                'no_launch': facts['game_launch_count'] == 0}
    if scenario == 'wrong_identity':
        return {**common, 'wrong_identity_blocked': facts['wrong_identity_active'],
                'no_takeover': not facts['logoff_observed'] and not facts['switch_observed'],
                'no_session_attempt': facts['session_attempt_count'] == 0,
                'no_session_effect': facts['preparation_count'] == 0 and
                                     facts['stream_session_count'] == 0 and
                                     facts['transport_count'] == 0,
                'no_launch': facts['game_launch_count'] == 0}
    return {key: False for key in SCENARIO_ASSERTIONS[scenario]}


def emit(step_id: str, status: str, assertions: dict[str, bool], config: dict) -> None:
    print(json.dumps({'schema_version': 1, 'step_id': step_id, 'status': status,
                      'assertions': assertions,
                      'observations': {'fixture_alias': config.get('_fixture_alias', step_id),
                                       'config_sha256': config['_sha256'],
                                       'scope_id': config['_scope_id']}},
                     sort_keys=True, separators=(',', ':')))


def cleanup(config: dict) -> dict[str, bool]:
    alias = config['cleanup_fixture_alias']
    raw = run_direct(config['_cleanup'] + context_args(config, 'cleanup', alias), config)
    try:
        result = json.loads(raw.decode('utf-8'))
    except (UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise ValueError('Cleanup output is not one JSON object') from exc
    exact_keys(result, {'schema_version', 'scenario', 'fixture_alias', 'scope_id',
                        'config_sha256', 'controller_id', 'android_serial', 'host_id',
                        'assertions'}, 'cleanup')
    check_context(result, config, 'cleanup', alias)
    assertions = result['assertions']
    exact_keys(assertions, {'test_owned_state_restored', 'no_game_running',
                            'no_stream_running'}, 'cleanup.assertions')
    if result['schema_version'] != 1 or any(value is not True for value in assertions.values()):
        raise ValueError('Fixture cleanup did not positively restore scoped state')
    lab_runner.reject_output_secrets(result, 'cleanup')
    return assertions


def execute(config: dict, scenario: str, step_id: str, binding: dict[str, str]) -> None:
    if config['binding_status'] != 'BOUND':
        raise ValueError('UNBOUND scenario config cannot execute')
    bind_invocation(config, binding, scenario, step_id)
    if scenario == 'verify':
        config['_fixture_alias'] = step_id
        assertions = verify_target(config)
    elif scenario == 'cleanup':
        config['_fixture_alias'] = config['cleanup_fixture_alias']
        assertions = cleanup(config)
    elif scenario == 'timeout_late_ready':
        config['_fixture_alias'] = config['scenarios'][scenario]['fixture_alias']
        assertions = assertions_for(scenario, {})
    else:
        definition = config['scenarios'][scenario]
        config['_fixture_alias'] = definition['fixture_alias']
        if not definition['fixture_bound']:
            raise ValueError('Scenario fixture is not bound')
        verify_target(config)
        baseline_cursor, _ = observer_snapshot(config, scenario, definition['fixture_alias'])
        open_console(config)
        navigate(config, definition['navigation'])
        if definition['outcome_text']:
            await_node(config, definition['outcome_text'], target=False)
        facts = await_observer(config, scenario, definition['fixture_alias'], baseline_cursor)
        assertions = assertions_for(scenario, facts)
    if set(assertions) != set(SCENARIO_ASSERTIONS.get(scenario, assertions)) or not all(assertions.values()):
        emit(step_id, 'FAIL', assertions, config)
        raise SystemExit(1)
    emit(step_id, 'PASS', assertions, config)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--repo', type=Path, required=True)
    parser.add_argument('--config', type=Path, required=True)
    parser.add_argument('--scenario', choices=['verify', 'cleanup', *SCENARIO_ASSERTIONS], required=True)
    parser.add_argument('--step-id', required=True)
    parser.add_argument('--plan-sha256', required=True)
    parser.add_argument('--apk-sha256', required=True)
    parser.add_argument('--controller-id', required=True)
    parser.add_argument('--android-serial', required=True)
    parser.add_argument('--host-id', required=True)
    args = parser.parse_args(argv)
    try:
        config = load_config(args.config.resolve(), args.repo.resolve())
        execute(config, args.scenario, args.step_id,
                {'plan_sha256': args.plan_sha256, 'apk_sha256': args.apk_sha256,
                 'controller_id': args.controller_id, 'android_serial': args.android_serial,
                 'host_id': args.host_id})
        return 0
    except (OSError, ValueError, KeyError, TypeError, json.JSONDecodeError,
            subprocess.TimeoutExpired):
        print(json.dumps({'schema_version': 1, 'step_id': args.step_id, 'status': 'FAIL',
                          'assertions': {}, 'observations': {'contract_failed': True}},
                         sort_keys=True, separators=(',', ':')))
        return 1


if __name__ == '__main__':
    raise SystemExit(main())
