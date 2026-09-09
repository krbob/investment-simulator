# Investment Simulator

A small, stateless Kotlin backend for comparing the same accumulating global equity exposure
inside a Polish taxable brokerage account and an OKI. It starts with deterministic accounting:
FIFO purchases, contributions, net withdrawals, migration costs and calendar-year taxes.

The simulation engine is shared by the CLI, HTTP API, sensitivity grids and seeded Monte Carlo.
Portfolio remains the source of investor transactions. This project stores no canonical portfolio
and does not execute trades.

## Run

Requires JDK 21. The repository includes a checksum-pinned Gradle wrapper.

```sh
./gradlew test installDist
build/install/investment-simulator/bin/investment-simulator analyze-portfolio examples/portfolio-analysis.json > analysis.local.json
build/install/investment-simulator/bin/investment-simulator compare examples/migration.json > result.json
build/install/investment-simulator/bin/investment-simulator serve
```

The server listens on `127.0.0.1:8080` by default. `HOST` and `PORT` override that address.
It has no authentication or persistence; keep it on a trusted interface.

```sh
curl -sS http://127.0.0.1:8080/v1/strategy-comparisons \
  -H 'Content-Type: application/json' \
  --data-binary @examples/migration.json
```

All money in JSON is a **decimal string**, such as `"200000.00"`. Rates are fractions:
`0.0085` means 0.85%, `0.19` means 19%. Dates are ISO calendar dates. Unknown input fields
are rejected except within the imported upstream Portfolio DTOs. HTTP JSON bodies are limited
to 2 MiB. `compare -`, `import-portfolio -` and `analyze-portfolio -` read stdin.

The [OpenAPI contract](openapi/investment-simulator-v1.json) is the endpoint and schema reference.
It is generated from the same serializers used at runtime, exposed at `/openapi.json`, and
checked against the committed snapshot by tests. To regenerate after a DTO change:

```sh
./gradlew --quiet run --args=openapi > openapi/investment-simulator-v1.json
```

## What it compares

- Keep existing positions and route new contributions to taxable brokerage, OKI or a split.
- Sell a specified fraction of one account and repurchase on the other at the start.
- Fund net withdrawals and taxes from cash, then taxable first, OKI first or proportionally.
- Separate the accumulation period from withdrawals using `contributionUntil` and `withdrawalFrom`.
- Start annual percentage withdrawals from the current portfolio after accumulation, while
  keeping the rest invested throughout a separate withdrawal period.
- Compare terminal market value and real value after hypothetical liquidation, all outstanding
  taxes and trading costs; report withdrawal shortfalls separately.

For a migration, the entire sale proceeds after trading fees are repurchased immediately.
The sale's gain enters that year's PIT calculation; its tax is paid the following April.
Consequently, a migration of an appreciated portfolio never silently resets its tax basis for free.

The `initialTransfer` result distinguishes realized gain, estimated additional PIT, net proceeds
and the repurchased position. Its tax estimate can change when later sales in the same year
realize losses. `liquidationTaxPln` is the **additional** terminal tax adjustment and can be
negative when a hypothetical loss offsets gains already assessed for the final year.

Strategies share identical household contributions, spending rules and market paths. Fixed
spending requests are identical; percentage-based spending varies with each strategy's assets.
Tax is funded inside that budget; avoided tax therefore remains invested or in the cash buffer
without adding a second artificial "tax saving" contribution.

## Annual withdrawals after accumulation

Set `annualWithdrawalPlan` to `{ "startDate": "2047-01-01", "rate": 0.04 }` to withdraw 4% of
each strategy's **current** portfolio every 1 January. The base includes taxable equity, OKI
equity and outside cash. The requested amount is net household spending; taxes and fees are
funded additionally inside the portfolio. Monthly contributions stop before that date.

`endDate` is the end of the entire simulation, including the withdrawal period. There is no
liquidation at retirement. The [retirement example](examples/retirement.json) accumulates for
20 years, then withdraws for 30 years; the amount is recalculated each year and can fall.

