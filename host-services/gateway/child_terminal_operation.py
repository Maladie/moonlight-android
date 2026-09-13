"""Single-flight owner for one child session's terminal host effects."""

import threading
import time


class ChildTerminalOperation:
    def __init__(self, clock=time.monotonic, wait=time.sleep):
        self._condition = threading.Condition()
        self._active = None
        self._terminal_handoff = None
        self.clock = clock
        self.wait = wait

    def await_provider_stop(self, request):
        """Retain the caller's exact permit across the Bridge's 10 s grace."""
        deadline = self.clock() + 20.0
        while True:
            remaining = deadline - self.clock()
            if remaining <= 0:
                return False
            try:
                accepted, result = request(min(8.0, remaining))
            except OSError:
                return False
            if not isinstance(result, dict):
                return False
            state = str(result.get("state") or "").casefold()
            if accepted and state in {"stopped", "already_stopped"} \
                    and result.get("cleanup_required") is not True:
                return True
            if not accepted or state != "ending":
                return False
            self.wait(min(1.0, max(0.0, deadline - self.clock())))

    def run(self, session, terminal, effect):
        while True:
            with self._condition:
                active = self._active
                if active is None:
                    active = {"session": session, "terminal": terminal,
                              "terminal_pending": False, "done": False,
                              "result": None, "error": None}
                    self._active = active
                    if terminal and self._terminal_handoff is session:
                        self._terminal_handoff = None
                    break
                if active["session"] is session and terminal \
                        and not active["terminal"]:
                    active["terminal_pending"] = True
                    self._terminal_handoff = session
                    self._condition.notify_all()
                join = active["session"] is session and (
                    active["terminal"] or not terminal)
                if join:
                    while not active["done"]:
                        self._condition.wait()
                    if active["error"] is not None:
                        raise active["error"]
                    return active["result"]
                self._condition.wait()
        try:
            active["result"] = effect()
            return active["result"]
        except BaseException as error:
            active["error"] = error
            raise
        finally:
            with self._condition:
                active["done"] = True
                self._active = None
                self._condition.notify_all()

    def wait_until_idle(self, session, timeout):
        """Boundedly join cleanup of this exact in-memory session generation."""
        deadline = time.monotonic() + max(0.0, timeout)
        with self._condition:
            while ((self._active is not None and self._active["session"] is session)
                   or self._terminal_handoff is session):
                remaining = deadline - time.monotonic()
                if remaining <= 0:
                    return False
                self._condition.wait(remaining)
        return True

    def terminal_pending(self, session):
        with self._condition:
            return self._active is not None \
                and self._active["session"] is session \
                and self._active["terminal_pending"]
