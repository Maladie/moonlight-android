"""Focused contracts for S03 external-controller scenarios; no device/host action."""
from __future__ import annotations

import contextlib
import io
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest import mock
import xml.etree.ElementTree as ET

LAB = Path(__file__).resolve().parent
if str(LAB) not in sys.path:
    sys.path.insert(0, str(LAB))

import lab_runner
import s03_scenarios as scenarios


class ScenarioTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.repo = Path(self.temp.name).resolve()
        self.lab = self.repo / 'lab'
        self.lab.mkdir()
        for name in ('fake_adb.py', 'fake_observer.py', 'fake_cleanup.py'):
            (self.lab / name).write_text('', encoding='utf-8')
        template = json.loads((LAB / 's03-scenarios.unbound.json').read_text())
        template['binding_status'] = 'BOUND'
        template['android_package'] = 'com.limelight.debug'
        template['console_component'] = \
            'com.limelight.debug/com.limelight.console.ConsoleActivity'
        template['adb_argv'] = ['{python}', 'fake_adb.py']
        template['observer_argv'] = ['{python}', 'fake_observer.py']
        template['cleanup_argv'] = ['{python}', 'fake_cleanup.py']
        template['cleanup_bound'] = True
        template['poll_seconds'] = 0.001
        template['timeout_seconds'] = 0.02
        for name, definition in template['scenarios'].items():
            definition['fixture_alias'] = definition['fixture_alias'].replace('_UNBOUND', '')
            definition['fixture_bound'] = name != 'timeout_late_ready'
        self.path = self.lab / 'config.json'
        self.path.write_text(json.dumps(template), encoding='utf-8')
        self.config = scenarios.load_config(self.path, self.repo)
        self.binding = {'plan_sha256': 'sha256:' + '1' * 64,
                        'apk_sha256': 'sha256:' + '2' * 64,
                        'controller_id': 'LAB_CONTROLLER',
                        'android_serial': 'LAB_ANDROID', 'host_id': 'LAB_HOST'}
        self.commands: list[list[str]] = []
        self.active = ''
        self.navigation_index = 0
        self.component = self.config['console_component']
        self.device_digest = '2' * 64
        self.response_override = None

    def facts(self, name):
        facts = {key: False for key in scenarios.FACT_KEYS}
        for key in scenarios.COUNT_KEYS:
            facts[key] = 0
        terminal = {'success': 'READY', 'warmup': 'READY',
                    'denied': 'REMOTE_SIGN_IN_NOT_GRANTED',
                    'wrong_identity': 'OTHER_PROFILE_ACTIVE'}.get(name)
        facts.update(complete=True, action_attempt_count=1, terminal_outcome=terminal,
                     terminal_count=1, settled=True, late_effects_observed=False)
        if name in {'success', 'warmup'}:
            facts.update(gateway_before_streamer=True, requested_identity_ready=True,
                         streamer_ready=True, remote_sign_in_granted=True,
                         session_attempt_count=1, preparation_count=1,
                         stream_session_count=1, transport_count=1)
        if name == 'success':
            facts['game_launch_count'] = 1
        elif name == 'warmup':
            facts['warmup_observed'] = True
        elif name == 'wrong_identity':
            facts['wrong_identity_active'] = True
        return facts

    @staticmethod
    def arg(args, name):
        return args[args.index(name) + 1]

    def response_context(self, args):
        return {name[2:].replace('-', '_'): self.arg(args, name)
                for name in ('--scenario', '--fixture-alias', '--scope-id', '--config-sha256',
                             '--controller-id', '--android-serial', '--host-id')}

    def fake_command(self, argv, config):
        self.commands.append(list(argv))
        script = Path(argv[1]).name
        args = argv[2:]
        if script == 'fake_cleanup.py':
            result = {'schema_version': 1, **self.response_context(args),
                      'assertions': {'test_owned_state_restored': True,
                                     'no_game_running': True, 'no_stream_running': True}}
            return json.dumps(result).encode()
        if script == 'fake_observer.py':
            result = {'schema_version': 1, **self.response_context(args),
                      'cursor': 11 if '--after-cursor' in args else 10,
                      'facts': self.facts(self.active)}
            if self.response_override:
                self.response_override(result)
            return json.dumps(result).encode()
        self.assertEqual(args[:2], ['-s', 'LAB_ANDROID'])
        args = args[2:]
        if args == ['shell', 'getprop', 'persist.sys.locale']:
            return b'en-US\n'
        if args[:5] == ['shell', 'cmd', 'package', 'resolve-activity', '--brief']:
            return (self.component + '\n').encode()
        if args == ['shell', 'pm', 'path', 'com.limelight.debug']:
            return b'package:/data/app/LAB/base.apk\n'
        if args == ['shell', 'toybox', 'sha256sum', '/data/app/LAB/base.apk']:
            return (self.device_digest + '  /data/app/LAB/base.apk\n').encode()
        if args[:4] == ['shell', 'am', 'start', '-W']:
            self.navigation_index = 0
            return b'Starting: ConsoleActivity\n'
        if args[:4] == ['shell', 'input', 'keyevent', 'KEYCODE_DPAD_CENTER']:
            self.navigation_index += 1
            return b''
        if args[:3] == ['shell', 'input', 'keyevent']:
            return b''
        if args == ['shell', 'uiautomator', 'dump', '/dev/tty']:
            definition = config['scenarios'][self.active]
            if self.navigation_index < len(definition['navigation']):
                selector = definition['navigation'][self.navigation_index]['selector']
                nodes = (f'<node package="com.limelight.debug" focusable="true" '
                         f'clickable="true" focused="true" content-desc="Card {selector} Ready">'
                         f'<node package="com.limelight.debug" text="{selector}"/>'
                         f'</node><node package="other.app" focusable="true" '
                         f'text="{selector}"/>')
            elif definition['outcome_text']:
                nodes = (f'<node package="com.limelight.debug" '
                         f'text="{definition["outcome_text"]}"/>'
                         f'<node package="other.app" text="{definition["outcome_text"]}"/>')
            else:
                nodes = ''
            return ('<?xml version="1.0"?><hierarchy>' + nodes + '</hierarchy>').encode()
        raise AssertionError('Unexpected controller argv: ' + repr(args))

    def run_scenario(self, name):
        self.active = name
        self.navigation_index = 0
        output = io.StringIO()
        with mock.patch.object(scenarios, 'run_direct', side_effect=self.fake_command), \
             contextlib.redirect_stdout(output):
            scenarios.execute(self.config, name, 'LAB_' + name.upper(), self.binding)
        return json.loads(output.getvalue())

    def test_success_warmup_denial_and_wrong_identity_assert_exact_contracts(self):
        for name in ('success', 'warmup', 'denied', 'wrong_identity'):
            with self.subTest(name=name):
                payload = self.run_scenario(name)
                self.assertEqual(payload['status'], 'PASS')
                self.assertEqual(set(payload['assertions']), set(scenarios.SCENARIO_ASSERTIONS[name]))
                self.assertTrue(all(payload['assertions'].values()))

    def test_target_is_verified_before_ui_effect_and_uses_authoritative_serial(self):
        self.run_scenario('success')
        adb_calls = [call[2:] for call in self.commands if Path(call[1]).name == 'fake_adb.py']
        effect = next(i for i, call in enumerate(adb_calls) if call[2:6] == ['shell', 'am', 'start', '-W'])
        self.assertIn(['-s', 'LAB_ANDROID', 'shell', 'pm', 'path', 'com.limelight.debug'],
                      adb_calls[:effect])
        self.assertIn(['-s', 'LAB_ANDROID', 'shell', 'toybox', 'sha256sum',
                       '/data/app/LAB/base.apk'], adb_calls[:effect])
        starts = [call[2:] for call in adb_calls if call[2:6] == ['shell', 'am', 'start', '-W']]
        self.assertEqual(starts, [['shell', 'am', 'start', '-W', '-n', self.component]])
        flattened = json.dumps(adb_calls)
        self.assertNotIn('Game', flattened)
        self.assertNotIn('--es', flattened)

    def test_wrong_resolved_package_or_installed_digest_fails_before_effect(self):
        original_component = self.component
        for attribute, value, message in (
                ('component', 'com.limelight/com.limelight.console.ConsoleActivity', 'package/component'),
                ('device_digest', '0' * 64, 'artifact SHA-256')):
            with self.subTest(attribute=attribute):
                setattr(self, attribute, value)
                self.commands.clear()
                with mock.patch.object(scenarios, 'run_direct', side_effect=self.fake_command):
                    with self.assertRaisesRegex(ValueError, message):
                        scenarios.execute(self.config, 'success', 'LAB_SUCCESS', self.binding)
                self.assertNotIn("'am', 'start'", repr(self.commands))
                self.component = original_component
                self.device_digest = '2' * 64

    def test_realistic_hierarchy_resolves_child_to_unique_package_bound_ancestor(self):
        xml = ET.fromstring('<hierarchy><node package="com.limelight.debug" focusable="true" '
                            'clickable="true" focused="true" content-desc="Card LAB_PICK Ready">'
                            '<node package="com.limelight.debug" text="LAB_PICK"/></node>'
                            '<node package="other.app" focusable="true" text="LAB_PICK"/></hierarchy>')
        targets = scenarios.selector_targets(xml, 'LAB_PICK', 'com.limelight.debug')
        self.assertEqual(len(targets), 1)
        self.assertEqual(targets[0].attrib['content-desc'], 'Card LAB_PICK Ready')

    def test_unrelated_package_cannot_supply_selector_or_outcome(self):
        raw = (b'<?xml version="1.0"?><hierarchy><node package="other.app" '
               b'focusable="true" focused="true" text="LAB_MISSING"/></hierarchy>')
        with mock.patch.object(scenarios, 'adb', return_value=raw):
            with self.assertRaisesRegex(ValueError, 'bounded deadline'):
                scenarios.await_node(self.config, 'LAB_MISSING', target=True)
            with self.assertRaisesRegex(ValueError, 'bounded deadline'):
                scenarios.await_node(self.config, 'LAB_MISSING', target=False)

    def test_ambiguous_package_bound_selector_fails_closed(self):
        raw = (b'<?xml version="1.0"?><hierarchy>'
               b'<node package="com.limelight.debug" focusable="true" text="LAB_PICK"/>'
               b'<node package="com.limelight.debug" clickable="true" text="LAB_PICK"/>'
               b'</hierarchy>')
        with mock.patch.object(scenarios, 'adb', return_value=raw):
            with self.assertRaisesRegex(ValueError, 'ambiguous'):
                scenarios.await_node(self.config, 'LAB_PICK', target=True)

    def test_observer_request_and_response_are_bound_to_invocation_scope(self):
        self.run_scenario('wrong_identity')
        calls = [call[2:] for call in self.commands if Path(call[1]).name == 'fake_observer.py']
        self.assertEqual(len(calls), 2)
        for name, value in (('--scenario', 'wrong_identity'), ('--controller-id', 'LAB_CONTROLLER'),
                            ('--android-serial', 'LAB_ANDROID'), ('--host-id', 'LAB_HOST'),
                            ('--config-sha256', self.config['_sha256'])):
            self.assertEqual(self.arg(calls[0], name), value)
        self.assertEqual(self.arg(calls[0], '--scope-id'), self.arg(calls[1], '--scope-id'))

    def test_mismatched_observer_identity_or_stale_cursor_fails_closed(self):
        alias = self.config['scenarios']['success']['fixture_alias']
        scenarios.bind_invocation(self.config, self.binding, 'success', 'LAB_SUCCESS')
        self.active = 'success'
        self.response_override = lambda result: result.update(host_id='LAB_OTHER')
        with mock.patch.object(scenarios, 'run_direct', side_effect=self.fake_command):
            with self.assertRaisesRegex(ValueError, 'correlation'):
                scenarios.observer_snapshot(self.config, 'success', alias)
        self.response_override = lambda result: result.update(cursor=7)
        with mock.patch.object(scenarios, 'run_direct', side_effect=self.fake_command):
            with self.assertRaisesRegex(ValueError, 'monotonically'):
                scenarios.await_observer(self.config, 'success', alias, 7)

    def test_each_observer_poll_must_advance_beyond_the_previous_cursor(self):
        incomplete = self.facts('success')
        incomplete['complete'] = False
        with mock.patch.object(scenarios, 'observer_snapshot', side_effect=[
                (20, incomplete), (11, self.facts('success'))]):
            with self.assertRaisesRegex(ValueError, 'monotonically'):
                scenarios.await_observer(self.config, 'success', 'LAB_SUCCESS_FIXTURE', 10)

    def test_incomplete_late_effect_is_immediately_fatal_and_cannot_be_erased(self):
        forbidden = self.facts('success')
        forbidden.update(complete=False, late_effects_observed=True)
        observer = mock.Mock(side_effect=[(20, forbidden), (11, self.facts('success'))])
        with mock.patch.object(scenarios, 'observer_snapshot', observer):
            with self.assertRaisesRegex(ValueError, 'forbidden late effect'):
                scenarios.await_observer(self.config, 'success', 'LAB_SUCCESS_FIXTURE', 10)
        self.assertEqual(observer.call_count, 1)

    def test_incomplete_negative_effect_is_immediately_fatal_and_cannot_be_erased(self):
        forbidden = self.facts('denied')
        forbidden.update(complete=False, session_attempt_count=1, transport_count=1)
        observer = mock.Mock(side_effect=[(20, forbidden), (21, self.facts('denied'))])
        with mock.patch.object(scenarios, 'observer_snapshot', observer):
            with self.assertRaisesRegex(ValueError, 'negative-scenario effect'):
                scenarios.await_observer(self.config, 'denied', 'LAB_DENIED_FIXTURE', 10)
        self.assertEqual(observer.call_count, 1)

    def test_prior_false_negative_passes_are_rejected(self):
        for name in ('denied', 'wrong_identity'):
            facts = self.facts(name)
            facts['action_attempt_count'] = 0
            facts['terminal_count'] = 0
            facts['terminal_outcome'] = None
            assertions = scenarios.assertions_for(name, facts)
            self.assertFalse(assertions['fresh_action_attempt'])
            self.assertFalse(assertions['exact_terminal'])
            self.assertFalse(assertions['single_terminal'])
        facts = self.facts('success')
        facts['session_attempt_count'] = 0
        self.assertFalse(scenarios.assertions_for('success', facts)['single_session_attempt'])

    def test_negative_outcome_text_is_mandatory(self):
        original = json.loads(self.path.read_text())
        for name in ('denied', 'wrong_identity'):
            data = json.loads(json.dumps(original))
            data['scenarios'][name]['outcome_text'] = None
            self.path.write_text(json.dumps(data), encoding='utf-8')
            with self.assertRaisesRegex(ValueError, 'outcome_text is required'):
                scenarios.load_config(self.path, self.repo)

    def test_cleanup_request_and_positive_response_are_scope_bound(self):
        payload = self.run_scenario('cleanup')
        self.assertTrue(all(payload['assertions'].values()))
        call = next(call[2:] for call in self.commands if Path(call[1]).name == 'fake_cleanup.py')
        self.assertEqual(self.arg(call, '--scenario'), 'cleanup')
        self.assertEqual(self.arg(call, '--host-id'), 'LAB_HOST')
        self.assertEqual(self.arg(call, '--config-sha256'), self.config['_sha256'])
        self.assertRegex(self.arg(call, '--scope-id'), r'^LAB_SCOPE_[0-9A-F]{32}$')

    def test_timeout_late_ready_contract_remains_unbound_and_fails(self):
        output = io.StringIO()
        with contextlib.redirect_stdout(output):
            with self.assertRaises(SystemExit):
                scenarios.execute(self.config, 'timeout_late_ready', 'LAB_TIMEOUT', self.binding)
        payload = json.loads(output.getvalue())
        self.assertEqual(payload['status'], 'FAIL')
        self.assertFalse(any(payload['assertions'].values()))

    def test_navigation_is_bounded_and_coordinate_free(self):
        data = json.loads(self.path.read_text())
        data['scenarios']['success']['navigation'][0]['keys'] = ['KEYCODE_DPAD_RIGHT'] * 33
        self.path.write_text(json.dumps(data), encoding='utf-8')
        with self.assertRaisesRegex(ValueError, '32 bounded'):
            scenarios.load_config(self.path, self.repo)
        data['scenarios']['success']['navigation'][0]['keys'] = ['TAP_10_20']
        self.path.write_text(json.dumps(data), encoding='utf-8')
        with self.assertRaisesRegex(ValueError, 'DPAD'):
            scenarios.load_config(self.path, self.repo)

    def test_unbound_plan_uses_authoritative_placeholders_and_dry_runs(self):
        plan_path = LAB / 's03-scenarios-plan.unbound.json'
        plan = json.loads(plan_path.read_text())
        lab_runner.validate_plan(plan)
        encoded = json.dumps(plan)
        for placeholder in ('{plan_sha256}', '{apk_sha256}', '{controller_id}',
                            '{android_serial}', '{host_id}'):
            self.assertIn(placeholder, encoded)
        output = io.StringIO()
        with contextlib.redirect_stdout(output):
            self.assertEqual(lab_runner.main(['--plan', str(plan_path), '--repo', str(LAB.parent)]), 0)
        self.assertEqual(json.loads(output.getvalue())['status'], 'DRY_RUN_NOT_EVIDENCE')

    def test_secret_config_and_host_control_status_are_rejected(self):
        data = json.loads(self.path.read_text())
        data['observer_argv'].append('--token=secret-value')
        self.path.write_text(json.dumps(data), encoding='utf-8')
        with self.assertRaisesRegex(ValueError, 'credential'):
            scenarios.load_config(self.path, self.repo)
        data['observer_argv'] = ['MoonWakerHostControl.exe', 'Status']
        self.path.write_text(json.dumps(data), encoding='utf-8')
        with self.assertRaisesRegex(ValueError, 'not a read-only observer'):
            scenarios.load_config(self.path, self.repo)


if __name__ == '__main__':
    unittest.main()
