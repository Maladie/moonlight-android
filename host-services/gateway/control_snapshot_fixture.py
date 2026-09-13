"""Explicit authoritative telemetry envelope for offline Gateway test snapshots."""


def control_evidence(connected_count=0, app_running=False):
    return {"control_fresh": True, "control_unavailable_sources": [],
            "session": {"active": connected_count > 0, "count": connected_count,
                        "app_running": app_running}}
