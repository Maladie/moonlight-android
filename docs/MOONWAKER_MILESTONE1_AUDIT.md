# MoonWaker milestone 1 audit

Audited: 2026-07-15

## Repositories and immutable tags

| Repository | Working HEAD | Baseline commit | Local/remote annotated tag object |
| --- | --- | --- | --- |
| Moonlight | `db5d9266b45a88bfd83fac2b08d2bca9f6e795cc` | `b5620e8f329371fd7b7d8ab8bd1aca7d01d1d6c7` | `877ef3a6b211ff99c45412aba86b449633188f82` |
| Wake | `5188905b69de522153838138ca84e883592a14d6` | `5188905b69de522153838138ca84e883592a14d6` | `fd71d3c60250a76726bc486d7b2a9e689f23e6b8` |

Both local annotated tags dereference to the expected baseline commits. Their
remote tag-object IDs match the local objects. Tags were not moved.

Moonlight work continues on `feature/moonwaker-unified-console`, created from
the clean documentation HEAD `db5d9266`. `origin` remains the Maladie fork and
`upstream` points to the official Moonlight Android repository.

## Identity and manifests

- Non-root release resolves to `com.limelight.unofficial`; debug resolves to
  the separate `com.limelight.debug` package.
- The current launcher remains `PcView` during milestone 1.
- Saved hosts/apps/status providers and public STREAM, RETURN_STREAM,
  DISCONNECT_STREAM, and QUIT_STREAM_APP contracts remain registered.
- `Game` retains its existing `singleTop` lifecycle and background-surface path.
- Wake remains `com.limelight.launcher`; its `main` branch is untouched.

## Build and environment

The required SDK, shared Gradle cache, signing directory, and historical
keystore paths exist. Gradle must run on the available JDK 17 rather than the
system Java 24. Both required APK variants build, 16 JVM tests pass, and the
release APK verifies with the required historical certificate. Exact results
and hashes are recorded in `CODEX_HANDOFF.md`.

GitHub CLI authentication for account `Maladie` was found invalid on this
machine. Public remote reads work; push requires `gh auth refresh -h github.com`
or another already-authorized Git credential without exposing a token.
