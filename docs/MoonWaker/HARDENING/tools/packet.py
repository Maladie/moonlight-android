#!/usr/bin/env python3
"""Select bounded Codex context and validate packet metadata. No model/network calls."""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
from pathlib import Path
import subprocess
import sys
from typing import Any

ROOT = Path(__file__).resolve().parents[1]
ACCEPTED = {'CHECKPOINT_ACCEPTED', 'ACCEPTED_NO_CHANGE'}
OWNER_CLOSED = 'CLOSED_BY_OWNER'  # user-authorized closure; satisfies dependencies, claims no evidence
SATISFIED = ACCEPTED | {OWNER_CLOSED}
ACTIVE = {'IN_PROGRESS', 'DESIGN_REVIEW_PENDING', 'REVIEW_PENDING', 'CHANGES_REQUESTED', 'CODE_VERIFIED_DEVICE_PENDING'}


def local_path(name: str) -> Path:
    """Resolve packet references without allowing an escape outside the packet."""
    p = (ROOT / name).resolve()
    if p != ROOT and ROOT not in p.parents:
        raise ValueError(f'Packet reference escapes its root: {name}')
    return p


def read_json(name: str) -> Any:
    with local_path(name).open(encoding='utf-8') as f:
        return json.load(f)


def read_text(name: str) -> str:
    return local_path(name).read_text(encoding='utf-8').strip()


def indexed(records: list[dict[str, Any]], label: str) -> dict[str, dict[str, Any]]:
    result = {x['id']: x for x in records}
    if len(result) != len(records):
        raise ValueError(f'Duplicate {label} IDs')
    return result


def load() -> dict[str, Any]:
    m = read_json('MANIFEST.json')
    c = read_json('CONTRACTS.json')
    return dict(manifest=m, state=read_json('STATE.json'),
                tasks=indexed(m['tasks'], 'task'),
                invariants=indexed(c['invariants'], 'invariant'),
                flows=indexed(c['flows'], 'flow'),
                decisions=indexed(c['decisions'], 'decision'),
                findings=indexed(read_json('FINDINGS.json')['records'], 'finding'),
                tests=indexed(read_json('ACCEPTANCE.json')['tests'], 'test'))


def file_digest(name: str) -> str:
    return 'sha256:' + hashlib.sha256(local_path(name).read_bytes()).hexdigest()


