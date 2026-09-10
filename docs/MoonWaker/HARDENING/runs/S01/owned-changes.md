# S01 owned changes

Input fingerprint: `sha256:db6880c0f0597149222d926db820b8438b2ca836d046cdb8c86dac8aacc7a387`

Output fingerprint: `sha256:ff1458b66850bbb923c134c88d1e042072d66c0f456029440be35ee2988155fc`

Owned product/test files:

- `app/src/main/java/com/limelight/console/PlayniteTransitionGateway.java`
- `app/src/main/java/com/limelight/console/HostGatewayClient.java`
- `app/src/main/java/com/limelight/Game.java`
- `app/src/main/java/com/limelight/console/ConsoleActivity.java`
- `app/src/test/java/com/limelight/console/PlayniteTransitionGatewayTest.java`
- `app/src/test/java/com/limelight/GameTransitionCancelContractTest.java`
- `host-services/gateway/test_child_sessions_http.py`

Run/evidence files under `docs/MoonWaker/HARDENING/runs/S01/` are also worker-owned. `host-services/control/Test-MoonWakerHostControl.ps1` is the already accepted S00 input and is explicitly excluded from S01 ownership. All other dirty/untracked files are preserved user/pre-existing work.

The complete S01 review diff is the live diff for the seven paths above against HEAD `00366fd92a1f283bf3aa04171ed3455ce62897ac`; reviewers must also retain the accepted S00 input when recomputing the output fingerprint.
