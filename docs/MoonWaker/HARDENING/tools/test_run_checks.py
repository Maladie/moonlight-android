"""Tests of the trusted-command runner itself, never MoonWaker or a real device."""
import contextlib
import io
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import time
import unittest
from unittest import mock
import packet
import run_checks as runner


def command(code="print('check')", **changes):
    result={'id':'probe','kind':'CHECK','effect':'LOCAL','argv':['{python}','-c',code],
            'cwd':'.','timeout_seconds':5}
    result.update(changes)
    return result


class RunnerTests(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory(); self.addCleanup(self.temp.cleanup)
        self.root=Path(self.temp.name).resolve(); self.out=self.root/'out'; self.out.mkdir()

    def execute(self,check,allow=False,auth=None):
        return runner.run_one(check,self.root,self.out,allow,auth)

    def test_zero_exit_local_check(self):
        result=self.execute(command());self.assertEqual(result['status'],'PASS')
        self.assertEqual(result['exit_code'],0)
        self.assertEqual(result['log_sha256'],runner.digest(Path(result['log_artifact'])))

    def test_actual_unittest_count(self):
        code="import unittest\nclass T(unittest.TestCase):\n def test_one(self): self.assertTrue(True)\nunittest.main()"
        check=command(code,kind='TEST',observation={'format':'unittest_log','minimum_executed':1})
        result=self.execute(check);self.assertEqual(result['status'],'PASS',result)
        self.assertEqual(result['observation']['tests_executed'],1)

    def test_zero_tests_not_green(self):
        result=self.execute(command('import unittest;unittest.main()',kind='TEST',
            observation={'format':'unittest_log','minimum_executed':1}))
        self.assertEqual(result['status'],'FAIL')

    def test_all_skipped_not_green(self):
        check=command(kind='TEST',observation={'format':'unittest_log','minimum_executed':1,'maximum_skipped':10})
        with self.assertRaisesRegex(ValueError,'Insufficient'):
            runner.observe(check,self.root,'Ran 1 test in 0.001s\n\nOK (skipped=1)\n',time.time())

    def test_expected_failure_not_passing_execution(self):
        check=command(kind='TEST',observation={'format':'unittest_log','minimum_executed':1,'maximum_skipped':10})
        with self.assertRaisesRegex(ValueError,'Insufficient'):
            runner.observe(check,self.root,'Ran 1 test in 0.001s\n\nOK (expected failures=1)\n',time.time())

    def test_nonzero_exit_fails(self):
        result=self.execute(command('raise SystemExit(3)'))
        self.assertEqual((result['status'],result['exit_code']),('FAIL',3))

    def test_missing_command_not_run(self):
        result=self.execute(command(argv=[str(self.root/'not-installed')]))
        self.assertEqual(result['status'],'NOT_RUN')

    def test_timeout_fails(self):
        result=self.execute(command('import time; time.sleep(10)',timeout_seconds=0.05))
        self.assertEqual(result['status'],'FAIL');self.assertIn('timed out',result['reason'])

    def test_lab_without_authorization_never_executes(self):
        code="from pathlib import Path;Path('should-not-exist').write_text('bad')"
        result=self.execute(command(code,effect='LAB',device_evidence_path='device.json'),allow=True)
        self.assertEqual(result['status'],'NOT_RUN');self.assertFalse((self.root/'should-not-exist').exists())

    def test_authorized_fixture_lab_requires_observation_file(self):
        result=self.execute(command(effect='LAB',device_evidence_path='missing.json'),allow=True,auth='fixture-only')
        self.assertEqual(result['status'],'FAIL')

    def test_lab_fixture_logs_hash_of_device_file(self):
        # The subprocess merely writes a fake fixture to verify bookkeeping.
        code="from pathlib import Path;Path('device.json').write_text('synthetic fixture, not hardware')"
        result=self.execute(command(code,effect='LAB',device_evidence_path='device.json'),allow=True,auth='fixture-only')
        self.assertEqual(result['status'],'PASS',result)
        self.assertEqual(result['device_evidence_sha256'],runner.digest(self.root/'device.json'))

    def test_path_escape_rejected(self):
        with self.assertRaises(ValueError):runner.within(self.root,'../escape')

    def test_stale_junit_rejected(self):
        p=self.root/'results.xml';p.write_text('<testsuite><testcase name="a"/></testsuite>')
        os.utime(p,(1,1))
        check=command(kind='TEST',observation={'format':'junit_xml','glob':'*.xml','minimum_executed':1})
        with self.assertRaisesRegex(ValueError,'Stale'):runner.observe(check,self.root,'',time.time())

    def test_junit_failure_rejected(self):
        p=self.root/'results.xml';p.write_text('<testsuite><testcase name="a"><failure/></testcase></testsuite>')
        check=command(kind='TEST',observation={'format':'junit_xml','glob':'*.xml','minimum_executed':1})
        with self.assertRaisesRegex(ValueError,'Insufficient'):runner.observe(check,self.root,'',time.time())

    def test_unbound_plan_cannot_execute(self):
        plan=self.root/'plan.json';plan.write_text(json.dumps({'plan_id':'p','task_id':'S00','binding_status':'UNBOUND','checks':[command()]}))
        with contextlib.redirect_stderr(io.StringIO()):
            result=runner.main(['--plan',str(plan),'--repo',str(self.root),'--execute','--output',str(self.root/'evidence')])
        self.assertEqual(result,2);self.assertFalse((self.root/'evidence').exists())

    def test_archived_bound_plan_rejected_before_read_or_output(self):
        plan = self.root / 'unsafe.executed.txt'
        plan.write_text(json.dumps({'binding_status': 'BOUND'}))
        with contextlib.redirect_stderr(io.StringIO()), mock.patch.object(
                Path, 'read_text', side_effect=AssertionError('Archive was read')):
            result = runner.main(['--plan', str(plan), '--repo', str(self.root),
                                  '--execute', '--output', str(self.root/'evidence')])
        self.assertEqual(result, 2)
        self.assertFalse((self.root/'evidence').exists())

    def test_dry_run_does_not_execute(self):
        plan=self.root/'plan.json';plan.write_text(json.dumps({'plan_id':'p','task_id':'S00','binding_status':'UNBOUND',
            'checks':[command("from pathlib import Path;Path('executed').write_text('bad')")]}))
        with contextlib.redirect_stdout(io.StringIO()) as stdout:
            result=runner.main(['--plan',str(plan),'--repo',str(self.root)])
        self.assertEqual(result,0);self.assertIn('DRY_RUN_NOT_EVIDENCE',stdout.getvalue())
        self.assertFalse((self.root/'executed').exists())

    def test_tests_cannot_use_exit_only_observation(self):
        with self.assertRaisesRegex(ValueError,'TEST requires'):
            runner.validate_plan({'plan_id':'p','task_id':'S00','checks':[command(kind='TEST')]})


@unittest.skipUnless(shutil.which('git'),'Git required for candidate-bound runner tests')
class BoundRunnerTests(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.addCleanup(self.temp.cleanup)
        self.repo=Path(self.temp.name).resolve();subprocess.run(['git','init','-q',str(self.repo)],check=True)
        self.root=self.repo/'docs/MoonWaker/HARDENING';self.root.mkdir(parents=True)
        self.patch=mock.patch.object(packet,'ROOT',self.root);self.patch.start();self.addCleanup(self.patch.stop)
        (self.repo/'code.txt').write_text('candidate')
        self.plan=self.root/'run.json'
        self.data={'plan_id':'p','task_id':'S00','binding_status':'BOUND',
            'checkpoint_plan_sha256':'sha256:synthetic-fixture',
            'source_fingerprint':packet.source_fingerprint(self.repo),'checks':[command()]}

    def invoke(self):
        self.plan.write_text(json.dumps(self.data))
        with contextlib.redirect_stdout(io.StringIO()),contextlib.redirect_stderr(io.StringIO()):
            return runner.main(['--plan',str(self.plan),'--repo',str(self.repo),'--output',str(self.root/'evidence'),'--execute'])

    def test_bound_check_records_candidate(self):
        self.assertEqual(self.invoke(),0)
        report=json.loads((self.root/'evidence/gate.json').read_text())
        self.assertEqual(report['source_fingerprint_after'],self.data['source_fingerprint'])
        self.assertEqual(report['status'],'PASS')

    def test_same_output_never_overwritten(self):
        self.assertEqual(self.invoke(),0);self.assertEqual(self.invoke(),2)

    def test_candidate_drift_prevents_execution(self):
        (self.repo/'code.txt').write_text('changed')
        self.assertEqual(self.invoke(),2);self.assertFalse((self.root/'evidence').exists())

    def test_test_mutating_source_cannot_pass_gate(self):
        self.data['checks']=[command("from pathlib import Path;Path('code.txt').write_text('changed by check')")]
        self.assertEqual(self.invoke(),1)
        report=json.loads((self.root/'evidence/gate.json').read_text())
        self.assertEqual(report['status'],'FAIL')
