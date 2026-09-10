"""Synthetic metadata validation tests, not evidence of MoonWaker correctness."""
import copy
import json
from pathlib import Path
import tempfile
import unittest
from unittest import mock
import packet


class CheckpointTests(unittest.TestCase):
    def setUp(self):
        self.bundle = packet.load()
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.patch = mock.patch.object(packet, 'ROOT', self.root)
        self.patch.start()
        self.addCleanup(self.patch.stop)
        self.addCleanup(self.temp.cleanup)
        self.task = 'S01'
        self.entry = {'checkpoint_plan':'plan.json','input_fingerprint':'input','output_fingerprint':'output'}
        self.plan = {'task_id':self.task,'approved_by_coordinator_run':'coordinator-1',
            'input_fingerprint':'input','required_checks':['regression','build'],
            'target_regression_check_ids':['regression'], 'cumulative_fixed_path_check_ids':['regression'],
            'build_and_package_check_ids':['build'],'device_checks_required':False,
            'protected_flows':[{'flow_id':f'F{i:02}','check_ids':['regression']} for i in range(1,8)]}
        self.write('plan.json',self.plan)
        plan_hash = packet.file_digest('plan.json')
        self.impl = {'source':{'input_fingerprint':'input'}, 'checkpoint':{
            'usable_without_followup':True,'production_path_integrated':True,'future_task_required':False,
            'improvement':'Bounded correction with existing path integrated',
            'plan_artifact':'plan.json','plan_sha256':plan_hash,'gate_report':'gate.json',
            'architecture_artifact':'architecture.json','compatibility_artifact':'compatibility.json',
            'recovery_artifact':'recovery.json',
            'protected_flows':[{'flow_id':f'F{i:02}','status':'PASS_BASELINE_FLOOR'} for i in range(1,8)]}}
        self.review = {'behavioral_verdict':'ACCEPT','architecture_verdict':'ACCEPT',
            'whole_slice_independently_usable':True,'reviewed_plan_sha256':plan_hash}
        (self.root/'test.log').write_text('Synthetic tool fixture; no product execution.\n')
        self.gate = {'task_id':self.task,'status':'PASS','source_fingerprint':'output',
            'source_fingerprint_after':'output','checkpoint_plan_sha256':plan_hash,
            'checks':[{'id':name,'kind':kind,'status':'PASS','exit_code':0,'effect':'LOCAL',
                       'log_artifact':'test.log','log_sha256':packet.file_digest('test.log'),
                       'observation':{'tests_executed':1}} for name,kind in [('regression','TEST'),('build','BUILD')]]}
        self.write('gate.json',self.gate)
        self.write('architecture.json',{'task_id':self.task,'status':'PASS','responsibilities':[{'owner':'fixture'}],
                                       'duplicate_authoritative_owners':[]})
        self.write('compatibility.json',{'status':'PASS'})
        self.write('recovery.json',{'status':'PASS'})

    def write(self,name,data):
        (self.root/name).write_text(json.dumps(data),encoding='utf-8')

    def check(self):
        packet.checkpoint_evidence(self.task,self.entry,self.impl,self.review,self.bundle)

    def test_consistent_synthetic_checkpoint(self):
        self.check()

    def test_future_fix_cannot_accept(self):
        self.impl['checkpoint']['future_task_required']=True
        with self.assertRaisesRegex(ValueError,'without a future task'): self.check()

    def test_new_class_without_integrated_path_cannot_accept(self):
        self.impl['checkpoint']['production_path_integrated']=False
        with self.assertRaises(ValueError): self.check()

    def test_modified_plan_rejected(self):
        self.plan['outcome']='changed after review'; self.write('plan.json',self.plan)
        with self.assertRaisesRegex(ValueError,'plan changed'): self.check()

    def test_zero_test_discovery_rejected(self):
        self.gate['checks'][0]['observation']['tests_executed']=0; self.write('gate.json',self.gate)
        with self.assertRaisesRegex(ValueError,'zero'): self.check()

    def test_nonzero_exit_rejected(self):
        self.gate['checks'][0]['exit_code']=1; self.write('gate.json',self.gate)
        with self.assertRaisesRegex(ValueError,'mandatory check'): self.check()

    def test_missing_source_after_rejected(self):
        del self.gate['source_fingerprint_after']; self.write('gate.json',self.gate)
        with self.assertRaisesRegex(ValueError,'source changed'): self.check()

    def test_changed_log_rejected(self):
        (self.root/'test.log').write_text('tampered')
        with self.assertRaisesRegex(ValueError,'log fingerprint'): self.check()

    def test_seven_flows_required(self):
        self.impl['checkpoint']['protected_flows'].pop()
        with self.assertRaisesRegex(ValueError,'seven'): self.check()

    def test_architecture_duplicates_rejected(self):
        self.write('architecture.json',{'task_id':self.task,'status':'PASS','duplicate_authoritative_owners':['other']})
        with self.assertRaisesRegex(ValueError,'architecture outcome'): self.check()

    def test_superficial_extraction_rejected(self):
        self.write('architecture.json',{'task_id':self.task,'status':'PASS','responsibilities':[]})
        with self.assertRaisesRegex(ValueError,'no extraction'): self.check()

    def test_required_hardware_cannot_be_disabled(self):
        self.bundle['tasks'][self.task]['mandatory_real_device_topics']=['real-stream']
        with self.assertRaisesRegex(ValueError,'device-critical'): self.check()

    def test_pending_device_not_dispatchable(self):
        original = Path(__file__).resolve().parents[1]
        with mock.patch.object(packet, 'ROOT', original):
            bundle=packet.load()
            bundle['state']['tasks']['S00']['status']='CODE_VERIFIED_DEVICE_PENDING'
            self.assertEqual(packet.next_tasks(bundle)['dispatch_now'],[])

    def test_unrelated_packet_state_rejected(self):
        original = Path(__file__).resolve().parents[1]
        with mock.patch.object(packet,'ROOT',original):
            bundle=packet.load();bundle['state']['packet_id']='unrelated-packet'
            with self.assertRaisesRegex(ValueError,'packet ID mismatch'): packet.validate(bundle)
