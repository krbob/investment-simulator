# Monte Carlo retirement income

`monte-carlo` and `POST /v1/monte-carlo-analyses` evaluate the supplied strategies on common,
seeded annual market paths. Each path uses the same accounting engine as `compare`: migrations
realize taxable gains, PIT remains invested until settlement, OKI tax uses daily values, and
annual withdrawals pay a percentage of the current portfolio. No financial rules are replaced
with a shortcut or an average tax rate.

This is a bounded, synchronous research model. Its return parameters are explicit inputs,
without historical calibration. Results describe outcomes under those assumptions; the service
does not infer a single investment recommendation from aggregate win counts.

## Run and inspect

The [synthetic example](../examples/monte-carlo.json) uses the retirement plan: 20 years of
accumulation followed by 30 annual withdrawals of 4% of current assets. It has 100 paths, seed
42, arithmetic expected nominal PLN return 7%, annual log-return volatility 0.18, fixed CPI
2.5%, and the supplied OKI schedule. Its PLN 40,000 annual real income floor is illustrative.
Neither the return parameters nor the floor describe a particular investor or a calibrated forecast.

```sh
./gradlew installDist
build/install/investment-simulator/bin/investment-simulator monte-carlo \
  examples/monte-carlo.json > monte-carlo.local.json
python3 scripts/render-monte-carlo.py \
  --input monte-carlo.local.json --output monte-carlo.local.html
```

Open the standalone HTML locally. Select a strategy and a sampled path to inspect annual
income, marginal P10/P50/P90 bands, exact monetary tables, income declines and the remainder.
The report makes no network requests and needs no Python packages. Local reports can contain
portfolio data; the renderer writes them with owner-only permissions. Generated private input,
result and HTML files should keep the ignored `.local.json` / `.local.html` suffixes.

The equivalent HTTP request is:

```sh
curl -sS http://127.0.0.1:8080/v1/monte-carlo-analyses \
  -H 'Content-Type: application/json' --data-binary @examples/monte-carlo.json
```

## Inputs

| Field | Meaning |
| --- | --- |
| `baseRequest` | Complete `ComparisonRequest`, including all annual assumptions and candidate strategies |
| `seed` | Required integer from 0 through 281474976710655; stable 48-bit random seed |
| `annualLogReturnVolatility` | Required finite number from 0 through 0.5; standard deviation of annual log gross returns |
| `pathCount` | 1–256 paths, default 100; also subject to work limits |
| `minimumRealAnnualIncomePln` | Optional nonnegative decimal string, in simulation-start purchasing power; omitted/null means not assessed |

The base must include an annual withdrawal policy whose first payment occurs within the
simulation, and must end on 31 December. At least one payment is required; a one-payment
history has no eligible relative-income-decline observations. A zero percentage rate is
accepted and produces zero-income observations, not a claim of safe income.

The floor is a diagnostic, not an additional spending request. Setting it does not alter the
withdrawal policy, household budget, feasibility or comparison objective. The maximum floor
is PLN 10^15 with at most 12 fractional digits, matching other engine input amount limits.

An integrated Portfolio analysis with status `COMPLETE` supplies a `resolvedRequest` that can
be used as `baseRequest`; set its annual policy and supply assumptions through the desired
withdrawal end first. The adapter's explicit tax-state and verified purchase-cost requirements
still apply. Monte Carlo needs no separate connection to Portfolio or copy of its transaction store.

## Return model

For each year with supplied arithmetic expected simple return `m` and log-return volatility `s`:

```text
Z ~ standard normal, independently for each year and path
R = exp(log(1 + m) - s²/2 + sZ) - 1
```

