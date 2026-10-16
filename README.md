# MoonWaker

MoonWaker is a console-style Android TV client for streaming PC games. It is built on
[Moonlight](https://moonlight-stream.org) and adds a Windows host side that wakes the PC,
signs the right Windows user in, starts the game from a Playnite library and hands the TV a
stream that is revealed only when the game is actually on screen.

It is a personal project under active development, not a released product. Breaking changes
between the Android app and the host services are normal: both are built and installed from
the same source revision.

## What it adds to Moonlight

- **Console UI for TV** – host and profile selection, a Playnite library (carousel, grid,
  filters, artwork), an on-screen keyboard and controller glyphs, all usable with a remote
  or a gamepad.
- **Session lifecycle** – Wake-on-LAN, launch preflight, warm-up, remote Windows sign-in,
  Fast User Switching, suspend/resume, a stream kept alive in the background, and switching
  games without tearing down the transport.
- **Privacy gate** – the stream stays covered until the expected game is ready and a fresh
  video frame has been rendered.
- **Game operations** – install and uninstall from the TV (Steam; Epic partially) with a
  durable journal on the host.
- **Profiles** – optional PIN per profile, child profiles with a parent-defined library and
  play-time limits.
- **Discord** – community panel, direct messages and voice, plus USB microphone support.

## Repository layout

| Path | Contents |
| --- | --- |
| `app/` | Android application. MoonWaker code lives in `com.limelight.console`, `stream`, `gateway`, `discord` and `diagnostics`; the remaining packages are Moonlight. |
| `app/src/main/jni/moonlight-core/` | Upstream Moonlight streaming core (submodule `moonlight-common-c`). Kept as close to upstream as possible. |
| `host-services/` | Windows host: Gateway (the only LAN-facing API), an in-process Vibepollo client, per-profile Bridges for Playnite and (optional) Discord, Host Control, installer, updater, Login Broker and Credential Provider. See [host-services/README.md](host-services/README.md). |
| `lab/` | Deterministic lab runner for device scenarios. |
| `docs/` | Agent guides, hardening packet, UX backlog and the [work plan](docs/MoonWaker/PLAN_PL.md). |

## Architecture in one paragraph

The TV talks to the host over two channels. Media goes directly to the streaming host
(Vibepollo, Sunshine-compatible) through the Moonlight core. Everything else goes through
the MoonWaker **Gateway**: pinned HTTPS, pairing, Bearer tokens and per-profile grants.
The Gateway forwards to loopback-only **Bridges** that run inside each Windows profile.
Durable game-operation state lives on the host; the Android app keeps presentation state
only. Details and ownership rules are in
[MOONWAKER_ARCHITECTURE.md](MOONWAKER_ARCHITECTURE.md) and
[docs/agents/SESSION_CONTRACTS.md](docs/agents/SESSION_CONTRACTS.md).

## Building

Requirements: JDK 17, Android SDK (compile SDK 34) with NDK 27.0.12077973, Python 3 and
Windows PowerShell for the host services.

```bash
git submodule update --init --recursive
```

Create `local.properties` in the repository root with `sdk.dir=` pointing at the Android
SDK.

Android debug APK (`nonRoot` is the normal flavor):

```bash
./gradlew assembleNonRootDebug
```

The Discord features need the Discord Social SDK, which is a licensed binary and is not in
the repository. Place it in `local-sdk/discord-social-sdk-1.10.18687` or pass
`-PdiscordSocialSdkDir=<path>`. Without it the build is configured to leave Discord disabled.

Host installer (runs the host test suites as part of the build, output in
`host-services/dist`; add `-WithDiscord` to include the optional Discord Bridge):

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\Build-HostServices.ps1
```

## Testing

```bash
./gradlew testNonRootDebugUnitTest
```

```bash
python -m unittest discover -s host-services/gateway -p 'test*.py'
```

```bash
python -m unittest discover -s host-services/bridges/playnite -p 'test*.py'
```

These are unit tests. Anything that claims a working stream, Windows sign-in or real input
needs a run on a device; see [docs/agents/VALIDATION.md](docs/agents/VALIDATION.md) for
which checks are safe to run locally and which touch a real host or TV.

## Working on the code

- [AGENTS.md](AGENTS.md) – invariants, authority and scope rules for every change.
- [CODEX_WORKFLOW.md](CODEX_WORKFLOW.md) – how delegated tasks and reviews are run.
- [docs/MoonWaker/HARDENING/](docs/MoonWaker/HARDENING/START_HERE.md) – the session
  hardening packet currently in progress.
- [docs/MoonWaker/PLAN_PL.md](docs/MoonWaker/PLAN_PL.md) – prioritised plan of next steps.

## Upstream and credits

MoonWaker is a fork of
[MoreOrLessSoftware/moonlight-android](https://github.com/MoreOrLessSoftware/moonlight-android)
(branched at v0.4.10), which is itself a fork of
[Moonlight Android](https://github.com/moonlight-stream/moonlight-android) by Cameron Gutman
and contributors, with changes from
[Artemis](https://github.com/ClassicOldSong/moonlight-android).

Licensed under the GNU General Public License v3; see [LICENSE.txt](LICENSE.txt). Third-party
attributions are in [NOTICE](NOTICE) and
[docs/THIRD_PARTY_AUDIO.md](docs/THIRD_PARTY_AUDIO.md).
