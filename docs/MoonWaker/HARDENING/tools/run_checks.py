#!/usr/bin/env python3
"""Opt-in trusted-command runner. Not a sandbox, test implementation or device authorization."""
from __future__ import annotations
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import signal
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

import packet


def digest(path: Path) -> str:
    return 'sha256:' + hashlib.sha256(path.read_bytes()).hexdigest()


def within(root: Path, relative: str) -> Path:
    path = (root / relative).resolve()
    if path != root and root not in path.parents:
        raise ValueError('Path escapes repository: ' + relative)
    return path


def validate_plan(plan: dict) -> None:
    if not plan.get('plan_id') or not plan.get('task_id') or not plan.get('checks'):
        raise ValueError('Plan needs plan_id, task_id and nonempty checks')
    ids = set()
    for check in plan['checks']:
        name = check.get('id', '')
        if not re.fullmatch(r'[A-Za-z0-9_-]+', name) or name in ids:
            raise ValueError('Invalid/duplicate check ID: ' + name)
        ids.add(name)
        if check.get('kind') not in {'TEST', 'BUILD', 'CHECK'}:
            raise ValueError('Invalid kind for ' + name)
        if check.get('effect') not in {'LOCAL', 'LAB'}:
            raise ValueError('Each check must declare LOCAL or LAB effect')
        if not 0 < float(check.get('timeout_seconds', 0)) <= 7200:
            raise ValueError('Each check needs a bounded timeout (0,7200]')
        obs = check.get('observation', {})
        if check['kind'] == 'TEST':
            if obs.get('format') not in {'unittest_log', 'junit_xml', 'json_summary'}:
                raise ValueError('TEST requires observed test counts, not exit-only success')
            if obs.get('minimum_executed', 0) < 1:
                raise ValueError('TEST minimum_executed must be >= 1')
            if obs.get('maximum_skipped', 0) < 0:
                raise ValueError('maximum_skipped cannot be negative')
        if check['effect'] == 'LAB' and not check.get('device_evidence_path'):
            raise ValueError('LAB requires a fresh device_evidence_path under repository evidence storage')
        if not check.get('argv') and not (check.get('argv_unix') and check.get('argv_windows')):
            raise ValueError('Missing platform command for ' + name)


def observe(check: dict, repo: Path, text: str, started: float) -> dict:
    if check['kind'] != 'TEST':
        return {'format': 'exit_status', 'tests_executed': None}
    obs = check['observation']; fmt = obs['format']
    failed = skipped = count = 0
    if fmt == 'unittest_log':
        matches = re.findall(r'^Ran (\d+) tests? in .+$', text, re.M)
        if len(matches) != 1:
            raise ValueError('Expected exactly one unittest run summary')
        count = int(matches[0])
        ok = re.search(r'^OK(?: \(([^\n]*)\))?\s*$', text, re.M)
        if not ok:
            raise ValueError('No successful unittest summary')
        if ok.group(1):
            values = dict(re.findall(r'(\w+)=(\d+)', ok.group(1)))
            skipped = int(values.get('skipped', 0)) + int(values.get('expected failures', 0))
            # Expected failures are not proven passing executions.
            ef = re.search(r'expected failures=(\d+)', ok.group(1))
            skipped += int(ef.group(1)) if ef else 0
    elif fmt == 'junit_xml':
        pattern = obs.get('glob', '')
        if not pattern or Path(pattern).is_absolute() or '..' in Path(pattern).parts:
            raise ValueError('Invalid repository-local XML glob')
        files = sorted(repo.glob(pattern))
        if not files:
            raise ValueError('No JUnit XML discovered')
        for path in files:
            path = within(repo, str(path.relative_to(repo)))
            if path.stat().st_mtime < started - 1:
                raise ValueError('Stale JUnit XML; rerun tests, do not reuse old evidence')
            root = ET.parse(path).getroot()
            cases = list(root.iter('testcase'))
            count += len(cases)
            skipped += sum(c.find('skipped') is not None for c in cases)
            failed += sum(c.find('failure') is not None or c.find('error') is not None for c in cases)
    else:
        path = within(repo, obs.get('path', ''))
        if not path.is_file() or path.stat().st_mtime < started - 1:
            raise ValueError('Missing/stale JSON test summary')
        data = json.loads(path.read_text(encoding='utf-8'))
        if data.get('status') != 'PASS':
            raise ValueError('JSON test summary did not pass')
        count = int(data['tests_run']); skipped = int(data.get('tests_skipped', 0)); failed = int(data.get('tests_failed', 0))
    executed = count - skipped
    if min(count, skipped, failed) < 0 or executed < obs['minimum_executed'] or failed:
        raise ValueError(f'Insufficient passing test evidence: total={count}, skipped={skipped}, failed={failed}')
    if skipped > obs.get('maximum_skipped', 0):
        raise ValueError('Skipped tests exceed explicitly reviewed allowance')
    return {'format': fmt, 'tests_run': count, 'tests_executed': executed, 'tests_skipped': skipped, 'tests_failed': failed}


def stop_owned_process(proc: subprocess.Popen) -> None:
    """Best effort scoped to this newly started process tree; never kill by name."""
    if proc.poll() is not None:
        return
    if os.name == 'nt':
        subprocess.run(['taskkill', '/PID', str(proc.pid), '/T', '/F'], stdout=subprocess.DEVNULL,
                       stderr=subprocess.DEVNULL, timeout=10, check=False)
    else:
        try:
            os.killpg(proc.pid, signal.SIGKILL)
        except ProcessLookupError:
            pass
    proc.wait(timeout=10)


