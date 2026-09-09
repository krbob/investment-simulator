#!/usr/bin/env python3
"""Capture Portfolio data, run the local simulator, and atomically save its analysis report."""

from __future__ import annotations

import argparse
from http.client import HTTPException
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import sys


ROOT = Path(__file__).resolve().parents[1]
DEFAULT_SIMULATOR = ROOT / "build/install/investment-simulator/bin/investment-simulator"
CAPTURE_SPEC = importlib.util.spec_from_file_location("capture_portfolio", ROOT / "scripts/capture-portfolio.py")
capture = importlib.util.module_from_spec(CAPTURE_SPEC)
CAPTURE_SPEC.loader.exec_module(capture)
SIMULATION_TIMEOUT_SECONDS = 300


class AnalysisError(ValueError):
    """A safe, non-sensitive explanation of why no report was published."""


def reject_nonfinite(_value: str):
    raise AnalysisError("JSON must not contain NaN or Infinity.")


def read_settings(path: Path) -> dict:
    try:
        settings = json.loads(path.read_text(encoding="utf-8"), parse_constant=reject_nonfinite)
    except (OSError, ValueError, UnicodeError):
        raise AnalysisError("Cannot read settings as valid JSON.") from None
    if not isinstance(settings, dict) or set(settings) != {"portfolio", "plan"}:
        raise AnalysisError("Settings must contain exactly the portfolio and plan objects.")
    portfolio = settings["portfolio"]
    if not isinstance(portfolio, dict) or not isinstance(portfolio.get("selection"), dict):
        raise AnalysisError("Settings portfolio must contain a selection object.")
    if portfolio.keys() - capture.SETTINGS_KEYS:
        raise AnalysisError("Settings portfolio contains unsupported fields.")
    if not isinstance(settings["plan"], dict):
        raise AnalysisError("Settings plan must be a JSON object.")
    return settings


def run_analysis(simulator: Path, bundle: dict, plan: dict) -> tuple[dict, int]:
    request = json.dumps({"portfolio": bundle, "plan": plan}, ensure_ascii=False, allow_nan=False)
    # Portfolio authentication is needed only by capture, never by the simulator process.
    environment = {key: value for key, value in os.environ.items() if key != "PORTFOLIO_SESSION_COOKIE"}
    try:
        process = subprocess.run(
            [str(simulator), "analyze-portfolio", "-"], input=request, text=True, encoding="utf-8",
            capture_output=True, check=False, timeout=SIMULATION_TIMEOUT_SECONDS, env=environment,
        )
    except subprocess.TimeoutExpired:
        raise AnalysisError("Local simulation exceeded its five-minute timeout.") from None
    except (OSError, UnicodeError):
        raise AnalysisError("Cannot execute the local simulator; run ./gradlew installDist or check --simulator.") from None
    # A serializer or JVM error can include the input, paths or process environment: never echo it.
    if process.returncode not in {0, 3}:
        raise AnalysisError("Local simulator rejected the request or failed; check the settings against the analysis contract.")
    try:
        report = json.loads(process.stdout, parse_constant=reject_nonfinite)
    except (ValueError, UnicodeError):
        raise AnalysisError("Local simulator did not produce a valid JSON report.") from None
    if not isinstance(report, dict):
        raise AnalysisError("Local simulator did not produce an analysis object.")
    status = report.get("status")
    expected_exit = {"COMPLETE": 0, "NEEDS_INPUT": 3, "UNSUPPORTED": 3}.get(status) if isinstance(status, str) else None
    if expected_exit != process.returncode:
        raise AnalysisError("Local simulator report status and exit code do not match the analysis contract.")
    if any(not isinstance(report.get(key), list) for key in ("dataGaps", "strategyDescriptions", "assumptions")):
        raise AnalysisError("Local simulator report is missing analysis fields.")
    if status == "COMPLETE" and (
        report["dataGaps"] or not isinstance(report.get("resolvedRequest"), dict) or
        not isinstance(report.get("comparison"), dict)
    ):
        raise AnalysisError("Local simulator returned an incomplete successful report.")
    if status != "COMPLETE" and not report["dataGaps"]:
        raise AnalysisError("Local simulator returned a data-gap report without explaining its gaps.")
    return report, process.returncode


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", required=True, help="Portfolio API base URL; omit the /v1 endpoint suffix")
    parser.add_argument("--settings", required=True, type=Path, help="Local JSON with portfolio selection/tax inputs and plan")
    parser.add_argument("--output", required=True, type=Path, help="Local report, including NEEDS_INPUT/UNSUPPORTED results")
    parser.add_argument("--simulator", type=Path, default=DEFAULT_SIMULATOR,
                        help="Local executable (default: build/install/investment-simulator/bin/investment-simulator)")
    parser.add_argument("--timeout", type=float, default=15,
                        help="Per-Portfolio-request timeout in seconds, at most 60 (default: 15)")
    args = parser.parse_args(argv)
    try:
        settings = read_settings(args.settings)
        simulator = args.simulator.resolve()
        if not simulator.is_file() or not os.access(simulator, os.X_OK):
            raise AnalysisError("Local simulator is unavailable; run ./gradlew installDist or provide --simulator.")
        if args.output.resolve() in {args.settings.resolve(), simulator}:
            raise AnalysisError("Output must differ from the settings file and simulator executable.")
        bundle = capture.capture_bundle(
            args.base_url, settings["portfolio"], os.environ.get("PORTFOLIO_SESSION_COOKIE"), args.timeout,
        )
        report, exit_code = run_analysis(simulator, bundle, settings["plan"])
        capture.write_bundle(report, args.output)
    except (AnalysisError, capture.CaptureError) as error:
        print(f"Analysis failed: {error}", file=sys.stderr)
        return 2
    except (OSError, ValueError, UnicodeError, HTTPException):
        # Do not echo source values, filesystem paths, subprocess output or credentials on errors.
        print("Analysis failed: cannot read Portfolio data or write the local report.", file=sys.stderr)
        return 2
    print(f"Saved local analysis report ({report['status']}).")
    return exit_code


if __name__ == "__main__":
    raise SystemExit(main())
