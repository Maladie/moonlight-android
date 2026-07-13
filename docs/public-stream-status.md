# Public stream status

Moonlight X exposes its current or most recent stream state through a read-only,
signature-protected `ContentProvider`.

## Permission and URI

```xml
<uses-permission android:name="com.limelight.unofficial.permission.READ_STREAM_STATUS" />
```

Debug package:

```text
content://streamstatus.com.limelight.debug/current
```

Release package:

```text
content://streamstatus.com.limelight.unofficial/current
```

The caller must be signed with the same certificate as Moonlight X. Consumers
can register a `ContentObserver` for the URI; Moonlight sends a change
notification for every state or bitrate update.

Both the provider authority and its signature permission are derived from the
installed Moonlight package ID. Development builds therefore use
`com.limelight.debug.permission.READ_STREAM_STATUS`, while the normal unofficial
release uses `com.limelight.unofficial.permission.READ_STREAM_STATUS`.

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
activity_alive 1 while the stream Activity is alive, otherwise 0
started_at     Session start time in Unix milliseconds
updated_at     Unix time in milliseconds
```

`ended` and `error` remain available after the activity exits so the launcher
can explain what happened. The next stream changes the state to `connecting`.
Consumers should combine `state` with `activity_alive` before presenting a
return-to-stream action because a persisted state may outlive the process.

## Runtime bitrate control

The stream protocol negotiates bitrate while creating the connection. When the
experimental **Runtime bitrate controls** setting is enabled, the overlay stages
changes in 5 Mbps increments and labels the new value `APPLY`. Applying a change
reconnects the transport while leaving the host application running. The option
is disabled by default. The override lasts for the restarted stream activity
only; it does not overwrite the saved per-app or global profile.