def checkpoint_evidence(task_id: str, entry: dict, impl: dict, review: dict, bundle: dict) -> None:
    """Consistency checks on reports; no claim that metadata proves real-world truth."""
    task = bundle['tasks'][task_id]
    cp = impl.get('checkpoint', {})
    if cp.get('usable_without_followup') is not True or cp.get('production_path_integrated') is not True or cp.get('future_task_required') is not False:
        raise ValueError('checkpoint must be integrated and usable without a future task')
    if not cp.get('improvement'):
        raise ValueError('checkpoint outcome is missing')
    if review.get('behavioral_verdict') != 'ACCEPT' or review.get('architecture_verdict') != 'ACCEPT' or review.get('whole_slice_independently_usable') is not True:
        raise ValueError('whole-slice behavior and architecture must both be independently accepted')
    plan_path = cp.get('plan_artifact')
    if not plan_path or plan_path != entry['checkpoint_plan']:
        raise ValueError('checkpoint plan does not match coordinator state')
    plan = read_json(plan_path); plan_hash = file_digest(plan_path)
    if plan.get('task_id') != task_id or not plan.get('approved_by_coordinator_run'):
        raise ValueError('plan is not bound to this task/coordinator')
    if plan.get('input_fingerprint') != impl.get('source',{}).get('input_fingerprint') or plan.get('input_fingerprint') != entry.get('input_fingerprint'):
        raise ValueError('plan input fingerprint mismatch')
    if cp.get('plan_sha256') != plan_hash or review.get('reviewed_plan_sha256') != plan_hash:
        raise ValueError('plan changed after implementation/review evidence')
    required = plan.get('required_checks', [])
    if not required or len(set(required)) != len(required):
        raise ValueError('required check set empty or duplicated')
    for category in ['target_regression_check_ids','build_and_package_check_ids']:
        if not plan.get(category) or not set(plan[category]) <= set(required):
            raise ValueError('missing required checkpoint category: '+category)
    if task['task_kind'] != 'BASELINE' and not plan.get('cumulative_fixed_path_check_ids'):
        raise ValueError('missing cumulative regression checks')
    if not set(plan.get('cumulative_fixed_path_check_ids',[])) <= set(required):
        raise ValueError('cumulative checks not required')
    gate = read_json(cp.get('gate_report',''))
    if gate.get('task_id') != task_id or gate.get('status') != 'PASS':
        raise ValueError('gate is not a passing report for this task')
    if gate.get('source_fingerprint') != entry['output_fingerprint'] or gate.get('checkpoint_plan_sha256') != plan_hash:
        raise ValueError('gate source/plan mismatch')
    if gate.get('source_fingerprint_after') != entry['output_fingerprint']:
        raise ValueError('source changed during verification')
    checks = indexed(gate.get('checks', []), 'check')
    for name in required:
        check = checks.get(name,{})
        if check.get('status') != 'PASS' or check.get('exit_code') != 0:
            raise ValueError('mandatory check missing/failed/not-run: '+name)
        artifact = check.get('log_artifact')
        if not artifact or not local_path(artifact).is_file():
            raise ValueError('check log artifact missing: '+name)
        if check.get('log_sha256') != file_digest(artifact):
            raise ValueError('check log fingerprint mismatch: '+name)
        if check.get('kind') == 'TEST' and check.get('observation',{}).get('tests_executed',0) < 1:
            raise ValueError('zero/unverified test discovery: '+name)
    for name in plan.get('target_regression_check_ids',[]) + plan.get('cumulative_fixed_path_check_ids',[]):
        if checks[name].get('kind') != 'TEST':
            raise ValueError('behavioral regression requires executed TEST evidence: '+name)
    if not any(checks[name].get('kind') == 'BUILD' for name in plan['build_and_package_check_ids']):
        raise ValueError('build/package gate lacks an actual BUILD check')
    planned_flows = {f.get('flow_id'): f for f in plan.get('protected_flows',[])}
    reported_flows = {f.get('flow_id'): f for f in cp.get('protected_flows',[])}
    expected_flows = {f'F{i:02}' for i in range(1,8)}
    if set(planned_flows) != expected_flows or set(reported_flows) != expected_flows:
        raise ValueError('all seven protected flows must be planned and reported')
    for fid,flow in planned_flows.items():
        names=flow.get('check_ids',[])
        if not names or not set(names) <= set(required):
            raise ValueError('flow lacks required executable/check evidence: '+fid)
        if reported_flows[fid].get('status') not in {'PASS_FIXED','PASS_BASELINE_FLOOR'}:
            raise ValueError('protected flow is not passing: '+fid)
    topics = task.get('mandatory_real_device_topics',[])
    if topics and plan.get('device_checks_required') is not True:
        raise ValueError('device-critical checkpoint cannot disable required device evidence')
    if plan.get('device_checks_required'):
        device_ids=plan.get('required_device_check_ids',[])
        if not device_ids or not set(device_ids)<=set(required):
            raise ValueError('missing required device check IDs')
        for name in device_ids:
            if checks[name].get('effect')!='LAB' or not checks[name].get('device_evidence_artifact'):
                raise ValueError('missing actual lab evidence for '+name)
            if not local_path(checks[name]['device_evidence_artifact']).is_file():
                raise ValueError('device evidence artifact does not exist')
            if checks[name].get('device_evidence_sha256') != file_digest(checks[name]['device_evidence_artifact']):
                raise ValueError('device evidence fingerprint mismatch')
        bindings = {x.get('topic'):x.get('check_ids',[]) for x in plan.get('device_topic_bindings',[])}
        for topic in topics:
            if not bindings.get(topic) or not set(bindings[topic])<=set(device_ids):
                raise ValueError('required physical topic is not covered: '+topic)
    for field in ['architecture_artifact','compatibility_artifact','recovery_artifact']:
        if not cp.get(field) or not local_path(cp[field]).is_file():
            raise ValueError('missing checkpoint artifact: '+field)
    architecture=read_json(cp['architecture_artifact'])
    if architecture.get('task_id')!=task_id or architecture.get('status')!='PASS' or architecture.get('duplicate_authoritative_owners'):
        raise ValueError('architecture outcome not passing')
    if not architecture.get('responsibilities') and not architecture.get('no_extraction_needed_justification'):
        raise ValueError('no extraction or justified existing boundary evidence')


