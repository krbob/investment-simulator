"""One-command workflow tests using synthetic loopback HTTP data and a controlled local CLI."""

from contextlib import redirect_stderr, redirect_stdout
import importlib.util
import io
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from test_capture_portfolio import fixture_server


ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location("analyze_portfolio", ROOT / "scripts/analyze-portfolio.py")
analyze = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(analyze)


class AnalyzePortfolioTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.path = Path(self.directory.name)
        self.bundle = json.loads((ROOT / "portfolio-adapter/src/test/resources/portfolio-bundle.json").read_text())
        self.settings = {
            "portfolio": {"selection": self.bundle["selection"], "openingTaxState": {}},
            "plan": {
                "startDate": "2027-01-01", "endDate": "2027-12-31",
                "assumptions": [{"year": 2027, "equityReturnRate": 0.05, "inflationRate": 0.025, "okiTaxRate": 0.0085}],
                "taxStateAsOfDate": "2027-01-01", "openingValuationPolicy": "USE_CAPTURED_VALUES_UNCHANGED",
            },
        }
        self.settings_path = self.path / "settings.local.json"
        self.settings_path.write_text(json.dumps(self.settings))
        self.output = self.path / "analysis.local.json"
        self.output.write_text('{"previous":"report"}\n')
        self.executable = self.path / "fixture-simulator"
        self.marker = self.path / "invoked"

    def simulator(self, status="COMPLETE", exit_code=None, raw_output=None, stderr=""):
        exit_code = (0 if status == "COMPLETE" else 3) if exit_code is None else exit_code
        report = {
            "status": status,
            "dataGaps": [] if status == "COMPLETE" else [{"code": "MISSING_TAX_STATE", "path": "/portfolio/openingTaxState", "message": "Provide tax state."}],
            "strategyDescriptions": [], "assumptions": [],
            "resolvedRequest": {} if status == "COMPLETE" else None,
            "comparison": {} if status == "COMPLETE" else None,
        }
        expected_portfolio = {key: value for key, value in self.settings["portfolio"].items()}
        script = "\n".join([
            "#!/usr/bin/env python3", "import json, os, pathlib, sys",
            "assert sys.argv[1:] == ['analyze-portfolio', '-']",
            "assert 'PORTFOLIO_SESSION_COOKIE' not in os.environ",
            "request = json.load(sys.stdin)",
            "assert set(request) == {'portfolio', 'plan'}",
            f"assert request['plan'] == {self.settings['plan']!r}",
            f"assert request['portfolio']['holdings'] == {self.bundle['holdings']!r}",
            f"assert request['portfolio']['snapshot']['transactions'] == {self.bundle['snapshot']['transactions']!r}",
            f"assert all(request['portfolio'][key] == value for key, value in {expected_portfolio!r}.items())",
            "assert request['portfolio']['snapshot']['exportedAt'] == '2026-09-09T12:00:01Z'",
            f"pathlib.Path({str(self.marker)!r}).write_text('invoked')",
            f"print({json.dumps(report) if raw_output is None else raw_output!r})",
            f"print({stderr!r}, file=sys.stderr)", f"sys.exit({exit_code})", "",
        ])
        self.executable.write_text(script)
        self.executable.chmod(0o700)
        return report

    def invoke(self, url, extra=None):
        stdout, stderr = io.StringIO(), io.StringIO()
        with redirect_stdout(stdout), redirect_stderr(stderr), patch.dict(os.environ, {"PORTFOLIO_SESSION_COOKIE": "portfolio_session=secret-synthetic"}):
            exit_code = analyze.main([
                "--base-url", url, "--settings", str(self.settings_path), "--output", str(self.output),
                "--simulator", str(self.executable), *(extra or []),
            ])
        self.assertNotIn("secret-synthetic", stdout.getvalue() + stderr.getvalue())
        return exit_code, stdout.getvalue(), stderr.getvalue()

    def test_success_captures_four_gets_pipes_bundle_to_local_cli_and_replaces_report(self):
        report = self.simulator()
        with fixture_server(self.bundle) as (url, requests):
            exit_code, stdout, stderr = self.invoke(url)
        self.assertEqual(0, exit_code, stderr)
        self.assertTrue(self.marker.exists())
        self.assertEqual(report, json.loads(self.output.read_text()))
        self.assertEqual(4, len(requests))
        self.assertTrue(all(cookie == "portfolio_session=secret-synthetic" for _path, cookie in requests))
        self.assertIn("COMPLETE", stdout)
        self.assertEqual("", stderr)
        self.assertNotIn("secret-synthetic", self.output.read_text())
        self.assertEqual([], list(self.path.glob(".portfolio-capture-*")))

    def test_data_gaps_are_saved_atomically_despite_exit_three(self):
        for status in ("NEEDS_INPUT", "UNSUPPORTED"):
            with self.subTest(status=status):
                report = self.simulator(status=status)
                with fixture_server(self.bundle) as (url, _requests):
                    exit_code, stdout, stderr = self.invoke(url)
                self.assertEqual(3, exit_code, stderr)
                self.assertEqual(report, json.loads(self.output.read_text()))
                self.assertIn(status, stdout)
                self.assertEqual("", stderr)

    def test_cli_errors_invalid_json_and_status_mismatch_preserve_existing_report_and_redact_output(self):
        for options in (
            {"exit_code": 2}, {"raw_output": "not-json secret-synthetic"},
            {"status": "COMPLETE", "exit_code": 3}, {"raw_output": "{}"},
            {"raw_output": '{"status":[]}'},
        ):
            with self.subTest(options=options):
                self.simulator(**options, stderr="secret-synthetic")
                before = self.output.read_bytes()
                with fixture_server(self.bundle) as (url, _requests):
                    exit_code, stdout, stderr = self.invoke(url)
                self.assertEqual(2, exit_code)
                self.assertEqual(before, self.output.read_bytes())
                self.assertEqual("", stdout)
                self.assertIn("Analysis failed:", stderr)

    def test_changed_canonical_state_and_redirect_do_not_invoke_simulator_or_change_report(self):
        self.simulator()
        for options in ({"changed": True}, {"redirect": True}):
            with self.subTest(options=options):
                before = self.output.read_bytes()
                with fixture_server(self.bundle, **options) as (url, _requests):
                    exit_code, _stdout, stderr = self.invoke(url)
                self.assertEqual(2, exit_code, stderr)
                self.assertFalse(self.marker.exists())
                self.assertEqual(before, self.output.read_bytes())

    def test_invalid_settings_and_executable_settings_are_rejected_before_network_access(self):
        self.simulator()
        for settings in (
            "{", "[]", '{"portfolio":{},"plan":{},"simulator":"secret-synthetic"}',
            json.dumps({**self.settings, "plan": []}),
            json.dumps({**self.settings, "portfolio": {**self.settings["portfolio"], "secret-synthetic": "value"}}),
            '{"portfolio":{"selection":{}},"plan":{"amount":NaN}}',
        ):
            with self.subTest(settings=settings):
                self.settings_path.write_text(settings)
                before = self.output.read_bytes()
                with fixture_server(self.bundle) as (url, requests):
                    exit_code, _stdout, stderr = self.invoke(url)
                self.assertEqual(2, exit_code, stderr)
                self.assertEqual([], requests)
                self.assertEqual(before, self.output.read_bytes())

    def test_missing_distribution_is_explained_before_capturing_data(self):
        with fixture_server(self.bundle) as (url, requests):
            exit_code, _stdout, stderr = self.invoke(url)
        self.assertEqual(2, exit_code)
        self.assertIn("installDist", stderr)
        self.assertEqual([], requests)

    def test_malformed_url_does_not_leak_its_value_or_overwrite_report(self):
        self.simulator()
        before = self.output.read_bytes()
        with fixture_server(self.bundle) as (url, requests):
            exit_code, _stdout, stderr = self.invoke(url + "/secret-synthetic bad-path")
        self.assertEqual(2, exit_code)
        self.assertIn("Analysis failed:", stderr)
        self.assertEqual([], requests)
        self.assertEqual(before, self.output.read_bytes())

    def test_output_must_not_overwrite_input_or_executable(self):
        self.simulator()
        for output in (self.settings_path, self.executable):
            with self.subTest(output=output):
                before = output.read_bytes()
                with fixture_server(self.bundle) as (url, requests):
                    exit_code, _stdout, _stderr = self.invoke(url, ["--output", str(output)])
                self.assertEqual(2, exit_code)
                self.assertEqual([], requests)
                self.assertEqual(before, output.read_bytes())


if __name__ == "__main__":
    unittest.main()
