import unittest
from pathlib import Path


class VibepolloBridgeContractTest(unittest.TestCase):
    @staticmethod
    def source() -> str:
        return (Path(__file__).with_name("VibepolloBridge.ps1")
                .read_text(encoding="utf-8-sig"))

    def test_active_display_probe_is_local_and_bounded(self):
        source = self.source()
        start = source.index("'^/diagnostics/active-displays$'")
        end = source.index("'^/diagnostics/stream-sources$'", start)
        route = source[start:end]

        self.assertIn("Get-ActiveDisplayDiagnostics", route)
        self.assertNotIn("Invoke-VibepolloApi", route)
        self.assertNotIn("Build-StreamSourceDiagnostics", route)

    def test_richer_stream_sources_endpoint_remains_diagnostic_only(self):
        source = self.source()
        start = source.index("function Build-StreamSourceDiagnostics")
        end = source.index("function Send-JsonResponse", start)
        diagnostics = source[start:end]

        self.assertIn("Invoke-VibepolloApi", diagnostics)
        self.assertIn("Get-ActiveDisplayDiagnostics", diagnostics)

    def test_tls_helper_has_startup_margin_over_transport_deadline(self):
        source = self.source()

        self.assertIn("$script:ApiProcessTimeoutMs = 5000", source)
        self.assertIn("WaitForExit($script:ApiProcessTimeoutMs)", source)

    def test_identity_proof_route_uses_trusted_state_and_live_client(self):
        source = self.source()
        identity = (Path(__file__).with_name("VibepolloIdentity.ps1")
                    .read_text(encoding="utf-8-sig"))
        self.assertIn("'^/identity/verify$'", source)
        self.assertIn("Get-VibepolloIdentityProof", source)
        self.assertIn("Get-VibepolloIdentityInstallRoot", source)
        self.assertIn("Get-VibepolloLiveUniqueId", source)
        self.assertIn("Get-VibepolloClientRecords", source)
        self.assertIn('throw "pairing_required"', source)
        self.assertIn('throw "identity_ambiguous"', source)
        self.assertIn("$liveMatches", source)
        self.assertIn("-cne [string]$state.host_uniqueid", source)
        self.assertIn("RSACertificateExtensions]::GetRSAPublicKey", identity)
        self.assertIn("ReparsePoint", identity)
        self.assertIn("1MB", identity)

    def test_profile_deployment_copies_identity_helper(self):
        installer = (Path(__file__).parents[2] / "install" /
                     "Install-WakePlayProfile.ps1").read_text(encoding="utf-8-sig")
        self.assertIn('"VibepolloIdentity.ps1"', installer)

    def test_pairing_waits_for_pending_session_and_reports_exact_phase(self):
        source = self.source()
        start = source.index("function Pair-MoonWakerClient")
        end = source.index("function Get-Snapshot", start)
        pairing = source[start:end]

        self.assertIn("$pinWait = [Diagnostics.Stopwatch]::StartNew()", pairing)
        self.assertIn("Start-Sleep -Milliseconds 250", pairing)
        self.assertIn("did not expose a pending Moonlight pairing session", pairing)
        self.assertIn("accepted the PIN, but", pairing)
        self.assertIn("expected $($script:MoonWakerClientPermissions), received $actualPermissions", pairing)


if __name__ == "__main__":
    unittest.main()