def accepted_evidence_errors(task_id: str, entry: dict[str, Any], bundle: dict[str, Any]) -> list[str]:
    errors: list[str] = []
    if entry['status'] not in ACCEPTED:
        return errors
    try:
        for field in ['implementation_report', 'review_report', 'input_fingerprint', 'output_fingerprint', 'checkpoint_plan']:
            if not entry.get(field):
                raise ValueError(f'missing {field}')
        impl = read_json(entry['implementation_report'])
        review = read_json(entry['review_report'])
        if impl.get('task_id') != task_id or review.get('task_id') != task_id:
            raise ValueError('report task ID mismatch')
        if review.get('verdict') != 'ACCEPT' or review.get('stage') not in {'CODE', 'RELEASE'}:
            raise ValueError('no accepted code/release review')
        if review.get('independent_context') is not True:
            raise ValueError('review is not marked independent')
        if not impl.get('run_id') or not review.get('run_id') or impl['run_id'] == review['run_id']:
            raise ValueError('missing/distinct implementer and reviewer run IDs required')
        if review.get('implementer_run_id') != impl['run_id']:
            raise ValueError('review does not identify this implementer run')
        expected = entry['output_fingerprint']
        if review.get('reviewed_fingerprint') != expected or impl.get('source', {}).get('output_fingerprint') != expected:
            raise ValueError('candidate fingerprint mismatch')
        if impl.get('blockers') or review.get('findings'):
            raise ValueError('accepted task still has implementation blockers/review findings')
        if not impl.get('task_checks') or any(x.get('status') != 'PASS' for x in impl['task_checks']):
            raise ValueError('assigned checkpoint checks need passing evidence; mandatory missing device evidence cannot pass')
        for check in impl['task_checks']:
            if not check.get('observed') or not check.get('evidence_artifact'):
                raise ValueError('passing task check lacks observed result/evidence reference')
            if check.get('command') and check.get('exit_code') != 0:
                raise ValueError('passing command check has nonzero/missing exit code')
        checkpoint_evidence(task_id, entry, impl, review, bundle)
        if bundle['tasks'][task_id]['pre_edit_contract_review'] and entry['status'] != 'ACCEPTED_NO_CHANGE':
            if not entry.get('design_review_report'):
                raise ValueError('missing required pre-edit contract review')
            design = read_json(entry['design_review_report'])
            if design.get('stage') != 'DESIGN' or design.get('verdict') != 'ACCEPT' or design.get('independent_context') is not True:
                raise ValueError('pre-edit review not independently accepted')
            if design.get('reviewed_fingerprint') != impl.get('source', {}).get('input_fingerprint'):
                raise ValueError('pre-edit review not bound to task input fingerprint')
    except (OSError, ValueError, KeyError, TypeError) as exc:
        errors.append(f'{task_id}: invalid acceptance metadata: {exc}')
    return errors


