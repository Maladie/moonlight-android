# S02 compatibility

S02 changes no host API, route, TLS, pinning, token, profile, request-ID, resume-record or retained-session schema. Retention remains an Android-local preference and the existing host needs no coordinated change.

- Fresh new debug or release client: an absent `background_stream_retention_minutes` key resolves to the user-authorized 30 minutes in both variants.
- Upgraded client with an explicit stored value: the integer is read unchanged, including `0` (off), positive minute values and `-1` (no limit). S02 does not materialize or overwrite an absent key during read.
- New Android client with existing host, or existing Android client with any host: host behavior and wire compatibility are unchanged.

The timeout applies only when the transport is parked after MoonWaker leaves the foreground. `openConsoleHome()`, host-picker presentation and `RetainedStreamSessionCoordinator` receive no new timer and remain distinct. S02 does not move physical transport ownership or promise survival across Activity/process destruction; that pre-existing H05 scope remains assigned to S07.
