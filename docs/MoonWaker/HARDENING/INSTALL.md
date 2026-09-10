# Install the execution packet

Unpack the ZIP in the repository root. It adds docs/MoonWaker/HARDENING/ only. It does not modify app/host source, root AGENTS, Git refs, services or devices, and does not start a test or agent.

The packet is a fresh, unstarted execution plan. Read OPERATOR_START_PL.md and give its prompt to a new coordinator session. The coordinator creates codex/session-hardening from the current named base before starting S00; merely extracting files does not create a branch.

Use one copy of the packet at the canonical path. Extract into an empty destination. If that path is already occupied by an unused plan, replace the whole unused directory rather than overlaying files and leaving stale instructions. Never overwrite actual STATE/runs, local configuration or unrelated work. Once execution begins, retain its live state and evidence.

The shipped Python tooling uses Python 3.9+ and the standard library only. Use python3 or py -3 when that is the installed command. Product tests/builds still require the actual project toolchain and dependencies.

packet.py validates metadata, checks a prepared branch and creates bounded contexts. run_checks.py is an opt-in local command runner; plans are templates until inspected and bound to actual repository commands. It is not a sandbox, does not install SDKs, and does not implement the proposed Android/Windows/TV test scenarios. GitHub Actions is not required. Keep sensitive logs/local lab configuration in approved ignored locations. Never commit secrets or personal desktop captures.
