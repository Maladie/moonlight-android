# Public stream status

Moonlight X exposes its current or most recent stream state through a read-only,
signature-protected `ContentProvider`.

## Permission and URI

```xml
<uses-permission android:name="com.limelight.permission.READ_STREAM_STATUS" />
```

Debug package:

```text
content://streamstatus.com.limelight.debug/current
```

Release package:

```text
content://streamstatus.com.limelight/current
```

The caller must be signed with the same certificate as Moonlight X. Consumers
can register a `ContentObserver` for the URI; Moonlight sends a change
notification for every state or bitrate update.

## Columns

```text
state          idle | connecting | streaming | reconnecting | ended | error
stage          Current connection stage or status detail
host           Host address
computer       Display name of the computer
app            Streamed application name
bitrate_kbps   Current session target bitrate
width          Requested stream width
height         Requested stream height
fps            Requested stream frame rate
hdr            1 when HDR was requested, otherwise 0
error_code     Last connection error, or 0
updated_at     Unix time in milliseconds
```

`ended` and `error` remain available after the activity exits so the launcher
can explain what happened. The next stream changes the state to `connecting`.

## Runtime bitrate control

The stream protocol negotiates bitrate while creating the connection. The
overlay therefore stages changes in 5 Mbps increments and labels the new value
`APPLY`. Applying a change reconnects the transport while leaving the host
application running. The override lasts for the current Android activity only;
it does not overwrite the saved per-app or global profile.
