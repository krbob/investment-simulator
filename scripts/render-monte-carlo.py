#!/usr/bin/env python3
"""Render a Monte Carlo result as a standalone local HTML report with an interactive income chart."""

from __future__ import annotations

import argparse
from datetime import date
from decimal import Decimal, InvalidOperation
import json
import math
import os
from pathlib import Path
import re
import sys
import tempfile


MAX_INPUT_BYTES = 64 * 1024 * 1024
DISTRIBUTION_FIELDS = ("minimum", "p10", "p50", "p90", "maximum")
STRATEGY_MONEY_FIELDS = (
    "realNetLiquidationValuePln", "realWithdrawalsPaidPln", "realTotalBenefitPln", "comparisonValuePln",
    "objectiveAdvantageVsBaselinePln", "withdrawalShortfallPln", "unpaidTaxPln", "outstandingTaxPln",
    "capitalGainsTaxPaidPln", "okiTaxPaidPln", "tradingFeesPln",
)
ANNUAL_MONEY_FIELDS = ("portfolioValuePln", "requestedPln", "paidPln", "realPaidPln")
OBJECTIVES = {"REAL_TERMINAL_WEALTH", "REAL_WITHDRAWALS_PLUS_TERMINAL_WEALTH"}


class ReportError(ValueError):
    """A safe explanation of invalid report data."""


def require(condition: bool, message: str) -> None:
    if not condition:
        raise ReportError(message)


def is_count(value) -> bool:
    return type(value) is int and value >= 0


def is_rate(value) -> bool:
    return type(value) in (float, int) and math.isfinite(value)


def is_date(value) -> bool:
    try:
        return isinstance(value, str) and date.fromisoformat(value).isoformat() == value
    except ValueError:
        return False


def is_money(value) -> bool:
    if not isinstance(value, str) or len(value) > 256 or not re.fullmatch(r"[+-]?\d+(?:\.\d+)?(?:[eE][+-]?\d+)?", value):
        return False
    try:
        return Decimal(value).is_finite()
    except InvalidOperation:
        return False


def validate_distribution(value, expected_count: int) -> None:
    require(isinstance(value, dict) and is_count(value.get("sampleCount")) and value["sampleCount"] == expected_count and expected_count > 0, "Distribution sample count does not match its population.")
    require(all(is_money(value.get(key)) for key in DISTRIBUTION_FIELDS), "Distribution values must be finite decimal strings.")
    values = [Decimal(value[key]) for key in DISTRIBUTION_FIELDS]
    require(values == sorted(values), "Distribution quantiles must be ordered from minimum to maximum.")


