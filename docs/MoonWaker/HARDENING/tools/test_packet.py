"""Tests of packet tooling only; these do not test MoonWaker product code."""
import copy
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

import packet


class PacketTests(unittest.TestCase):
    def setUp(self):
        self.bundle = packet.load()
        # These are initial-state tooling fixtures, not assertions about a live run.
        for entry in self.bundle['state']['tasks'].values():
            entry.update(status='NOT_STARTED', implementation_report=None,
                         design_review_report=None, review_report=None)

    def test_only_baseline_initially_dispatchable(self):
        self.assertEqual(packet.next_tasks(self.bundle)['dispatch_now'], ['S00'])

    def test_missing_dependency_blocks_worker(self):
        with self.assertRaises(ValueError):
            packet.make_context('S01', 'worker', False, self.bundle)

    def test_unknown_task_rejected(self):
        with self.assertRaises(ValueError):
            packet.make_context('S99', 'worker', True, self.bundle)

    def test_cycle_rejected(self):
        self.bundle['tasks']['S00']['depends_on'] = ['S01']
        with self.assertRaisesRegex(ValueError, 'cycle'):
            packet.validate(self.bundle)

    def test_unknown_contract_rejected(self):
        self.bundle['tasks']['S01']['invariants'].append('I99')
        with self.assertRaisesRegex(ValueError, 'unknown invariants'):
            packet.validate(self.bundle)

    def test_no_acceptance_without_reports(self):
        self.bundle['state']['tasks']['S00']['status'] = 'CHECKPOINT_ACCEPTED'
        with self.assertRaisesRegex(ValueError, 'invalid acceptance metadata'):
            packet.validate(self.bundle)

    def test_reference_escape_rejected(self):
        with self.assertRaises(ValueError):
            packet.local_path('../../../../outside.json')

    def test_review_requires_actual_handoff(self):
        with self.assertRaises(ValueError):
            packet.make_context('S00', 'reviewer', False, self.bundle)

class FingerprintTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        subprocess.run(['git', 'init', '-q', str(self.root)], check=True)
        (self.root / '.gitignore').write_text('build/\n', encoding='utf-8')
        (self.root / 'source.java').write_text('class Example {}\n', encoding='utf-8')
        self.original_packet_root = packet.ROOT
        packet.ROOT = self.root / 'docs/MoonWaker/HARDENING'
        packet.ROOT.mkdir(parents=True)

    def tearDown(self):
        packet.ROOT = self.original_packet_root
        self.temp.cleanup()

    def test_source_edit_changes_hash_at_same_head(self):
        a = packet.source_fingerprint(self.root)
        (self.root / 'source.java').write_text('class Example { int x; }\n', encoding='utf-8')
        b = packet.source_fingerprint(self.root)
        self.assertNotEqual(a, b)

    def test_ignored_build_output_not_input(self):
        a = packet.source_fingerprint(self.root)
        (self.root / 'build').mkdir()
        (self.root / 'build/output.bin').write_bytes(b'generated')
        self.assertEqual(a, packet.source_fingerprint(self.root))

    def test_packet_state_does_not_change_source_fingerprint(self):
        a = packet.source_fingerprint(self.root)
        (packet.ROOT / 'STATE.json').write_text('{"changed": true}\n', encoding='utf-8')
        self.assertEqual(a, packet.source_fingerprint(self.root))

    def test_untracked_source_is_included(self):
        a = packet.source_fingerprint(self.root)
        (self.root / 'new_source.py').write_text('x = 1\n', encoding='utf-8')
        self.assertNotEqual(a, packet.source_fingerprint(self.root))


if __name__ == '__main__':
    unittest.main()
