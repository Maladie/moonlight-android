"""Explicit authoritative telemetry envelope for offline Gateway test snapshots."""


def control_evidence(connected_count=0, app_running=False):
    return {"control_fresh": True, "control_unavailable_sources": [],
            "session": {"active": connected_count > 0, "count": connected_count,
                        "app_running": app_running}}


class RoutedVibepollo:
    """Test double of the Gateway's Vibepollo client. It answers from the fake `proxy` a test
    installs on the Gateway state, looked up per call, under fixed routes: "/snapshot?force=1"
    (control snapshot), "/action/close-app" and "/health" (API online)."""

    def __init__(self, state):
        self.state = state
        self.running_app = None  # running_app_id(): None (unknown) unless a test sets it

    def running_app_id(self, timeout=0.6):
        return self.running_app

    def _route(self, path, timeout):
        try:
            return self.state.proxy("vibepollo", path, timeout=timeout)
        except OSError:
            return False, {"error": "fixture_transport_error"}

    def control_snapshot(self, timeout=8.0):
        return self._route("/snapshot?force=1", timeout)

    def close_app(self, timeout=8.0):
        return self._route("/action/close-app", timeout)

    def api_online(self, timeout=1.0):
        return bool(self._route("/health", timeout)[0])


def route_vibepollo_through_proxy(gateway_state_class):
    """Give every Gateway state a RoutedVibepollo; a test module calls this once at import."""
    if getattr(gateway_state_class, "_routed_vibepollo", False):
        return
    original = gateway_state_class.__init__

    def init(self, *args, **kwargs):
        original(self, *args, **kwargs)
        self.vibepollo = RoutedVibepollo(self)

    gateway_state_class.__init__ = init
    gateway_state_class._routed_vibepollo = True


def routed(proxy):
    """A RoutedVibepollo answering from one fake `proxy` function."""
    class Holder:
        pass
    holder = Holder()
    holder.proxy = proxy
    return RoutedVibepollo(holder)