```sh
build/install/investment-simulator/bin/investment-simulator compare \
  examples/retirement.json > retirement.local.json
build/install/investment-simulator/bin/investment-simulator sensitivity \
  examples/retirement-sensitivity.json > retirement-sensitivity.local.json
python3 scripts/render-sensitivity.py \
  --input retirement-sensitivity.local.json --output retirement.local.html
```

The sensitivity example compares 10 and 20 years of accumulation, each followed by 30 years of
withdrawals. The report separates accumulation end from simulation end and shows annual nominal
and real withdrawals. With this policy, `AUTO` ranks cumulative real spending plus real residual
wealth; terminal wealth remains available separately. This is an explicit comparison objective,
not a guaranteed-income or retirement-safety assessment. Read the [retirement guide](docs/retirement-withdrawals.md).

## Scope and methodology

This research MVP evaluates deterministic paths and sampled annual return paths. It uses one
taxpayer, one taxable account, one OKI and one
synthetic accumulating global equity exposure in PLN. The global ETF case has **no OKI asset
allowance**. Supplied equity returns already include fund costs and currency effects; only
broker trading fees are applied separately. The rate schedule is supplied explicitly, including
the status `ESTABLISHED` or `ASSUMED`; the engine does not independently verify that status.

The example uses 7% returns, 2.5% inflation and 0.85% future OKI rates as illustrative assumptions,
not forecasts. The 2027 OKI rate is fixed by the enacted law; subsequent example rates are assumed.

Read [financial methodology](docs/financial-methodology.md) before interpreting results. It specifies
the event order, daily OKI base, FIFO and rounding, annual tax payment dates, initial cash buffer,
partial-year convention and terminal netting. Important limits include no prior-year loss
carryforwards, calibrated forecasts, within-year volatility, intraday trading, multiple instruments,
broker FX spreads, or automatic selection of a globally optimal policy. Ranking only compares
the supplied strategies on each supplied or sampled path. Keep the input JSON alongside the result for replay.

## Monte Carlo income analysis

The `monte-carlo` command and `POST /v1/monte-carlo-analyses` evaluate all candidate strategies
on the same seeded annual return paths. The report shows real annual income P10/P50/P90,
individual trajectories, declines of at least 25%/50% from the first real annual payment,
and optional years below a real income floor. Residual wealth, cumulative real withdrawals and
paired objective advantage over the baseline remain separate.

```sh
build/install/investment-simulator/bin/investment-simulator monte-carlo \
  examples/monte-carlo.json > monte-carlo.local.json
python3 scripts/render-monte-carlo.py \
  --input monte-carlo.local.json --output monte-carlo.local.html
```

The synthetic example runs 100 paths with 20 years of accumulation and 30 years of annual
4% withdrawals. Its lognormal return model and volatility are explicit, uncalibrated assumptions;
inflation and OKI rates stay on their supplied schedules. Annual quantile bands are marginal
summaries, not a single trajectory or guarantees. `monte-carlo-path` resolves a sampled path for
exact `compare` replay. See the [Monte Carlo guide](docs/monte-carlo.md) for parameters, limits,
income denominators, paired comparisons and reproducibility.

## Portfolio integration

`analyze-portfolio` combines captured Portfolio data with an explicit plan and compares five
strategies: leave existing positions and direct new money to taxable brokerage or OKI, or migrate
25%, 50% or 100% of the taxable position to OKI. All use the same withdrawal order. This is a fixed
candidate set; custom splits and reverse migrations remain available through `compare`.

The adapter reconstructs FIFO lots, reconciles quantities and cash, and requires verified PLN
purchase costs where accounting FX cannot establish tax cost. Missing tax state is unknown,
even on 1 January. The workflow returns `NEEDS_INPUT` with actionable `dataGaps`, or `UNSUPPORTED`
for cases outside the model, without a comparison. A `COMPLETE` report includes assumptions,
source metadata, a replayable `resolvedRequest`, and the engine's `comparison`.

To capture from a running Portfolio API and analyze in one command, first copy the settings
template and fill in your account/instrument selection, plan and verified tax inputs:

