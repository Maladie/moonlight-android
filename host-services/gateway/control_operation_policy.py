"""Pure policy for the Gateway-owned MoonWaker control-operation ledger."""

import hashlib
import json
from typing import Any


class ControlOperationPolicy:
    ALLOWED = frozenset({
        "launch", "resume", "reconnect", "replacement", "warm_up",
        "full_quit", "child_terminal", "hard_reset", "provider_transition", "reauthorize",
    })

    @classmethod
    def direction(cls, intent: str) -> str:
        if intent not in cls.ALLOWED:
            raise ValueError("Invalid control operation intent.")
        if intent in {"full_quit", "child_terminal", "hard_reset"}:
            return "stop"
        return "transition" if intent == "provider_transition" else "start"

    @staticmethod
    def bridge_binding(operation: dict[str, Any]) -> dict[str, Any]:
        return {**{key: operation[key] for key in (
            "operation_id", "generation", "actor_lease_epoch",
            "actor_profile_id", "execution_profile_id", "stream_session_id")},
                "effect_sequence": int(operation.get("effect_sequence") or 0),
                "child_session_id": str(operation.get("child_session_id") or "")}

    @staticmethod
    def provider_request(request_id: str, stream: dict[str, Any]) -> dict[str, Any]:
        return {
            "operation_id": "provider-" + hashlib.sha256(
                request_id.encode("utf-8")).hexdigest(),
            "intent": "provider_transition",
            "stream_session_id": str(stream.get("stream_session_id") or ""),
            "sunshine_app_id": int(stream.get("sunshine_app_id") or 0),
            "expected_generation": int(stream.get("generation") or 0),
            "observed_sunshine_app_id": int(stream.get("sunshine_app_id") or 0),
            "child_session_id": str(stream.get("child_session_id") or ""),
        }

    @staticmethod
    def effect_hash(path: str, payload: dict[str, Any]) -> str:
        encoded = json.dumps([path, payload], sort_keys=True,
                             separators=(",", ":")).encode("utf-8")
        return hashlib.sha256(encoded).hexdigest()

    @staticmethod
    def supplied_matches(active: Any, supplied: Any,
                         identity: tuple[str, str, str]) -> bool:
        return isinstance(active, dict) and isinstance(supplied, dict) and all(
            active.get(key) == supplied.get(key)
            for key in ("operation_id", "generation", "actor_lease_epoch")) and all(
            active.get(key) == value for key, value in zip(
                ("client_id", "actor_profile_id", "execution_profile_id"), identity))

    @staticmethod
    def _snapshot_items(value: Any) -> list[Any] | None:
        if isinstance(value, list):
            return value
        if isinstance(value, dict):
            return [value]
        return None

    @staticmethod
    def _authoritative_session(snapshot: Any) -> dict[str, Any] | None:
        if not isinstance(snapshot, dict) or snapshot.get("control_fresh") is not True \
                or snapshot.get("control_unavailable_sources") != []:
            return None
        session = snapshot.get("session")
        if not isinstance(session, dict) or type(session.get("active")) is not bool \
                or type(session.get("app_running")) is not bool \
                or type(session.get("count")) is not int or session["count"] < 0 \
                or session["active"] != (session["count"] > 0):
            return None
        return session

    @staticmethod
    def streamer_transport_gone(snapshot: Any) -> bool:
        """No live transport: zero sessions and no connected client. The app may stay paused."""
        session = ControlOperationPolicy._authoritative_session(snapshot)
        if session is None or session["count"] != 0:
            return False
        clients = ControlOperationPolicy._snapshot_items(snapshot.get("clients"))
        return clients is not None and not any(
            not isinstance(item, dict) or item.get("connected") is not False
            for item in clients)

    @staticmethod
    def streamer_idle(snapshot: Any) -> bool:
        if not ControlOperationPolicy.streamer_transport_gone(snapshot) \
                or snapshot["session"]["app_running"]:
            return False
        apps = ControlOperationPolicy._snapshot_items(snapshot.get("apps"))
        return apps is not None and not any(
            not isinstance(item, dict) or item.get("running") is not False for item in apps)

    @staticmethod
    def streamer_runs_only(snapshot: Any, app_uuid: str) -> bool:
        """The streamer authoritatively runs exactly the application with this UUID."""
        if ControlOperationPolicy._authoritative_session(snapshot) is None:
            return False
        apps = ControlOperationPolicy._snapshot_items(snapshot.get("apps"))
        if apps is None or any(not isinstance(item, dict)
                               or type(item.get("running")) is not bool for item in apps):
            return False
        running = [item for item in apps if item["running"]]
        return len(running) == 1 and str(running[0].get("uuid") or "").lower() == app_uuid

    @staticmethod
    def streamer_snapshot_authoritative(snapshot: Any) -> bool:
        return ControlOperationPolicy._authoritative_session(snapshot) is not None

    @staticmethod
    def streamer_close_allowed(snapshot: Any, stream: Any) -> bool:
        """A client's close: the record's app is the only one running and every session
        is the record's client, attributed by UUID."""
        observed = ControlOperationPolicy._own_app_transport(snapshot, stream)
        if observed is None:
            return False
        count, connected = observed
        if count != len(connected):
            return False
        if len(connected) == 1:
            return bool(stream.get("vibepollo_client_uuid")) and str(
                connected[0].get("uuid") or "").lower() == str(
                stream.get("vibepollo_client_uuid")).lower()
        return not connected

    @staticmethod
    def host_streamer_close_allowed(snapshot: Any, stream: Any) -> bool:
        """The host's close of its own record (`host_held`: tray, idle close): the record's
        app is the only one running. A session no client is attributed to is not a foreign
        client; a connected client other than the record's is."""
        observed = ControlOperationPolicy._own_app_transport(snapshot, stream)
        if observed is None:
            return False
        count, connected = observed
        uuid = str(stream.get("vibepollo_client_uuid") or "").lower()
        return len(connected) <= count and all(
            uuid and str(item.get("uuid") or "").lower() == uuid for item in connected)

    @staticmethod
    def _own_app_transport(snapshot: Any, stream: Any) -> tuple[int, list[dict]] | None:
        """(session count, connected clients) while the streamer authoritatively runs the
        record's application and nothing else, else None."""
        session = ControlOperationPolicy._authoritative_session(snapshot)
        if session is None or not isinstance(stream, dict):
            return None
        clients = ControlOperationPolicy._snapshot_items(snapshot.get("clients"))
        if clients is None or any(not isinstance(item, dict) or
                                 not isinstance(item.get("connected"), bool)
                                 for item in clients):
            return None
        connected = [item for item in clients if isinstance(item, dict)
                     and item.get("connected") is True]
        apps = ControlOperationPolicy._snapshot_items(snapshot.get("apps"))
        if apps is None or any(not isinstance(item, dict) or
                              not isinstance(item.get("running"), bool) for item in apps):
            return None
        running = [item for item in apps if isinstance(item, dict)
                   and item.get("running") is True]
        try:
            observed_app = int(running[0].get("app_id") or 0) \
                if len(running) == 1 else 0
            expected_app = int(stream.get("sunshine_app_id") or 0)
        except (TypeError, ValueError):
            return None
        if expected_app <= 0 or len(running) != 1 or observed_app != expected_app \
                or session["app_running"] is not True:
            return None
        return session["count"], connected

    @staticmethod
    def streamer_start_effect(snapshot: Any, operation: Any) -> str:
        """What a snapshot shows of this start: "absent" (no transport, no running app),
        "present" (its app is the only one running and no other client is connected),
        "foreign" (something this start does not own), else "unknown"."""
        session = ControlOperationPolicy._authoritative_session(snapshot)
        if session is None or not isinstance(operation, dict):
            return "unknown"
        if ControlOperationPolicy.streamer_idle(snapshot):
            return "absent"
        clients = ControlOperationPolicy._snapshot_items(snapshot.get("clients"))
        apps = ControlOperationPolicy._snapshot_items(snapshot.get("apps"))
        if clients is None or apps is None \
                or any(not isinstance(item, dict) or type(item.get("connected")) is not bool
                       for item in clients) \
                or any(not isinstance(item, dict) or type(item.get("running")) is not bool
                       for item in apps):
            return "unknown"
        connected = [str(item.get("uuid") or "").lower() for item in clients
                     if item["connected"]]
        running = [item.get("app_id") for item in apps if item["running"]]
        if session["count"] != len(connected) or session["app_running"] != bool(running):
            return "unknown"
        app_id = operation.get("sunshine_app_id")
        uuid = str(operation.get("vibepollo_client_uuid") or "").lower()
        own = type(app_id) is int and app_id > 0 \
            and [type(item) is int and item for item in running] == [app_id] \
            and all(uuid and item == uuid for item in connected)
        return "present" if own else "foreign"
