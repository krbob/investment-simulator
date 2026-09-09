#!/usr/bin/env python3
"""Render a sensitivity JSON result as a standalone, interactive local HTML report."""

from __future__ import annotations

import argparse
from datetime import date
from decimal import Decimal, InvalidOperation
import itertools
import json
import math
import os
from pathlib import Path
import re
import sys
import tempfile


MAX_INPUT_BYTES = 32 * 1024 * 1024
MONEY_FIELDS = (
    "realNetLiquidationValuePln", "netLiquidationValuePln", "advantageVsBaselinePln",
    "contributionsPln", "withdrawalsPaidPln", "withdrawalShortfallPln", "unpaidTaxPln",
    "outstandingTaxPln", "liquidationTaxPln", "liquidationFeesPln", "capitalGainsTaxPaidPln",
    "okiTaxPaidPln", "tradingFeesPln",
)
TRANSFER_FIELDS = (
    "grossSoldPln", "realizedGainPln", "estimatedAdditionalCapitalGainsTaxPln",
    "proceedsTransferredPln", "purchasedValuePln", "feesPln",
)
COORDINATES = ("equityReturnRateShift", "inflationRateShift", "assumedOkiTaxRateShift", "endDate")
AXES = ("equityReturnRateShifts", "inflationRateShifts", "assumedOkiTaxRateShifts", "endDates")
TRANSITION_AXES = dict(zip(
    ("EQUITY_RETURN_RATE_SHIFT", "INFLATION_RATE_SHIFT", "ASSUMED_OKI_TAX_RATE_SHIFT", "END_DATE"), COORDINATES,
))
TRANSITION_KINDS = {"PREFERRED_STRATEGY_CHANGE", "BASELINE_BREAK_EVEN", "MINIMUM_ADVANTAGE_CROSSING", "FEASIBILITY_CHANGE"}


class ReportError(ValueError):
    """Invalid report input; messages deliberately do not echo investor data."""


def require(condition: bool, message: str) -> None:
    if not condition:
        raise ReportError(message)


def decimal_string(value, nullable=False) -> bool:
    if nullable and value is None:
        return True
    if not isinstance(value, str) or len(value) > 256 or not re.fullmatch(r"[+-]?\d+(?:\.\d+)?(?:[eE][+-]?\d+)?", value):
        return False
    try:
        return Decimal(value).is_finite()
    except InvalidOperation:
        return False


def valid_date(value) -> bool:
    try:
        return isinstance(value, str) and date.fromisoformat(value).isoformat() == value
    except ValueError:
        return False


def count(value) -> bool:
    return type(value) is int and value >= 0