def validate(bundle: dict[str, Any]) -> dict[str, Any]:
    errors: list[str] = []
    if bundle['state'].get('packet_id') != bundle['manifest'].get('packet_id'):
        errors.append('STATE packet ID mismatch; do not copy state from an unrelated packet')
    tasks = bundle['tasks']
    states = bundle['state']['tasks']
    if set(states) != set(tasks):
        errors.append('STATE task IDs differ from MANIFEST')
    for task_id, task in tasks.items():
        for key in ['findings', 'invariants', 'flows', 'tests', 'decisions']:
            missing = set(task[key]) - set(bundle[key])
            if missing:
                errors.append(f'{task_id}: unknown {key}: {sorted(missing)}')
        for dep in task['depends_on']:
            if dep not in tasks:
                errors.append(f'{task_id}: unknown dependency {dep}')
        if not local_path(task['instructions']).is_file():
            errors.append(f'{task_id}: missing instructions')
        if not task.get('scenario_assertion_scope'):
            errors.append(f'{task_id}: missing task-specific scenario scope')
        entry = states.get(task_id, {})
        if entry.get('status') not in bundle['manifest']['task_statuses']:
            errors.append(f'{task_id}: invalid state')
        if entry.get('status') == OWNER_CLOSED:
            amendment = next((a for a in bundle['state'].get('contract_amendments', [])
                              if a.get('id') == entry.get('closure_decision')), None)
            if not amendment or amendment.get('status') != 'USER_AUTHORIZED':
                errors.append(f'{task_id}: owner closure needs a USER_AUTHORIZED closure_decision amendment')
        if entry.get('status') in ACCEPTED:
            for dep in task['depends_on']:
                dep_status = states.get(dep, {}).get('status')
                if dep_status not in SATISFIED:
                    errors.append(f'{task_id}: accepted before dependency {dep}')
                elif dep_status in ACCEPTED and entry.get('input_fingerprint') != states.get(dep,{}).get('output_fingerprint'):
                    errors.append(f'{task_id}: input does not match the accepted predecessor source')
        errors.extend(accepted_evidence_errors(task_id, entry, bundle))
    marks: dict[str, int] = {}
    def visit(task_id: str) -> None:
        if marks.get(task_id) == 1:
            raise ValueError(f'Dependency cycle at {task_id}')
        if marks.get(task_id) == 2:
            return
        marks[task_id] = 1
        for dep in tasks[task_id]['depends_on']:
            if dep in tasks:
                visit(dep)
        marks[task_id] = 2
    try:
        for task_id in tasks:
            visit(task_id)
    except ValueError as exc:
        errors.append(str(exc))
    covered_findings = set().union(*(set(t['findings']) for t in tasks.values() if t['id'] not in {'S00','S10'}))
    if set(bundle['findings']) - covered_findings:
        errors.append('An inherited finding has no bounded repair task')
    covered_tests = set().union(*(set(t['tests']) for t in tasks.values()))
    if set(bundle['tests']) - covered_tests:
        errors.append('A scenario has no task owner')
    for path in ['BRANCHING.md','CHECKPOINTS.md','DECOMPOSITION.md','automation/TEST_STRATEGY.md','RULES.md','CORRELATION.md','START_HERE.md','roles/WORKER.md','roles/REVIEWER.md','roles/COORDINATOR.md']:
        if not local_path(path).is_file():
            errors.append(f'Missing {path}')
    if errors:
        raise ValueError('\n'.join(errors))
    return dict(status='VALID_PACKET_METADATA', tasks=len(tasks), findings=len(bundle['findings']),
                scenarios=len(bundle['tests']), invariants=len(bundle['invariants']),
                warning='Metadata validation is not code, test, review or device verification.')


def pending_dependencies(task: dict[str, Any], bundle: dict[str, Any]) -> list[str]:
    return [d for d in task['depends_on'] if bundle['state']['tasks'][d]['status'] not in SATISFIED]


def next_tasks(bundle: dict[str, Any]) -> dict[str, Any]:
    active = [i for i,s in bundle['state']['tasks'].items() if s['status'] in ACTIVE]
    locks = {lock for i in active for lock in bundle['tasks'][i]['resource_locks']}
    capacity = max(0, bundle['manifest']['default_policy']['max_mutating_workers'] - len(active))
    eligible = []
    blocked = {}
    for i, task in bundle['tasks'].items():
        if bundle['state']['tasks'][i]['status'] != 'NOT_STARTED':
            continue
        pending = pending_dependencies(task,bundle)
        if pending:
            blocked[i] = pending
        elif not set(task['resource_locks']) & locks:
            eligible.append(i)
    return dict(active_or_review_pending=active, eligible_tasks=eligible, dispatch_now=eligible[:capacity],
                pending_dependencies=blocked, policy='One mutating worker followed by review by default. Read-only work can be parallel.')


