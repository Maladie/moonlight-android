# S03 compatibility

S03 adds no host route, protocol version, TLS, pinning, token, permission, credential or deployment change. It corrects Android-side sequencing over the existing protocol-6 Gateway profile and Windows-session endpoints.

- New Android client with an existing protocol-6 host: the authenticated profile result binds actor and execution identity before session preparation. Existing hosts may omit the newly consumed optional response correlation fields; absence remains compatible because the request is already bound to that authenticated profile. If fields are present, any mismatch fails closed.
- Existing Android client with an unchanged host: behavior is unchanged.
- New Android client with an unavailable/older capability: null, unavailable, action-required, denied or unrecognized outcomes stop before streamer/game dispatch. There is no credential retry, parent fallback, implicit takeover or logoff.

Play and the existing warm-up now share the corrected entrypoint. LaunchTransitionController, retained-stream transitions and the accepted S01 actor and S02 retention contracts remain authoritative and unchanged.

Amendment A01 is external repository-local LAB tooling only. It is not packaged in the APK or host installer, adds no endpoint and changes no old/new client-host combination. Concrete device scripts may be added later only through a new reviewed BOUND plan; the shipped UNBOUND example cannot execute or claim device evidence.

Amendment A02 remains external and `UNBOUND`. It adds no Android extras, debug entrypoint, host observer endpoint or PowerShell remoting assumption. The scenario controller binds the installed package/component and uses only the exported production `ConsoleActivity`, ordinary DPAD input and read-only UI/host observation. Host observation and scoped cleanup are deliberately injected reviewed executables so existing OS authentication and site-specific diagnostic/restoration sources stay outside repository templates. Timeout/late-READY cannot be marked ready until a legitimate controlled pending-attempt fixture is separately bound.