def validate_report(report: dict) -> None:
    require(isinstance(report, dict), "Input must be a Monte Carlo result object.")
    for key in ("monteCarloVersion", "engineVersion", "taxRulesVersion", "returnModel", "quantileMethod"):
        require(isinstance(report.get(key), str) and bool(report[key]), "Report model and version metadata are required.")
    require(report.get("comparisonObjective") in OBJECTIVES, "The report must identify its resolved comparison objective.")
    request = report.get("request")
    require(isinstance(request, dict) and isinstance(request.get("baseRequest"), dict), "The original request and base scenario are required.")
    base = request["baseRequest"]
    require(is_date(base.get("startDate")) and is_date(base.get("endDate")) and base["startDate"] <= base["endDate"], "Base scenario dates are invalid.")
    require(type(request.get("seed")) is int and 0 <= request["seed"] <= 281474976710655, "Seed must be the model's supported nonnegative 48-bit integer.")
    require(is_rate(request.get("annualLogReturnVolatility")) and request["annualLogReturnVolatility"] >= 0, "Annual log-return volatility must be a finite nonnegative number.")
    floor = request.get("minimumRealAnnualIncomePln")
    require(floor is None or is_money(floor) and Decimal(floor) >= 0, "The optional real income floor must be a nonnegative decimal string.")
    count = report.get("pathCount")
    require(is_count(count) and count > 0 and request.get("pathCount") == count, "Report and request path counts must agree.")
    require(is_count(report.get("evaluatedStrategyDays")), "Evaluated strategy-day count is missing.")
    strategies = base.get("strategies")
    require(isinstance(strategies, list) and bool(strategies) and all(isinstance(value, dict) and isinstance(value.get("id"), str) and value["id"] for value in strategies), "The base scenario must identify every strategy.")
    strategy_ids = [value["id"] for value in strategies]
    require(len(set(strategy_ids)) == len(strategy_ids) and base.get("baselineStrategyId") in strategy_ids, "Strategy IDs must be unique and include the baseline.")
    assumptions = base.get("assumptions")
    require(isinstance(assumptions, list) and bool(assumptions) and all(isinstance(value, dict) and type(value.get("year")) is int and all(is_rate(value.get(key)) for key in ("equityReturnRate", "inflationRate", "okiTaxRate")) for value in assumptions), "Annual return, inflation and OKI assumptions are required.")
    paths = report.get("paths")
    require(isinstance(paths, list) and len(paths) == count, "Path results are incomplete.")
    path_ids = set()
    all_infeasible = 0
    annual_dates = {strategy_id: None for strategy_id in strategy_ids}
    for path in paths:
        require(isinstance(path, dict) and isinstance(path.get("id"), str) and path["id"] and path["id"] not in path_ids, "Path IDs must be nonempty and unique.")
        path_ids.add(path["id"])
        returns = path.get("annualReturns")
        require(isinstance(returns, list) and all(isinstance(value, dict) and type(value.get("year")) is int and is_rate(value.get("equityReturnRate")) and value["equityReturnRate"] > -1 for value in returns), "Sampled annual returns are invalid.")
        require([value["year"] for value in returns] == list(range(int(base["startDate"][:4]), int(base["endDate"][:4]) + 1)), "Sampled annual returns must cover the base scenario in order.")
        results = path.get("strategies")
        require(isinstance(results, list) and len(results) == len(strategy_ids) and all(isinstance(value, dict) and isinstance(value.get("strategyId"), str) for value in results), "Each path must include all strategy results.")
        require({value["strategyId"] for value in results} == set(strategy_ids), "A path has duplicate or unknown strategies.")
        feasible_ids = set()
        for result in results:
            require(type(result.get("feasible")) is bool, "Strategy feasibility must be explicit.")
            if result["feasible"]:
                feasible_ids.add(result["strategyId"])
            require(all(is_money(result.get(key)) for key in STRATEGY_MONEY_FIELDS), "Strategy amounts must be finite decimal strings.")
            withdrawals = result.get("annualWithdrawals")
            require(isinstance(withdrawals, list) and bool(withdrawals), "Annual withdrawal history must contain at least one payment.")
            dates = []
            for withdrawal in withdrawals:
                require(isinstance(withdrawal, dict) and is_date(withdrawal.get("date")) and withdrawal["date"].endswith("-01-01"), "Annual withdrawal dates must be 1 January.")
                require(base["startDate"] <= withdrawal["date"] <= base["endDate"] and (not dates or dates[-1] < withdrawal["date"]), "Annual withdrawals must be unique, ordered and inside the scenario horizon.")
                require(is_rate(withdrawal.get("rate")) and 0 <= withdrawal["rate"] <= 1, "Annual withdrawal fractions are invalid.")
                require(all(is_money(withdrawal.get(key)) for key in ANNUAL_MONEY_FIELDS), "Annual withdrawal amounts must be finite decimal strings.")
                dates.append(withdrawal["date"])
            previous_dates = annual_dates[result["strategyId"]]
            require(previous_dates is None or previous_dates == dates, "Annual withdrawal dates must match across paths for each strategy.")
            annual_dates[result["strategyId"]] = dates
            income = result.get("income")
            require(isinstance(income, dict) and is_count(income.get("annualPaymentCount")) and income["annualPaymentCount"] == len(dates), "Income metrics must match the annual payment history.")
            require(all(is_money(income.get(key)) for key in ("firstRealPaymentPln", "minimumRealPaymentPln")), "Income metric amounts must be finite decimal strings.")
            require(is_count(income.get("zeroPaymentYearCount")) and income["zeroPaymentYearCount"] <= len(dates), "Zero-payment year count is invalid.")
            require(type(income.get("declineEligible")) is bool, "Income decline eligibility must be explicit.")
            eligible = len(dates) >= 2 and Decimal(income["firstRealPaymentPln"]) > 0
            require(income["declineEligible"] == eligible, "Income declines require at least two payments and a positive first real payment.")
            for key in ("declineAtLeast25Percent", "declineAtLeast50Percent"):
                require(type(income.get(key)) is bool if eligible else income.get(key) is None, "Income decline flags must be null when the path is ineligible.")
            for key in ("yearsBelowMinimum", "longestRunBelowMinimum"):
                require(income.get(key) is None if floor is None else is_count(income.get(key)) and income[key] <= len(dates), "Income floor metrics must be null without a floor, or valid counts with a floor.")
        for key in ("preferredStrategyId", "highestObjectiveStrategyId"):
            require(key in path and (path[key] in feasible_ids if isinstance(path[key], str) else path[key] is None and not feasible_ids), "Preferred and highest-objective strategies must be feasible, or null when all are infeasible.")
        require(path.get("recommendation") in {"CHANGE", "KEEP_BASELINE", "NO_CLEAR_ADVANTAGE", "WITHDRAWAL_SHORTFALL"}, "Path recommendation is invalid.")
        all_infeasible += not feasible_ids
    require(report.get("allInfeasiblePathCount") == all_infeasible, "All-infeasible path count does not match the results.")
    summaries = report.get("strategySummaries")
    require(isinstance(summaries, list) and len(summaries) == len(strategy_ids) and all(isinstance(value, dict) and isinstance(value.get("strategyId"), str) for value in summaries), "Strategy summaries are incomplete.")
    require({value["strategyId"] for value in summaries} == set(strategy_ids), "Strategy summaries contain duplicate or unknown IDs.")
    for summary in summaries:
        require(summary.get("pathCount") == count, "Summary path count must match the report.")
        for key in ("feasiblePathCount", "preferredPathCount", "highestObjectivePathCount", "comparableToBaselinePathCount", "incomeDeclineEligiblePathCount", "zeroFirstPaymentPathCount", "insufficientIncomeHistoryPathCount", "anyZeroPaymentPathCount"):
            require(is_count(summary.get(key)) and summary[key] <= count, "Summary path counts are invalid.")
        for key in ("declineAtLeast25PercentPathCount", "declineAtLeast50PercentPathCount"):
            require(is_count(summary.get(key)) and summary[key] <= summary["incomeDeclineEligiblePathCount"], "Income decline counts must use the eligible-path denominator.")
        for key in ("realNetLiquidationValuePln", "realWithdrawalsPaidPln", "comparisonValuePln"):
            validate_distribution(summary.get(key), count)
        pairs = summary["comparableToBaselinePathCount"]
        if pairs:
            validate_distribution(summary.get("objectiveAdvantageVsBaselinePln"), pairs)
        else:
            require(summary.get("objectiveAdvantageVsBaselinePln") is None, "Paired advantage must be unassessed when no baseline pairs are feasible.")
        dates = annual_dates[summary["strategyId"]]
        for key in ("anyYearBelowMinimumPathCount", "maximumYearsBelowMinimum", "maximumConsecutiveYearsBelowMinimum"):
            maximum = count if key == "anyYearBelowMinimumPathCount" else len(dates)
            require(summary.get(key) is None if floor is None else is_count(summary.get(key)) and summary[key] <= maximum, "Summary floor metrics must match the optional income floor.")
        annual = summary.get("annualIncome")
        require(isinstance(annual, list) and all(isinstance(value, dict) for value in annual) and [value.get("date") for value in annual] == dates, "Annual income summaries must cover each payment date in order.")
        for observation in annual:
            validate_distribution(observation.get("realPaidPln"), count)
            require(is_count(observation.get("zeroPaymentPathCount")) and observation["zeroPaymentPathCount"] <= count, "Annual zero-payment counts are invalid.")
            require(observation.get("belowMinimumPathCount") is None if floor is None else is_count(observation.get("belowMinimumPathCount")) and observation["belowMinimumPathCount"] <= count, "Annual floor counts must match the optional income floor.")
    require(isinstance(report.get("limitations"), list) and all(isinstance(value, str) for value in report["limitations"]), "Report limitations must be a list of text entries.")


