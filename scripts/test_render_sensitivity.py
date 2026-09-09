"""Standalone report contract and publication tests; all scenario data are synthetic."""

from contextlib import redirect_stderr, redirect_stdout
from decimal import Decimal
from html.parser import HTMLParser
import importlib.util
import io
import itertools
import json
from pathlib import Path
import stat
import tempfile
import unittest
from unittest.mock import patch


ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location("render_sensitivity", ROOT / "scripts/render-sensitivity.py")
renderer = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(renderer)


def synthetic_report():
    axes = {
        "equityReturnRateShifts": [0.0, 0.02], "inflationRateShifts": [0.0, 0.01],
        "assumedOkiTaxRateShifts": [0.0, 0.001], "endDates": ["2030-12-31", "2031-12-31"],
    }
    scenarios = []
    for index, coordinates in enumerate(itertools.product(*(axes[key] for key in renderer.AXES))):
        feasible = index != 15
        preferred = ("move" if coordinates[0] > 0 else "baseline") if feasible else None
        strategies = []
        for strategy_id in ("baseline", "move"):
            advantage = "0" if strategy_id == "baseline" else ("5.000000000001" if coordinates[0] > 0 else "-5.000000000001")
            result = {key: "0" for key in renderer.MONEY_FIELDS}
            result.update({
                "strategyId": strategy_id, "feasible": feasible,
                "realNetLiquidationValuePln": str(Decimal("1000000000000000.000000000001") + Decimal(advantage)),
                "netLiquidationValuePln": "1200000000000000.000000000001",
                "advantageVsBaselinePln": advantage, "regretVsBestFeasiblePln": "0" if feasible else None,
                "initialTransfer": None,
            })
            if strategy_id == "move":
                result["initialTransfer"] = {key: "100.000000000001" for key in renderer.TRANSFER_FIELDS}
                result["initialTransfer"]["direction"] = "TAXABLE_TO_OKI"
            strategies.append(result)
        scenarios.append({
            "id": f"scenario-{index}", "coordinates": dict(zip(renderer.COORDINATES, coordinates)),
            "preservedEstablishedOkiYears": [2027], "shiftedAssumedOkiYears": [2028, 2029, 2030],
            "omittedCashFlowCount": 0, "preferredStrategyId": preferred, "highestValueStrategyId": preferred,
            "recommendation": "WITHDRAWAL_SHORTFALL" if not feasible else ("CHANGE" if preferred == "move" else "KEEP_BASELINE"),
            "strategies": strategies,
        })
    summaries = []
    for horizon, strategy_id in itertools.product(axes["endDates"], ("baseline", "move")):
        selected = [scenario for scenario in scenarios if scenario["coordinates"]["endDate"] == horizon]
        summaries.append({
            "endDate": horizon, "strategyId": strategy_id, "scenarioCount": len(selected),
            "feasibleScenarioCount": sum(scenario["preferredStrategyId"] is not None for scenario in selected),
            "preferredScenarioCount": sum(scenario["preferredStrategyId"] == strategy_id for scenario in selected),
            "highestValueScenarioCount": sum(scenario["highestValueStrategyId"] == strategy_id for scenario in selected),
            "comparableToBaselineScenarioCount": sum(scenario["preferredStrategyId"] is not None for scenario in selected),
            "minimumAdvantageVsBaselinePln": "0" if strategy_id == "baseline" else "-5.000000000001",
            "maximumAdvantageVsBaselinePln": "0" if strategy_id == "baseline" else "5.000000000001",
            "maximumRegretVsBestFeasiblePln": "5.000000000001",
        })
    return {
        "sensitivityVersion": "synthetic-1", "engineVersion": "synthetic-engine", "taxRulesVersion": "synthetic-tax",
        "request": {"axes": axes, "baseRequest": {
            "startDate": "2027-01-01", "endDate": "2031-12-31", "strategies": [{"id": "baseline"}, {"id": "move"}],
            "baselineStrategyId": "baseline", "assumptions": [{"year": 2027, "equityReturnRate": 0.07, "inflationRate": 0.025, "okiTaxRate": 0.0085, "okiRateStatus": "ESTABLISHED"}],
        }},
        "scenarioCount": len(scenarios), "evaluatedStrategyDays": 1000, "allInfeasibleScenarioCount": 1,
        "scenarios": scenarios, "strategySummaries": summaries,
        "transitions": [{"axis": "EQUITY_RETURN_RATE_SHIFT", "kind": "PREFERRED_STRATEGY_CHANGE", "fromScenarioId": "scenario-0", "toScenarioId": "scenario-8", "strategyId": None}],
        "limitations": ["Synthetic scenarios; sampled counts are not probabilities."],
    }


