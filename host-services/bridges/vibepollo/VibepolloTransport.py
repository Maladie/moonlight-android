#!/usr/bin/env python3
"""TLS transport for Vibepollo; reads JSON from stdin to hide the token."""
import json
import ssl
import sys
import base64
import urllib.error
import urllib.request
from concurrent.futures import ThreadPoolExecutor
from time import monotonic


CONTROL_PATHS = {
    "session": "/api/session/status",
    "rtsp": "/api/rtsp/sessions",
    "webrtc": "/api/webrtc/sessions",
    "apps": "/api/apps",
    "clients": "/api/clients/list",
}


def reply(value):
    # ASCII-only JSON avoids Windows console code-page failures (for example
    # a BOM character embedded in Vibepollo logs).
    sys.stdout.write(json.dumps(value, ensure_ascii=True, separators=(",", ":")))
    sys.stdout.flush()


def control_snapshot(base_url, headers, context):
    """Fetch only the fixed control sources in one bounded helper process."""
    def fetch(item):
        name, path = item
        started = monotonic()
        request = urllib.request.Request(base_url + path, headers=headers, method="GET")
        try:
            with urllib.request.urlopen(request, context=context, timeout=0.6) as response:
                result = {"ok": True, "status": response.status,
                          "content": response.read().decode("utf-8", errors="replace")}
        except urllib.error.HTTPError as error:
            result = {"ok": False, "status": error.code, "timeout": False}
        except Exception as error:
            reason = getattr(error, "reason", None)
            result = {"ok": False, "status": 0,
                      "timeout": isinstance(error, TimeoutError) or isinstance(reason, TimeoutError)}
        result["duration_ms"] = int((monotonic() - started) * 1000)
        return name, result

    with ThreadPoolExecutor(max_workers=len(CONTROL_PATHS)) as workers:
        sources = dict(workers.map(fetch, CONTROL_PATHS.items()))
    reply({"ok": True, "control_sources": sources})
    return 0


def main():
    request_data = json.load(sys.stdin)
    base_url = request_data["base_url"].rstrip("/")
    method = request_data.get("method", "GET").upper()
    token = request_data.get("token", "")
    username = request_data.get("username", "")
    password = request_data.get("password", "")
    body = request_data.get("body")
    output_path = request_data.get("output_path")
    data = None
    headers = {"Accept": "application/json"}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    elif username or password:
        encoded = base64.b64encode(f"{username}:{password}".encode("utf-8")).decode("ascii")
        headers["Authorization"] = f"Basic {encoded}"
    if body is not None:
        data = json.dumps(body, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
        headers["Content-Type"] = "application/json"
    if request_data.get("control_snapshot") is True:
        context = ssl.create_default_context()
        context.check_hostname = False
        context.verify_mode = ssl.CERT_NONE
        return control_snapshot(base_url, headers, context)
    url = base_url + request_data["path"]
    req = urllib.request.Request(url, data=data, headers=headers, method=method)
    context = ssl.create_default_context()
    context.check_hostname = False
    context.verify_mode = ssl.CERT_NONE
    try:
        # The bridge is intentionally local and single-process. A short timeout
        # prevents one unavailable Vibepollo API from blocking health checks and
        # every remote client queued behind the failing request.
        with urllib.request.urlopen(req, context=context, timeout=3) as response:
            content = response.read()
            if output_path:
                with open(output_path, "wb") as output:
                    output.write(content)
                reply({"ok": True, "status": response.status, "output_path": output_path, "length": len(content)})
            else:
                reply({"ok": True, "status": response.status, "content": content.decode("utf-8", errors="replace")})
    except urllib.error.HTTPError as error:
        content = error.read().decode("utf-8", errors="replace")
        reply({"ok": False, "status": error.code, "error": content or str(error)})
        return 2
    except Exception as error:
        reply({"ok": False, "status": 0, "error": f"{type(error).__name__}: {error}"})
        return 3
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
