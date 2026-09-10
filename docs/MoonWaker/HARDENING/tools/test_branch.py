"""Read-only workspace-guard tests in disposable repositories, not MoonWaker tests."""
import copy
import json
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

import packet


@unittest.skipUnless(shutil.which('git'), 'Git unavailable; branch-guard tests not run')
class BranchTests(unittest.TestCase):
    def setUp(self):
        self.bundle = packet.load()
        self.original_root = packet.ROOT
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.git('init', '-q')
        self.git('config', 'user.email', 'packet-test@example.invalid')
        self.git('config', 'user.name', 'Packet test')
        self.git('checkout', '-q', '-b', 'codex/multi-profile-remote-sign-in')
        (self.root / 'source.txt').write_text('baseline\n', encoding='utf-8')
        self.git('add', 'source.txt')
        self.git('commit', '-q', '-m', 'Synthetic fixture baseline')
        self.base = self.git('rev-parse', 'HEAD')
        self.git('checkout', '-q', '--no-track', '-b', 'codex/session-hardening', self.base)
        packet.ROOT = self.root / 'docs/MoonWaker/HARDENING'
        packet.ROOT.mkdir(parents=True)
        self.addCleanup(setattr, packet, 'ROOT', self.original_root)
        self.report = {
            'status': 'PREPARED', 'repository': 'Maladie/moonlight-android',
            'remote': 'origin', 'base_branch': 'codex/multi-profile-remote-sign-in',
            'work_branch': 'codex/session-hardening',
            'fetched_remote_head': self.base, 'selected_base_head': self.base,
            'branch_creation_head': self.base, 'local_base_head': self.base,
            'local_vs_remote': 'EQUAL', 'selection_reason': 'Synthetic equal tips',
            'fetch_succeeded': True, 'remote_identity_verified': True,
            'commands_and_observed_results': ['SYNTHETIC FIXTURE: no network fetch performed'],
            'observed_at_utc': '2026-09-09T00:00:00Z', 'blockers': []
        }
        self.bundle['state']['workspace'].update({
            'status': 'PREPARED', 'base_head': self.base,
            'fetched_remote_head': self.base, 'branch_creation_head': self.base,
            'branch_setup_report': 'branch-setup.json'
        })
        self.bundle['state']['baseline_head'] = self.base
        self.bundle['manifest']['execution_head'] = self.base
        self.save_report()

    def git(self, *args):
        result = subprocess.run(['git', '-C', str(self.root), *args],
                                stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                                check=True)
        return result.stdout.decode().strip()

    def save_report(self):
        (packet.ROOT / 'branch-setup.json').write_text(json.dumps(self.report), encoding='utf-8')

    def check(self):
        return packet.branch_check(self.root, self.bundle)

    def test_prepared_branch_passes_without_mutation(self):
        before = self.git('status', '--porcelain')
        state = copy.deepcopy(self.bundle['state'])
        result = self.check()
        self.assertEqual(result['status'], 'PREPARED_WORK_BRANCH_VERIFIED')
        self.assertEqual(before, self.git('status', '--porcelain'))
        self.assertEqual(state, self.bundle['state'])
        self.assertEqual(self.base, self.git('rev-parse', 'HEAD'))

    def test_unprepared_workspace_rejected(self):
        self.bundle['state']['workspace']['status'] = 'NOT_PREPARED'
        with self.assertRaisesRegex(ValueError, 'not prepared'): self.check()

    def test_base_branch_rejected(self):
        self.git('checkout', '-q', 'codex/multi-profile-remote-sign-in')
        with self.assertRaisesRegex(ValueError, 'Wrong work branch'): self.check()

    def test_detached_head_rejected(self):
        self.git('checkout', '-q', '--detach', self.base)
        with self.assertRaises(ValueError): self.check()

    def test_missing_report_rejected(self):
        self.bundle['state']['workspace']['branch_setup_report'] = None
        with self.assertRaisesRegex(ValueError, 'Missing branch setup report'): self.check()

    def test_unperformed_fetch_rejected(self):
        self.report['fetch_succeeded'] = False
        self.save_report()
        with self.assertRaisesRegex(ValueError, 'Actual base fetch'): self.check()

    def test_unverified_remote_rejected(self):
        self.report['remote_identity_verified'] = False
        self.save_report()
        with self.assertRaisesRegex(ValueError, 'remote identity'): self.check()

    def test_divergence_cannot_be_marked_prepared(self):
        self.report['local_vs_remote'] = 'DIVERGED'
        self.save_report()
        with self.assertRaisesRegex(ValueError, 'Divergent'): self.check()

    def test_missing_local_branch_supported(self):
        self.report.update(local_vs_remote='MISSING', local_base_head=None)
        self.save_report()
        self.check()

    def test_abbreviated_hash_rejected(self):
        self.bundle['state']['workspace']['base_head'] = self.base[:8]
        with self.assertRaisesRegex(ValueError, 'full resolved commit'): self.check()

    def test_initial_head_mismatch_rejected(self):
        self.bundle['state']['workspace']['branch_creation_head'] = 'a' * 40
        with self.assertRaisesRegex(ValueError, 'Initial branch HEAD'): self.check()

    def test_committed_resume_does_not_refork(self):
        self.git('commit', '-q', '--allow-empty', '-m', 'Synthetic later change')
        current = self.git('rev-parse', 'HEAD')
        self.assertEqual(self.check()['head'], current)
        self.assertNotEqual(current, self.base)
        self.assertEqual(self.bundle['state']['workspace']['base_head'], self.base)

    def test_owned_uncommitted_resume_work_preserved(self):
        (self.root / 'source.txt').write_text('candidate edit\n', encoding='utf-8')
        self.check()
        self.assertEqual((self.root / 'source.txt').read_text(), 'candidate edit\n')

    def test_local_ahead_tip_supported(self):
        fetched = self.base
        self.git('commit', '-q', '--allow-empty', '-m', 'Synthetic local base successor')
        local = self.git('rev-parse', 'HEAD')
        self.report.update(local_vs_remote='AHEAD', local_base_head=local,
                           selected_base_head=local, branch_creation_head=local,
                           fetched_remote_head=fetched)
        self.bundle['state']['workspace'].update(base_head=local, branch_creation_head=local)
        self.bundle['state']['baseline_head'] = local
        self.bundle['manifest']['execution_head'] = local
        self.save_report()
        self.check()

    def test_selected_base_cannot_lack_fetched_commits(self):
        self.git('commit', '-q', '--allow-empty', '-m', 'Synthetic fetched successor')
        fetched = self.git('rev-parse', 'HEAD')
        self.report.update(local_vs_remote='AHEAD', fetched_remote_head=fetched)
        self.bundle['state']['workspace']['fetched_remote_head'] = fetched
        self.save_report()
        with self.assertRaisesRegex(ValueError, 'Git command failed'): self.check()

    def test_unrelated_worktree_history_rejected(self):
        self.git('checkout', '-q', '--orphan', 'temporary-orphan')
        self.git('commit', '-q', '-m', 'Synthetic unrelated history')
        self.git('branch', '-D', 'codex/session-hardening')
        self.git('branch', '-m', 'codex/session-hardening')
        with self.assertRaisesRegex(ValueError, 'Git command failed'): self.check()

    def test_evidence_repo_mismatch_rejected(self):
        self.report['repository'] = 'another/repository'
        self.save_report()
        with self.assertRaisesRegex(ValueError, 'repository mismatch'): self.check()

    def test_packet_and_repo_roots_must_match(self):
        actual = packet.ROOT
        packet.ROOT = self.root / 'noncanonical'
        packet.ROOT.mkdir()
        self.save_report()
        try:
            with self.assertRaisesRegex(ValueError, 'canonical root'): self.check()
        finally:
            packet.ROOT = actual


if __name__ == '__main__':
    unittest.main()