def render_report(report: dict) -> str:
    validate_report(report)
    payload = json.dumps(report, ensure_ascii=False, allow_nan=False, separators=(",", ":"))
    for literal, escaped in (("&", "\\u0026"), ("<", "\\u003c"), (">", "\\u003e"), ("\u2028", "\\u2028"), ("\u2029", "\\u2029")):
        payload = payload.replace(literal, escaped)
    return HTML.replace("__REPORT_JSON__", payload)


def write_report(html: str, output: Path) -> None:
    temporary_name = None
    try:
        with tempfile.NamedTemporaryFile(mode="w", encoding="utf-8", dir=output.parent,
                                         prefix=".monte-carlo-report-", suffix=".html", delete=False) as temporary:
            temporary_name = temporary.name
            os.fchmod(temporary.fileno(), 0o600)
            temporary.write(html)
            temporary.flush()
            os.fsync(temporary.fileno())
        os.replace(temporary_name, output)
    finally:
        if temporary_name is not None:
            Path(temporary_name).unlink(missing_ok=True)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", required=True, type=Path, help="Monte Carlo result JSON")
    parser.add_argument("--output", required=True, type=Path, help="Standalone local HTML report")
    args = parser.parse_args(argv)
    try:
        require(args.input.resolve() != args.output.resolve(), "Output must differ from the input JSON file.")
        with args.input.open("rb") as source:
            raw = source.read(MAX_INPUT_BYTES + 1)
        require(len(raw) <= MAX_INPUT_BYTES, "Input exceeds the 64 MiB report limit.")
        write_report(render_report(json.loads(raw)), args.output)
    except ReportError as error:
        print(f"Report failed: {error}", file=sys.stderr)
        return 2
    except (OSError, ValueError, UnicodeError, TypeError, RecursionError, OverflowError):
        print("Report failed: input is invalid or the local report cannot be written.", file=sys.stderr)
        return 2
    print("Saved standalone Monte Carlo report. Open the HTML file in a browser.")
    return 0


