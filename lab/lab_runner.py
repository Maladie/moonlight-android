#!/usr/bin/env python3
"""Deterministic, opt-in LAB plan runner. It grants no device authorization."""
from __future__ import annotations

import argparse
import ctypes
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import signal
import subprocess
import sys
import threading
import time

HARDENING_TOOLS = Path(__file__).resolve().parents[1] / 'docs/MoonWaker/HARDENING/tools'
sys.path.insert(0, str(HARDENING_TOOLS))
import packet


MAX_OUTPUT_BYTES = 256 * 1024
SAFE_ENV = {
    'APPDATA', 'COMSPEC', 'HOME', 'LANG', 'LC_ALL', 'LOCALAPPDATA', 'PATH',
    'PATHEXT', 'PROGRAMDATA', 'SYSTEMROOT', 'TEMP', 'TMP', 'TMPDIR',
    'USERPROFILE', 'WINDIR',
}
SECRET_NAME = re.compile(
    r'(?<![A-Za-z0-9])(?:password|passwd|pwd|pin|token|bearer|authorization|auth[_-]?header|'
    r'cookie|secret|session[_-]?(?:token|key|secret|cookie))(?![A-Za-z0-9])', re.IGNORECASE)
SECRET_VALUE = re.compile(r'\bbearer\s+\S+|[a-z][a-z0-9+.-]*://[^\s/@:]+:[^\s/@]+@', re.IGNORECASE)
SAFE_VALUE = re.compile(r'[A-Za-z0-9_.:/ @+,-]{1,240}')
PLAN_PLACEHOLDERS = {
    '{plan_sha256}', '{source_fingerprint}', '{apk_sha256}',
    '{controller_id}', '{android_serial}', '{host_id}',
}


def digest(path: Path) -> str:
    return 'sha256:' + hashlib.sha256(path.read_bytes()).hexdigest()


def within(root: Path, value: str | Path) -> Path:
    path = (root / value).resolve() if not Path(value).is_absolute() else Path(value).resolve()
    if path == root or root not in path.parents:
        raise ValueError('Path must be below repository root: ' + str(value))
    return path


def exact_keys(value: dict, required: set[str], optional: set[str], label: str) -> None:
    if not isinstance(value, dict):
        raise ValueError(label + ' must be an object')
    missing = required - value.keys()
    unknown = value.keys() - required - optional
    if missing or unknown:
        raise ValueError(f'{label} keys invalid; missing={sorted(missing)}, unknown={sorted(unknown)}')


def reject_secret(name: str, value: str, label: str) -> None:
    if SECRET_NAME.search(name) or SECRET_VALUE.search(value) or SECRET_NAME.search(value):
        raise ValueError(label + ' contains a credential/PIN/token/session-secret field')


def reject_output_secrets(value, label: str = 'outcome') -> None:
    if isinstance(value, dict):
        for key, item in value.items():
            reject_secret(str(key), '', label)
            reject_output_secrets(item, label)
    elif isinstance(value, list):
        for item in value:
            reject_output_secrets(item, label)
    elif isinstance(value, str) and SECRET_VALUE.search(value):
        raise ValueError(label + ' contains a credential-like value')


