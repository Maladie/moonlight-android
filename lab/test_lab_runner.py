"""Contract tests for the deterministic LAB runner; no device action is performed."""
import contextlib
from datetime import datetime, timedelta, timezone
import io
import json
from pathlib import Path
import socket
import subprocess
import sys
import tempfile
import time
import unittest
from unittest import mock

LAB = Path(__file__).resolve().parent
if str(LAB) not in sys.path:
    sys.path.insert(0, str(LAB))

import lab_runner as runner
import packet


HELPER = r'''import json
from pathlib import Path
import sys
import threading

mode, step_id, trace, assertion = sys.argv[1:]
with Path(trace).open('a', encoding='utf-8') as stream:
    stream.write(step_id + '\n')
if mode == 'block':
    threading.Event().wait(60)
elif mode == 'exit':
    raise SystemExit(7)
elif mode == 'huge':
    sys.stdout.write('x' * (300 * 1024))
elif mode == 'malformed':
    print('not-json')
else:
    status = 'FAIL' if mode == 'fail' else 'PASS'
    value = False if mode == 'false' else True
    print(json.dumps({'schema_version': 1, 'step_id': step_id, 'status': status,
                      'assertions': {assertion: value}, 'observations': {'marker': step_id}}))
'''

SPAWNER = r'''import json
from pathlib import Path
import subprocess
import sys
import time

step_id, ready, port = sys.argv[1:]
child = subprocess.Popen([sys.executable, 'fake_child.py', ready, port], stdin=subprocess.DEVNULL,
                         stdout=sys.stdout, stderr=subprocess.DEVNULL)
deadline = time.monotonic() + 5
while not Path(ready).exists() and child.poll() is None and time.monotonic() < deadline:
    pass
if not Path(ready).exists():
    raise SystemExit(3)
print(json.dumps({'schema_version': 1, 'step_id': step_id, 'status': 'PASS',
                  'assertions': {'observed': True}, 'observations': {'marker': step_id}}))
'''

QUIET_SPAWNER = r'''import json
from pathlib import Path
import subprocess
import sys
import time

step_id, ready, port = sys.argv[1:]
child = subprocess.Popen([sys.executable, 'fake_child.py', ready, port], stdin=subprocess.DEVNULL,
                         stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
deadline = time.monotonic() + 5
while not Path(ready).exists() and child.poll() is None and time.monotonic() < deadline:
    pass
if not Path(ready).exists():
    raise SystemExit(3)
print(json.dumps({'schema_version': 1, 'step_id': step_id, 'status': 'PASS',
                  'assertions': {'observed': True}, 'observations': {'marker': step_id}}))
'''

CHILD = r'''from pathlib import Path
import socket
import sys
import threading

ready, port = sys.argv[1:]
listener = socket.socket()
listener.bind(('127.0.0.1', int(port)))
listener.listen()
Path(ready).write_text('ready', encoding='utf-8')
threading.Event().wait(60)
'''


class LabRunnerTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.repo = Path(self.temp.name).resolve()
        subprocess.run(['git', 'init', '-q', str(self.repo)], check=True)
        self.root = self.repo / 'docs/MoonWaker/HARDENING'
        self.root.mkdir(parents=True)
        self.packet_patch = mock.patch.object(packet, 'ROOT', self.root)
        self.packet_patch.start()
        self.addCleanup(self.packet_patch.stop)
        self.helper = self.repo / 'fake_step.py'
        self.helper.write_text(HELPER, encoding='utf-8')
        (self.repo / 'fake_spawner.py').write_text(SPAWNER, encoding='utf-8')
        (self.repo / 'fake_quiet_spawner.py').write_text(QUIET_SPAWNER, encoding='utf-8')
        (self.repo / 'fake_child.py').write_text(CHILD, encoding='utf-8')
        self.apk = self.repo / 'candidate.apk'
        self.apk.write_bytes(b'apk fixture')
        self.trace = self.root / 'trace.txt'
        self.plan_path = self.root / 'plan.json'
        self.output = self.root / 'evidence'

    def step(self, step_id, mode='pass', timeout=5):
        return {'id': step_id,
                'argv': ['{python}', 'fake_step.py', mode, step_id,
                         'docs/MoonWaker/HARDENING/trace.txt', 'observed'],
                'timeout_seconds': timeout,
                'required_assertions': ['observed']}

    def plan(self, bound=True):
        now = datetime.now(timezone.utc)
        plan = {
            'schema_version': 1, 'plan_id': 'fixture', 'task_id': 'S03',
            'binding_status': 'BOUND' if bound else 'UNBOUND',
            'source_fingerprint': packet.source_fingerprint(self.repo) if bound else None,
            'apk': {'path': 'candidate.apk', 'sha256': runner.digest(self.apk) if bound else None},
            'device': {'controller_id': 'controller-fixture', 'android_serial': 'android-fixture',
                       'host_id': 'host-fixture'} if bound else {
                           'controller_id': 'UNBOUND', 'android_serial': 'UNBOUND', 'host_id': 'UNBOUND'},
            'authorization': ({'reference': 'AUTH-123', 'scope': 'fixture LAB only',
                               'not_before_utc': (now - timedelta(minutes=1)).isoformat(),
                               'expires_at_utc': (now + timedelta(hours=1)).isoformat()} if bound else None),
            'steps': {'setup': [self.step('setup')], 'scenarios': [self.step('scenario')],
                      'cleanup': [self.step('cleanup')]},
        }
        return plan

    def invoke(self, plan, execute=True, output=None):
        self.plan_path.write_text(json.dumps(plan), encoding='utf-8')
        argv = ['--plan', str(self.plan_path), '--repo', str(self.repo)]
        if execute:
            argv += ['--execute', '--authorization-ref', 'AUTH-123',
                     '--output', str(output or self.output)]
        with contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
            return runner.main(argv)

    def test_dry_run_never_executes_unbound_plan(self):
        self.assertEqual(self.invoke(self.plan(False), execute=False), 0)
        self.assertFalse(self.trace.exists())
        self.assertFalse(self.output.exists())

    def test_source_and_apk_bindings_fail_before_execution(self):
        plan = self.plan()
        plan['source_fingerprint'] = 'sha256:' + '0' * 64
        self.assertEqual(self.invoke(plan), 2)
        self.assertFalse(self.output.exists())

    def test_plan_placeholders_are_injected_only_from_authoritative_top_level_bindings(self):
        plan = self.plan()
        plan['steps']['setup'][0]['argv'].extend([
            '{plan_sha256}', '{source_fingerprint}', '{apk_sha256}',
            '{controller_id}', '{android_serial}', '{host_id}'])
        captured = {}

        def capture_plan(_plan, _repo, out, _plan_hash, _source, _apk, _apk_hash, prepared):
            captured['argv'] = prepared['setup'][1]
            return {'status': 'PASS'}

        self.plan_path.write_text(json.dumps(plan), encoding='utf-8')
        expected_plan_hash = runner.digest(self.plan_path)
        with mock.patch.object(runner, 'execute_plan', side_effect=capture_plan):
            self.assertEqual(self.invoke(plan), 0)
        self.assertEqual(captured['argv'][-6:], [
            expected_plan_hash, plan['source_fingerprint'], plan['apk']['sha256'],
            plan['device']['controller_id'], plan['device']['android_serial'],
            plan['device']['host_id']])

        plan['steps']['setup'][0]['argv'][-1] = '{different_host_id}'
        with self.assertRaisesRegex(ValueError, 'unknown plan placeholder'):
            runner.validate_plan(plan)
        plan = self.plan()
        plan['apk']['sha256'] = 'sha256:' + '0' * 64
        self.assertEqual(self.invoke(plan), 2)
        self.assertFalse(self.output.exists())

    def test_steps_are_sequential_and_evidence_is_hashed(self):
        plan = self.plan()
        plan['steps']['scenarios'].append(self.step('scenario-two'))
        self.assertEqual(self.invoke(plan), 0)
        self.assertEqual(self.trace.read_text().splitlines(), ['setup', 'scenario', 'scenario-two', 'cleanup'])
        report = json.loads((self.output / 'lab-result.json').read_text())
        self.assertEqual(report['status'], 'PASS')
        self.assertEqual(report['source_fingerprint_after'], plan['source_fingerprint'])
        self.assertEqual(report['apk_sha256_after'], plan['apk']['sha256'])
        self.assertEqual(report['plan_sha256'], runner.digest(self.plan_path))
        for step in report['steps']:
            self.assertEqual(step['log_sha256'], runner.digest(Path(step['log_artifact'])))

    def test_failure_is_fail_fast_but_cleanup_always_runs(self):
        plan = self.plan()
        plan['steps']['scenarios'] = [self.step('fail', 'fail'), self.step('must-skip')]
        self.assertEqual(self.invoke(plan), 1)
        self.assertEqual(self.trace.read_text().splitlines(), ['setup', 'fail', 'cleanup'])
        report = json.loads((self.output / 'lab-result.json').read_text())
        self.assertEqual(report['status'], 'FAIL')
        self.assertEqual(report['skipped_steps'], [{'id': 'must-skip', 'phase': 'scenarios'}])

    def test_timeout_fails_and_cleanup_runs(self):
        plan = self.plan()
        plan['steps']['scenarios'] = [self.step('blocked', 'block', 0.5)]
        self.assertEqual(self.invoke(plan), 1)
        report = json.loads((self.output / 'lab-result.json').read_text())
        blocked = next(step for step in report['steps'] if step['id'] == 'blocked')
        self.assertTrue(blocked['timed_out'])
        self.assertEqual(self.trace.read_text().splitlines(), ['setup', 'blocked', 'cleanup'])

    def test_successful_parent_cannot_leave_a_descendant_or_stdout_open(self):
        listener = socket.socket()
        listener.bind(('127.0.0.1', 0))
        port = listener.getsockname()[1]
        listener.close()
        plan = self.plan()
        plan['steps']['scenarios'] = [{
            'id': 'spawn-descendant',
            'argv': ['{python}', 'fake_spawner.py', 'spawn-descendant',
                     'docs/MoonWaker/HARDENING/child-ready', str(port)],
            'timeout_seconds': 0.5,
            'required_assertions': ['observed'],
        }]
        started = time.monotonic()
        self.assertEqual(self.invoke(plan), 0)
        self.assertLess(time.monotonic() - started, 1.5)
        probe = socket.socket()
        self.addCleanup(probe.close)
        probe.bind(('127.0.0.1', port))

    @unittest.skipUnless(sys.platform == 'win32', 'Windows Job completion contract')
    def test_runner_waits_for_quiet_windows_job_descendant_to_exit(self):
        listener = socket.socket()
        listener.bind(('127.0.0.1', 0))
        port = listener.getsockname()[1]
        listener.close()
        plan = self.plan()
        plan['steps']['scenarios'] = [{
            'id': 'spawn-quiet-descendant',
            'argv': ['{python}', 'fake_quiet_spawner.py', 'spawn-quiet-descendant',
                     'docs/MoonWaker/HARDENING/quiet-child-ready', str(port)],
            'timeout_seconds': 0.5,
            'required_assertions': ['observed'],
        }]
        self.assertEqual(self.invoke(plan), 0)
        probe = socket.socket()
        self.addCleanup(probe.close)
        probe.bind(('127.0.0.1', port))

    def test_all_cwds_are_validated_before_any_effect(self):
        plan = self.plan()
        plan['steps']['cleanup'] = [self.step('bad-cleanup'), self.step('later-cleanup')]
        plan['steps']['cleanup'][0]['cwd'] = '../'
        self.assertEqual(self.invoke(plan), 2)
        self.assertFalse(self.trace.exists())
        self.assertFalse(self.output.exists())

    def test_missing_cleanup_executable_is_rejected_before_any_effect(self):
        plan = self.plan()
        plan['steps']['cleanup'][0]['argv'] = ['moonwaker-lab-missing-executable']
        self.assertEqual(self.invoke(plan), 2)
        self.assertFalse(self.trace.exists())
        self.assertFalse(self.output.exists())

    def test_cleanup_runtime_failure_does_not_skip_later_cleanup(self):
        plan = self.plan()
        failed = self.step('failed-cleanup', 'exit')
        plan['steps']['cleanup'] = [failed, self.step('later-cleanup')]
        self.assertEqual(self.invoke(plan), 1)
        report = json.loads((self.output / 'lab-result.json').read_text())
        cleanup = [step for step in report['steps'] if step['phase'] == 'cleanup']
        self.assertEqual([step['id'] for step in cleanup], ['failed-cleanup', 'later-cleanup'])
        self.assertEqual([step['status'] for step in cleanup], ['FAIL', 'PASS'])
        self.assertEqual(self.trace.read_text().splitlines(),
                         ['setup', 'scenario', 'failed-cleanup', 'later-cleanup'])

    def test_interrupted_cleanup_log_failure_does_not_skip_later_cleanup_or_report(self):
        plan = self.plan()
        plan['steps']['cleanup'] = [self.step('interrupted-cleanup'), self.step('later-cleanup')]
        original_run_step_guarded = runner.run_step_guarded
        original_write_json_once = runner.write_json_once

        def interrupt_first_cleanup(step, phase, index, prepared, out):
            if step['id'] == 'interrupted-cleanup':
                raise KeyboardInterrupt
            return original_run_step_guarded(step, phase, index, prepared, out)

        def fail_interrupted_log(path, value):
            if path.name == '003-cleanup-interrupted-cleanup.json':
                path.write_text('{', encoding='utf-8')
                raise OSError('injected evidence write failure')
            return original_write_json_once(path, value)

        with mock.patch.object(runner, 'run_step_guarded', side_effect=interrupt_first_cleanup), \
             mock.patch.object(runner, 'write_json_once', side_effect=fail_interrupted_log):
            self.assertEqual(self.invoke(plan), 1)

        report = json.loads((self.output / 'lab-result.json').read_text())
        cleanup = [step for step in report['steps'] if step['phase'] == 'cleanup']
        self.assertEqual([step['id'] for step in cleanup], ['interrupted-cleanup', 'later-cleanup'])
        self.assertIn('evidence log failed: OSError', cleanup[0]['reason'])
        self.assertEqual(cleanup[1]['status'], 'PASS')
        self.assertEqual(self.trace.read_text().splitlines(), ['setup', 'scenario', 'later-cleanup'])

    def test_command_eval_and_implicit_shell_forms_are_rejected(self):
        commands = [
            ['powershell.exe', '-c', 'Write-Output example'],
            ['powershell.exe', '-e', 'example'],
            ['pwsh.exe', 'Write-Output example'],
            ['sh.exe', '-c', 'echo example'],
            ['{python}', '-c', 'print(1)'],
            ['fixture.cmd', 'example'],
        ]
        for argv in commands:
            with self.subTest(argv=argv):
                plan = self.plan()
                plan['steps']['setup'][0]['argv'] = argv
                with self.assertRaises(ValueError):
                    runner.validate_plan(plan)

    def test_malformed_or_non_json_observation_fails_closed(self):
        plan = self.plan()
        plan['steps']['scenarios'] = [self.step('bad-json', 'malformed')]
        self.assertEqual(self.invoke(plan), 1)
        report = json.loads((self.output / 'lab-result.json').read_text())
        bad = next(step for step in report['steps'] if step['id'] == 'bad-json')
        self.assertIn('exactly one UTF-8 JSON object', bad['reason'])

    def test_step_output_is_bounded_before_it_is_retained(self):
        plan = self.plan()
        plan['steps']['scenarios'] = [self.step('huge-output', 'huge')]
        self.assertEqual(self.invoke(plan), 1)
        report = json.loads((self.output / 'lab-result.json').read_text())
        failed = next(step for step in report['steps'] if step['id'] == 'huge-output')
        self.assertIn('bounded limit', failed['reason'])

    def test_false_or_missing_assertion_cannot_pass(self):
        plan = self.plan()
        plan['steps']['scenarios'] = [self.step('false-assertion', 'false')]
        self.assertEqual(self.invoke(plan), 1)
        report = json.loads((self.output / 'lab-result.json').read_text())
        failed = next(step for step in report['steps'] if step['id'] == 'false-assertion')
        self.assertIn('assertions', failed['reason'])

    def test_secret_argv_or_env_is_rejected_before_output(self):
        for mutation in ('argv', 'env', 'authorization'):
            with self.subTest(mutation=mutation):
                plan = self.plan()
                if mutation == 'argv':
                    plan['steps']['setup'][0]['argv'].append('--password=hunter2')
                elif mutation == 'env':
                    plan['steps']['setup'][0]['env'] = {'ACCESS_TOKEN': 'hunter2'}
                else:
                    plan['authorization']['reference'] = 'Bearer hunter2'
                self.assertEqual(self.invoke(plan, output=self.root / ('evidence-' + mutation)), 2)
                self.assertFalse((self.root / ('evidence-' + mutation)).exists())

    def test_execution_requires_matching_unexpired_authorization(self):
        plan = self.plan()
        plan['authorization']['reference'] = 'AUTH-OTHER'
        self.assertEqual(self.invoke(plan, output=self.root / 'evidence-mismatch'), 2)
        self.assertFalse((self.root / 'evidence-mismatch').exists())
        plan = self.plan()
        past = datetime.now(timezone.utc) - timedelta(hours=1)
        plan['authorization']['not_before_utc'] = (past - timedelta(minutes=1)).isoformat()
        plan['authorization']['expires_at_utc'] = past.isoformat()
        self.assertEqual(self.invoke(plan, output=self.root / 'evidence-expired'), 2)
        self.assertFalse((self.root / 'evidence-expired').exists())

    def test_existing_evidence_directory_is_never_overwritten(self):
        plan = self.plan()
        self.assertEqual(self.invoke(plan), 0)
        original = (self.output / 'lab-result.json').read_bytes()
        self.assertEqual(self.invoke(plan), 2)
        self.assertEqual((self.output / 'lab-result.json').read_bytes(), original)


if __name__ == '__main__':
    unittest.main()
