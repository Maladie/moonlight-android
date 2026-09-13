"""Common provider RPC execution and retirement for parent and child callers.

The Gateway's existing ledger owns permits and receipts. This owner handles the
remote effect boundary and preserves definitive rejections separately from lost
responses, so a failed stop cannot strand the whole host.
"""

import time

from control_operation_policy import ControlOperationPolicy


class ProviderControlOperation:
    def __init__(self, binding, complete, proxy):
        self.binding = binding
        self.complete = complete
        self.proxy = proxy

    def execute(self, client, request_id, path, payload, timeout, profile_id=None,
                supplied=None):
        digest = ControlOperationPolicy.effect_hash(path, payload)
        permit, owns = self.binding(client, request_id, digest, supplied)
        if permit is False:
            return False, {"error": "control_operation_required"}
        if permit.get("direction") == "stop" and permit.get("process_token"):
            exact_process = path == "/game/stop-verified" and \
                payload.get("expected_process_token") == permit.get("process_token")
            expected_game = str(permit.get("provider_game_id") or "").strip().casefold()
            requested_game = str(payload.get("game_id") or "").strip().casefold()
            if not exact_process or expected_game and requested_game != expected_game:
                return False, {"error": "control_process_identity_mismatch"}
        if permit.get("state") in {"succeeded", "failed"}:
            result = dict(permit.get("effect_result") or {})
            return permit["state"] == "succeeded", result
        request = {**payload, "control_operation":
                   ControlOperationPolicy.bridge_binding(permit)}
        try:
            ok, result = self.proxy("game_provider", path, request,
                                    timeout=timeout, profile_id=profile_id)
        except OSError:
            ok, result = False, {"error": "provider_response_unavailable"}
        accepted = ok and isinstance(result, dict) and result.get("accepted") is not False
        # A returned provider response is final even when the requested effect
        # failed. An RPC transport failure needs the provider retirement barrier.
        definite = isinstance(result, dict) and (ok or result.get("accepted") is False)
        if owns and not self.complete(client, permit, accepted, result, definite):
            return False, {"error": "control_operation_uncertain",
                           "provider_reason": self.reason(result)}
        return ok, result

    @staticmethod
    def reason(result):
        return str(result.get("reason") or result.get("error") or "provider_rejected") \
            if isinstance(result, dict) else "provider_response_unavailable"

    def retire(self, operation):
        binding = ControlOperationPolicy.bridge_binding(operation)
        try:
            ok, result = self.proxy("game_provider", "/control/operation/retire",
                                    binding, timeout=3.0,
                                    profile_id=operation["execution_profile_id"])
        except OSError:
            return False, None
        exact = ok and isinstance(result, dict) and result.get("accepted") is True \
            and result.get("state") == "retired" and all(
                result.get(key) == value for key, value in binding.items())
        return exact, result.get("result") if exact else None


class StreamerControlOperation:
    @staticmethod
    def _await_idle(proxy, profile_id, timeout=5.0):
        deadline = time.monotonic() + timeout
        while True:
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                return False
            try:
                ok, observed = proxy(
                    "vibepollo", "/snapshot?force=1",
                    timeout=min(2.0, remaining), profile_id=profile_id)
                if ok and ControlOperationPolicy.streamer_idle(observed):
                    return True
            except OSError:
                pass
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                return False
            time.sleep(min(0.25, remaining))

    @staticmethod
    def force_close(proxy, profile_id):
        try:
            proxy("vibepollo", "/action/close-app", timeout=8.0,
                  profile_id=profile_id)
        except OSError:
            pass
        return StreamerControlOperation._await_idle(proxy, profile_id)

    @staticmethod
    def close(proxy, profile_id, stream):
        """Caller holds the exact Gateway permit through observation and effect."""
        def snapshot(timeout=8.0):
            return proxy("vibepollo", "/snapshot?force=1", timeout=timeout,
                         profile_id=profile_id)
        # A forced snapshot fans out to several local Vibepollo endpoints. One
        # transiently unavailable source is not evidence that another owner has
        # the stream, so retry only the unavailable observation. An authoritative
        # identity mismatch still fails closed immediately.
        observation_deadline = time.monotonic() + 12.0
        while True:
            try:
                ok, observed = snapshot(min(8.0, max(
                    0.1, observation_deadline - time.monotonic())))
            except OSError:
                ok, observed = False, None
            if ok and ControlOperationPolicy.streamer_idle(observed):
                return True, "streamer_already_idle"
            if ok and ControlOperationPolicy.streamer_snapshot_authoritative(observed):
                if not ControlOperationPolicy.streamer_close_allowed(observed, stream):
                    return False, "streamer_identity_unconfirmed"
                break
            if time.monotonic() >= observation_deadline:
                return False, "streamer_snapshot_unavailable"
            time.sleep(0.25)
        try:
            proxy("vibepollo", "/action/close-app", timeout=8.0,
                  profile_id=profile_id)
        except OSError:
            pass
        # An accepted close is asynchronous, and Vibepollo can also return HTTP
        # 500 after closing or lose the response.  In every case, keep the same
        # permit until fresh physical evidence confirms the postcondition.
        if StreamerControlOperation._await_idle(proxy, profile_id):
            return True, "streamer_idle_after_close"
        return False, "streamer_close_unconfirmed"