def reviewed_script_index(argv: list[str]) -> int | None:
    executable = Path(argv[0]).name.lower()
    if Path(argv[0]).suffix.lower() in {'.bat', '.cmd', '.ps1', '.sh'}:
        raise ValueError('Implicit shell script execution is forbidden')
    if executable in {'cmd', 'cmd.exe', 'sh', 'sh.exe', 'bash', 'bash.exe', 'zsh', 'zsh.exe',
                      'node', 'node.exe', 'ruby', 'ruby.exe', 'perl', 'perl.exe'}:
        raise ValueError('Shell/eval interpreters are forbidden; invoke a reviewed script directly')
    if executable in {'{python}', 'python', 'python.exe', 'python3', 'python3.exe', 'py', 'py.exe'}:
        if len(argv) < 2 or argv[1].startswith('-') or Path(argv[1]).suffix.lower() != '.py':
            raise ValueError('Python must invoke a reviewed .py file; -c/-m/stdin are forbidden')
        return 1
    if executable in {'powershell', 'powershell.exe', 'pwsh', 'pwsh.exe'}:
        lowered = [item.lower() for item in argv]
        if lowered.count('-file') != 1:
            raise ValueError('PowerShell must use the exact -File switch; command/eval/stdin forms are forbidden')
        index = lowered.index('-file')
        cursor = 1
        while cursor < index:
            option = lowered[cursor]
            if option in {'-noprofile', '-noninteractive', '-nologo'}:
                cursor += 1
            elif option == '-executionpolicy' and cursor + 1 < index:
                if lowered[cursor + 1] not in {'allsigned', 'bypass', 'default', 'remotesigned',
                                                'restricted', 'undefined', 'unrestricted'}:
                    raise ValueError('Unsupported PowerShell execution policy')
                cursor += 2
            else:
                raise ValueError('Unsupported PowerShell option before -File')
        if index + 1 >= len(argv) or Path(argv[index + 1]).suffix.lower() != '.ps1':
            raise ValueError('PowerShell -File must name a reviewed .ps1 file')
        return index + 1
    return None


def validate_plan(plan: dict) -> None:
    exact_keys(plan,
               {'schema_version', 'plan_id', 'task_id', 'binding_status', 'source_fingerprint',
                'apk', 'device', 'authorization', 'steps'}, set(), 'plan')
    if plan['schema_version'] != 1 or plan['binding_status'] not in {'BOUND', 'UNBOUND'}:
        raise ValueError('Unsupported schema_version or binding_status')
    for key in ('plan_id', 'task_id'):
        if not isinstance(plan[key], str) or not re.fullmatch(r'[A-Za-z0-9_.-]{1,100}', plan[key]):
            raise ValueError('Invalid ' + key)

    exact_keys(plan['apk'], {'path', 'sha256'}, set(), 'apk')
    if not isinstance(plan['apk']['path'], str) or not plan['apk']['path']:
        raise ValueError('apk.path must be nonempty')
    exact_keys(plan['device'], {'controller_id', 'android_serial', 'host_id'}, set(), 'device')
    for key, value in plan['device'].items():
        if not isinstance(value, str) or not SAFE_VALUE.fullmatch(value):
            raise ValueError('Invalid device.' + key)

    authorization = plan['authorization']
    if authorization is not None:
        exact_keys(authorization, {'reference', 'scope', 'not_before_utc', 'expires_at_utc'}, set(), 'authorization')
        for key, value in authorization.items():
            if not isinstance(value, str) or not SAFE_VALUE.fullmatch(value):
                raise ValueError('Invalid authorization.' + key)
            if key in {'reference', 'scope'}:
                reject_secret('', value, 'authorization.' + key)
    sha256 = re.compile(r'sha256:[0-9a-f]{64}')
    if plan['binding_status'] == 'BOUND':
        if (not isinstance(plan['source_fingerprint'], str) or
                not sha256.fullmatch(plan['source_fingerprint']) or
                not isinstance(plan['apk']['sha256'], str) or not sha256.fullmatch(plan['apk']['sha256']) or
                authorization is None):
            raise ValueError('BOUND plan requires source/APK SHA-256 and authorization')
    elif (plan['source_fingerprint'] is not None or plan['apk']['sha256'] is not None or
          authorization is not None or any(value != 'UNBOUND' for value in plan['device'].values())):
        raise ValueError('UNBOUND example must not contain candidate, device or authorization bindings')

    exact_keys(plan['steps'], {'setup', 'scenarios', 'cleanup'}, set(), 'steps')
    ids: set[str] = set()
    for phase in ('setup', 'scenarios', 'cleanup'):
        steps = plan['steps'][phase]
        if not isinstance(steps, list) or not steps:
            raise ValueError('Each phase must contain at least one step: ' + phase)
        for step in steps:
            exact_keys(step, {'id', 'argv', 'timeout_seconds', 'required_assertions'}, {'cwd', 'env'}, 'step')
            step_id = step['id']
            if not isinstance(step_id, str) or not re.fullmatch(r'[A-Za-z0-9_-]{1,100}', step_id) or step_id in ids:
                raise ValueError('Invalid/duplicate step id: ' + str(step_id))
            ids.add(step_id)
            argv = step['argv']
            if not isinstance(argv, list) or not argv or any(not isinstance(item, str) or not item for item in argv):
                raise ValueError(step_id + '.argv must be a nonempty string array')
            reviewed_script_index(argv)
            for index, item in enumerate(argv):
                if re.fullmatch(r'\{[^{}]+\}', item) and item not in PLAN_PLACEHOLDERS | {'{python}'}:
                    raise ValueError(step_id + ' contains an unknown plan placeholder')
                reject_secret('', item, f'{step_id}.argv[{index}]')
            timeout = step['timeout_seconds']
            if isinstance(timeout, bool) or not isinstance(timeout, (int, float)) or not 0 < timeout <= 3600:
                raise ValueError(step_id + ' needs timeout_seconds in (0,3600]')
            assertions = step['required_assertions']
            if (not isinstance(assertions, list) or not assertions or
                    any(not isinstance(item, str) or not re.fullmatch(r'[A-Za-z0-9_.-]{1,100}', item)
                        for item in assertions) or len(set(assertions)) != len(assertions)):
                raise ValueError(step_id + ' needs unique required_assertions')
            if 'cwd' in step and (not isinstance(step['cwd'], str) or not step['cwd']):
                raise ValueError(step_id + '.cwd must be nonempty')
            env = step.get('env', {})
            if not isinstance(env, dict) or any(not isinstance(k, str) or not isinstance(v, str) for k, v in env.items()):
                raise ValueError(step_id + '.env must be a string map')
            for key, value in env.items():
                reject_secret(key, value, step_id + '.env')