def run_one(check: dict, repo: Path, out: Path, allow_lab: bool, authorization: str | None) -> dict:
    result = {'id': check['id'], 'kind': check['kind'], 'effect': check['effect'], 'status': 'NOT_RUN', 'exit_code': None}
    if check['effect'] == 'LAB' and (not allow_lab or not authorization):
        return dict(result, reason='LAB check requires explicit bounded authorization plus --allow-lab; no command executed')
    argv = check.get('argv') or check.get('argv_windows' if os.name == 'nt' else 'argv_unix')
    if not isinstance(argv, list) or not argv or any(not isinstance(x, str) for x in argv):
        raise ValueError('argv must be a nonempty list of strings')
    argv = [sys.executable if x == '{python}' else x for x in argv]
    cwd = within(repo, check.get('cwd', '.'))
    log = out / (check['id'] + '.log')
    start = time.time(); mono = time.monotonic(); proc = None
    try:
        with log.open('x', encoding='utf-8') as stream:
            kwargs = {'start_new_session': True} if os.name != 'nt' else {'creationflags': subprocess.CREATE_NEW_PROCESS_GROUP}
            proc = subprocess.Popen(argv, cwd=cwd, stdout=stream, stderr=subprocess.STDOUT, stdin=subprocess.DEVNULL, **kwargs)
            try:
                code = proc.wait(timeout=float(check['timeout_seconds']))
            except subprocess.TimeoutExpired:
                stop_owned_process(proc)
                return dict(result, status='FAIL', reason='Command timed out; owned process tree termination requested',
                            log_artifact=str(log), elapsed_seconds=round(time.monotonic()-mono,3))
        result.update(exit_code=code, log_artifact=str(log), log_sha256=digest(log), elapsed_seconds=round(time.monotonic()-mono,3))
        if code != 0:
            return dict(result, status='FAIL', reason='Nonzero command exit')
        evidence = observe(check, repo, log.read_text(encoding='utf-8', errors='replace'), start)
        if check['effect'] == 'LAB':
            device = within(repo, check.get('device_evidence_path', ''))
            if not device.is_file() or device.stat().st_mtime < start - 1:
                raise ValueError('Missing/stale device evidence; a lab command exit alone is insufficient')
            result.update(device_evidence_artifact=str(device), device_evidence_sha256=digest(device))
        return dict(result, status='PASS', observation=evidence)
    except KeyboardInterrupt:
        if proc is not None:
            stop_owned_process(proc)
        raise
    except (OSError, ValueError, KeyError, TypeError, ET.ParseError) as exc:
        if proc is not None and proc.poll() is None:
            stop_owned_process(proc)
        return dict(result, status='FAIL' if proc is not None else 'NOT_RUN', reason=str(exc), log_artifact=str(log) if log.exists() else None)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--plan', type=Path, required=True)
    parser.add_argument('--repo', type=Path, default=Path('.'))
    parser.add_argument('--output', type=Path)
    parser.add_argument('--execute', action='store_true')
    parser.add_argument('--allow-lab', action='store_true', help='Only after actual authorization; this flag alone is not authorization')
    args = parser.parse_args(argv)
    try:
        if args.plan.suffix.lower() != '.json':
            raise ValueError('Executable plans must use a .json path; archived plans cannot run')
        plan = json.loads(args.plan.read_text(encoding='utf-8')); validate_plan(plan)
        if not args.execute:
            print(json.dumps({'status':'DRY_RUN_NOT_EVIDENCE','binding_status':plan.get('binding_status'),
                              'plan_sha256':digest(args.plan),'checks':plan['checks']}, indent=2))
            return 0
        if plan.get('binding_status') != 'BOUND':
            raise ValueError('UNBOUND example plans cannot execute; inspect and bind the actual commands first')
        repo = args.repo.resolve(); fp = packet.source_fingerprint(repo)
        if plan.get('source_fingerprint') != fp:
            raise ValueError('Run plan is not bound to the current candidate fingerprint')
        if not plan.get('checkpoint_plan_sha256'):
            raise ValueError('Run plan must name its approved checkpoint-plan hash')
        if args.output is None:
            raise ValueError('--output is required for executed evidence')
        out = args.output.resolve(); out.mkdir(parents=True, exist_ok=False)
        result = {'schema_version':1, 'task_id':plan['task_id'], 'plan_id':plan['plan_id'],
                  'command_plan_sha256':digest(args.plan), 'checkpoint_plan_sha256':plan['checkpoint_plan_sha256'],
                  'source_fingerprint':fp,'status':'NOT_RUN','checks':[]}
        interrupted = False
        try:
            for check in plan['checks']:
                result['checks'].append(run_one(check,repo,out,args.allow_lab,plan.get('lab_authorization_ref')))
        except KeyboardInterrupt:
            interrupted = True
            result['interruption'] = 'Interrupted; no successful gate claim. Inspect scoped cleanup.'
        after = packet.source_fingerprint(repo)
        result['source_fingerprint_after'] = after
        result['status'] = 'PASS' if (not interrupted and after==fp and len(result['checks'])==len(plan['checks'])
                                     and all(x['status']=='PASS' for x in result['checks'])) else 'FAIL'
        (out/'gate.json').write_text(json.dumps(result,indent=2)+'\n',encoding='utf-8')
        print(json.dumps({'status':result['status'],'gate_report':str(out/'gate.json')},indent=2))
        return 0 if result['status']=='PASS' else 1
    except (OSError, ValueError, KeyError, TypeError) as exc:
        print('ERROR: '+str(exc),file=sys.stderr)
        return 2

if __name__ == '__main__':
    raise SystemExit(main())
