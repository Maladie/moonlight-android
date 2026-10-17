"""Test double for the provider's exact retained-target status on /observation.

A fake provider's running_games are its whole process world: a target token that is not listed
there is gone. Tests that need an unreadable or reused target pass ``status`` explicitly.
"""
import urllib.parse


def is_observation(path):
    return urllib.parse.urlsplit(path).path == "/observation"


def with_target(path, observation, status=None):
    query = urllib.parse.parse_qs(urllib.parse.urlsplit(path).query)
    ids, tokens = query.get("target_process_id"), query.get("target_process_token")
    process = observation.get("process") if isinstance(observation, dict) else None
    if ids and tokens and isinstance(process, dict):
        live = any(item.get("process_token") == tokens[0]
                   for item in process.get("running_games") or [])
        process["target"] = {"process_id": int(ids[0]),
                             "status": status or ("alive" if live else "gone")}
    return observation


def target_aware(proxy, status=lambda: None):
    """Wrap a fake proxy: the fake sees plain /observation, the result gains the target."""
    def wrapped(name, path, *args, **kwargs):
        if name == "game_provider" and is_observation(path):
            ok, body = proxy(name, "/observation", *args, **kwargs)
            return ok, (with_target(path, body, status()) if ok else body)
        return proxy(name, path, *args, **kwargs)
    return wrapped
