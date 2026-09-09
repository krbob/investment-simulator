"""Synthetic Monte Carlo report tests; no browser packages, market data, or external services."""

from contextlib import redirect_stderr, redirect_stdout
from decimal import Decimal
from html.parser import HTMLParser
import importlib.util
import io
import json
from pathlib import Path
import stat
import tempfile
import unittest
from unittest.mock import patch


ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location("render_monte_carlo", ROOT / "scripts/render-monte-carlo.py")
renderer = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(renderer)


def distribution(values):
    values = sorted(map(Decimal, values))
    def quantile(percent):
        return str(values[max(1, (percent * len(values) + 99) // 100) - 1])
    return {"sampleCount": len(values), "minimum": str(values[0]), "p10": quantile(10),
            "p50": quantile(50), "p90": quantile(90), "maximum": str(values[-1])}


def synthetic_report(floor=None, zero_rate=False, all_infeasible=False):
    ids = ["baseline", "retirement"]
    dates = ["2027-01-01", "2028-01-01", "2029-01-01"]
    request = {
        "seed": 281474976710655, "annualLogReturnVolatility": 0.18, "pathCount": 3,
        "baseRequest": {
            "startDate": dates[0], "endDate": "2029-12-31", "baselineStrategyId": "baseline",
            "strategies": [{"id": value} for value in ids],
            "annualWithdrawalPlan": {"startDate": dates[0], "rate": 0.0 if zero_rate else 0.04},
            "assumptions": [{"year": year, "equityReturnRate": 0.07, "inflationRate": 0.025,
                             "okiTaxRate": 0.0085, "okiRateStatus": "ASSUMED"} for year in range(2027, 2030)],
        },
    }
    if floor is not None:
        request["minimumRealAnnualIncomePln"] = floor
    paths = []
    income_values = [
        [["100.000000000001", "80", "40"], ["120", "100", "95"]],
        [["100", "100", "100"], ["80", "40", "20"]],
        [["100", "90", "70"], ["0", "0", "0"]],
    ]
    for path_index in range(3):
        results = []
        for index, strategy_id in enumerate(ids):
            payments = [Decimal(value) for value in (["0", "0", "0"] if zero_rate else income_values[path_index][index])]
            annual = []
            for payment_date, value in zip(dates, payments):
                paid = value * 2
                annual.append({"date": payment_date, "portfolioValuePln": "10000" if zero_rate else str(paid / Decimal("0.04")),
                               "rate": 0.0 if zero_rate else 0.04, "requestedPln": str(paid), "paidPln": str(paid), "realPaidPln": str(value)})
            eligible = payments[0] > 0
            flags = [value < Decimal(floor) for value in payments] if floor is not None else []
            longest, run = 0, 0
            for below in flags:
                run = run + 1 if below else 0
                longest = max(longest, run)
            income = {
                "annualPaymentCount": len(payments), "firstRealPaymentPln": str(payments[0]),
                "minimumRealPaymentPln": str(min(payments)), "zeroPaymentYearCount": payments.count(Decimal(0)),
                "declineEligible": eligible,
                "declineAtLeast25Percent": any(value <= payments[0] * Decimal("0.75") for value in payments[1:]) if eligible else None,
                "declineAtLeast50Percent": any(value <= payments[0] * Decimal("0.5") for value in payments[1:]) if eligible else None,
                "yearsBelowMinimum": sum(flags) if floor is not None else None,
                "longestRunBelowMinimum": longest if floor is not None else None,
            }
            terminal = Decimal(1000 + path_index * 100 - index * 100)
            withdrawals = sum(payments)
            result = {key: "0" for key in renderer.STRATEGY_MONEY_FIELDS}
            result.update({"strategyId": strategy_id, "feasible": not all_infeasible and not (path_index == 2 and index == 0),
                           "realNetLiquidationValuePln": str(terminal), "realWithdrawalsPaidPln": str(withdrawals),
                           "realTotalBenefitPln": str(terminal + withdrawals), "comparisonValuePln": str(terminal + withdrawals),
                           "annualWithdrawals": annual, "income": income})
            results.append(result)
        for result in results:
            result["objectiveAdvantageVsBaselinePln"] = str(Decimal(result["comparisonValuePln"]) - Decimal(results[0]["comparisonValuePln"]))
        feasible = [value for value in results if value["feasible"]]
        preferred = max(feasible, key=lambda value: Decimal(value["comparisonValuePln"]))["strategyId"] if feasible else None
        paths.append({"id": f"p{path_index+1:04d}", "annualReturns": [{"year": year, "equityReturnRate": 0.04 + path_index * 0.01} for year in range(2027, 2030)],
                      "preferredStrategyId": preferred, "highestObjectiveStrategyId": preferred,
                      "recommendation": "WITHDRAWAL_SHORTFALL" if not feasible else "KEEP_BASELINE" if preferred == "baseline" else "CHANGE",
                      "strategies": results})
    summaries = []
    for index, strategy_id in enumerate(ids):
        values = [path["strategies"][index] for path in paths]
        paired = [value["objectiveAdvantageVsBaselinePln"] for path, value in zip(paths, values) if value["feasible"] and path["strategies"][0]["feasible"]]
        annual = []
        for payment_index, payment_date in enumerate(dates):
            payments = [value["annualWithdrawals"][payment_index]["realPaidPln"] for value in values]
            annual.append({"date": payment_date, "realPaidPln": distribution(payments),
                           "zeroPaymentPathCount": sum(Decimal(value) == 0 for value in payments),
                           "belowMinimumPathCount": sum(Decimal(value) < Decimal(floor) for value in payments) if floor is not None else None})
        summaries.append({
            "strategyId": strategy_id, "pathCount": 3, "feasiblePathCount": sum(value["feasible"] for value in values),
            "preferredPathCount": sum(path["preferredStrategyId"] == strategy_id for path in paths),
            "highestObjectivePathCount": sum(path["highestObjectiveStrategyId"] == strategy_id for path in paths),
            "comparableToBaselinePathCount": len(paired), "objectiveAdvantageVsBaselinePln": distribution(paired) if paired else None,
            "realNetLiquidationValuePln": distribution(value["realNetLiquidationValuePln"] for value in values),
            "realWithdrawalsPaidPln": distribution(value["realWithdrawalsPaidPln"] for value in values),
            "comparisonValuePln": distribution(value["comparisonValuePln"] for value in values),
            "incomeDeclineEligiblePathCount": sum(value["income"]["declineEligible"] for value in values),
            "zeroFirstPaymentPathCount": sum(Decimal(value["income"]["firstRealPaymentPln"]) == 0 for value in values),
            "insufficientIncomeHistoryPathCount": 0,
            "declineAtLeast25PercentPathCount": sum(value["income"]["declineAtLeast25Percent"] is True for value in values),
            "declineAtLeast50PercentPathCount": sum(value["income"]["declineAtLeast50Percent"] is True for value in values),
            "anyZeroPaymentPathCount": sum(value["income"]["zeroPaymentYearCount"] > 0 for value in values),
            "anyYearBelowMinimumPathCount": sum(value["income"]["yearsBelowMinimum"] > 0 for value in values) if floor is not None else None,
            "maximumYearsBelowMinimum": max(value["income"]["yearsBelowMinimum"] for value in values) if floor is not None else None,
            "maximumConsecutiveYearsBelowMinimum": max(value["income"]["longestRunBelowMinimum"] for value in values) if floor is not None else None,
            "annualIncome": annual,
        })
    return {"monteCarloVersion": "synthetic-1", "engineVersion": "synthetic-engine", "taxRulesVersion": "synthetic-tax",
            "request": request, "returnModel": "Illustrative IID annual lognormal returns", "quantileMethod": "EMPIRICAL_NEAREST_RANK",
            "comparisonObjective": "REAL_WITHDRAWALS_PLUS_TERMINAL_WEALTH", "pathCount": 3, "evaluatedStrategyDays": 100,
            "allInfeasiblePathCount": 3 if all_infeasible else 0, "paths": paths, "strategySummaries": summaries,
            "limitations": ["Synthetic uncalibrated model; this is not a forecast."]}


class ReportParser(HTMLParser):
    def __init__(self, html):
        super().__init__(convert_charrefs=False)
        self.scripts, self.assets, self.tags = [], [], []
        self.current_script = None
        self.feed(html)

    def handle_starttag(self, tag, attrs):
        attributes = dict(attrs)
        self.tags.append(tag)
        if tag == "script":
            self.current_script = {"attributes": attributes, "text": ""}
            self.scripts.append(self.current_script)
        if "src" in attributes or "href" in attributes or any(key.startswith("on") for key in attributes):
            self.assets.append((tag, attributes))

    def handle_endtag(self, tag):
        if tag == "script":
            self.current_script = None

    def handle_data(self, text):
        if self.current_script is not None:
            self.current_script["text"] += text


class RenderMonteCarloTest(unittest.TestCase):
    def test_report_preserves_precision_paired_populations_and_self_contained_chart(self):
        report = synthetic_report(floor="75.000000000001")
        html = renderer.render_report(report)
        parsed = ReportParser(html)
        self.assertEqual([], parsed.assets)
        self.assertEqual(2, len(parsed.scripts))
        self.assertEqual(report, json.loads(parsed.scripts[0]["text"]))
        self.assertEqual("100.000000000001", report["paths"][0]["strategies"][0]["annualWithdrawals"][0]["realPaidPln"])
        self.assertEqual(2, report["strategySummaries"][1]["objectiveAdvantageVsBaselinePln"]["sampleCount"])
        self.assertEqual(3, report["strategySummaries"][1]["realNetLiquidationValuePln"]["sampleCount"])
        self.assertIn('id="income-chart"', html)
        self.assertIn('id="strategy"', html)
        self.assertIn('id="path"', html)
        self.assertIn("marginal quantiles for each payment year, not one coherent trajectory", html)
        self.assertIn("uncalibrated", html)
        self.assertIn("connect-src 'none'", html)
        self.assertIn("monte-carlo-path request.local.json", html)

    def test_injection_is_escaped_in_metadata_strategy_ids_and_path_ids(self):
        report = synthetic_report()
        injection = '</script><script src="https://invalid.example/x.js">alert(1)</script><img src=x onerror=alert(1)>&\u2028\u2029'
        def replace(value):
            if isinstance(value, dict):
                return {key: replace(item) for key, item in value.items()}
            if isinstance(value, list):
                return [replace(item) for item in value]
            return injection if value == "retirement" else value
        report = replace(report)
        report["paths"][0]["id"] = "p'0001; " + injection
        report["limitations"].append(injection)
        html = renderer.render_report(report)
        parsed = ReportParser(html)
        self.assertEqual(2, len(parsed.scripts))
        self.assertEqual([], parsed.assets)
        self.assertNotIn("img", parsed.tags)
        self.assertNotIn(injection, html)
        self.assertEqual(report, json.loads(parsed.scripts[0]["text"]))
        self.assertIn("\\u003c/script\\u003e", parsed.scripts[0]["text"])
        self.assertIn("\\u2028\\u2029", parsed.scripts[0]["text"])
        for unsafe_sink in ("innerHTML", "outerHTML", "insertAdjacentHTML", "document.write", "eval("):
            self.assertNotIn(unsafe_sink, parsed.scripts[1]["text"])

    def test_missing_optional_floor_remains_unassessed(self):
        report = synthetic_report()
        self.assertNotIn("minimumRealAnnualIncomePln", report["request"])
        html = renderer.render_report(report)
        parsed = json.loads(ReportParser(html).scripts[0]["text"])
        self.assertIsNone(parsed["strategySummaries"][0]["anyYearBelowMinimumPathCount"])
        self.assertIsNone(parsed["strategySummaries"][0]["annualIncome"][0]["belowMinimumPathCount"])
        self.assertIn("No minimum real annual income was supplied", html)

    def test_zero_rate_is_valid_and_declines_have_zero_eligible_denominator(self):
        report = synthetic_report(zero_rate=True)
        html = renderer.render_report(report)
        result = json.loads(ReportParser(html).scripts[0]["text"])
        for summary in result["strategySummaries"]:
            self.assertEqual(0, summary["incomeDeclineEligiblePathCount"])
            self.assertEqual(3, summary["zeroFirstPaymentPathCount"])
            self.assertEqual(0, summary["declineAtLeast25PercentPathCount"])
            self.assertEqual(0, summary["declineAtLeast50PercentPathCount"])
        self.assertTrue(result["paths"][0]["strategies"][0]["feasible"])
        self.assertEqual(0, result["paths"][0]["strategies"][0]["annualWithdrawals"][0]["rate"])
        self.assertIn("Not assessed (0 eligible paths)", html)
        self.assertIn("Zero-first-payment paths are explicitly excluded", html)

    def test_no_feasible_baseline_pairs_preserves_unassessed_advantage(self):
        report = synthetic_report(all_infeasible=True)
        result = json.loads(ReportParser(renderer.render_report(report)).scripts[0]["text"])
        self.assertEqual(3, result["allInfeasiblePathCount"])
        self.assertTrue(all(value["objectiveAdvantageVsBaselinePln"] is None for value in result["strategySummaries"]))

    def test_incomplete_results_invalid_quantiles_and_wrong_denominators_are_rejected(self):
        mutations = {
            "missing path": lambda value: value["paths"].pop(),
            "duplicate path": lambda value: value["paths"][1].update(id=value["paths"][0]["id"]),
            "missing annual history": lambda value: value["paths"][0]["strategies"][0].update(annualWithdrawals=[]),
            "missing annual summary": lambda value: value["strategySummaries"][0]["annualIncome"].pop(),
            "wrong quantile population": lambda value: value["strategySummaries"][0]["realNetLiquidationValuePln"].update(sampleCount=2),
            "unordered quantiles": lambda value: value["strategySummaries"][0]["realNetLiquidationValuePln"].update(p90="0"),
            "wrong paired population": lambda value: value["strategySummaries"][0]["objectiveAdvantageVsBaselinePln"].update(sampleCount=3),
            "numeric money": lambda value: value["paths"][0]["strategies"][0].update(realWithdrawalsPaidPln=100.01),
            "too many eligible declines": lambda value: value["strategySummaries"][1].update(declineAtLeast50PercentPathCount=3),
            "zero first included in declines": lambda value: value["paths"][2]["strategies"][1]["income"].update(declineEligible=True),
            "missing floor metrics": lambda value: value["request"].update(minimumRealAnnualIncomePln="75"),
            "unsafe integer seed": lambda value: value["request"].update(seed=9007199254740993),
        }
        for description, mutate in mutations.items():
            with self.subTest(description=description):
                report = synthetic_report()
                mutate(report)
                with self.assertRaises(renderer.ReportError):
                    renderer.render_report(report)

    def invoke(self, source, output):
        stdout, stderr = io.StringIO(), io.StringIO()
        with redirect_stdout(stdout), redirect_stderr(stderr):
            result = renderer.main(["--input", str(source), "--output", str(output)])
        return result, stdout.getvalue(), stderr.getvalue()

    def test_atomic_publish_creates_private_html_and_preserves_existing_file_on_failure(self):
        with tempfile.TemporaryDirectory() as directory:
            source, output = Path(directory) / "result.json", Path(directory) / "report.html"
            report = synthetic_report()
            source.write_text(json.dumps(report))
            output.write_text("previous report")
            output.chmod(0o644)
            old_inode = output.stat().st_ino
            exit_code, stdout, stderr = self.invoke(source, output)
            self.assertEqual(0, exit_code, stderr)
            self.assertEqual("", stderr)
            self.assertIn("Saved standalone", stdout)
            self.assertEqual(0o600, stat.S_IMODE(output.stat().st_mode))
            self.assertNotEqual(old_inode, output.stat().st_ino)
            self.assertEqual(report, json.loads(ReportParser(output.read_text()).scripts[0]["text"]))
            before = output.read_bytes()
            with patch.object(renderer.os, "replace", side_effect=OSError("secret-filesystem-path")):
                exit_code, _stdout, stderr = self.invoke(source, output)
            self.assertEqual(2, exit_code)
            self.assertNotIn("secret-filesystem-path", stderr)
            self.assertEqual(before, output.read_bytes())
            self.assertEqual([], list(Path(directory).glob(".monte-carlo-report-*")))

    def test_invalid_input_and_input_output_collision_never_overwrite_files(self):
        with tempfile.TemporaryDirectory() as directory:
            source, output = Path(directory) / "result.json", Path(directory) / "report.html"
            output.write_text("previous report")
            for raw in ("{", "[]", "{}", '{"secret-data":NaN}'):
                source.write_text(raw)
                exit_code, stdout, stderr = self.invoke(source, output)
                self.assertEqual(2, exit_code)
                self.assertEqual("", stdout)
                self.assertNotIn("secret-data", stderr)
                self.assertEqual("previous report", output.read_text())
            source.write_text(json.dumps(synthetic_report()))
            before = source.read_bytes()
            exit_code, _stdout, stderr = self.invoke(source, source)
            self.assertEqual(2, exit_code)
            self.assertIn("Output must differ", stderr)
            self.assertEqual(before, source.read_bytes())


if __name__ == "__main__":
    unittest.main()