def validate_report(report: dict) -> None:
    require(isinstance(report, dict), "Input must be a sensitivity result object.")
    for key in ("sensitivityVersion", "engineVersion", "taxRulesVersion"):
        require(isinstance(report.get(key), str) and bool(report[key]), "Report version metadata is missing.")
    require(isinstance(report.get("request"), dict), "The original sensitivity request is missing.")
    request = report["request"]
    base, axes = request.get("baseRequest"), request.get("axes")
    require(isinstance(base, dict) and isinstance(axes, dict), "Base request and sensitivity axes are required.")
    require(valid_date(base.get("startDate")) and valid_date(base.get("endDate")), "Base request dates must be ISO calendar dates.")
    strategies = base.get("strategies")
    require(isinstance(strategies, list) and bool(strategies), "The base request must identify its strategies.")
    require(all(isinstance(item, dict) and isinstance(item.get("id"), str) and item["id"] for item in strategies), "Strategy IDs must be nonempty strings.")
    strategy_ids = [item["id"] for item in strategies]
    require(len(set(strategy_ids)) == len(strategy_ids), "Base strategy IDs must be unique.")
    require(base.get("baselineStrategyId") in strategy_ids, "The baseline strategy must be present.")
    assumptions = base.get("assumptions")
    require(isinstance(assumptions, list) and bool(assumptions), "The base annual assumptions are missing.")
    require(all(isinstance(item, dict) and type(item.get("year")) is int and all(type(item.get(key)) in (int, float) and math.isfinite(item[key]) for key in ("equityReturnRate", "inflationRate", "okiTaxRate")) for item in assumptions), "The base annual assumptions are incomplete.")
    axis_values = []
    for key in AXES[:3]:
        values = axes.get(key)
        require(isinstance(values, list) and bool(values), "Each rate axis must contain at least one sample.")
        require(all(type(value) in (int, float) and math.isfinite(value) for value in values), "Rate samples must be finite numbers.")
        require(len(set(values)) == len(values), "Rate axis samples must be unique.")
        axis_values.append(values)
    horizons = axes.get("endDates")
    require(isinstance(horizons, list) and all(valid_date(value) for value in horizons), "Horizon samples must be ISO calendar dates.")
    horizons = horizons or [base["endDate"]]
    require(len(set(horizons)) == len(horizons), "Horizon samples must be unique.")
    axis_values.append(horizons)
    scenarios = report.get("scenarios")
    require(isinstance(scenarios, list) and bool(scenarios), "The report has no scenario results.")
    require(count(report.get("scenarioCount")) and report["scenarioCount"] == len(scenarios), "Scenario count does not match its results.")
    require(count(report.get("evaluatedStrategyDays")), "Evaluated strategy-day count is missing.")
    require(math.prod(map(len, axis_values)) == len(scenarios), "Scenario results do not cover the complete requested grid.")
    expected = set(itertools.product(*axis_values))
    by_id = {}
    cells = set()
    infeasible_count = 0
    for scenario in scenarios:
        require(isinstance(scenario, dict) and isinstance(scenario.get("id"), str) and scenario["id"], "Each scenario requires an ID.")
        require(scenario["id"] not in by_id, "Scenario IDs must be unique.")
        coordinates = scenario.get("coordinates")
        require(isinstance(coordinates, dict) and all(key in coordinates for key in COORDINATES), "Scenario coordinates are incomplete.")
        require(all(type(coordinates[key]) in (int, float) and math.isfinite(coordinates[key]) for key in COORDINATES[:3]) and valid_date(coordinates["endDate"]), "Scenario coordinates have invalid types.")
        cell = tuple(coordinates[key] for key in COORDINATES)
        require(cell in expected and cell not in cells, "Scenario grid has duplicate or unexpected cells.")
        cells.add(cell)
        by_id[scenario["id"]] = scenario
        results = scenario.get("strategies")
        require(isinstance(results, list) and len(results) == len(strategy_ids), "A scenario is missing strategy results.")
        require(all(isinstance(item, dict) and isinstance(item.get("strategyId"), str) for item in results), "Strategy results have invalid IDs.")
        require({item["strategyId"] for item in results} == set(strategy_ids), "Each scenario must contain every strategy exactly once.")
        feasible_ids = set()
        for result in results:
            require(type(result.get("feasible")) is bool, "Strategy feasibility must be explicit.")
            if result["feasible"]:
                feasible_ids.add(result["strategyId"])
            require(all(decimal_string(result.get(key)) for key in MONEY_FIELDS), "Strategy money values must be finite decimal strings.")
            require("regretVsBestFeasiblePln" in result and decimal_string(result["regretVsBestFeasiblePln"], nullable=True), "Strategy regret must be a decimal string or null.")
            require("initialTransfer" in result, "Initial migration details must be present or null.")
            transfer = result["initialTransfer"]
            require(transfer is None or (isinstance(transfer, dict) and transfer.get("direction") in {"TAXABLE_TO_OKI", "OKI_TO_TAXABLE"} and all(decimal_string(transfer.get(key)) for key in TRANSFER_FIELDS)), "Initial migration details are incomplete.")
        for key in ("preferredStrategyId", "highestValueStrategyId"):
            require(key in scenario and (scenario[key] in feasible_ids if isinstance(scenario[key], str) else scenario[key] is None and not feasible_ids), "Selected strategies must be feasible, or null when every strategy is infeasible.")
        infeasible_count += not feasible_ids
        require(scenario.get("recommendation") in {"CHANGE", "KEEP_BASELINE", "NO_CLEAR_ADVANTAGE", "WITHDRAWAL_SHORTFALL"}, "Scenario recommendation is missing or invalid.")
        for key in ("preservedEstablishedOkiYears", "shiftedAssumedOkiYears"):
            require(isinstance(scenario.get(key), list) and all(type(year) is int for year in scenario[key]), "Scenario rate provenance is missing.")
        require(count(scenario.get("omittedCashFlowCount")), "Scenario cash-flow provenance is missing.")
    require(count(report.get("allInfeasibleScenarioCount")) and report["allInfeasibleScenarioCount"] == infeasible_count, "Infeasible scenario count does not match the results.")
    summaries = report.get("strategySummaries")
    require(isinstance(summaries, list), "Horizon summaries are missing.")
    summary_keys = set()
    for summary in summaries:
        require(isinstance(summary, dict) and isinstance(summary.get("strategyId"), str) and isinstance(summary.get("endDate"), str), "A horizon summary is incomplete.")
        key = (summary["endDate"], summary["strategyId"])
        require(key in set(itertools.product(horizons, strategy_ids)) and key not in summary_keys, "Horizon summaries contain duplicate or unexpected entries.")
        summary_keys.add(key)
        require(count(summary.get("scenarioCount")) and summary["scenarioCount"] == len(scenarios) // len(horizons), "Horizon summary sample count does not match the grid.")
        for field in ("feasibleScenarioCount", "preferredScenarioCount", "highestValueScenarioCount", "comparableToBaselineScenarioCount"):
            require(count(summary.get(field)) and summary[field] <= summary["scenarioCount"], "Horizon summary counts are invalid.")
        for field in ("minimumAdvantageVsBaselinePln", "maximumAdvantageVsBaselinePln", "maximumRegretVsBestFeasiblePln"):
            require(field in summary and decimal_string(summary[field], nullable=True), "Horizon summary money values are incomplete.")
    require(len(summary_keys) == len(horizons) * len(strategy_ids), "Horizon summaries do not cover every strategy.")
    transitions = report.get("transitions")
    require(isinstance(transitions, list), "Sampled transition brackets are missing.")
    for transition in transitions:
        require(isinstance(transition, dict), "A sampled transition bracket is invalid.")
        require(transition.get("axis") in TRANSITION_AXES and transition.get("kind") in TRANSITION_KINDS, "A sampled transition has an invalid axis or kind.")
        require(transition.get("fromScenarioId") in by_id and transition.get("toScenarioId") in by_id, "A sampled transition refers to a missing scenario.")
        require(transition.get("strategyId") is None or transition.get("strategyId") in strategy_ids, "A sampled transition refers to an unknown strategy.")
        left = by_id[transition["fromScenarioId"]]["coordinates"]
        right = by_id[transition["toScenarioId"]]["coordinates"]
        changed = [key for key in COORDINATES if left[key] != right[key]]
        require(changed == [TRANSITION_AXES[transition["axis"]]], "A sampled transition must change only its stated axis.")
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
                                         prefix=".sensitivity-report-", suffix=".html", delete=False) as temporary:
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
    parser.add_argument("--input", required=True, type=Path, help="Sensitivity result JSON")
    parser.add_argument("--output", required=True, type=Path, help="Standalone local HTML report")
    args = parser.parse_args(argv)
    try:
        require(args.input.resolve() != args.output.resolve(), "Output must differ from the input JSON file.")
        with args.input.open("rb") as source:
            raw = source.read(MAX_INPUT_BYTES + 1)
        require(len(raw) <= MAX_INPUT_BYTES, "Input exceeds the 32 MiB report limit.")
        report = json.loads(raw)
        write_report(render_report(report), args.output)
    except ReportError as error:
        print(f"Report failed: {error}", file=sys.stderr)
        return 2
    except (OSError, ValueError, UnicodeError, TypeError, RecursionError, OverflowError):
        print("Report failed: input is invalid or the local report cannot be written.", file=sys.stderr)
        return 2
    print("Saved standalone sensitivity report. Open the HTML file in a browser.")
    return 0