def make_context(task_id: str, role: str, preview: bool, bundle: dict[str, Any]) -> str:
    if task_id not in bundle['tasks']:
        raise ValueError(f'Unknown task: {task_id}')
    task = bundle['tasks'][task_id]
    pending = pending_dependencies(task,bundle)
    state = bundle['state']['tasks'][task_id]
    blocked_reasons = [f'unaccepted dependencies: {pending}'] if pending else []
    if role == 'reviewer' and not state.get('implementation_report') and not state.get('design_review_report'):
        # Initial design review may be dispatched explicitly from the worker's DESIGN_REVIEW_PENDING handoff.
        if state.get('status') != 'DESIGN_REVIEW_PENDING':
            blocked_reasons.append('missing worker handoff/design-review request in STATE')
    if blocked_reasons and not preview:
        raise ValueError('Cannot create execution dispatch: '+ '; '.join(blocked_reasons)+'. Use --preview for inspection only.')
    header=dict(task_id=task_id,role=role,review_stage=('DESIGN' if state.get('status') == 'DESIGN_REVIEW_PENDING' else ('RELEASE' if task_id == 'S10' else 'CODE')) if role == 'reviewer' else None,mode='PREVIEW_NOT_EXECUTION_AUTHORIZATION' if preview else 'VERIFY_LIVE_REPO_BEFORE_EXECUTION',
                repository=bundle['manifest']['repository'],expected_branch=bundle['manifest']['expected_branch'],
                branch_policy=bundle['manifest']['branch_policy'],workspace=bundle['state'].get('workspace',{}),
                audit_reference=bundle['manifest']['audit_base_sha'],model_hint=task['implementation_model_hint'] if role=='worker' else task['review_model_hint'],
                expected_input_fingerprint=state.get('input_fingerprint'),resource_locks=task['resource_locks'],
                source_hints=task['source_hints'],write_scope_note=task['write_scope_note'],
                pre_edit_contract_review=task['pre_edit_contract_review'],blocked_reasons=blocked_reasons,
                scenario_assertion_scope=task['scenario_assertion_scope'],
                independently_usable_outcome=task['independently_usable_outcome'],decomposition_requirement=task['decomposition_requirement'],
                test_risk=task['test_risk'],mandatory_real_device_topics=task['mandatory_real_device_topics'],
                dependency_handoffs={d:bundle['state']['tasks'][d] for d in task['depends_on']},
                this_task_state=state,contract_amendments=bundle['state'].get('contract_amendments',[]))
    sections=['# MoonWaker bounded task dispatch', '```json\n'+json.dumps(header,indent=2,ensure_ascii=False)+'\n```',
              read_text('BRANCHING.md'),read_text('RULES.md'),read_text('CHECKPOINTS.md'),read_text('DECOMPOSITION.md'),read_text('roles/WORKER.md' if role=='worker' else 'roles/REVIEWER.md'),read_text(task['instructions'])]
    if task['include_correlation_contract']:
        sections.append(read_text('CORRELATION.md'))
    for key in ['invariants','flows','decisions','findings','tests']:
        chosen=[bundle[key][i] for i in task[key]]
        if chosen:
            sections.append('## Selected '+key+'\n\n```json\n'+json.dumps(chosen,indent=2,ensure_ascii=False)+'\n```')
    sections.append('## Required output\nUse templates/TASK_RESULT.json (worker) or templates/REVIEW_RESULT.json (reviewer). Read actual dependency reports referenced above. Resolve paths relative to docs/MoonWaker/HARDENING. Do not load the full reference audit or unrelated tasks by default. Do not mark a task accepted yourself as worker.')
    return '\n\n'.join(sections)+'\n'


def git_bytes(repo: Path, *args: str) -> bytes:
    proc=subprocess.run(['git','-C',str(repo),*args],stdout=subprocess.PIPE,stderr=subprocess.PIPE,check=False)
    if proc.returncode:
        raise ValueError('Git command failed: git '+ ' '.join(args)+': '+proc.stderr.decode('utf-8','replace').strip())
    return proc.stdout


