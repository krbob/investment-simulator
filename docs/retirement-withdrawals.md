# Annual withdrawals from the current portfolio

The annual policy pays a percentage of the current portfolio each year after accumulation.
It does not fix the first year's amount and then index that amount to inflation. For example,
with no returns, taxes, fees or other flows, PLN 100,000 produces a first withdrawal of PLN 4,000,
then PLN 3,840 from the remaining PLN 96,000 in the following year.

## Two different endpoints

- `annualWithdrawalPlan.startDate` is the first withdrawal date, on 1 January. The preceding
  31 December ends accumulation, and monthly contributions stop no later than that day.
- `endDate` is the end of the whole simulation, after the modeled withdrawal period.

There is no sale of the entire portfolio at the start of withdrawals. Remaining assets keep
following the supplied return path and tax accounting. At the final simulation end, residual
assets are valued using the existing hypothetical-liquidation convention; they are not counted
as an additional household withdrawal.

The complete [example](../examples/retirement.json) starts in 2027, accumulates through 2046,
withdraws annually from 2047 through 2076 and ends on 31 December 2076. These are illustrative
20-year accumulation and 30-year withdrawal periods, not a forecast or a required planning duration.
The overall engine limit remains 50 years; provide one annual assumption set for every year.

Within a full comparison request, the new settings are:

```json
{
  "endDate": "2076-12-31",
  "annualWithdrawalPlan": { "startDate": "2047-01-01", "rate": 0.04 },
  "comparisonObjective": "AUTO",
  "monthlyPlan": { "contributionPln": "2000", "indexContributions": true }
}
```

`rate` is a fraction between zero and one. The default is `0.04`. This version pays the whole
annual amount once on 1 January, rather than distributing it across monthly installments.
An explicit earlier `contributionUntil` remains effective. Fixed monthly withdrawals must be
zero when the annual policy is present; one-off contributions or spending remain supported.
An annual start beyond a shortened simulation end is allowed and produces no annual payments.

The same `annualWithdrawalPlan` and `comparisonObjective` fields are available in the Portfolio
analysis `plan`. Opening tax-state and acquisition-cost requirements still apply. The resulting
`resolvedRequest` can be compared or passed directly to sensitivity analysis.

## What the 4% applies to

On each annual payment date, the engine first processes scheduled contributions, any initial
strategy migration, due taxes and other scheduled household spending. It then sums current
taxable equity, OKI equity and outside-OKI cash, rounds that base to cents, multiplies by the rate
and rounds the requested payment to cents. Daily market growth occurs afterwards.

Tax liabilities that are not yet due remain in the market-value base. They are settled later
through the same household/portfolio budget and deducted when valuing residual assets. Already
due tax reduces the assets before that day's annual withdrawal base is measured.

The annual amount is **net household spending**. It is separate from tax and trading costs, so
the total reduction of invested assets can exceed 4%. Sales use the selected strategy's existing
funding order; taxable sales retain FIFO gains, annual netting and later PIT payment. There is
no immediate tax gross-up or free reset of acquisition cost.

Every strategy follows the same percentage rule, but can produce a different amount because
its portfolio has paid different taxes or costs. Income can decline with assets. A strategy
with very low or zero income may still be feasible when all amounts requested by that shrinking
percentage rule are funded; this policy provides no fixed minimum income.

## Comparing spending and the remainder

Terminal wealth alone can favor a strategy that paid less to the household. The explicit
comparison objective makes that choice visible:

| `comparisonObjective` | Ranked amount |
| --- | --- |
| `AUTO` | Real withdrawals plus real terminal wealth when an annual plan is present; otherwise real terminal wealth |
| `REAL_TERMINAL_WEALTH` | Real value of residual assets after liabilities and hypothetical liquidation |
| `REAL_WITHDRAWALS_PLUS_TERMINAL_WEALTH` | Cumulative real household spending plus that residual value |

Each actual household payment, including an explicit one-off withdrawal, is converted to
start-date purchasing power using CPI at payment and rounded to cents. These payments form
`realWithdrawalsPaidPln`. Adding the reported `realNetLiquidationValuePln` gives `realTotalBenefitPln`.
The selected objective is reported in `comparisonValuePln`; its difference from baseline is
`objectiveAdvantageVsBaselinePln`, which drives ranking and the strict minimum-advantage threshold.

The existing `advantageVsBaselinePln` remains the difference in real terminal wealth. Both
amounts are retained so consumers can distinguish a terminal advantage from a total-benefit
advantage. Feasibility and baseline tie preferences still apply.

The total-benefit sum values payments in real PLN without an additional time preference,
spending-utility function or later investment return on household cash already paid out.
It is an explicit comparison convention, not a universal ranking of retirement preferences.
Inspect the payout history and the residual assets separately as well.

`annualWithdrawals` records each payment date, current portfolio base, percentage, requested
net amount, paid amount and its real value. Zero annual payments are retained in the history.
The HTML report presents this history alongside cumulative withdrawals, residual wealth and
the objective used for the map and preferred-strategy counts.

## Sensitivity to the accumulation horizon

Use `accumulationEndDates` and `withdrawalYears` to vary how long accumulation lasts while
keeping a full withdrawal period after each date:

```json
{
  "equityReturnRateShifts": [-0.02, 0, 0.02],
  "inflationRateShifts": [0],
  "assumedOkiTaxRateShifts": [-0.004, 0, 0.004],
  "accumulationEndDates": ["2036-12-31", "2046-12-31"],
  "withdrawalYears": 30
}
```

This is an `axes` object. The [full sensitivity example](../examples/retirement-sensitivity.json)
includes the base request and complete return/tax-rate schedule. The two horizons become
withdrawals in 2037–2066 and 2047–2076, respectively. Each still has 30 annual payments.
The base request must have an annual policy and assumptions through the latest required end;
the service does not extrapolate missing years. Existing grid/work limits still apply.

Accumulation dates must be 31 December within the supplied simulation path. They cannot be
combined with `endDates`, which keeps its existing meaning of truncating the whole simulation
while retaining fixed plan dates. In accumulation mode, the annual start moves to the next
1 January; monthly contributions follow their existing earlier cutoff or the moved annual start.
Explicit one-off flows keep their original dates and are omitted only when after the case's
final simulation end.

Coordinates retain the actual simulation `endDate` and also carry `accumulationEndDate`.
Results are grouped by those endpoints, and the report labels both phases. The new
`ACCUMULATION_END_DATE` transition compares adjacent accumulation horizons with the same
withdrawal duration and rate shifts. Such a change also changes contributions and market exposure
time; it is not an isolated comparison of tax rates.

For annual plans the map's preferred strategy and threshold brackets follow the selected
objective. Terminal-value metrics remain separately available. Sample counts continue to
describe a finite grid, without probabilities or a guarantee about outcomes between samples.
