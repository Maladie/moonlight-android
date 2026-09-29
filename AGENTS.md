# MoonWaker

Android TV client built on Moonlight, with a Windows Gateway and per-profile Bridges.

## Outcome and scope

Deliver the requested behavior through its real callers. A hardening checkpoint must remain usable without a later task completing its integration. Improve the touched responsibility: move its state and effects, migrate callers, and remove obsolete ownership. Do not turn extraction into a helper that reaches back into the whole Activity. Leave unrelated cleanup alone.

Continue through implementation, relevant checks, and corrections caused by this change. Resolve local naming/path drift autonomously. Pause for a required design gate, missing authority, or a decision changing the agreed product/security boundary—not merely because a first implementation exists.

Do not add compatibility shims, legacy aliases, migrations, old-shape detection or rejection, fallback paths, dual behavior, or stale overrides. This is not production code, you can introduce breaking changes.

Remove any unnecessary fallback paths, compatibility, migrations, or related legacy behavior you encounter while implementing code.

## Invariants

- Gateway is the only LAN-facing host API; Bridges stay loopback-only. Preserve shared pinned HTTPS, pairing, Bearer authentication, profile headers, mutation request IDs, endpoint permissions, and bounded responses. No trust-all TLS or generic Bridge proxy.
- Keep one authoritative owner per fact. GameOps durability belongs to the host journal. Preserve correlation and reject stale effects; resume metadata is not a live connection.
- Keep transition/readiness/input decisions in the canonical privacy gate. Preserve its fresh-frame checks and explicitly authorized manual-reveal path; never bypass the gate to hide a failure.
- A child's use of a parent's Windows profile does not grant parent privileges. Keep host-side entitlement enforcement and process/window identity validation.
- Prefer MoonWaker seams over changes to upstream `app/src/main/jni/moonlight-core/`; justify a necessary exception.

## Authority

Local edits, builds, and verified isolated tests are allowed within the task. Fix and rerun affected checks without repeated approval. “Local” alone does not mean isolated: use the validation guide for unknown scripts.

Commit, push, publish, deployment, installation, live sign-in, credential changes, sleep/restart, and destructive host actions need explicit authorization. Permission for one does not authorize the others. Preserve unrelated work; do not discard or reset it. Keep secrets, machine-specific configuration, and generated artifacts out of commits and shared evidence.

## Read by relevance

All paths below are repository-relative. Load needed sections, not a mandatory reading stack.

- Session, privacy, or host-API contracts: [session guide](docs/agents/SESSION_CONTRACTS.md); consult [architecture](MOONWAKER_ARCHITECTURE.md) for affected ownership boundaries. Verify historical design claims against current code.
- Delegation, independent review, or resuming a multi-task plan: [workflow](CODEX_WORKFLOW.md).
- Selecting checks or assessing their side effects: [validation](docs/agents/VALIDATION.md).
- Producing a host installer: the `moonwaker-host-artifacts` skill.

The assigned task packet supplies additional gates and its own state store; do not weaken them to save usage. Unassigned task documents and state files are not startup instructions.