def branch_check(repo: Path, bundle: dict[str, Any]) -> dict[str, Any]:
    """Verify recorded branch binding locally; never fetch, create refs or change state."""
    workspace = bundle['state'].get('workspace', {})
    policy = bundle['manifest']['branch_policy']
    if workspace.get('status') != 'PREPARED':
        raise ValueError('Workspace not prepared: complete BRANCHING.md before S00')
    for key in ['remote', 'base_branch', 'work_branch']:
        if workspace.get(key) != policy.get(key):
            raise ValueError('Workspace branch policy mismatch: ' + key)
    if policy['work_branch'] == policy['base_branch'] or policy['work_branch'] != bundle['manifest']['expected_branch']:
        raise ValueError('Work branch must be distinct from the base and match MANIFEST')
    base = workspace.get('base_head', '')
    fetched = workspace.get('fetched_remote_head', '')
    if not all(isinstance(x, str) and re.fullmatch(r'[0-9a-f]{40}|[0-9a-f]{64}', x) for x in [base, fetched]):
        raise ValueError('Workspace requires full resolved commit IDs')
    if workspace.get('branch_creation_head') != base or bundle['state'].get('baseline_head') != base:
        raise ValueError('Initial branch HEAD/baseline does not match the recorded base')
    if bundle['manifest'].get('execution_head') != base:
        raise ValueError('MANIFEST execution_head must record the initial selected base')
    report_path = workspace.get('branch_setup_report')
    if not isinstance(report_path, str) or not report_path:
        raise ValueError('Missing branch setup report')
    report = read_json(report_path)
    if report.get('status') != 'PREPARED' or report.get('blockers'):
        raise ValueError('Branch setup report is not prepared')
    if report.get('repository') != bundle['manifest']['repository']:
        raise ValueError('Branch setup repository mismatch')
    for key in ['remote', 'base_branch', 'work_branch', 'fetched_remote_head', 'branch_creation_head']:
        if report.get(key) != workspace.get(key):
            raise ValueError('Branch setup report mismatch: ' + key)
    if report.get('selected_base_head') != base:
        raise ValueError('Branch setup selected base mismatch')
    if report.get('fetch_succeeded') is not True or report.get('remote_identity_verified') is not True:
        raise ValueError('Actual base fetch and remote identity evidence are required')
    if not report.get('commands_and_observed_results') or not report.get('observed_at_utc') or not report.get('selection_reason'):
        raise ValueError('Branch setup report lacks observed evidence')
    relation = report.get('local_vs_remote')
    if relation not in {'MISSING', 'EQUAL', 'BEHIND', 'AHEAD'}:
        raise ValueError('Divergent or unresolved base relationship blocks setup')
    if relation == 'AHEAD':
        if report.get('local_base_head') != base or base == fetched:
            raise ValueError('Selected newer local base is not recorded consistently')
    elif base != fetched:
        raise ValueError('Selected base must equal the fetched tip unless local base is ahead')
    root = Path(os.fsdecode(git_bytes(repo, 'rev-parse', '--show-toplevel').strip())).resolve()
    if ROOT.resolve() != root / bundle['manifest']['packet_root']:
        raise ValueError('Packet and checked repository do not share the canonical root')
    actual_branch = git_bytes(root, 'symbolic-ref', '--quiet', '--short', 'HEAD').decode().strip()
    if actual_branch != policy['work_branch']:
        raise ValueError('Wrong work branch: ' + actual_branch)
    head = git_bytes(root, 'rev-parse', '--verify', 'HEAD^{commit}').decode().strip()
    for sha in {base, fetched}:
        if git_bytes(root, 'rev-parse', '--verify', sha + '^{commit}').decode().strip() != sha:
            raise ValueError('Recorded commit does not resolve exactly')
    git_bytes(root, 'merge-base', '--is-ancestor', fetched, base)
    git_bytes(root, 'merge-base', '--is-ancestor', base, head)
    local = report.get('local_base_head')
    if relation in {'EQUAL', 'BEHIND'}:
        if not isinstance(local, str) or not re.fullmatch(r'[0-9a-f]{40}|[0-9a-f]{64}', local):
            raise ValueError('Missing resolved local base commit')
        if (relation == 'EQUAL') != (local == fetched):
            raise ValueError('Local/remote relation does not match recorded tips')
        git_bytes(root, 'merge-base', '--is-ancestor', local, fetched)
    if relation == 'MISSING' and local is not None:
        raise ValueError('Missing-local-base report contains a local tip')
    return dict(status='PREPARED_WORK_BRANCH_VERIFIED', branch=actual_branch,
                head=head, base_head=base, fetched_remote_head=fetched,
                branch_setup_report=report_path,
                warning='Local consistency check only; no fetch, branch creation, product test or remote-freshness proof.')


