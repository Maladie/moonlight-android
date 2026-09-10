# S01 compatibility

S01 changes no host protocol, endpoint, permission, token, certificate, profile-session, capability or request-ID schema. `GatewayConnection` remains the actor-profile request descriptor and `GatewayTransport` remains the only HTTPS/TLS, pinning and header implementation.

- New Android + existing host: parent snapshot still requests health/current/readiness; child snapshot requests only current/readiness and child gameplay/quit uses the existing permitted focus/stop routes. The host continues resolving the child actor header to the parent Windows execution profile.
- Existing Android + existing or newly packaged unchanged host: behavior is unchanged.
- New Android + newly packaged unchanged host: same existing wire protocol and actor allowlist; no deployment ordering constraint.

The old ambiguous Java overloads are source-internal APIs and are removed together with every production caller. No external wire client depends on them. A mismatched `GatewayConnection` and actor context is rejected before a request. Parent-only health, Discord, management and child-library-sharing permissions were not widened.

The full quit latency/native completion defects H03/H09 remain pre-existing and assigned to later slices; S01 changes only the actor-correct provider routing selected before those operations.
