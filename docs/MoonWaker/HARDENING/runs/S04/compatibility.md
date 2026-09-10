# S04 compatibility

- New Android client with existing host services: compatible. S04 changes no wire protocol or host package; it makes the local native transport stop the replacement barrier.
- Existing Android client with existing host services: unchanged.
- Stop failure or cancellation: reported as non-success. No newer generation is cleared or stopped, and no false Home/live state is restored.
- Game-only retain and Home behavior: preserved. S04 does not convert ordinary retained navigation into a whole-session quit.
- Scope boundary: host/provider/child orchestration remains S05; full physical-runtime relocation remains S07.

The debug APK is signed with the normal local debug configuration. The release artifact is intentionally unsigned, matching the repository's local release build.
