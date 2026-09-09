#!/usr/bin/env python3
"""Capture a local, read-only Portfolio bundle for import-portfolio; no third-party packages."""

from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
import sys
import tempfile
from urllib.error import HTTPError, URLError
from urllib.parse import urlsplit
from urllib.request import HTTPRedirectHandler, Request, build_opener


MAX_RESPONSE_BYTES = 16 * 1024 * 1024
SETTINGS_KEYS = {"selection", "verifiedPurchaseCostsPln", "okiOpenedOn", "openingTaxState"}


class CaptureError(ValueError):
    """The source cannot provide a supported, consistent snapshot."""


class RejectRedirects(HTTPRedirectHandler):
    def redirect_request(self, request, response, code, message, headers, new_url):
        raise CaptureError("Portfolio returned an HTTP redirect; provide its final API base URL.")


def capture_bundle(base_url: str, settings: dict, session_cookie: str | None = None, timeout: float = 15) -> dict:
    parsed = urlsplit(base_url)
    if parsed.scheme not in {"http", "https"} or not parsed.hostname:
        raise CaptureError("--base-url must be an absolute http(s) API base URL.")
    if parsed.username is not None or parsed.password is not None or parsed.query or parsed.fragment:
        raise CaptureError("API base URL must not contain credentials, a query, or a fragment.")
    if not isinstance(settings, dict) or not isinstance(settings.get("selection"), dict):
        raise CaptureError("Settings must be a JSON object containing selection.")
    unknown = settings.keys() - SETTINGS_KEYS
    if unknown:
        raise CaptureError("Unsupported settings keys: " + ", ".join(sorted(unknown)))
    if not 0 < timeout <= 60:
        raise CaptureError("Timeout must be greater than zero and at most 60 seconds.")
    if session_cookie and ("\n" in session_cookie or "\r" in session_cookie):
        raise CaptureError("PORTFOLIO_SESSION_COOKIE must not contain line breaks.")

    headers = {"Accept": "application/json", "User-Agent": "investment-simulator-portfolio-capture/1"}
    if session_cookie:
        headers["Cookie"] = session_cookie
    opener = build_opener(RejectRedirects())

    def get(path: str):
        request = Request(base_url.rstrip("/") + path, headers=headers, method="GET")
        try:
            with opener.open(request, timeout=timeout) as response:
                data = response.read(MAX_RESPONSE_BYTES + 1)
        except HTTPError as error:
            raise CaptureError(f"Portfolio GET {path} returned HTTP {error.code}.") from None
        except (URLError, TimeoutError, OSError):
            raise CaptureError(f"Portfolio GET {path} failed; check the API URL, authentication and connectivity.") from None
        if len(data) > MAX_RESPONSE_BYTES:
            raise CaptureError(f"Portfolio GET {path} exceeded the 16 MiB response limit.")
        try:
            return json.loads(data)
        except (ValueError, UnicodeError):
            raise CaptureError(f"Portfolio GET {path} did not return valid JSON.") from None

    before = get("/v1/portfolio/state/export")
    holdings = get("/v1/portfolio/holdings")
    account_summaries = get("/v1/portfolio/accounts")
    after = get("/v1/portfolio/state/export")
    for exported in (before, after):
        if not isinstance(exported, dict) or exported.get("schemaVersion") != 5:
            raise CaptureError("Portfolio must return an export with schemaVersion 5.")
        if not isinstance(exported.get("exportedAt"), str):
            raise CaptureError("Portfolio export is missing exportedAt.")
        if any(not isinstance(exported.get(key), list) for key in ("accounts", "instruments", "transactions")):
            raise CaptureError("Portfolio export is missing its canonical account, instrument, or transaction arrays.")
    canonical_before = {key: value for key, value in before.items() if key != "exportedAt"}
    canonical_after = {key: value for key, value in after.items() if key != "exportedAt"}
    if canonical_before != canonical_after:
        raise CaptureError("Portfolio canonical state changed during capture; no bundle was written. Retry after edits finish.")
    if not isinstance(holdings, list) or not isinstance(account_summaries, list):
        raise CaptureError("Portfolio holdings and account summaries must be JSON arrays.")
    return {"snapshot": after, "holdings": holdings, "accountSummaries": account_summaries, **settings}


def write_bundle(bundle: dict, output: Path) -> None:
    # Publish only a complete capture; a failed capture never truncates an existing file.
    temporary_name = None
    try:
        with tempfile.NamedTemporaryFile(mode="w", encoding="utf-8", dir=output.parent,
                                         prefix=".portfolio-capture-", suffix=".json", delete=False) as temporary:
            temporary_name = temporary.name
            json.dump(bundle, temporary, ensure_ascii=False, indent=2, allow_nan=False)
            temporary.write("\n")
            temporary.flush()
            os.fsync(temporary.fileno())
        os.replace(temporary_name, output)
    finally:
        if temporary_name is not None:
            Path(temporary_name).unlink(missing_ok=True)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", required=True, help="Portfolio API base URL; omit the /v1 endpoint suffix")
    parser.add_argument("--settings", required=True, type=Path,
                        help="JSON containing selection and optional verified costs, OKI opening date, opening tax state")
    parser.add_argument("--output", required=True, type=Path, help="Local bundle file to publish after successful capture")
    parser.add_argument("--timeout", type=float, default=15, help="Per-request timeout in seconds, at most 60 (default: 15)")
    args = parser.parse_args(argv)
    try:
        settings = json.loads(args.settings.read_text(encoding="utf-8"))
        bundle = capture_bundle(args.base_url, settings, os.environ.get("PORTFOLIO_SESSION_COOKIE"), args.timeout)
        write_bundle(bundle, args.output)
    except (OSError, ValueError) as error:
        print(f"Capture failed: {error}", file=sys.stderr)
        return 2
    print(f"Wrote Portfolio bundle to {args.output}. Validate it with import-portfolio before using it.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