def retirement_report():
    report = synthetic_report()
    axes, base = report["request"]["axes"], report["request"]["baseRequest"]
    axes["accumulationEndDates"] = axes.pop("endDates")
    axes["endDates"] = []
    axes["withdrawalYears"] = 5
    base["endDate"] = "2036-12-31"
    base["annualWithdrawalPlan"] = {"startDate": "2031-01-01", "rate": 0.04}
    base["comparisonObjective"] = "AUTO"
    report["comparisonObjective"] = "REAL_WITHDRAWALS_PLUS_TERMINAL_WEALTH"
    for scenario in report["scenarios"]:
        coordinates = scenario["coordinates"]
        coordinates["accumulationEndDate"] = coordinates["endDate"]
        start_year = int(coordinates["endDate"][:4]) + 1
        coordinates["endDate"] = f"{start_year + 4}-12-31"
        feasible = scenario["preferredStrategyId"] is not None
        scenario["highestValueStrategyId"] = "baseline" if feasible else None
        scenario["highestObjectiveStrategyId"] = "move" if feasible else None
        scenario["preferredStrategyId"] = scenario["highestObjectiveStrategyId"]
        scenario["recommendation"] = "CHANGE" if feasible else "WITHDRAWAL_SHORTFALL"
        for result in scenario["strategies"]:
            is_move = result["strategyId"] == "move"
            result.update({
                "realNetLiquidationValuePln": "990" if is_move else "1000",
                "advantageVsBaselinePln": "-10" if is_move else "0",
                "regretVsBestFeasiblePln": ("10" if is_move else "0") if feasible else None,
                "realWithdrawalsPaidPln": "200" if is_move else "100",
                "realTotalBenefitPln": "1190" if is_move else "1100",
                "comparisonValuePln": "1190" if is_move else "1100",
                "objectiveAdvantageVsBaselinePln": "90" if is_move else "0",
                "objectiveRegretVsBestFeasiblePln": ("0" if is_move else "90") if feasible else None,
                "annualWithdrawals": [{
                    "date": f"{year}-01-01", "portfolioValuePln": "2000" if is_move else "1000",
                    "rate": 0.04, "requestedPln": "80" if is_move else "40", "paidPln": "80" if is_move else "40",
                    "realPaidPln": "40" if is_move else "20",
                } for year in range(start_year, start_year + 5)],
            })
    for summary in report["strategySummaries"]:
        summary["accumulationEndDate"] = summary["endDate"]
        summary["endDate"] = f"{int(summary['endDate'][:4]) + 5}-12-31"
        is_move = summary["strategyId"] == "move"
        summary["preferredScenarioCount"] = summary["feasibleScenarioCount"] if is_move else 0
        summary["highestValueScenarioCount"] = 0 if is_move else summary["feasibleScenarioCount"]
        summary["highestObjectiveScenarioCount"] = summary["preferredScenarioCount"]
        summary["minimumObjectiveAdvantageVsBaselinePln"] = "90" if is_move else "0"
        summary["maximumObjectiveAdvantageVsBaselinePln"] = "90" if is_move else "0"
        summary["maximumObjectiveRegretVsBestFeasiblePln"] = "0" if is_move else "90"
    report["transitions"] = [{
        "axis": "ACCUMULATION_END_DATE", "kind": "FEASIBILITY_CHANGE", "fromScenarioId": "scenario-14",
        "toScenarioId": "scenario-15", "strategyId": "move",
    }]
    return report