def parse_utc(value: str) -> datetime:
    parsed = datetime.fromisoformat(value.replace('Z', '+00:00'))
    if parsed.tzinfo is None:
        raise ValueError('Authorization times must include a UTC offset')
    return parsed.astimezone(timezone.utc)


def resolve_cwd(repo: Path, value: str) -> Path:
    path = (repo / value).resolve() if not Path(value).is_absolute() else Path(value).resolve()
    if path != repo and repo not in path.parents:
        raise ValueError('Step cwd escapes repository: ' + value)
    if not path.is_dir():
        raise ValueError('Step cwd is not an existing directory: ' + value)
    return path


def step_environment(step: dict) -> dict[str, str]:
    env = {key: value for key, value in os.environ.items() if key.upper() in SAFE_ENV}
    env.update(step.get('env', {}))
    return env


def prevalidate_step_paths(plan: dict, repo: Path, bindings: dict[str, str]) -> dict[str, tuple[Path, list[str], dict[str, str]]]:
    resolved: dict[str, tuple[Path, list[str], dict[str, str]]] = {}
    for phase in ('setup', 'scenarios', 'cleanup'):
        for step in plan['steps'][phase]:
            cwd = resolve_cwd(repo, step.get('cwd', '.'))
            argv = [sys.executable if item == '{python}' else bindings.get(item, item)
                    for item in step['argv']]
            for index, item in enumerate(argv):
                reject_secret('', item, f'{step["id"]}.resolved_argv[{index}]')
            env = step_environment(step)
            script_index = reviewed_script_index(argv)
            if script_index is not None:
                script = Path(argv[script_index])
                script = script.resolve() if script.is_absolute() else (cwd / script).resolve()
                if repo not in script.parents or not script.is_file():
                    raise ValueError('Reviewed script must be an existing repository file: ' + str(script))
                argv[script_index] = str(script)
            executable = Path(argv[0])
            if executable.is_absolute() or executable.parent != Path('.'):
                executable = executable.resolve() if executable.is_absolute() else (cwd / executable).resolve()
                if step['argv'][0] != '{python}' and repo not in executable.parents:
                    raise ValueError('Step executable path must be below repository root: ' + argv[0])
                if not executable.is_file():
                    raise ValueError('Step executable is not an existing file: ' + argv[0])
            else:
                found = shutil.which(argv[0], path=env.get('PATH'))
                if found is None:
                    raise ValueError('Step executable was not found on the sanitized PATH: ' + argv[0])
                executable = Path(found).resolve()
            argv[0] = str(executable)
            resolved[step['id']] = cwd, argv, env
    return resolved


