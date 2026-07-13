# Public stream launch Intent

Moonlight X exposes a versioned public Android Intent and deep link for launching
an already paired host and, optionally, one of its applications. The entry point
uses the same Wake-on-LAN and host readiness flow as Quick Launch shortcuts.

## Deep link

```text
moonlightx://stream/v1?host_uuid=<uuid>&app_id=<integer>
```

Supported query parameters:

- `host_uuid` (preferred) or `host_name`
- `app_id` (preferred) or `app_name` (optional; omitting both opens the host)
- `quick_launch` (launches a saved Quick Launch item and takes precedence)

Examples:

```text
moonlightx://stream/v1?host_name=Gaming%20PC&app_name=Steam
moonlightx://stream/v1?quick_launch=Steam%20TV
```

Test from ADB:

```shell
adb shell am start -a android.intent.action.VIEW -d "moonlightx://stream/v1?host_name=Gaming%20PC&app_name=Steam"
```

## Explicit/implicit Android Intent

Action:

```text
com.limelight.action.STREAM
```

String extras:

```text
com.limelight.extra.HOST_UUID
com.limelight.extra.HOST_NAME
com.limelight.extra.APP_ID
com.limelight.extra.APP_NAME
com.limelight.extra.QUICK_LAUNCH
```

Host UUID and app ID are the stable preferred identifiers. Names are convenient
fallbacks and must exactly match a host or cached application name in Moonlight X.
The caller cannot provide an address or arbitrary command: the host must already
exist in Moonlight X and be paired.