The correction `-s²/2` makes the model's expected simple return equal to `m`. This follows the
[lognormal mean formula](https://www.itl.nist.gov/div898/handbook/eda/section3/eda3669.htm).
Here `m` is not a CAGR or median return, and `s` is not the standard deviation of simple returns.
The choice of this distribution is an MVP modeling assumption, not evidence that it describes
future global equity returns. At `s=0`, the engine receives the original annual returns exactly.

Shocks are independent; their mean can vary with the supplied annual schedule. Inflation and
OKI rates, including their declared `ESTABLISHED`/`ASSUMED` statuses, remain unchanged.
Returns are nominal PLN after fund costs and currency effects. No separate FX process is added.
The accounting engine still spreads each sampled annual return smoothly across calendar days.
Thus this version models annual sequence effects but omits within-year volatility around
monthly purchases, sales and daily OKI assessments. For a partial first year it uses only the
remaining days' portion of that year's sampled full-year factor.

The model also omits fat tails, serial dependence, regime changes and parameter uncertainty.
Use deterministic sensitivity scenarios alongside it to examine alternative inflation, OKI,
expected-return and volatility assumptions. Do not treat a narrow simulated band as evidence
that uncertainty outside the model is small.

## Reproducibility and validation

`monteCarloVersion` identifies generation and aggregation semantics; `engineVersion` and
`taxRulesVersion` identify the accounting rules. Version 0.1.0 uses
[`java.util.Random`](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/Random.html),
whose specified algorithm reproduces the same sequence for a seed and method-call sequence.
The seed range matches its 48-bit internal seed and is exactly representable in JSON clients.

For each path in ID order, and each year in ascending order, two `nextDouble()` calls give:

```text
u1 = 1 - nextDouble()      # (0, 1], so log(0) cannot occur
u2 = nextDouble()          # [0, 1)
Z  = sqrt(-2 * log(u1)) * cos(2 * PI * u2)
```

Transcendental functions use `StrictMath`; the second normal is not cached. Both random draws
are consumed even at zero volatility. Increasing the path count preserves earlier paths for
the same seed and year schedule. Changing strategy order cannot change market paths. Changing
the number of modeled years changes the later paths' random-number positions.

All paths are generated and validated before any financial comparison. The existing engine
accepts annual simple returns from -0.99 through 5.0. A generated value outside that range
rejects the **entire request**, naming the path and year. There is no clipping, redraw or partial
report. Lognormal returns are unbounded above, so some valid parameter sets/seeds cannot be
evaluated within this engine range. Repeatedly changing seeds until all paths pass would bias
the sample by conditioning away its extremes; a different supported model/range would be needed
to study such settings properly.

Work limits are 256 paths, 10,000,000 inclusive calendar strategy-days and 500,000 opening-lot
replays (`paths × strategies × opening lots`). The 100-path example uses 9,131,500 strategy-days.
The existing 50-year, 32-strategy, input-size and amount limits still apply. These are per-request
budgets, not a multi-user admission controller. The service has no queue, cache or authentication;
keep it on a trusted interface. Batch ledgers are disabled to bound the response.

Save the normalized request from a result and replay any path through `compare`:

```sh
python3 - <<'PY'
import json
from pathlib import Path
report = json.loads(Path('monte-carlo.local.json').read_text())
Path('monte-carlo-request.local.json').write_text(json.dumps(report['request'], indent=2) + '\n')
PY
build/install/investment-simulator/bin/investment-simulator monte-carlo-path \
  monte-carlo-request.local.json p0001 > path.local.json
build/install/investment-simulator/bin/investment-simulator compare \
  path.local.json > path-result.local.json
```

`monte-carlo-path` validates the whole batch under the same budgets. Its output is the exact
comparison input with `includeLedger=false`; enable the ledger in that resolved request when
needed. Full yearly accounting and initial-transfer details are available from `compare`.
The batch result also retains every path's annual returns and annual payment histories.

## Reading distributions and counts

P10/P50/P90 use empirical **nearest rank**: for `n` sorted samples and fraction `p`, take the
one-based observation `max(1, ceil(p*n))`. No interpolation is used; values come directly from
rounded engine results. With 100 paths, P10 is the tenth sorted observation. A small sample
has coarse tail resolution. These are outcome quantiles, not confidence intervals.

Annual bands summarize each payment date independently. Following P10 across all years does
not reconstruct one possible market/income path; the selected individual path provides that
coherent trajectory. Every path, including infeasible and zero-income outcomes, contributes to
income, cumulative real withdrawals, residual-wealth and objective distributions.

`objectiveAdvantageVsBaselinePln` compares a strategy and baseline on the **same** market path.
Only paths where both are feasible enter that paired advantage distribution; its explicit
sample count can be smaller than `pathCount`. It is null if there are no comparable pairs.
Do not subtract independent strategy quantiles to estimate the distribution of differences.

Preferred counts use the existing comparison objective, minimum-advantage threshold and baseline
tie policy. Highest-objective counts omit that threshold. All-infeasible paths have no winner.
With the annual policy, `AUTO` values cumulative real spending plus residual real wealth; the
[retirement guide](retirement-withdrawals.md) explains that explicit comparison convention.
Always inspect the income and remainder separately rather than choosing a strategy solely
from its win count. There is no aggregate recommendation field.

## Income diagnostics

Annual income is the actual net annual percentage payment, deflated to purchasing power at
simulation start. One-off withdrawals do not enter annual income diagnostics; they do enter
`realWithdrawalsPaidPln` and the total-benefit objective.

Relative declines compare each later real annual payment with the first real annual payment
of that same strategy and path. A path is eligible only with at least two payments and a
positive first payment. A later payment at most 75% or 50% of the first counts as a decline
of at least 25% or 50%, including equality. This is not drawdown from a rolling peak.

Reports expose the eligible denominator, zero-first-payment count, insufficient-history count
and paths with any zero payment. Zero-first and insufficient-history counts can overlap.
Ineligible paths have null relative-decline flags, not false; they remain in all-path income
distributions. A shrinking percentage withdrawal can be fully funded while providing very
little income, so feasible does not mean adequate.

If a real income floor is provided, payments **strictly below** it count toward years below
the minimum. Equality meets the floor. The result includes each path's number of below-floor
years and longest consecutive run, each date's below-floor count, and the number of paths with
at least one such year. These floor diagnostics use all paths, including infeasible paths.
Without a floor they are null rather than zero.