```sh
cp examples/portfolio-analysis-settings.json analysis-settings.local.json
python3 scripts/analyze-portfolio.py \
  --base-url http://127.0.0.1:18082 \
  --settings analysis-settings.local.json \
  --output analysis.local.json
```

The template deliberately leaves tax state unknown; it cannot produce a recommendation unchanged.
CLI exit code `0` means a complete comparison, `3` means a saved report with data gaps, and `2`
means malformed input or execution failure. No Python dependencies are needed. The equivalent
offline HTTP endpoint is `POST /v1/portfolio/analyses`; the HTTP API does not fetch remote data.

The lower-level `import-portfolio` command and `/v1/portfolio/snapshots` endpoint remain available
for mapping only. See the [integration guide](docs/portfolio-integration.md) for settings, tax-state
examples, valuation dates, replay, authentication and the capture consistency boundary.

## Project structure

| Path | Responsibility |
| --- | --- |
| `engine` | Pure simulation inputs, accounting, comparison and financial tests |
| `portfolio-adapter` | Portfolio mapping, data-gap checks and scenario construction |
| `src/main` | CLI, Ktor transport, generated OpenAPI and research entry point |
| `examples` | Synthetic comparison/analysis inputs and live-capture settings template |
| `scripts` | Data capture, integrated analysis and research plotting helpers |

The engine does not call HTTP, read a database or fetch a forecast. Live data providers can prepare
the same versioned inputs later. Keeping this a separate repository lets Portfolio consume its
results without mixing historical read models with forward tax projections.

## Research experiments

The `sensitivity` command and `POST /v1/sensitivity-analyses` evaluate a bounded grid around an
existing comparison request, including the `resolvedRequest` returned by Portfolio analysis.
Each cell uses the same accounting engine. Additive return/inflation shifts and shifts to
**ASSUMED** OKI rates preserve the supplied annual path; **ESTABLISHED** OKI rates stay fixed.
Shorter horizons retain the original dates of the household plan.

```sh
build/install/investment-simulator/bin/investment-simulator sensitivity \
  examples/sensitivity.json > sensitivity.local.json
python3 scripts/render-sensitivity.py \
  --input sensitivity.local.json --output sensitivity.local.html
```

Open the standalone HTML locally for a selectable horizon/inflation slice, a strategy map,
per-cell financial details, per-horizon strategy counts and observed transition brackets.
The report requires no external services or Python packages. Counts describe sampled grid
cells, not probabilities; brackets are not interpolated exact break-even rates.
See the [sensitivity guide](docs/sensitivity-analysis.md) for axes, limits and exact cell replay.

### Fixed research sweeps

After `installDist`, generate the three parameter sweeps with the JVM entry point:

```sh
java -cp 'build/install/investment-simulator/lib/*' \
  net.bobinski.investmentsimulator.research.ExperimentsKt docs/research
python3 -m venv .venv-research
.venv-research/bin/python -m pip install -r requirements-research.txt
.venv-research/bin/python scripts/plot-experiments.py docs/research
```

The plotting helper uses an isolated Python environment with Matplotlib.
The CSV files remain usable without Python. The [research notes](docs/research/README.md) describe
the comparison and assumptions. Those plots are sensitivity experiments, not investment forecasts.

## Validation

```sh
./gradlew test
python3 -m unittest discover -s scripts -p 'test_*.py' -v
```

Tests include independently calculated migration PIT, same-year loss netting, FIFO, tax-payment
dates, taxes financed by sales, daily OKI ownership denominators, actual same-day cash round trips,
tax-base rounding, budget conservation, Portfolio reconciliation, data-gap handling, exact replay
and HTTP/CLI contracts. GitHub Actions runs these checks, builds the distribution, and exercises
the packaged CLI against synthetic examples, including sensitivity replay and HTML report generation.
Sensitivity tests cover rate preservation, horizons, feasibility, sampled transitions and runtime
bounds. Monte Carlo tests cover seeded replay, zero-volatility equivalence, paired outcomes,
income diagnostics, quantiles and batch validation. CI uses no Portfolio credentials or investor data.
