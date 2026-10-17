"""Durable provider effect fence; its serial lock survives Condition.wait releases.

Retirement is a barrier, not a game/stream stop. Once it acknowledges an exact
operation, a delayed request with that binding can never execute. The Gateway
can then reconcile or issue a separately authorized cleanup operation.
"""

import json
import os
import re
import threading


class ControlEffectFence:
    KEYS = ("generation", "actor_lease_epoch", "effect_sequence", "operation_id",
            "stream_session_id", "child_session_id", "actor_profile_id",
            "execution_profile_id")

    def __init__(self, path, state_lock):
        self.path = path
        self.state_lock = state_lock
        self.serial = threading.Lock()
        self.current = None
        self.retired = False
        self.result = None
        if path is not None and path.is_file():
            # Corrupt fencing data must not silently reset the high-water mark.
            if path.stat().st_size > 65536:
                raise ValueError("Oversized provider control fence")
            value = json.loads(path.read_text(encoding="utf-8-sig"))
            self.current = self.binding(value)
            self.retired = value.get("retired") is True
            self.result = value.get("result")

    @classmethod
    def binding(cls, value):
        if not isinstance(value, dict):
            raise ValueError("A control operation binding is required.")
        value = {**value, "effect_sequence": value.get("effect_sequence", 0),
                 "child_session_id": str(value.get("child_session_id") or "")}
        for key in cls.KEYS[:3]:
            number = value.get(key)
            if isinstance(number, bool) or not isinstance(number, int) \
                    or number < (0 if key == "effect_sequence" else 1):
                raise ValueError("Invalid control operation binding.")
        for key in ("operation_id", "stream_session_id", "actor_profile_id",
                    "execution_profile_id"):
            if not isinstance(value.get(key), str) or not re.fullmatch(
                    r"[A-Za-z0-9._:-]{1,128}", value[key]):
                raise ValueError("Invalid control operation binding.")
        if value["child_session_id"] and not re.fullmatch(
                r"[0-9A-Fa-f-]{36}", value["child_session_id"]):
            raise ValueError("Invalid child control binding.")
        return tuple(value[key] for key in cls.KEYS)

    def _save(self):
        if self.path is None:
            return
        self.path.parent.mkdir(parents=True, exist_ok=True)
        temporary = self.path.with_name(self.path.name + ".tmp")
        with temporary.open("w", encoding="utf-8", newline="") as output:
            json.dump({"version": 1, **dict(zip(self.KEYS, self.current)),
                       "retired": self.retired, "result": self.result}, output,
                      separators=(",", ":"))
            output.flush()
            os.fsync(output.fileno())
        os.replace(temporary, self.path)

    def _authorize(self, value, retiring=False):
        candidate = self.binding(value)
        current = self.current
        generation, epoch, sequence = candidate[:3]
        if current is not None and (generation < current[0]
                or generation > current[0] and epoch <= current[1]
                or generation == current[0] and (
                    epoch != current[1] or candidate[4:] != current[4:]
                    or sequence < current[2]
                    or sequence == current[2] and candidate != current)):
            raise ValueError("Stale control operation binding.")
        if candidate == current and self.retired and not retiring:
            raise ValueError("Retired control operation binding.")
        if candidate != current:
            self.current, self.result, self.retired = candidate, None, False
        self._save()  # Persist before admitting any effect; never swallow errors.

    def authorize(self, value):
        with self.serial, self.state_lock:
            self._authorize(value)

    def run(self, value, effect):
        with self.serial:
            with self.state_lock:
                self._authorize(value)
                result = effect()
                self.result = result if isinstance(result, dict) else None
                self._save()
                return result

    def retire(self, value):
        if not self.serial.acquire(blocking=False):
            return {"accepted": False, "state": "in_progress",
                    "reason": "provider_effect_in_progress"}
        try:
            with self.state_lock:
                self._authorize(value, retiring=True)
                self.retired = True
                self._save()
                return {"accepted": True, "state": "retired",
                        **dict(zip(self.KEYS, self.current)), "result": self.result}
        finally:
            self.serial.release()
