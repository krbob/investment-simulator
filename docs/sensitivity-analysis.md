# Sensitivity analysis

Sensitivity analysis repeats the existing deterministic comparison across a caller-supplied
Cartesian grid. It answers which tested strategies are preferred under different assumptions,
where their advantage changes sign, and how much terminal value a feasible strategy gives up
relative to the best feasible candidate in each cell. It performs no forecasting, random sampling,
automatic market-data fetching, or global policy optimization.

## Run an analysis

```sh
./gradlew test installDist
build/install/investment-simulator/bin/investment-simulator sensitivity \
  examples/sensitivity.json > sensitivity.local.json
python3 scripts/render-sensitivity.py \
  --input sensitivity.local.json --output sensitivity.local.html
```

The example contains 36 cells around the existing synthetic migration scenario, with two
horizons, three equity-return shifts, two inflation shifts and three shifts to assumed future
OKI rates. Return and CPI inputs remain illustrative. The established 2027 OKI input is retained;
the simulator does not independently verify caller-assigned rate status.

The equivalent endpoint accepts the same JSON and returns the same result:

```sh
curl -sS http://127.0.0.1:8080/v1/sensitivity-analyses \
  -H 'Content-Type: application/json' \
  --data-binary @examples/sensitivity.json
```

The CLI reads stdin with `sensitivity -`. A valid grid returns exit 0, including grids with
infeasible strategies. Invalid JSON or a rejected grid returns exit 2 / HTTP 400. Every cell is
validated before simulation; an invalid cell rejects the entire request. HTTP bodies retain
the existing 2 MiB limit.

## Input and axis semantics

The request has `baseRequest` (a complete `ComparisonRequest`) and optional `axes`. To use a
Portfolio analysis, put its successful `resolvedRequest` in `baseRequest`. Do not use a blocked
Portfolio report or substitute zero for missing opening tax data.

```json
{
  "equityReturnRateShifts": [-0.02, 0, 0.02],
  "inflationRateShifts": [0, 0.015],
  "assumedOkiTaxRateShifts": [-0.004, 0, 0.004],
  "endDates": ["2036-12-31", "2046-12-31"]
}
```

This is the `axes` object, not a complete request. The generated
[OpenAPI contract](../openapi/investment-simulator-v1.json) defines exact schemas.

| Axis | Effect |
| --- | --- |
| `equityReturnRateShifts` | Add the selected shift to every retained year's nominal PLN equity return |
| `inflationRateShifts` | Add the shift to every retained year's inflation; the engine also changes indexed cash flows and real-value conversion |
| `assumedOkiTaxRateShifts` | Add the shift only to retained years labeled `ASSUMED`; never change `ESTABLISHED` years |
| `endDates` | End on a supplied 31 December between the original start and original end |

Shifts are **additive fractional rate units**: `0.01` is one percentage point. For example,
7% plus `-0.02` becomes 5%, and an assumed 0.85% plus `0.004` becomes 1.25%. Annual variation
in the base path remains intact. No rate is clamped into range or inferred from CPI; every shifted
rate must meet the underlying engine's input range. A nonzero OKI shift requires at least one
`ASSUMED` year in the base path.

Each rate axis defaults to `[0]`; omitted or empty `endDates` means the base end date. Explicit
rate arrays must be nonempty. Values must be finite and unique, including equivalent positive
and negative zero. Axes are sorted for stable scenario IDs, independent of input array order.
The base scenario itself is included only if the requested coordinates contain it.

## Horizons and household plans

Both the base horizon and each selected horizon must end on 31 December. This avoids using the
engine's partial-year OKI closure convention. A grid cannot extend a base path: supply a longer
comparison request with a complete annual schedule first.

Every cell keeps the original opening balances, acquisition costs, tax history, strategy set,
trading fees and minimum-advantage threshold. Shortening the horizon:

- trims annual assumptions and one-off cash flows after the new end;
- retains `contributionUntil` and `withdrawalFrom` without moving the retirement plan;
- retains opening liabilities even when their payment date is outside the horizon;
- reports how many one-off cash flows were omitted.

A shorter horizon may therefore finish before planned withdrawals begin. Compare strategies
within that cell; do not interpret the short horizon as funding the later spending period.
The sample's 2036 horizon precedes its withdrawal phase, while 2046 includes it.

## Reading the result

