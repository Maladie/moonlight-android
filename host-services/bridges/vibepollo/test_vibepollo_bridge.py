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


if __name__ == "__main__":
    unittest.main()