HTML = r'''<!doctype html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<meta http-equiv="Content-Security-Policy" content="default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; connect-src 'none'; base-uri 'none'; form-action 'none'">
<title>Retirement income simulation</title>
<style>
:root{font:16px/1.5 system-ui,sans-serif;color:#24313e;background:#f5f7f9;color-scheme:light}*{box-sizing:border-box}body{margin:0}main{max-width:1320px;margin:auto;padding:32px 24px 60px}
h1{font-size:2rem;line-height:1.2;margin:0 0 12px}h2{font-size:1.3rem;margin:0 0 10px}h3{font-size:1rem;margin:16px 0 8px}p{margin:8px 0 16px}.eyebrow{font-size:.8rem;text-transform:uppercase;letter-spacing:.1em;color:#526575}.muted{color:#576573}
p,li,caption{overflow-wrap:anywhere}
section,.panel{background:white;border:1px solid #dce2e8;border-radius:12px;padding:22px;margin:20px 0}.note{border-left:3px solid #9cafc4;padding-left:12px}.controls{display:flex;flex-wrap:wrap;gap:20px;margin:18px 0}label{display:flex;flex-direction:column;gap:6px;font-weight:600}
select{font:inherit;padding:8px 12px;border:1px solid #a4afbb;border-radius:6px;background:white;max-width:420px}select:focus-visible,summary:focus-visible{outline:3px solid #126bc5;outline-offset:3px}.scroll{overflow:auto}table{border-collapse:collapse;width:100%;font-size:.9rem}th,td{padding:10px 12px;border-bottom:1px solid #e1e6eb;text-align:left;vertical-align:top}th{background:#f3f5f8;font-weight:650}td.money{font-variant-numeric:tabular-nums;white-space:nowrap;text-align:right}caption{text-align:left;color:#576573;padding:0 0 10px}
svg{display:block;width:100%;height:auto;max-height:420px}.legend{display:flex;gap:20px;flex-wrap:wrap;font-size:.9rem}.legend span{display:inline-flex;align-items:center;gap:7px}.key{display:inline-block;width:24px;height:4px;background:#137b99}.key.band{height:12px;background:#cce5f3;border:1px solid #9bc8df}.key.path{background:#bc6024}.empty{padding:14px;background:#f3f5f8;border-radius:6px}
details{margin:16px 0}summary{cursor:pointer;font-weight:600}pre{padding:14px;background:#f3f5f8;overflow:auto;border-radius:6px;font:12px/1.5 ui-monospace,monospace}ul{padding-left:22px}.income-table{max-height:460px}.income-table thead{position:sticky;top:0}
@media(max-width:640px){main{padding:22px 12px}section{padding:15px}h1{font-size:1.7rem}label,select{width:100%;max-width:100%}.controls{gap:12px}}
@media print{body{background:white}main{max-width:none;padding:0}.controls{display:none}.scroll,.income-table{overflow:visible;max-height:none}section{break-inside:avoid}}
</style></head><body><main>
<header><p class="eyebrow">Investment simulator · Monte Carlo</p><h1>Retirement income under simulated returns</h1><p id="overview" class="muted"></p>
<p class="note">These are illustrative, uncalibrated simulations with IID annual lognormal shocks. Counts and quantiles are conditional on the chosen model, volatility, seed and inputs. Inflation and OKI rates follow the same fixed assumptions on every path.</p><p id="objective-note"></p></header>
<noscript><p class="panel">Enable JavaScript to explore this local report. All code and data are included in the HTML file.</p></noscript>
<section aria-labelledby="income-heading"><h2 id="income-heading">Annual real income</h2>
<div class="controls"><label for="strategy">Strategy<select id="strategy"></select></label><label for="path">Individual path overlay<select id="path"></select></label></div>
<p class="muted">P10, P50 and P90 are marginal quantiles for each payment year, not one coherent trajectory. The optional path line follows one actual simulated sequence. The band includes feasible and infeasible paths.</p>
<div class="legend"><span><i class="key band" aria-hidden="true"></i>P10–P90 band</span><span><i class="key" aria-hidden="true"></i>P50 by year</span><span><i class="key path" aria-hidden="true"></i>Selected path</span></div>
<svg id="income-chart" viewBox="0 0 1080 370" role="img" aria-labelledby="chart-title chart-description"></svg><p id="chart-empty" class="empty" hidden></p>
<p class="muted">Amounts are in PLN at the simulation start's purchasing power. Each payment is deflated at its own date. Chart positions are approximate; the table preserves the supplied decimal values.</p>
<details><summary>Annual income values and counts</summary><div class="scroll income-table"><table id="income-table"></table></div></details></section>
<section aria-labelledby="risk-heading"><h2 id="risk-heading">Income risk counts</h2><p class="muted">Declines of at least 25% or 50% are measured against the first real annual payment, not a rolling peak. A path needs at least two payments and a positive first payment to be eligible. Zero-first-payment paths are explicitly excluded. A feasible path can still deliver very little or zero income.</p>
<p id="floor-note"></p><div class="scroll"><table id="risk-table"></table></div><p id="eligibility-note" class="muted"></p>
<details><summary>Income floor and eligibility details</summary><div class="scroll"><table id="floor-table"></table></div></details></section>
<section aria-labelledby="outcome-heading"><h2 id="outcome-heading">Outcome distributions</h2><p id="outcome-label"></p><p class="muted">Wealth, withdrawals and objective values include all paths. Paired advantages compare the same return path and include only paths where both the strategy and baseline are feasible.</p>
<div class="scroll"><table id="distribution-table"></table></div><h3>Feasibility and comparisons</h3><div class="scroll"><table id="counts-table"></table></div></section>
<section aria-labelledby="path-heading"><h2 id="path-heading">Selected path</h2><p id="path-label"></p><div class="scroll"><table id="path-table"></table></div>
<details><summary>Annual returns on this path</summary><div class="scroll"><table id="returns-table"></table></div></details>
<details><summary>Replay this path locally</summary><p>Save the normalized request below as <code>request.local.json</code>, then run these commands from the repository root. Every strategy receives the same sampled return path.</p><pre id="replay-command"></pre></details></section>
<section aria-labelledby="model-heading"><h2 id="model-heading">Model and input provenance</h2><p id="model-description"></p><p id="quantile-description"></p><p id="versions" class="muted"></p>
<details><summary>Fixed annual assumptions</summary><div class="scroll"><table id="assumptions-table"></table></div></details>
<details><summary>Normalized request for replay</summary><pre id="request-json"></pre></details><ul id="limitations"></ul></section>
</main><script id="report-data" type="application/json">__REPORT_JSON__</script>
<script>
'use strict';
const data=JSON.parse(document.getElementById('report-data').textContent),base=data.request.baseRequest;
const byId=id=>document.getElementById(id);
const summaries=new Map(data.strategySummaries.map(value=>[value.strategyId,value]));
const paths=new Map(data.paths.map(value=>[value.id,value]));
const ids=base.strategies.map(value=>value.id);
const floor=data.request.minimumRealAnnualIncomePln??null;
const objectiveLabel=data.comparisonObjective==='REAL_WITHDRAWALS_PLUS_TERMINAL_WEALTH'?'Total real withdrawals plus terminal wealth':'Real terminal wealth';
function node(tag,text,className){const value=document.createElement(tag);if(text!==undefined)value.textContent=String(text);if(className)value.className=className;return value;}
function svgNode(tag,attributes={},text){const value=document.createElementNS('http://www.w3.org/2000/svg',tag);for(const [key,item] of Object.entries(attributes))value.setAttribute(key,String(item));if(text!==undefined)value.textContent=String(text);return value;}
function option(select,value,label){const item=node('option',label);item.value=value;select.append(item);}
function money(value){if(value===null||value===undefined)return 'Not assessed';const match=/^([+-]?)(\d+)(\.\d+)?$/.exec(value);return match?match[1]+match[2].replace(/\B(?=(\d{3})+(?!\d))/g,'\u202f')+(match[3]||''):value;}
function rate(value){return (value*100).toFixed(8).replace(/\.?0+$/,'')+'%';}
function ratio(value,denominator){return value===null||value===undefined?'Not assessed':denominator===0?'Not assessed (0 eligible paths)':value+' / '+denominator;}
function table(id,headers){const target=byId(id);target.replaceChildren();const head=node('thead'),tr=node('tr');headers.forEach(text=>{const th=node('th',text);th.scope='col';tr.append(th);});head.append(tr);const body=node('tbody');target.append(head,body);return body;}
function row(body,values,moneyColumns=[]){const tr=node('tr');values.forEach((value,index)=>tr.append(node('td',value,moneyColumns.includes(index)?'money':'')));body.append(tr);return tr;}
function chosenSummary(){return summaries.get(ids[Number(byId('strategy').value)]);}
function chosenPath(){return paths.get(byId('path').value);}
function shellQuote(value){return "'"+String(value).replaceAll("'","'\\''")+"'";}
ids.forEach((id,index)=>option(byId('strategy'),String(index),id));
option(byId('path'),'','No single-path overlay');data.paths.forEach(path=>option(byId('path'),path.id,path.id));
byId('path').value=data.paths[0].id;
byId('overview').textContent=base.startDate+' to '+base.endDate+' · '+data.pathCount+' paths · '+ids.length+' strategies · annual log-return σ '+rate(data.request.annualLogReturnVolatility)+' · seed '+data.request.seed+'.';
byId('objective-note').textContent='Comparison objective: '+objectiveLabel+'. Preferred-path counts follow this objective. Baseline: '+base.baselineStrategyId+'.';
byId('floor-note').textContent=floor===null?'No minimum real annual income was supplied. Income-floor outcomes are not assessed.':'Minimum real annual income: '+money(floor)+' PLN. Floor counts compare each year\'s real net payment with this fixed purchasing-power amount.';
byId('model-description').textContent='Return model: '+data.returnModel+'. Sigma and the expected annual return inputs are illustrative and uncalibrated. CPI and OKI rates are held fixed; their uncertainty is not simulated.';
byId('quantile-description').textContent='Quantile method: '+data.quantileMethod+'. Quantiles are observed nearest-rank samples without interpolation. They are not confidence intervals or a forecast guarantee.';
byId('versions').textContent='Monte Carlo '+data.monteCarloVersion+' · Engine '+data.engineVersion+' · Tax rules '+data.taxRulesVersion+' · '+data.evaluatedStrategyDays+' strategy-days evaluated · '+data.allInfeasiblePathCount+' paths with no feasible strategy. This standalone file makes no network requests.';
byId('request-json').textContent=JSON.stringify(data.request,null,2);data.limitations.forEach(value=>byId('limitations').append(node('li',value)));
const assumptionsBody=table('assumptions-table',['Year','Expected nominal equity return','Fixed inflation','Fixed OKI tax rate','OKI rate status']);
base.assumptions.forEach(value=>row(assumptionsBody,[value.year,rate(value.equityReturnRate),rate(value.inflationRate),rate(value.okiTaxRate),value.okiRateStatus||'ASSUMED']));
function renderIncome(){
 const summary=chosenSummary(),path=chosenPath(),selected=path?.strategies.find(value=>value.strategyId===summary.strategyId);
 const history=new Map((selected?.annualWithdrawals||[]).map(value=>[value.date,value]));
 const body=table('income-table',['Payment date','Samples','P10 real PLN','P50 real PLN','P90 real PLN','Selected path real PLN','Zero-payment paths','Below-floor paths']);
 summary.annualIncome.forEach(value=>row(body,[value.date,value.realPaidPln.sampleCount,money(value.realPaidPln.p10),money(value.realPaidPln.p50),money(value.realPaidPln.p90),history.has(value.date)?money(history.get(value.date).realPaidPln):'No path selected',ratio(value.zeroPaymentPathCount,data.pathCount),ratio(value.belowMinimumPathCount,data.pathCount)],[2,3,4,5]));
 renderChart(summary,selected);
}
function renderChart(summary,selected){
 const svg=byId('income-chart');svg.replaceChildren();const observations=summary.annualIncome,history=new Map((selected?.annualWithdrawals||[]).map(value=>[value.date,value]));
 svg.append(svgNode('title',{id:'chart-title'},'Annual real income for '+summary.strategyId),svgNode('desc',{id:'chart-description'},'P10 to P90 marginal quantile band and P50 line across payment years. The selected path is a separate coherent trajectory. Exact values are in the following table.'));
 const plotted=observations.flatMap(value=>[Number(value.realPaidPln.p10),Number(value.realPaidPln.p50),Number(value.realPaidPln.p90),...(history.has(value.date)?[Number(history.get(value.date).realPaidPln)]:[])]);
 const available=observations.length>0&&plotted.every(value=>Number.isFinite(value)&&value>=0);svg.style.display=available?'block':'none';byId('chart-empty').hidden=available;
 byId('chart-empty').textContent=observations.length===0?'There are no annual portfolio withdrawals in this request. Income decline metrics are not assessed.':'These values exceed the chart\'s display range; use the exact-value table.';
 if(!available)return;
 // Number is used only for chart coordinates and approximate axis labels. Monetary reporting uses strings.
 const left=102,right=1045,top=22,bottom=305,maximum=Math.max(1,...plotted)*1.08;
 const x=index=>observations.length===1?(left+right)/2:left+index*(right-left)/(observations.length-1),y=value=>bottom-Number(value)/maximum*(bottom-top);
 for(let tick=0;tick<=4;tick++){const value=maximum*tick/4,py=y(value);svg.append(svgNode('line',{x1:left,x2:right,y1:py,y2:py,stroke:'#dfe6ec'}),svgNode('text',{x:left-10,y:py+4,'text-anchor':'end',fill:'#526575','font-size':12},value.toLocaleString('en-US',{maximumSignificantDigits:4})));}
 svg.append(svgNode('text',{x:left,y:355,fill:'#526575','font-size':12},'Annual real net payment · PLN at '+base.startDate+' purchasing power'));
 const upper=observations.map((value,index)=>x(index)+','+y(value.realPaidPln.p90)),lower=observations.map((value,index)=>x(index)+','+y(value.realPaidPln.p10)).reverse();
 svg.append(svgNode('polygon',{points:[...upper,...lower].join(' '),fill:'#cce5f3',stroke:'none'}));
 const line=(values,color,width)=>svg.append(svgNode('polyline',{points:values.join(' '),fill:'none',stroke:color,'stroke-width':width,'stroke-linejoin':'round','stroke-linecap':'round'}));
 line(upper,'#92bfd6',1);line([...lower].reverse(),'#92bfd6',1);line(observations.map((value,index)=>x(index)+','+y(value.realPaidPln.p50)),'#137b99',3);
 if(selected)line(observations.filter(value=>history.has(value.date)).map(value=>x(observations.indexOf(value))+','+y(history.get(value.date).realPaidPln)),'#bc6024',2.5);
 const interval=Math.max(1,Math.ceil(observations.length/9));
 observations.forEach((value,index)=>{if(index%interval===0||index===observations.length-1)svg.append(svgNode('text',{x:x(index),y:bottom+25,'text-anchor':'middle',fill:'#526575','font-size':12},value.date.slice(0,4)));
  const median=svgNode('circle',{cx:x(index),cy:y(value.realPaidPln.p50),r:3,fill:'#137b99'});median.append(svgNode('title',{},value.date+' · P10 '+money(value.realPaidPln.p10)+' · P50 '+money(value.realPaidPln.p50)+' · P90 '+money(value.realPaidPln.p90)+' real PLN'));svg.append(median);
  if(history.has(value.date)){const amount=history.get(value.date).realPaidPln,dot=svgNode('circle',{cx:x(index),cy:y(amount),r:3,fill:'#bc6024'});dot.append(svgNode('title',{},value.date+' · selected path '+money(amount)+' real PLN'));svg.append(dot);}
 });
}
function renderRisks(){
 const body=table('risk-table',['Strategy','Eligible decline paths','Decline ≥25% / eligible','Decline ≥50% / eligible','Any zero-payment year / all paths','Any year below floor / all paths']);
 const floorBody=table('floor-table',['Strategy','Zero first-payment paths','Insufficient history paths','Maximum years below floor','Longest consecutive run below floor']);
 data.strategySummaries.forEach(value=>{row(body,[value.strategyId,ratio(value.incomeDeclineEligiblePathCount,data.pathCount),ratio(value.declineAtLeast25PercentPathCount,value.incomeDeclineEligiblePathCount),ratio(value.declineAtLeast50PercentPathCount,value.incomeDeclineEligiblePathCount),ratio(value.anyZeroPaymentPathCount,data.pathCount),ratio(value.anyYearBelowMinimumPathCount,data.pathCount)]);row(floorBody,[value.strategyId,value.zeroFirstPaymentPathCount,value.insufficientIncomeHistoryPathCount,value.maximumYearsBelowMinimum??'Not assessed',value.maximumConsecutiveYearsBelowMinimum??'Not assessed']);});
 const chosen=chosenSummary();byId('eligibility-note').textContent=chosen.strategyId+': '+chosen.incomeDeclineEligiblePathCount+' eligible paths; '+chosen.zeroFirstPaymentPathCount+' zero-first-payment paths; '+chosen.insufficientIncomeHistoryPathCount+' paths with fewer than two annual payments. Counts describe the simulated sample under the stated model.';
}
function renderOutcomes(){
 const summary=chosenSummary();byId('outcome-label').textContent='Strategy: '+summary.strategyId+'. All amounts are real PLN at '+base.startDate+' purchasing power.';
 const body=table('distribution-table',['Measure','Samples','Minimum','P10','P50','P90','Maximum']);
 for(const [label,key] of [['Terminal wealth','realNetLiquidationValuePln'],['Cumulative withdrawals','realWithdrawalsPaidPln'],[objectiveLabel,'comparisonValuePln'],['Paired objective advantage vs baseline','objectiveAdvantageVsBaselinePln']]){const value=summary[key];row(body,[label,value?.sampleCount??0,...['minimum','p10','p50','p90','maximum'].map(field=>money(value?.[field]))],[2,3,4,5,6]);}
 const counts=table('counts-table',['Strategy','Feasible / all paths','Preferred / all paths','Highest objective / all paths','Comparable baseline pairs / all paths']);
 data.strategySummaries.forEach(value=>row(counts,[value.strategyId,ratio(value.feasiblePathCount,data.pathCount),ratio(value.preferredPathCount,data.pathCount),ratio(value.highestObjectivePathCount,data.pathCount),ratio(value.comparableToBaselinePathCount,data.pathCount)]));
}
function renderPath(){
 const path=chosenPath(),body=table('path-table',['Strategy','Feasible','Real terminal wealth (PLN)','Real withdrawals (PLN)','Total real benefit (PLN)','Paired objective advantage (PLN)','First real payment (PLN)','Minimum real payment (PLN)']);
 const returns=table('returns-table',['Year','Sampled nominal equity return']);
 if(!path){byId('path-label').textContent='Select an individual path above to inspect and replay a coherent trajectory.';byId('replay-command').textContent='Select a path to see replay commands.';return;}
 byId('path-label').textContent=path.id+' · Preferred strategy: '+(path.preferredStrategyId??'none; all strategies are infeasible')+'. The income chart overlays this path for the selected strategy.';
 const baseline=path.strategies.find(value=>value.strategyId===base.baselineStrategyId);
 path.strategies.forEach(value=>row(body,[value.strategyId,value.feasible?'Yes':'No',money(value.realNetLiquidationValuePln),money(value.realWithdrawalsPaidPln),money(value.realTotalBenefitPln),value.feasible&&baseline.feasible?money(value.objectiveAdvantageVsBaselinePln):'Not comparable',money(value.income.firstRealPaymentPln),money(value.income.minimumRealPaymentPln)],[2,3,4,5,6,7]));
 path.annualReturns.forEach(value=>row(returns,[value.year,rate(value.equityReturnRate)]));
 byId('replay-command').textContent='build/install/investment-simulator/bin/investment-simulator monte-carlo-path request.local.json '+shellQuote(path.id)+' > path.local.json\n'+'build/install/investment-simulator/bin/investment-simulator compare path.local.json > comparison.local.json';
}
function render(){renderIncome();renderRisks();renderOutcomes();renderPath();}
byId('strategy').addEventListener('change',render);byId('path').addEventListener('change',render);render();
</script></body></html>
'''


if __name__ == "__main__":
    raise SystemExit(main())
