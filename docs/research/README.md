# Deterministic migration sensitivity

Generate CSV data with the `ExperimentsKt` JVM entry point described in the root README. The
plotting helper renders PNG and SVG heatmaps from those CSVs. Every cell compares keeping the
starting portfolio on taxable brokerage with selling it all and repurchasing on OKI at the start.
Both scenarios use the same market path, budget, tax-payment conventions and liquidation metric.

Common illustrative assumptions: PLN 200,000 initial value, 7% nominal annual equity return
after fund costs, 2.5% inflation, 0.1% fee on each purchase/sale, no external contributions or
initial cash. The OKI profile has no asset allowance. Taxes are funded from the portfolio.
All horizons run from 1 January 2027 through 31 December of their final year.

| CSV | Varied parameters | Other assumptions |
| --- | --- | --- |
| `horizon-rate.csv` | Horizon and constant OKI rate | Acquisition basis equals initial value; no spending |
| `horizon-gain.csv` | Horizon and initial unrealized-gain fraction | Constant assumed OKI rate 0.85%; no spending |
| `withdrawal-start-amount.csv` | Years until withdrawals begin and monthly net amount in initial PLN | 30-year horizon, 40% initial unrealized gain, constant assumed OKI rate 0.85%, CPI-indexed spending |

Generated figures: [horizon and rate](horizon-rate.png), [horizon and existing gains](horizon-gain.png),
[withdrawal timing and amount](withdrawal-start-amount.png). Each also has an adjacent SVG for export.

Varying the 2027 rate in the first experiment is a deliberate counterfactual. Constant rates
thereafter are assumptions rather than forecasts of the NBP-linked statutory schedule.

Positive `advantage_real_pln` means higher real terminal liquidation value for migration. It
does not by itself establish a feasible policy: the CSV includes shortfalls and overdue taxes
for both strategies. On the withdrawal chart, cells with any such shortfall or terminal insolvency are marked `X`
and excluded from the color scale. Spending shortfalls are nominal cumulative amounts.

These experiments locate boundaries under fixed assumptions. They do not optimize a dynamic
strategy, quantify forecast uncertainty, or justify a transaction independently of the user's
portfolio, actual broker costs and tax history.