def validate_execution(plan: dict, repo: Path, authorization_ref: str | None,
                       now: datetime | None = None) -> tuple[str, Path, str]:
    if plan['binding_status'] != 'BOUND':
        raise ValueError('UNBOUND lab plans cannot execute')
    current = packet.source_fingerprint(repo)
    if plan['source_fingerprint'] != current:
        raise ValueError('Plan is not bound to the current source fingerprint')
    apk = within(repo, plan['apk']['path'])
    if not apk.is_file() or plan['apk']['sha256'] != digest(apk):
        raise ValueError('Plan is not bound to the current APK SHA-256')
    if any(value == 'UNBOUND' for value in plan['device'].values()):
        raise ValueError('BOUND plans require explicit device identities')
    authorization = plan['authorization']
    if not authorization or not authorization_ref or authorization_ref != authorization['reference']:
        raise ValueError('Execution requires the exact bounded authorization reference')
    moment = (now or datetime.now(timezone.utc)).astimezone(timezone.utc)
    not_before = parse_utc(authorization['not_before_utc'])
    expires = parse_utc(authorization['expires_at_utc'])
    if expires <= not_before or not not_before <= moment < expires:
        raise ValueError('Authorization is not currently valid')
    maximum_runtime = sum(float(step['timeout_seconds']) for phase in plan['steps'].values() for step in phase)
    if (expires - moment).total_seconds() < maximum_runtime:
        raise ValueError('Authorization expires before the bounded plan can finish')
    return current, apk, plan['apk']['sha256']


class PosixProcessGroup:
    def __init__(self, pid: int):
        self.pid = pid
        self.lock = threading.Lock()
        self.closed = False

    def close(self) -> None:
        with self.lock:
            if self.closed:
                return
            self.closed = True
            try:
                os.killpg(self.pid, signal.SIGKILL)
            except ProcessLookupError:
                pass

    def wait_closed(self) -> None:
        deadline = time.monotonic() + 10
        while True:
            try:
                os.killpg(self.pid, 0)
            except ProcessLookupError:
                return
            if time.monotonic() >= deadline:
                raise OSError('Owned process group did not terminate')
            time.sleep(0.01)