The result retains the normalized request with ledgers disabled, engine/tax-rule versions,
coordinates, compact per-strategy financial results and source-independent replay IDs. For each
cell, `preservedEstablishedOkiYears` and `shiftedAssumedOkiYears` show exactly which OKI years
were protected or shifted. A shorter cell with no assumed years can be unchanged by the OKI axis;
such cells remain separate, equally weighted grid samples and are disclosed by an empty shifted list.

`preferredStrategyId` follows the comparison engine's minimum-advantage threshold and baseline
tie preference. `highestValueStrategyId` selects the highest feasible real terminal value before
that threshold; baseline then strategy ID break ties. Both are null if every strategy is infeasible.
There is no aggregate recommendation for the whole grid.

Feasibility requires no unpaid requested withdrawals, no overdue tax, and a nonnegative terminal
value after hypothetical liquidation and outstanding taxes. A tax liability due after the horizon
is outstanding, not overdue. An infeasible strategy never receives a win. The underlying
`WITHDRAWAL_SHORTFALL` recommendation can also mean overdue tax or terminal insolvency.

The financial details include real and nominal terminal net value, advantage over the baseline,
contributions, withdrawals, paid and outstanding tax, liquidation adjustments, trading fees and
initial migration figures. The migration's estimated additional PIT is a liability estimate;
it is not added again to tax already included by the accounting engine.

`regretVsBestFeasiblePln` is the highest feasible real terminal value minus the candidate's value
in the **same cell**. It is null for an infeasible candidate. A large
worst regret means the strategy gives up considerable value in at least one tested case; it is
not an expected loss or a risk probability.

Strategy summaries are grouped by horizon. They report counts of feasible cells, preferred cells,
highest-value cells, and cells where both strategy and baseline are feasible. Advantage ranges
use only that last comparable subset; regret ranges use feasible cells. Review feasibility counts
alongside ranges: excluded infeasible cases are not evidence of a safe strategy.
Counts cover all inflation/return/OKI coordinates for the horizon, not just the displayed map slice.
They weight sampled grid cells equally and must not be interpreted as probabilities or confidence.

## Sampled transition brackets

The service compares adjacent points along each sorted axis while holding the other axes fixed.
Each transition links two scenario IDs and identifies one of these observations:

- `PREFERRED_STRATEGY_CHANGE`: two feasible recommendations select different strategies;
- `BASELINE_BREAK_EVEN`: a nonbaseline strategy's real advantage changes sign or touches zero;
- `MINIMUM_ADVANTAGE_CROSSING`: its advantage crosses the strict `>` decision threshold;
- `FEASIBILITY_CHANGE`: a strategy becomes feasible or infeasible.

Financial advantage transitions require both the strategy and the baseline to be feasible at
both endpoints. The same interval may have multiple kinds of transition. No exact root is
interpolated: taxes are rounded, strategy outcomes can be nonmonotonic, and multiple crossings
may lie between samples. No reported transition does not establish stability between points.
Refine the selected axis around an observed bracket to investigate it more closely.

## Reproduce a single cell

The saved result contains its normalized `request`. Extract it, resolve a scenario ID from the
report, and pass the result directly to `compare`:

```sh
python3 - <<'PY'
import json
from pathlib import Path
report = json.loads(Path("sensitivity.local.json").read_text())
Path("sensitivity-request.local.json").write_text(json.dumps(report["request"], indent=2) + "\n")
PY
build/install/investment-simulator/bin/investment-simulator sensitivity-scenario \
  sensitivity-request.local.json s0001 > sensitivity-case.local.json
build/install/investment-simulator/bin/investment-simulator compare \
  sensitivity-case.local.json > sensitivity-replay.local.json
```

Financial fields match the selected cell exactly. The ordinary comparison adds yearly detail;
set `includeLedger` to true in the resolved request if transaction-level events are needed.

## Runtime bounds

Each axis supports up to 16 values and the Cartesian grid up to 128 cells. The total inclusive
calendar days across all cells, multiplied by the number of strategies, cannot exceed 2,000,000.
Opening-lot replays (`cells × strategies × opening lots`) are limited to 250,000. These limits
are enforced before simulation, in addition to normal comparison validation.

The endpoint is synchronous and stateless. Smaller grids can be requested on demand; refine
specific brackets instead of submitting a large uniform grid. There is no cache, background job
queue or cross-request concurrency limit. Keep the service on its documented trusted interface;
a public deployment needs its own resource/concurrency policy.

Reports are generated locally, with embedded data and no external assets or network requests.
Store investor reports outside public examples. JSON and HTML include opening positions and tax
assumptions from the request; the renderer does not anonymize them.
