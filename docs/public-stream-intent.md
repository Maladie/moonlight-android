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
com.limelight.extra.EXTERNAL_FRONTEND
com.limelight.extra.EXTERNAL_FRONTEND_PACKAGE
com.limelight.extra.EXTERNAL_FRONTEND_MESSAGE
```

Host UUID and app ID are the stable preferred identifiers. Names are convenient
fallbacks and must exactly match a host or cached application name in Moonlight X.
The caller cannot provide an address or arbitrary command: the host must already
exist in Moonlight X and be paired.

`EXTERNAL_FRONTEND` is a boolean intended for full-screen TV shells. When true,
Moonlight does not place its own `PcView` behind the streaming activity. The
caller should also provide its package name in `EXTERNAL_FRONTEND_PACKAGE`.
Moonlight then hands HOME and BACK navigation directly to that frontend while
keeping the stream activity alive. If Android destroys the visible window
surface during the handoff, decoding continues on a drained background surface;
`RETURN_STREAM` restores the decoder to the original task and new window
surface without starting another host session. Explicit disconnect and quit actions remain
available through the session controls described below. A frontend can pass its
current loading caption in `EXTERNAL_FRONTEND_MESSAGE` so Moonlight preserves
the same loading surface during the cross-application hand-off.

## Public settings entry point

Frontends can open Moonlight's real settings UI without duplicating its
preferences. Start an Intent with this action and restrict it to the installed
Moonlight package:

```text
com.limelight.action.OPEN_SETTINGS
```

Pressing Back returns to the calling frontend.

## Return to an active stream

An external TV frontend can reveal the existing streaming activity without
starting the host application again:

```text
com.limelight.action.RETURN_STREAM
```

Restrict the Intent to the installed Moonlight package. The action is useful
only while `activity_alive=1` in the public stream-status provider. If no stream
activity exists, Moonlight displays a short message and returns immediately.

## Control an active stream

Same-signature frontends can disconnect the current client while leaving the
host application running:

```text
com.limelight.action.DISCONNECT_STREAM
```

They can also disconnect and request that the host application exits:

```text
com.limelight.action.QUIT_STREAM_APP
```

Both actions require the signature-level
`${applicationId}.permission.CONTROL_STREAM` permission. Callers should scope
the Intent to the installed Moonlight package and ask for confirmation before
using `QUIT_STREAM_APP`.

## Cached applications provider

Same-signature frontends can query the applications cached for a saved host:

```text
content://apps.com.limelight.unofficial/apps/<host-uuid>
```

Required release permission:

```xml
<uses-permission android:name="com.limelight.unofficial.permission.READ_SAVED_APPS" />
```

Columns:

```text
app_id
name
hdr_supported
poster_uri
```

The provider is intentionally cache-only. It never wakes a host or performs
network I/O. An empty result means Moonlight has not cached an application list
for that host yet.