class WindowsJob:
    KILL_ON_JOB_CLOSE = 0x00002000
    CREATE_SUSPENDED = 0x00000004
    SNAPTHREAD = 0x00000004
    THREAD_SUSPEND_RESUME = 0x0002

    def __init__(self):
        from ctypes import wintypes

        class BasicLimit(ctypes.Structure):
            _fields_ = [('PerProcessUserTimeLimit', ctypes.c_longlong),
                        ('PerJobUserTimeLimit', ctypes.c_longlong), ('LimitFlags', wintypes.DWORD),
                        ('MinimumWorkingSetSize', ctypes.c_size_t), ('MaximumWorkingSetSize', ctypes.c_size_t),
                        ('ActiveProcessLimit', wintypes.DWORD), ('Affinity', ctypes.c_size_t),
                        ('PriorityClass', wintypes.DWORD), ('SchedulingClass', wintypes.DWORD)]

        class IoCounters(ctypes.Structure):
            _fields_ = [('ReadOperationCount', ctypes.c_ulonglong), ('WriteOperationCount', ctypes.c_ulonglong),
                        ('OtherOperationCount', ctypes.c_ulonglong), ('ReadTransferCount', ctypes.c_ulonglong),
                        ('WriteTransferCount', ctypes.c_ulonglong), ('OtherTransferCount', ctypes.c_ulonglong)]

        class ExtendedLimit(ctypes.Structure):
            _fields_ = [('BasicLimitInformation', BasicLimit), ('IoInfo', IoCounters),
                        ('ProcessMemoryLimit', ctypes.c_size_t), ('JobMemoryLimit', ctypes.c_size_t),
                        ('PeakProcessMemoryUsed', ctypes.c_size_t), ('PeakJobMemoryUsed', ctypes.c_size_t)]

        class BasicAccounting(ctypes.Structure):
            _fields_ = [('TotalUserTime', ctypes.c_longlong), ('TotalKernelTime', ctypes.c_longlong),
                        ('ThisPeriodTotalUserTime', ctypes.c_longlong),
                        ('ThisPeriodTotalKernelTime', ctypes.c_longlong),
                        ('TotalPageFaultCount', wintypes.DWORD), ('TotalProcesses', wintypes.DWORD),
                        ('ActiveProcesses', wintypes.DWORD), ('TotalTerminatedProcesses', wintypes.DWORD)]

        self.api = ctypes.WinDLL('kernel32', use_last_error=True)
        self.api.CreateJobObjectW.argtypes = [ctypes.c_void_p, wintypes.LPCWSTR]
        self.api.CreateJobObjectW.restype = wintypes.HANDLE
        self.api.SetInformationJobObject.argtypes = [wintypes.HANDLE, ctypes.c_int, ctypes.c_void_p, wintypes.DWORD]
        self.api.SetInformationJobObject.restype = wintypes.BOOL
        self.api.AssignProcessToJobObject.argtypes = [wintypes.HANDLE, wintypes.HANDLE]
        self.api.AssignProcessToJobObject.restype = wintypes.BOOL
        self.api.CloseHandle.argtypes = [wintypes.HANDLE]
        self.api.CloseHandle.restype = wintypes.BOOL
        self.api.QueryInformationJobObject.argtypes = [wintypes.HANDLE, ctypes.c_int, ctypes.c_void_p,
                                                       wintypes.DWORD, ctypes.c_void_p]
        self.api.QueryInformationJobObject.restype = wintypes.BOOL
        self.api.TerminateJobObject.argtypes = [wintypes.HANDLE, wintypes.UINT]
        self.api.TerminateJobObject.restype = wintypes.BOOL
        self.BasicAccounting = BasicAccounting
        self.handle = self.api.CreateJobObjectW(None, None)
        if not self.handle:
            raise ctypes.WinError(ctypes.get_last_error())
        self.lock = threading.Lock()
        info = ExtendedLimit()
        info.BasicLimitInformation.LimitFlags = self.KILL_ON_JOB_CLOSE
        if not self.api.SetInformationJobObject(self.handle, 9, ctypes.byref(info), ctypes.sizeof(info)):
            error = ctypes.WinError(ctypes.get_last_error())
            self.close()
            raise error

    def assign(self, proc: subprocess.Popen) -> None:
        from ctypes import wintypes
        if not self.api.AssignProcessToJobObject(self.handle, wintypes.HANDLE(int(proc._handle))):
            raise ctypes.WinError(ctypes.get_last_error())

    def resume(self, pid: int) -> None:
        from ctypes import wintypes

        class ThreadEntry(ctypes.Structure):
            _fields_ = [('dwSize', wintypes.DWORD), ('cntUsage', wintypes.DWORD),
                        ('th32ThreadID', wintypes.DWORD), ('th32OwnerProcessID', wintypes.DWORD),
                        ('tpBasePri', wintypes.LONG), ('tpDeltaPri', wintypes.LONG), ('dwFlags', wintypes.DWORD)]

        self.api.CreateToolhelp32Snapshot.argtypes = [wintypes.DWORD, wintypes.DWORD]
        self.api.CreateToolhelp32Snapshot.restype = wintypes.HANDLE
        self.api.Thread32First.argtypes = [wintypes.HANDLE, ctypes.POINTER(ThreadEntry)]
        self.api.Thread32First.restype = wintypes.BOOL
        self.api.Thread32Next.argtypes = [wintypes.HANDLE, ctypes.POINTER(ThreadEntry)]
        self.api.Thread32Next.restype = wintypes.BOOL
        self.api.OpenThread.argtypes = [wintypes.DWORD, wintypes.BOOL, wintypes.DWORD]
        self.api.OpenThread.restype = wintypes.HANDLE
        self.api.ResumeThread.argtypes = [wintypes.HANDLE]
        self.api.ResumeThread.restype = wintypes.DWORD
        snapshot = self.api.CreateToolhelp32Snapshot(self.SNAPTHREAD, 0)
        if snapshot == ctypes.c_void_p(-1).value:
            raise ctypes.WinError(ctypes.get_last_error())
        resumed = 0
        try:
            entry = ThreadEntry()
            entry.dwSize = ctypes.sizeof(entry)
            more = self.api.Thread32First(snapshot, ctypes.byref(entry))
            while more:
                if entry.th32OwnerProcessID == pid:
                    thread = self.api.OpenThread(self.THREAD_SUSPEND_RESUME, False, entry.th32ThreadID)
                    if not thread:
                        raise ctypes.WinError(ctypes.get_last_error())
                    try:
                        if self.api.ResumeThread(thread) == 0xffffffff:
                            raise ctypes.WinError(ctypes.get_last_error())
                    finally:
                        self.api.CloseHandle(thread)
                    resumed += 1
                more = self.api.Thread32Next(snapshot, ctypes.byref(entry))
        finally:
            self.api.CloseHandle(snapshot)
        if resumed != 1:
            raise OSError('Expected exactly one suspended primary thread')

    def close(self) -> None:
        with getattr(self, 'lock', threading.Lock()):
            handle = getattr(self, 'handle', None)
            if not handle:
                return
            error = None
            try:
                accounting = self.BasicAccounting()
                if not self.api.QueryInformationJobObject(
                        handle, 1, ctypes.byref(accounting), ctypes.sizeof(accounting), None):
                    raise ctypes.WinError(ctypes.get_last_error())
                if accounting.ActiveProcesses and not self.api.TerminateJobObject(handle, 1):
                    raise ctypes.WinError(ctypes.get_last_error())
                deadline = time.monotonic() + 10
                while accounting.ActiveProcesses:
                    if time.monotonic() >= deadline:
                        raise OSError('Owned Windows Job processes did not terminate')
                    time.sleep(0.01)
                    accounting = self.BasicAccounting()
                    if not self.api.QueryInformationJobObject(
                            handle, 1, ctypes.byref(accounting), ctypes.sizeof(accounting), None):
                        raise ctypes.WinError(ctypes.get_last_error())
            except OSError as exc:
                error = exc
            finally:
                if not self.api.CloseHandle(handle) and error is None:
                    error = ctypes.WinError(ctypes.get_last_error())
                self.handle = None
            if error is not None:
                raise error

    def wait_closed(self) -> None:
        pass


