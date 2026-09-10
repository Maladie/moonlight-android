# MoonWaker — start hardening in Codex

Rozpakuj ZIP w głównym katalogu repozytorium. Pakiet ma znajdować się w
`docs/MoonWaker/HARDENING/`. Użyj jednej, kompletnej kopii tego katalogu.

Wklej poniższą instrukcję do nowej sesji koordynatora Sol:

```text
Read docs/MoonWaker/HARDENING/START_HERE.md and execute its workflow.

This is a fresh run. Before S00 or any product/test/build edits, follow BRANCHING.md:
verify Maladie/moonlight-android and origin, fetch codex/multi-profile-remote-sign-in,
resolve and record its current tip (preserve a strictly newer local base), then
create and switch to the new LOCAL branch codex/session-hardening.
Do not implement on the base branch, use main, or reset to the audit SHA.
Preserve user work; unrelated dirty changes, divergent tips or a target-name
collision must not be resolved by reset, clean, forced checkout, stash or discard.
Record workspace binding and pass packet.py branch-check --repo . before S00.
On subsequent resumes reuse this branch and recorded base; do not refork.

Follow actual repository instructions, RULES.md, CHECKPOINTS.md and DECOMPOSITION.md.
Every accepted implementation slice must be integrated, usable without a later task,
and preserve prior working behavior. Decompose affected monolith responsibilities
with their state/effects/callers and remove obsolete ownership. Do not defer
integration or newly introduced regressions.

Start S00 and bind actual local baseline/build/test commands. Build a LOCAL pipeline;
GitHub Actions is not required. Extend targeted and cumulative regression coverage
with each slice. Run available safe local checks yourself and record actual results.
The shipped runner is not a completed MoonWaker E2E suite. Missing mandatory device
evidence means CODE_VERIFIED_DEVICE_PENDING, not checkpoint acceptance.

Use one mutating worker and a genuinely independent review context. Follow Sol/Astra
role hints only when actual model selection is supported. Do not impersonate another
agent, fabricate model selection or claim device tests without real evidence.

The named base fetch and new local branch creation are authorized. Do not commit,
push, create a PR, deploy, install services, change credentials, force sign-in or
put hardware to sleep without explicit separate authorization.
```

Pakiet zawiera plan i narzędzia wykonawcze. Utworzenie gałęzi i budowę lokalnego
pipeline wykona agent w Twoim środowisku; samo rozpakowanie archiwum ich nie uruchamia.