def source_fingerprint(repo: Path) -> str:
    """Hash actual non-ignored Git-listed contents; exclude packet and inspect initialized submodules."""
    root=Path(os.fsdecode(git_bytes(repo,'rev-parse','--show-toplevel').strip())).resolve()
    h=hashlib.sha256()
    def update_tree(tree: Path, prefix: str, depth: int=0) -> None:
        if depth > 16:
            raise ValueError('Submodule recursion limit reached')
        head=subprocess.run(['git','-C',str(tree),'rev-parse','HEAD'],stdout=subprocess.PIPE,stderr=subprocess.PIPE,check=False)
        h.update(b'HEAD\0'+prefix.encode('utf-8','surrogateescape')+b'\0'+(head.stdout.strip() if head.returncode==0 else b'UNBORN')+b'\0')
        names=sorted(set(git_bytes(tree,'ls-files','-z','--cached','--others','--exclude-standard').split(b'\0'))-{b''})
        for raw in names:
            name=os.fsdecode(raw); p=tree/name
            # Do not resolve symlink targets; the link itself, not outside file data, is an input.
            lexical=Path(os.path.abspath(p))
            if lexical==ROOT or ROOT in lexical.parents:
                continue
            h.update(b'PATH\0'+(prefix+name).encode('utf-8','surrogateescape')+b'\0')
            if p.is_symlink():
                h.update(b'LINK\0'+os.fsencode(os.readlink(p))+b'\0')
            elif p.is_file():
                h.update(b'FILE\0'+str(p.stat().st_mode & 0o777).encode()+b'\0')
                with p.open('rb') as f:
                    while True:
                        chunk=f.read(1024*1024)
                        if not chunk: break
                        h.update(chunk)
                h.update(b'\0END\0')
            elif p.is_dir():
                if (p/'.git').exists():
                    update_tree(p,prefix+name+'/',depth+1)
                else:
                    # Preserve recorded gitlink for an uninitialized submodule.
                    h.update(b'UNINITIALIZED_DIR\0'+git_bytes(tree,'ls-files','--stage','--',name))
            else:
                h.update(b'DELETED\0')
    update_tree(root,'')
    return 'sha256:'+h.hexdigest()


def main() -> int:
    parser=argparse.ArgumentParser(description=__doc__)
    sub=parser.add_subparsers(dest='command',required=True)
    sub.add_parser('validate',help='Check packet metadata, DAG, references and acceptance report references.')
    sub.add_parser('next',help='List dependency-ready work without changing state or launching an agent.')
    bc=sub.add_parser('branch-check',help='Read-only check of the prepared work branch, recorded base and ancestry.')
    bc.add_argument('--repo',default='.')
    fp=sub.add_parser('fingerprint',help='Read-only hash of Git-listed source inputs, excluding this packet.')
    fp.add_argument('--repo',default='.')
    context=sub.add_parser('context',help='Produce a bounded worker/reviewer prompt.')
    context.add_argument('task')
    context.add_argument('--role',choices=['worker','reviewer'],default='worker')
    context.add_argument('--preview',action='store_true')
    context.add_argument('--output',type=Path)
    context.add_argument('--force',action='store_true',help='Allow replacing an existing generated output.')
    args=parser.parse_args()
    try:
        if args.command=='fingerprint':
            print(source_fingerprint(Path(args.repo)))
            return 0
        bundle=load(); result=validate(bundle)
        if args.command=='branch-check': print(json.dumps(branch_check(Path(args.repo),bundle),indent=2))
        elif args.command=='validate': print(json.dumps(result,indent=2))
        elif args.command=='next': print(json.dumps(next_tasks(bundle),indent=2))
        else:
            out=make_context(args.task,args.role,args.preview,bundle)
            if args.output:
                if args.output.exists() and not args.force:
                    raise ValueError(f'Output exists: {args.output}. Use a new path or explicit --force.')
                args.output.parent.mkdir(parents=True,exist_ok=True)
                args.output.write_text(out,encoding='utf-8')
                print(json.dumps(dict(output=str(args.output),bytes=len(out.encode('utf-8')),mode='preview' if args.preview else 'dispatch'),indent=2))
            else:
                sys.stdout.write(out)
        return 0
    except (OSError, ValueError, KeyError, TypeError) as exc:
        print('ERROR: '+str(exc),file=sys.stderr)
        return 2


if __name__=='__main__':
    raise SystemExit(main())