def start_owned_process(argv: list[str], cwd: Path, env: dict[str, str]) -> tuple[subprocess.Popen, object]:
    if os.name != 'nt':
        proc = subprocess.Popen(argv, cwd=cwd, env=env, stdin=subprocess.DEVNULL,
                                stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, start_new_session=True)
        return proc, PosixProcessGroup(proc.pid)
    job = WindowsJob()
    proc = None
    try:
        proc = subprocess.Popen(argv, cwd=cwd, env=env, stdin=subprocess.DEVNULL,
                                stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
                                creationflags=subprocess.CREATE_NEW_PROCESS_GROUP | WindowsJob.CREATE_SUSPENDED)
        job.assign(proc)
        job.resume(proc.pid)
        return proc, job
    except BaseException:
        job.close()
        if proc is not None and proc.poll() is None:
            proc.kill()
            proc.wait(timeout=10)
        raise


def failure_outcome(step_id: str, reason: str) -> dict:
    return {'schema_version': 1, 'step_id': step_id, 'status': 'FAIL',
            'assertions': {}, 'observations': {'runner_error': reason}}


def validate_outcome(step: dict, raw: bytes) -> dict:
    if len(raw) > MAX_OUTPUT_BYTES:
        raise ValueError('Step output exceeds the bounded limit')
    try:
        outcome = json.loads(raw.decode('utf-8'))
    except (UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise ValueError('Step must emit exactly one UTF-8 JSON object') from exc
    exact_keys(outcome, {'schema_version', 'step_id', 'status', 'assertions', 'observations'}, set(), 'outcome')
    if outcome['schema_version'] != 1 or outcome['step_id'] != step['id'] or outcome['status'] != 'PASS':
        raise ValueError('Step outcome is not a matching PASS')
    assertions = outcome['assertions']
    if (not isinstance(assertions, dict) or set(assertions) != set(step['required_assertions']) or
            any(value is not True for value in assertions.values())):
        raise ValueError('Required assertions must match exactly and all be true')
    if not isinstance(outcome['observations'], dict):
        raise ValueError('outcome.observations must be an object')
    reject_output_secrets(outcome)
    return outcome


def capture_bounded(proc: subprocess.Popen, owner, timeout: float) -> tuple[bytes, bool]:
    chunks: list[bytes] = []
    size = 0
    overflow = threading.Event()

    def drain() -> None:
        nonlocal size
        while chunk := proc.stdout.read(8192):
            size += len(chunk)
            if size > MAX_OUTPUT_BYTES:
                overflow.set()
                owner.close()
                return
            chunks.append(chunk)

    reader = threading.Thread(target=drain, name='lab-step-output', daemon=True)
    reader.start()
    timed_out = False
    try:
        try:
            proc.wait(timeout=timeout)
        except subprocess.TimeoutExpired:
            timed_out = True
    finally:
        close_error = None
        try:
            owner.close()
        except OSError as exc:
            close_error = exc
        if proc.poll() is None:
            proc.kill()
        proc.wait(timeout=10)
        owner.wait_closed()
        reader.join(timeout=10)
        if close_error is not None:
            raise close_error
    if reader.is_alive():
        raise ValueError('Step output reader did not terminate')
    if overflow.is_set():
        raise ValueError('Step output exceeds the bounded limit')
    return b''.join(chunks), timed_out


def write_json_once(path: Path, value: dict) -> str:
    with path.open('x', encoding='utf-8', newline='\n') as stream:
        json.dump(value, stream, ensure_ascii=False, sort_keys=True, separators=(',', ':'))
        stream.write('\n')
    return digest(path)


def run_step(step: dict, phase: str, index: int,
             prepared: tuple[Path, list[str], dict[str, str]], out: Path) -> dict:
    cwd, argv, env = prepared
    started = time.monotonic()
    proc = None
    owner = None
    exit_code = None
    timed_out = False
    reason = ''
    try:
        proc, owner = start_owned_process(argv, cwd, env)
        stdout, timed_out = capture_bounded(proc, owner, float(step['timeout_seconds']))
        exit_code = proc.returncode
        if timed_out:
            raise ValueError('Step timed out; owned process tree termination requested')
        if exit_code != 0:
            raise ValueError('Step exited nonzero')
        outcome = validate_outcome(step, stdout)
    except KeyboardInterrupt:
        if owner is not None:
            owner.close()
        raise
    except (OSError, ValueError) as exc:
        if owner is not None:
            owner.close()
        reason = str(exc)
        outcome = failure_outcome(step['id'], reason)
    log = out / f'{index:03d}-{phase}-{step["id"]}.json'
    log_hash = write_json_once(log, outcome)
    return {'id': step['id'], 'phase': phase, 'status': outcome['status'],
            'exit_code': exit_code, 'timed_out': timed_out,
            'elapsed_seconds': round(time.monotonic() - started, 3),
            'log_artifact': str(log), 'log_sha256': log_hash,
            **({'reason': reason} if reason else {})}


def guarded_failure_result(step: dict, phase: str, index: int, out: Path, reason: str) -> dict:
    result = {'id': step['id'], 'phase': phase, 'status': 'FAIL', 'exit_code': None,
              'timed_out': False, 'elapsed_seconds': 0, 'reason': reason}
    log = out / f'{index:03d}-{phase}-{step["id"]}.json'
    try:
        result.update(log_artifact=str(log),
                      log_sha256=write_json_once(log, failure_outcome(step['id'], reason)))
    except OSError as log_error:
        result['reason'] += '; evidence log failed: ' + type(log_error).__name__
    return result


def run_step_guarded(step: dict, phase: str, index: int,
                     prepared: tuple[Path, list[str], dict[str, str]], out: Path) -> dict:
    try:
        return run_step(step, phase, index, prepared, out)
    except KeyboardInterrupt:
        raise
    except Exception as exc:
        return guarded_failure_result(
            step, phase, index, out, 'Unexpected step failure: ' + type(exc).__name__)


def execute_plan(plan: dict, repo: Path, out: Path, plan_hash: str,
                 source_before: str, apk: Path, apk_hash: str,
                 prepared_steps: dict[str, tuple[Path, list[str], dict[str, str]]]) -> dict:
    out.mkdir(parents=True, exist_ok=False)
    report = {
        'schema_version': 1, 'task_id': plan['task_id'], 'plan_id': plan['plan_id'],
        'plan_sha256': plan_hash, 'source_fingerprint': source_before,
        'apk': {'path': str(apk), 'sha256': apk_hash}, 'device': plan['device'],
        'authorization': {'reference': plan['authorization']['reference'],
                          'scope': plan['authorization']['scope'],
                          'expires_at_utc': plan['authorization']['expires_at_utc']},
        'status': 'NOT_RUN', 'steps': [], 'skipped_steps': [],
    }
    failed = False
    interrupted = False
    index = 0
    try:
        for phase in ('setup', 'scenarios'):
            for step in plan['steps'][phase]:
                if failed:
                    report['skipped_steps'].append({'id': step['id'], 'phase': phase})
                    continue
                index += 1
                result = run_step_guarded(step, phase, index, prepared_steps[step['id']], out)
                report['steps'].append(result)
                failed = result['status'] != 'PASS'
    except KeyboardInterrupt:
        interrupted = failed = True
    finally:
        for step in plan['steps']['cleanup']:
            index += 1
            try:
                result = run_step_guarded(step, 'cleanup', index, prepared_steps[step['id']], out)
            except KeyboardInterrupt:
                interrupted = True
                result = guarded_failure_result(
                    step, 'cleanup', index, out,
                    'Cleanup interrupted; remaining cleanup continues')
            report['steps'].append(result)
            failed = failed or result['status'] != 'PASS'
    source_after = packet.source_fingerprint(repo)
    apk_after = digest(apk) if apk.is_file() else None
    report['source_fingerprint_after'] = source_after
    report['apk_sha256_after'] = apk_after
    if interrupted:
        report['interruption'] = 'Interrupted; cleanup executed and no PASS may be claimed.'
    report['status'] = ('PASS' if not failed and not interrupted and source_after == source_before
                        and apk_after == apk_hash else 'FAIL')
    write_json_once(out / 'lab-result.json', report)
    return report


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--plan', type=Path, required=True)
    parser.add_argument('--repo', type=Path, default=Path('.'))
    parser.add_argument('--output', type=Path)
    parser.add_argument('--execute', action='store_true')
    parser.add_argument('--authorization-ref')
    args = parser.parse_args(argv)
    try:
        if args.plan.suffix.lower() != '.json':
            raise ValueError('Lab plans must be JSON files')
        plan = json.loads(args.plan.read_text(encoding='utf-8'))
        validate_plan(plan)
        plan_hash = digest(args.plan)
        if not args.execute:
            print(json.dumps({'status': 'DRY_RUN_NOT_EVIDENCE', 'binding_status': plan['binding_status'],
                              'plan_sha256': plan_hash,
                              'steps': {phase: [step['id'] for step in steps]
                                        for phase, steps in plan['steps'].items()}}, indent=2))
            return 0
        if args.output is None:
            raise ValueError('--output is required with --execute')
        repo = args.repo.resolve()
        source_before, apk, apk_hash = validate_execution(plan, repo, args.authorization_ref)
        out = within(repo, args.output)
        bindings = {
            '{plan_sha256}': plan_hash,
            '{source_fingerprint}': plan['source_fingerprint'],
            '{apk_sha256}': plan['apk']['sha256'],
            '{controller_id}': plan['device']['controller_id'],
            '{android_serial}': plan['device']['android_serial'],
            '{host_id}': plan['device']['host_id'],
        }
        prepared_steps = prevalidate_step_paths(plan, repo, bindings)
        result = execute_plan(plan, repo, out, plan_hash, source_before, apk, apk_hash, prepared_steps)
        print(json.dumps({'status': result['status'], 'result': str(out / 'lab-result.json')}, indent=2))
        return 0 if result['status'] == 'PASS' else 1
    except (OSError, ValueError, KeyError, TypeError, json.JSONDecodeError) as exc:
        print('ERROR: ' + str(exc), file=sys.stderr)
        return 2


if __name__ == '__main__':
    raise SystemExit(main())