HTML = r'''<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<meta http-equiv="Content-Security-Policy" content="default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; connect-src 'none'; base-uri 'none'; form-action 'none'">
<title>Investment strategy sensitivity</title>
<style>
:root{font:16px/1.5 system-ui,sans-serif;color:#202a35;background:#f5f6f8;color-scheme:light}
*{box-sizing:border-box}body{margin:0}main{max-width:1420px;margin:auto;padding:32px 24px 60px}
h1{font-size:2rem;line-height:1.2;margin:0 0 12px}h2{font-size:1.3rem;margin:0 0 10px}h3{font-size:1.05rem;margin:0 0 8px}
p{margin:8px 0 16px}.muted{color:#566271}.eyebrow{font-size:.8rem;text-transform:uppercase;letter-spacing:.1em;color:#526575}
section,.panel{background:white;border:1px solid #dce1e7;border-radius:12px;padding:22px;margin:20px 0}
.controls{display:flex;flex-wrap:wrap;gap:18px;margin:22px 0}label{display:flex;flex-direction:column;gap:6px;font-weight:600}
select{font:inherit;padding:8px 12px;border:1px solid #a5aebb;border-radius:6px;background:white;max-width:100%}
.scroll{overflow:auto}table{border-collapse:collapse;width:100%;font-size:.9rem}th,td{padding:10px 12px;border-bottom:1px solid #e1e6eb;text-align:left;vertical-align:top}
th{font-weight:650;background:#f3f5f8}td.money{font-variant-numeric:tabular-nums;white-space:nowrap;text-align:right}th.money{text-align:right}
caption{text-align:left;color:#566271;padding:0 0 10px}button{font:inherit;cursor:pointer}button:focus-visible,select:focus-visible{outline:3px solid #126bc5;outline-offset:3px}
.map th{text-align:center;white-space:nowrap}.map td{padding:5px;border:0}.map th:first-child{min-width:140px}.cell{display:block;width:100%;min-width:130px;min-height:80px;border:2px solid transparent;border-radius:7px;padding:10px;color:#172330;overflow-wrap:anywhere}
.cell[aria-pressed=true]{border-color:#172330;box-shadow:0 0 0 2px white inset}.cell small{display:block;margin-top:5px;font-size:.76rem}
.legend{display:flex;flex-wrap:wrap;gap:10px 20px;font-size:.85rem;margin:12px 0}.legend span{display:inline-flex;align-items:center;gap:6px}.swatch{width:14px;height:14px;border:1px solid #73808d;border-radius:3px}
.badge{display:inline-block;padding:3px 9px;border-radius:5px;background:#e8edf3;font-size:.85rem}.link-button{background:none;border:0;color:#145d9c;text-decoration:underline;padding:0;text-align:left}
details{margin-top:16px}summary{cursor:pointer;font-weight:600}ul{padding-left:22px}.note{border-left:3px solid #a1b3c5;padding-left:12px}.empty{padding:12px;color:#566271}
@media(max-width:640px){main{padding:22px 12px}section,.panel{padding:15px}h1{font-size:1.7rem}label,select{width:100%}}
@media print{body{background:white}main{max-width:none;padding:0}section{break-inside:avoid}.controls{display:none}.scroll{overflow:visible}.cell{min-width:80px}button{color:inherit}}
</style>
</head>
<body><main>
<header><p class="eyebrow">Investment simulator · sensitivity analysis</p><h1>Which strategy holds up across your assumptions?</h1>
<p>Explore the supplied deterministic scenarios. Each cell compares the same strategies under one set of assumptions.</p>
<p class="muted" id="overview"></p><p class="note">Sample counts describe this chosen grid. They are not probabilities. Changes between adjacent samples bracket a transition; they do not establish an exact threshold.</p></header>
<noscript><p class="panel">Enable JavaScript to explore this local report. All data and code are contained in this file.</p></noscript>
<section aria-labelledby="map-heading"><h2 id="map-heading">Scenario map</h2>
<div class="controls"><label for="horizon">Horizon end<select id="horizon"></select></label><label for="inflation">Inflation rate shift<select id="inflation"></select></label><label for="metric">Map shows<select id="metric"></select></label></div>
<p class="muted">Columns shift annual equity returns; rows shift assumed OKI rates. Shifts are additive percentage points (pp). Established OKI rates stay unchanged.</p>
<div id="legend" class="legend" aria-label="Map legend"></div><div class="scroll"><table class="map" id="map"><caption>Choose a cell to inspect its strategy results.</caption></table></div></section>
<section aria-labelledby="detail-heading"><h2 id="detail-heading">Selected scenario</h2><p id="detail-label"></p><p id="detail-note" class="muted"></p>
<div class="scroll"><table id="results"><caption>Money is shown as the supplied decimal strings, without rounding. Real values use the simulation start's purchasing power.</caption></table></div>
<details><summary>Tax, spending and cost details</summary><div class="scroll"><table id="costs"></table></div></details>
<p id="rate-provenance" class="muted"></p></section>
<section aria-labelledby="summary-heading"><h2 id="summary-heading">Across the selected horizon</h2><p class="muted">All return, inflation and assumed OKI samples at this horizon, including inflation slices currently hidden from the map. Different horizons are summarized separately.</p>
<div class="scroll"><table id="summaries"></table></div></section>
<section aria-labelledby="transition-heading"><h2 id="transition-heading">Sampled transition brackets</h2><p class="muted">Adjacent samples touching the selected horizon. This table includes all inflation slices. The endpoints identify a sampled interval, without interpolation or a claim that the relationship is monotonic.</p>
<div class="scroll"><table id="transitions"></table></div><p id="transition-empty" class="empty" hidden>No transitions were observed between the supplied adjacent samples touching this horizon.</p></section>
<section aria-labelledby="assumptions-heading"><h2 id="assumptions-heading">Assumptions and limitations</h2><ul id="limitations"></ul>
<details><summary>Base annual assumptions</summary><div class="scroll"><table id="base-assumptions"></table></div></details><p id="versions" class="muted"></p></section>
</main>
<script id="report-data" type="application/json">__REPORT_JSON__</script>
<script>
'use strict';
const data=JSON.parse(document.getElementById('report-data').textContent);
const base=data.request.baseRequest, axes=data.request.axes;
const strategies=base.strategies.map(strategy=>strategy.id);
const scenarios=new Map(data.scenarios.map(scenario=>[scenario.id,scenario]));
const palette=['#cde4fa','#c8eadc','#f4ddae','#e4d9f6','#f5ced6','#cbe8ee','#e8e6b7','#dbdfe5'];
const neutral='#e7ebef';
const byId=id=>document.getElementById(id);
function node(tag,text,className){const result=document.createElement(tag);if(text!==undefined)result.textContent=String(text);if(className)result.className=className;return result;}
function option(select,value,label){const item=node('option',label);item.value=value;select.append(item);}
function pp(value){const scaled=(value*100).toFixed(8).replace(/\.?0+$/,'');return (value>0?'+':'')+(scaled==='-0'?'0':scaled)+' pp';}
function rate(value){return (value*100).toFixed(8).replace(/\.?0+$/,'')+'%';}
function money(value){if(value===null||value===undefined)return '—';const match=/^([+-]?)(\d+)(\.\d+)?$/.exec(value);return match?match[1]+match[2].replace(/\B(?=(\d{3})+(?!\d))/g,'\u202f')+(match[3]||''):value;}
function table(id,headers){const target=byId(id);target.querySelectorAll('thead,tbody').forEach(element=>element.remove());const head=node('thead'),row=node('tr');headers.forEach(header=>{const cell=node('th',header);cell.scope='col';row.append(cell);});head.append(row);const body=node('tbody');target.append(head,body);return body;}
function row(body,values,moneyColumns=[]){const result=node('tr');values.forEach((value,index)=>result.append(node('td',value,moneyColumns.includes(index)?'money':'')));body.append(result);return result;}
function describe(scenario){const c=scenario.coordinates;return c.endDate+' · return '+pp(c.equityReturnRateShift)+' · inflation '+pp(c.inflationRateShift)+' · assumed OKI '+pp(c.assumedOkiTaxRateShift);}
function strategyColor(id){return id===null?neutral:palette[strategies.indexOf(id)%palette.length];}
function metricStrategy(){const value=byId('metric').value;return value==='preferred'?null:strategies[Number(value)];}
function comparable(scenario,id){return scenario.strategies.find(value=>value.strategyId===base.baselineStrategyId).feasible&&scenario.strategies.find(value=>value.strategyId===id).feasible;}
let selectedId=null;
const horizons=[...new Set(data.scenarios.map(value=>value.coordinates.endDate))].sort();
horizons.forEach(value=>option(byId('horizon'),value,value));
[...axes.inflationRateShifts].sort((a,b)=>a-b).forEach(value=>option(byId('inflation'),String(value),pp(value)));
option(byId('metric'),'preferred','Preferred strategy');strategies.forEach((value,index)=>option(byId('metric'),String(index),'Real advantage · '+value));
byId('overview').textContent=data.scenarioCount+' scenarios · '+strategies.length+' strategies · '+horizons.length+' horizons · '+data.allInfeasibleScenarioCount+' scenarios with no feasible strategy. Baseline: '+base.baselineStrategyId+'.';
byId('versions').textContent='Sensitivity '+data.sensitivityVersion+' · Engine '+data.engineVersion+' · Tax rules '+data.taxRulesVersion+'. This standalone file makes no network requests.';
data.limitations.forEach(value=>byId('limitations').append(node('li',value)));
const assumptionBody=table('base-assumptions',['Year','Equity return','Inflation','OKI tax rate','OKI rate status']);
(base.assumptions||[]).forEach(value=>row(assumptionBody,[value.year,rate(value.equityReturnRate),rate(value.inflationRate),rate(value.okiTaxRate),value.okiRateStatus||'ASSUMED']));
function renderMap(){
 const horizon=byId('horizon').value,inflation=Number(byId('inflation').value),metric=metricStrategy();
 const slice=data.scenarios.filter(value=>value.coordinates.endDate===horizon&&value.coordinates.inflationRateShift===inflation);
 const returns=[...axes.equityReturnRateShifts].sort((a,b)=>a-b),oki=[...axes.assumedOkiTaxRateShifts].sort((a,b)=>a-b);
 if(!slice.some(value=>value.id===selectedId))selectedId=slice[0].id;
 const body=table('map',['Assumed OKI shift ↓ / return shift →',...returns.map(pp)]);
 // Decimal money stays text everywhere; Number is used only for the optional color intensity.
 const colorScale=metric===null?1:Math.max(1,...slice.filter(value=>comparable(value,metric)).map(value=>Math.abs(Number(value.strategies.find(item=>item.strategyId===metric).advantageVsBaselinePln))));
 for(const okiShift of oki){const tr=node('tr'),heading=node('th',pp(okiShift));heading.scope='row';tr.append(heading);
  for(const equityShift of returns){const scenario=slice.find(value=>value.coordinates.equityReturnRateShift===equityShift&&value.coordinates.assumedOkiTaxRateShift===okiShift);const td=node('td'),button=node('button',undefined,'cell');button.type='button';button.dataset.scenarioId=scenario.id;button.setAttribute('aria-pressed',String(scenario.id===selectedId));
   let label=scenario.preferredStrategyId===null?'No feasible strategy':scenario.preferredStrategyId,color=strategyColor(scenario.preferredStrategyId);
   if(metric!==null){if(comparable(scenario,metric)){const result=scenario.strategies.find(value=>value.strategyId===metric),value=Number(result.advantageVsBaselinePln);label=money(result.advantageVsBaselinePln)+' PLN';const intensity=Math.min(1,Math.abs(value)/colorScale);color='hsl('+(value<0?25:155)+' 55% '+(94-22*intensity)+'%)';}else{label=scenario.preferredStrategyId===null?'No feasible strategy':'Not comparable';color=neutral;}}
   button.style.backgroundColor=color;button.append(node('span',label),node('small',scenario.recommendation.toLowerCase().replaceAll('_',' ')));button.setAttribute('aria-label',describe(scenario)+'. '+label+'. Show strategy results.');button.addEventListener('click',()=>selectScenario(scenario.id));td.append(button);tr.append(td);
  }body.append(tr);
 }
 const legend=byId('legend');legend.replaceChildren();const legendItems=metric===null?[...strategies.map(value=>[value,strategyColor(value)]),['No feasible strategy',neutral]]:[['Positive real advantage','#b0dfcf'],['Negative real advantage','#efd0b3'],['Infeasible or baseline not comparable',neutral]];
 legendItems.forEach(([text,color])=>{const item=node('span'),swatch=node('i',undefined,'swatch');swatch.style.backgroundColor=color;swatch.setAttribute('aria-hidden','true');item.append(swatch,node('span',text));legend.append(item);});
 renderDetails();renderSummary();renderTransitions();
}
function selectScenario(id){const scenario=scenarios.get(id);selectedId=id;byId('horizon').value=scenario.coordinates.endDate;byId('inflation').value=String(scenario.coordinates.inflationRateShift);renderMap();byId('map').querySelectorAll('button').forEach(button=>{if(button.dataset.scenarioId===id)button.focus({preventScroll:true});});}
function renderDetails(){
 const scenario=scenarios.get(selectedId);byId('detail-label').textContent=scenario.id+' · '+describe(scenario);
 byId('detail-note').textContent=scenario.preferredStrategyId===null?'Every strategy is infeasible in this sample. No action is preferred.':'Preferred: '+scenario.preferredStrategyId+'. Highest real value among feasible strategies: '+scenario.highestValueStrategyId+'. Recommendation: '+scenario.recommendation.toLowerCase().replaceAll('_',' ')+'.';
 const body=table('results',['Strategy','Feasible','Real net wealth (PLN)','Real advantage vs baseline (PLN)','Real regret vs best feasible (PLN)','Initial migration PIT estimate (PLN)']);
 const costBody=table('costs',['Strategy','Contributions (PLN)','Net withdrawals paid (PLN)','Withdrawal shortfall (PLN)','Unpaid tax (PLN)','Outstanding tax (PLN)','PIT paid (PLN)','OKI tax paid (PLN)','Trading fees (PLN)','Liquidation tax (PLN)','Liquidation fees (PLN)']);
 scenario.strategies.forEach(result=>{const migration=result.initialTransfer;row(body,[result.strategyId,result.feasible?'Yes':'No',money(result.realNetLiquidationValuePln),comparable(scenario,result.strategyId)?money(result.advantageVsBaselinePln):'Not comparable',money(result.regretVsBestFeasiblePln),migration?money(migration.estimatedAdditionalCapitalGainsTaxPln):'No migration'],[2,3,4,5]);row(costBody,[result.strategyId,...['contributionsPln','withdrawalsPaidPln','withdrawalShortfallPln','unpaidTaxPln','outstandingTaxPln','capitalGainsTaxPaidPln','okiTaxPaidPln','tradingFeesPln','liquidationTaxPln','liquidationFeesPln'].map(key=>money(result[key]))],[1,2,3,4,5,6,7,8,9,10]);});
 byId('rate-provenance').textContent='Established OKI years kept unchanged: '+(scenario.preservedEstablishedOkiYears.join(', ')||'none')+'. Assumed OKI years shifted: '+(scenario.shiftedAssumedOkiYears.join(', ')||'none')+'. Explicit cash flows omitted after this horizon: '+scenario.omittedCashFlowCount+'. Migration PIT is an estimate at the initial sale; later tax settlement follows the scenario.';
}
function renderSummary(){
 const body=table('summaries',['Strategy','Samples','Feasible','Preferred','Highest real value','Comparable to baseline','Minimum real advantage (PLN)','Maximum real advantage (PLN)','Maximum real regret (PLN)']);
 data.strategySummaries.filter(value=>value.endDate===byId('horizon').value).forEach(value=>row(body,[value.strategyId,value.scenarioCount,value.feasibleScenarioCount,value.preferredScenarioCount,value.highestValueScenarioCount,value.comparableToBaselineScenarioCount,money(value.minimumAdvantageVsBaselinePln),money(value.maximumAdvantageVsBaselinePln),money(value.maximumRegretVsBestFeasiblePln)],[6,7,8]));
}
function renderTransitions(){
 const horizon=byId('horizon').value,visible=data.transitions.filter(value=>scenarios.get(value.fromScenarioId).coordinates.endDate===horizon||scenarios.get(value.toScenarioId).coordinates.endDate===horizon);
 const body=table('transitions',['Axis','Observed change','Strategy','From sample','To sample']);byId('transition-empty').hidden=visible.length>0;
 visible.forEach(value=>{const tr=row(body,[value.axis.toLowerCase().replaceAll('_',' '),value.kind.toLowerCase().replaceAll('_',' '),value.strategyId||'All strategies']);[value.fromScenarioId,value.toScenarioId].forEach(id=>{const td=node('td'),button=node('button',describe(scenarios.get(id)),'link-button');button.type='button';button.addEventListener('click',()=>selectScenario(id));td.append(button);tr.append(td);});});
}
['horizon','inflation','metric'].forEach(id=>byId(id).addEventListener('change',renderMap));renderMap();
</script></body></html>
'''


if __name__ == "__main__":
    raise SystemExit(main())