class ReportParser(HTMLParser):
    def __init__(self, html):
        super().__init__(convert_charrefs=False)
        self.scripts = []
        self.assets = []
        self.tags = []
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


class RenderSensitivityTest(unittest.TestCase):
    def test_zero_rate_annual_policy_is_accepted_with_zero_payments(self):
        report = retirement_report()
        report["request"]["baseRequest"]["annualWithdrawalPlan"]["rate"] = 0
        for scenario in report["scenarios"]:
            for strategy in scenario["strategies"]:
                for payment in strategy["annualWithdrawals"]:
                    payment.update(rate=0, requestedPln="0.00", paidPln="0.00", realPaidPln="0.00")
        parsed = ReportParser(renderer.render_report(report))
        self.assertEqual(report, json.loads(parsed.scripts[0]["text"]))

    def test_retirement_objective_income_history_and_horizon_pairs_are_preserved(self):
        report = retirement_report()
        html = renderer.render_report(report)
        parsed = ReportParser(html)
        result = json.loads(parsed.scripts[0]["text"])
        self.assertEqual(report, result)
        scenario = result["scenarios"][0]
        self.assertEqual("2030-12-31", scenario["coordinates"]["accumulationEndDate"])
        self.assertEqual("2035-12-31", scenario["coordinates"]["endDate"])
        self.assertEqual("move", scenario["highestObjectiveStrategyId"])
        self.assertEqual("baseline", scenario["highestValueStrategyId"])
        self.assertEqual("-10", scenario["strategies"][1]["advantageVsBaselinePln"])
        self.assertEqual("90", scenario["strategies"][1]["objectiveAdvantageVsBaselinePln"])
        self.assertEqual(5, len(scenario["strategies"][1]["annualWithdrawals"]))
        self.assertIn('id="annual-strategy"', html)
        self.assertIn("Cumulative real withdrawals", html)
        self.assertIn("Terminal wealth comparison", html)
        self.assertIn("There is no fixed income floor.", html)
        self.assertIn("after taxes due and other same-day flows", html)
        self.assertEqual([], parsed.assets)

    def test_retirement_requires_complete_objective_history_and_consistent_horizon_pairs(self):
        mutations = {
            "missing objective": lambda value: value.pop("comparisonObjective"),
            "unresolved objective": lambda value: value.update(comparisonObjective="AUTO"),
            "missing objective value": lambda value: value["scenarios"][0]["strategies"][0].pop("comparisonValuePln"),
            "missing real withdrawals": lambda value: value["scenarios"][0]["strategies"][0].pop("realWithdrawalsPaidPln"),
            "missing history": lambda value: value["scenarios"][0]["strategies"][0].pop("annualWithdrawals"),
            "numeric annual payment": lambda value: value["scenarios"][0]["strategies"][0]["annualWithdrawals"][0].update(realPaidPln=20),
            "duplicate annual date": lambda value: value["scenarios"][0]["strategies"][0]["annualWithdrawals"][1].update(date="2031-01-01"),
            "payment during accumulation": lambda value: value["scenarios"][0]["strategies"][0]["annualWithdrawals"][0].update(date="2030-01-01"),
            "wrong paired end date": lambda value: value["scenarios"][0]["coordinates"].update(endDate="2036-12-31"),
            "missing paired accumulation": lambda value: value["scenarios"][0]["coordinates"].pop("accumulationEndDate"),
            "wrong summary horizon": lambda value: value["strategySummaries"][0].update(accumulationEndDate="2031-12-31"),
            "missing objective summary": lambda value: value["strategySummaries"][0].pop("maximumObjectiveRegretVsBestFeasiblePln"),
            "mixed horizon modes": lambda value: value["request"]["axes"].update(endDates=["2035-12-31"]),
            "invalid withdrawal years": lambda value: value["request"]["axes"].update(withdrawalYears=0),
            "past base horizon": lambda value: value["request"]["axes"].update(withdrawalYears=20),
        }
        for description, mutate in mutations.items():
            with self.subTest(description=description):
                report = retirement_report()
                mutate(report)
                with self.assertRaises(renderer.ReportError):
                    renderer.render_report(report)

    def test_current_terminal_reports_and_zero_annual_payments_remain_renderable(self):
        report = retirement_report()
        report["comparisonObjective"] = "REAL_TERMINAL_WEALTH"
        report["request"]["baseRequest"]["comparisonObjective"] = "REAL_TERMINAL_WEALTH"
        for scenario in report["scenarios"]:
            scenario["preferredStrategyId"] = scenario["highestValueStrategyId"]
            scenario["highestObjectiveStrategyId"] = scenario["highestValueStrategyId"]
            for result in scenario["strategies"]:
                result["comparisonValuePln"] = result["realNetLiquidationValuePln"]
                result["objectiveAdvantageVsBaselinePln"] = result["advantageVsBaselinePln"]
                result["objectiveRegretVsBestFeasiblePln"] = result["regretVsBestFeasiblePln"]
        zero = report["scenarios"][0]["strategies"][0]
        zero["realWithdrawalsPaidPln"] = "0"
        zero["realTotalBenefitPln"] = zero["realNetLiquidationValuePln"]
        for withdrawal in zero["annualWithdrawals"]:
            withdrawal.update(portfolioValuePln="0", requestedPln="0", paidPln="0", realPaidPln="0")
        result = json.loads(ReportParser(renderer.render_report(report)).scripts[0]["text"])
        self.assertTrue(result["scenarios"][0]["strategies"][0]["feasible"])
        self.assertEqual("0", result["scenarios"][0]["strategies"][0]["annualWithdrawals"][0]["realPaidPln"])

    def test_result_is_self_contained_and_preserves_precise_decimal_strings(self):
        report = synthetic_report()
        html = renderer.render_report(report)
        parsed = ReportParser(html)
        self.assertEqual([], parsed.assets)
        self.assertEqual(2, len(parsed.scripts))
        payload = json.loads(parsed.scripts[0]["text"])
        self.assertEqual(report, payload)
        self.assertEqual("1000000000000000.000000000001", payload["scenarios"][0]["strategies"][0]["realNetLiquidationValuePln"])
        self.assertIn("connect-src 'none'", html)
        self.assertIn('id="horizon"', html)
        self.assertIn('id="inflation"', html)
        self.assertIn('id="metric"', html)
        self.assertIn("Sampled transition brackets", html)
        self.assertIn("Different horizons are summarized separately.", html)

    def test_script_and_html_injection_cannot_escape_the_embedded_json(self):
        report = synthetic_report()
        injection = '</script><script src="https://invalid.example/payload.js">alert("x")</script><img src=x onerror=alert(1)>&\u2028\u2029'
        report["engineVersion"] = injection
        report["limitations"].append(injection)
        # IDs also reach select options, table cells and the map through textContent.
        for strategy in report["request"]["baseRequest"]["strategies"]:
            if strategy["id"] == "move":
                strategy["id"] = injection
        for scenario in report["scenarios"]:
            for result in scenario["strategies"]:
                if result["strategyId"] == "move":
                    result["strategyId"] = injection
            for key in ("preferredStrategyId", "highestValueStrategyId"):
                if scenario[key] == "move":
                    scenario[key] = injection
        for summary in report["strategySummaries"]:
            if summary["strategyId"] == "move":
                summary["strategyId"] = injection
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

    def test_incomplete_duplicate_and_malformed_grid_results_are_rejected(self):
        mutations = {
            "missing cell": lambda value: value["scenarios"].pop(),
            "duplicate coordinates": lambda value: value["scenarios"][1].update(coordinates=value["scenarios"][0]["coordinates"]),
            "duplicate ID": lambda value: value["scenarios"][1].update(id=value["scenarios"][0]["id"]),
            "missing strategy": lambda value: value["scenarios"][0]["strategies"].pop(),
            "numeric money": lambda value: value["scenarios"][0]["strategies"][0].update(realNetLiquidationValuePln=1000.01),
            "nonfinite money": lambda value: value["scenarios"][0]["strategies"][0].update(realNetLiquidationValuePln="NaN"),
            "missing horizon summary": lambda value: value["strategySummaries"].pop(),
            "wrong infeasible count": lambda value: value.update(allInfeasibleScenarioCount=0),
            "infeasible preferred strategy": lambda value: value["scenarios"][-1].update(preferredStrategyId="baseline"),
            "missing transition endpoint": lambda value: value["transitions"][0].update(toScenarioId="absent"),
            "wrong transition axis": lambda value: value["transitions"][0].update(axis="END_DATE"),
            "nonfinite rate": lambda value: value["request"]["axes"]["inflationRateShifts"].append(float("nan")),
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

    def test_atomic_publish_produces_private_file_and_complete_report(self):
        with tempfile.TemporaryDirectory() as directory:
            source, output = Path(directory) / "input.json", Path(directory) / "report.html"
            report = synthetic_report()
            source.write_text(json.dumps(report))
            output.write_text("previous report")
            output.chmod(0o644)
            old_inode = output.stat().st_ino
            exit_code, stdout, stderr = self.invoke(source, output)
            self.assertEqual(0, exit_code, stderr)
            self.assertIn("Saved standalone", stdout)
            self.assertEqual("", stderr)
            self.assertEqual(0o600, stat.S_IMODE(output.stat().st_mode))
            self.assertNotEqual(old_inode, output.stat().st_ino)
            self.assertEqual(report, json.loads(ReportParser(output.read_text()).scripts[0]["text"]))
            self.assertEqual([], list(Path(directory).glob(".sensitivity-report-*")))

    def test_invalid_input_and_publish_failure_preserve_existing_output(self):
        with tempfile.TemporaryDirectory() as directory:
            source, output = Path(directory) / "input.json", Path(directory) / "report.html"
            output.write_text("previous report")
            for contents in ("{", "[]", "{}", '{"secret-investor-data":NaN}', json.dumps({**synthetic_report(), "scenarios": []})):
                with self.subTest(contents=contents[:40]):
                    source.write_text(contents)
                    exit_code, stdout, stderr = self.invoke(source, output)
                    self.assertEqual(2, exit_code)
                    self.assertEqual("", stdout)
                    self.assertNotIn("secret-investor-data", stderr)
                    self.assertEqual("previous report", output.read_text())
            source.write_text(json.dumps(synthetic_report()))
            with patch.object(renderer.os, "replace", side_effect=OSError("secret-filesystem-path")):
                exit_code, _stdout, stderr = self.invoke(source, output)
            self.assertEqual(2, exit_code)
            self.assertNotIn("secret-filesystem-path", stderr)
            self.assertEqual("previous report", output.read_text())
            self.assertEqual([], list(Path(directory).glob(".sensitivity-report-*")))

    def test_input_cannot_be_overwritten_by_its_report(self):
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory) / "input.json"
            original = json.dumps(synthetic_report())
            source.write_text(original)
            exit_code, _stdout, stderr = self.invoke(source, source)
            self.assertEqual(2, exit_code)
            self.assertIn("Output must differ", stderr)
            self.assertEqual(original, source.read_text())


if __name__ == "__main__":
    unittest.main()
