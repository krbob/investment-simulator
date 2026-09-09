# Portfolio integration

Portfolio remains the source of truth for investor accounts and transactions. The simulator
consumes a read-only snapshot and returns a proposed scenario; it does not create trades, transfer
assets, or modify Portfolio settings. No change to the Portfolio repository is required.

## Capture, plan and compare

The integrated workflow accepts `{ "portfolio": <snapshot bundle>, "plan": <analysis plan> }`.
The portfolio supplies opening positions; the plan supplies dates, annual return/inflation/OKI-rate
assumptions, contributions, withdrawals and fees. The same Kotlin service handles the CLI and
`POST /v1/portfolio/analyses` without network access.

Run the complete synthetic example:

```sh
./gradlew installDist
build/install/investment-simulator/bin/investment-simulator analyze-portfolio \
  examples/portfolio-analysis.json > analysis.local.json
```

It uses a fictitious 31 December 2026 capture, a 2027–2036 horizon and explicitly supplied opening
tax state. Its outstanding PLN 40 PIT corresponds to the fixture's 2026 FIFO gain of PLN 210.
The service does not derive that liability: it must be provided as verified input. The remaining
position is worth PLN 120 with a PLN 60 FIFO cost and PLN 1,150 cash; these small synthetic amounts
make the imported ledger easy to inspect. Returns and cash-flow plans are illustrative.

For one-command capture from a live API, copy the separate settings template and edit the local file:

```sh
cp examples/portfolio-analysis-settings.json analysis-settings.local.json
python3 scripts/analyze-portfolio.py \
  --base-url http://127.0.0.1:18082 \
  --settings analysis-settings.local.json \
  --output analysis.local.json
```

Settings contain exactly `portfolio` and `plan`. The `portfolio` object contains `selection` plus
optional `verifiedPurchaseCostsPln`, `okiOpenedOn` and `openingTaxState`; capture fills in `snapshot`,
`holdings` and `accountSummaries`. Replace the synthetic IDs and plan. The template omits tax state
and its confirmation date so that unknown tax history cannot silently become zero.

The helper uses the installed local CLI by default (`--simulator` overrides its path), passes the
captured bundle through stdin, and atomically saves the report. Portfolio's session cookie is
used only for capture and removed from the simulator subprocess environment. Each GET defaults
to a 15-second timeout (`--timeout`, maximum 60); the local simulation has a five-minute limit.
Failures preserve an existing output file. All examples use ignored `*.local.json` paths for
investor inputs and results. The HTTP equivalent accepts the complete offline request:

```sh
curl -sS http://127.0.0.1:8080/v1/portfolio/analyses \
  -H 'Content-Type: application/json' \
  --data-binary @examples/portfolio-analysis.json
```

| Result status | CLI / capture helper | HTTP | Meaning |
| --- | --- | --- | --- |
| `COMPLETE` | Exit 0; JSON report saved | 200 | Comparison calculated for the supplied scenario |
| `NEEDS_INPUT` | Exit 3; JSON report saved | 200 | Correct or supply the listed data before comparing |
| `UNSUPPORTED` | Exit 3; JSON report saved | 200 | The snapshot or plan exceeds current model scope |
| Malformed input / execution failure | Exit 2; helper preserves existing report | 400 for invalid request | No analysis report |

`dataGaps` contains a code, JSON field path, explanation, and transaction/account IDs where useful.
For example, each selected foreign taxable purchase without a verified original cost is listed as
`MISSING_VERIFIED_PURCHASE_COST`, including fully consumed purchases needed for FIFO replay.
Preflight collects identifiable gaps together; resolving them can reveal further mapping or plan
validation issues. Blocked results contain neither `resolvedRequest` nor `comparison`.

Successful results include source provenance, explicit assumptions, `strategyDescriptions`,
`resolvedRequest`, and `comparison`. `COMPLETE` means the calculation completed; inspect the
comparison's feasibility and explanation before treating any candidate as an actionable choice.
To reproduce the calculation independently of Portfolio or future prices:

```sh
python3 - <<'PY'
import json
from pathlib import Path
report = json.loads(Path("analysis.local.json").read_text())
assert report["status"] == "COMPLETE"
Path("resolved-request.local.json").write_text(json.dumps(report["resolvedRequest"], indent=2) + "\n")
PY
build/install/investment-simulator/bin/investment-simulator compare \
  resolved-request.local.json > replay.local.json
```

The candidate set keeps current positions and routes new money to taxable brokerage or OKI,
then compares moving 25%, 50% or 100% of existing taxable equity to OKI with new money also going
there. All candidates share the plan's withdrawal order. Migration candidates are omitted when
there is no taxable equity. Existing OKI assets remain in place. Use `compare` with custom strategies
for reverse transfers, other splits or different withdrawal orders; this workflow does not search
for a globally optimal policy.

## Opening dates and tax state

`plan.startDate` must follow the capture's calendar date in Europe/Warsaw and be in 2027 or later.
The default `REQUIRE_PREVIOUS_DAY_VALUES` policy requires both the capture and every selected
holding's observation date to be the calendar day before the start. A weekend or older quote can
therefore require an explicit decision even if Portfolio labels it `VALUED`.

For a what-if scenario using today's balances at a future start, explicitly set
`openingValuationPolicy` to `USE_CAPTURED_VALUES_UNCHANGED`. This carries the observed amounts
forward unchanged; it projects no intervening prices, contributions, withdrawals or taxes.
Tax state is still supplied separately for the actual simulation start. Neither policy allows
backdating the capture or treating a capture as the opening of its own calendar day.

Supply `portfolio.openingTaxState` and set `plan.taxStateAsOfDate` to the start date only after
checking that it describes the beginning of that day. For the synthetic example:

```json
{
  "realizedGainPln": "0",
  "okiValueDaysPln": "0",
  "liabilities": [
    { "kind": "CAPITAL_GAINS", "taxYear": 2026, "dueDate": "2027-04-30", "amountPln": "40" }
  ]
}
```

Omission means unknown; `{}` explicitly declares zero year-to-date gains, zero accumulated OKI
value-days and no outstanding liabilities. This is required even on 1 January, when prior-year
tax can still be payable. Year-to-date accumulators must be zero on 1 January; prior-year taxes
belong in `liabilities`. For a midyear start, provide gains and OKI value-days accumulated before
that day. A selected existing OKI also requires its actual `okiOpenedOn` date.

Declare any available prior-year loss in `plan.lossCarryforwardPln`; a positive amount returns
`UNSUPPORTED_LOSS_CARRYFORWARD` because the engine cannot apply it yet. The default zero is an
explicit scenario assumption to review. `plan.endDate` must be 31 December: the integrated workflow
rejects a final partial year rather than using the engine's partial-year OKI closure convention.

## Using the adapter

The implemented Kotlin adapter maps a caller-supplied JSON bundle. It is available through
`POST /v1/portfolio/snapshots` and the `import-portfolio` CLI command. The optional Python capture
helper reads a configured live Portfolio API and saves the bundle locally; the simulator HTTP API
does not initiate remote requests.

Run the synthetic example from the repository root:

```sh
./gradlew run --args='import-portfolio portfolio-adapter/src/test/resources/portfolio-bundle.json'
```

It returns one remaining FIFO lot worth PLN 120 with acquisition cost PLN 60, plus PLN 1,150 of
cash outside OKI. The upstream example's average acquisition-cost field is PLN 42 and is ignored.
These are synthetic amounts, not actual market prices.

To capture your own data, create a local `settings.json` containing the selection and, where
needed, verified acquisition-cost overrides or an explicit opening tax state:

```json
{
  "selection": {
    "taxableAccountId": "00000000-0000-0000-0000-000000000001",
    "instrumentId": "00000000-0000-0000-0000-000000000002"
  },
  "verifiedPurchaseCostsPln": []
}
```

Replace these example IDs with the IDs from Portfolio. An optional `selection.okiAccountId`
requires `okiOpenedOn` in the same settings object. `openingTaxState`, when supplied, is passed
through from your verified input; it is not reconstructed from historical purchases or taxes paid.

```sh
python3 scripts/capture-portfolio.py \
  --base-url http://127.0.0.1:18082 \
  --settings settings.json \
  --output portfolio-bundle.json
./gradlew run --args='import-portfolio portfolio-bundle.json'
```

The helper uses only Python's standard library. Its four GET requests have a 15-second timeout
each and a 16 MiB response limit. `--timeout` changes the per-request timeout up to 60 seconds.
It refuses HTTP redirects and URL credentials. If authentication is enabled, supply an existing
session through the `PORTFOLIO_SESSION_COOKIE` environment variable; the value is the complete
Cookie header, for example the session-cookie name followed by its value. The helper never logs
that value or includes it in the bundle. It creates no session and performs no Portfolio writes.

Capture verifies that the canonical exports agree, then atomically publishes the complete bundle.
The mapper performs the financial and scope validation afterwards. A successfully captured file
can therefore still be rejected as unsupported when imported. Source files and captured bundles
remain local; keep investor data outside committed examples.

The mapping result contains `initial`, source metadata, and explicit limitations. Copy `initial`
into a comparison request. `source.sourceAsOfDate` comes from `snapshot.exportedAt` in the Warsaw
time zone and is separate from the simulation's `startDate`. Starting a simulation later assumes
the supplied balances and tax state at that future start; the adapter does not project the gap.

The upstream objects inside the bundle accept extra Portfolio response fields. The wrapper
request and simulation input remain strict. See the generated
[OpenAPI contract](../openapi/investment-simulator-v1.json) for exact request and response schemas.

## Existing upstream API

The implementation sources below describe the upstream contract inspected for this integration.
Portfolio's generated OpenAPI remains its canonical API specification.

| Request | Data used |
| --- | --- |
| `GET /v1/portfolio/state/export` | `schemaVersion`, `exportedAt`, canonical `accounts`, `instruments`, and `transactions` |
| `GET /v1/portfolio/holdings` | Current quantity and PLN valuation for each `(accountId, instrumentId)`, plus valuation status and time |
| `GET /v1/portfolio/accounts` | Cash balances and valuation state for each account |
| `GET /v1/portfolio/withdrawal-settings` | Optional display labels; these do not establish legal account eligibility or tax rules |

The export currently uses schema version 5. It is assembled inside a database transaction and
contains all canonical entities in one response. The separate `/v1/accounts`, `/v1/instruments`,
and `/v1/transactions` reads exist but are unnecessary when an export is available.

Upstream source references:

- [Export response types](https://github.com/krbob/portfolio/blob/main/apps/api/src/main/kotlin/net/bobinski/portfolio/api/route/PortfolioOperationsModels.kt)
- [Read-model response types](https://github.com/krbob/portfolio/blob/main/apps/api/src/main/kotlin/net/bobinski/portfolio/api/route/PortfolioReadModelResponses.kt)
- [Read-model routes](https://github.com/krbob/portfolio/blob/main/apps/api/src/main/kotlin/net/bobinski/portfolio/api/route/PortfolioRouteModules.kt)
- [Transaction response types](https://github.com/krbob/portfolio/blob/main/apps/api/src/main/kotlin/net/bobinski/portfolio/api/route/TransactionRoute.kt)
- [Upstream financial methodology](https://github.com/krbob/portfolio/blob/main/docs/financial-methodology.md)

## Snapshot capture

There is no upstream endpoint that atomically returns the canonical ledger and live market
valuations together. The capture helper performs this bounded sequence:

1. Export canonical state.
2. Read holdings and account balances.
3. Export canonical state again.
4. Compare both complete canonical exports, excluding `exportedAt`. Reject the capture if any
   canonical content changed during valuation; the caller can retry.
5. Save the second export with the captured holdings, account summaries, and explicit settings.

On import, the mapper reconciles quantities and cash with this ledger before constructing a scenario.

Market prices can still have different observation times. Capture those times and the retrieval
time as provenance instead of describing the result as an atomic market snapshot. The mapper
requires the upstream holding status `VALUED`, complete market values, and observation dates
no earlier than any selected trade. It rejects `STALE`, missing, unsupported, or partial values.
An upstream `VALUED` status does not independently prove observation freshness relative to
`exportedAt`; inspect the returned `valuationDates`. Book value never replaces market value.

An offline snapshot supplied by the caller uses the same mapper without network requests. Its
required wrapper fields are `snapshot`, `holdings`, `accountSummaries`, and `selection`.

## Explicit selection and account roles

The request must identify the accounts to compare and assign each to `OKI` or ordinary taxable
brokerage. Portfolio's `Account.type` describes storage/account shape (`BROKERAGE`,
`BOND_REGISTER`, `CASH`), not the tax wrapper. Its withdrawal-planning `OKI`, `IKE`, and `IKZE`
labels carry no legal rules. Do not infer a tax role from account names or institutions.

The initial adapter supports exactly one taxable account, optionally one OKI, and one explicitly
selected accumulating equity ETF instrument ID shared by those accounts. Instrument kind
`ETF` and asset class `EQUITIES` alone do not establish that an instrument tracks the selected
broad index or accumulates distributions. Symbols such as `VWRA.L` identify a listing, not an
interchangeable tax lot. Preserve account and instrument identity during historical replay.

Selected accounts must be active brokerage accounts. Other instruments in their transaction
history, including fully sold positions, are rejected by this narrow adapter. Unsupported assets
or cash balances require a separately prepared and verified initial scenario. Unselected accounts
remain outside the comparison by explicit request selection.

## Reconstructing acquisition lots

Portfolio's equity `costBasisPln`, `averageCostPerUnitPln`, and `bookValuePln` are analytical
values. Its current read model proportionally reduces average acquisition cost when units are
sold. These fields must not be reused as a FIFO tax ledger.

Replay canonical transactions ordered by `tradeDate`, `createdAt`, and `id`, matching Portfolio's
ledger order. Keep a FIFO queue for each `(accountId, instrumentId)`:

- `BUY` creates a lot with its trade date, quantity, and validated PLN acquisition cost.
- `SELL` consumes the oldest remaining units in the same account and instrument, proportionally
  reducing a partially consumed lot's acquisition cost. Reject an oversell.
- Allocate each holding's current PLN market value across remaining lots by their quantity.
  Upstream `currentPricePln` is rounded to cents, so multiplying it by large quantities could
  introduce avoidable error. The final lot receives the allocation remainder, preserving the
  complete position value. Engine input amounts are rounded to at most 12 decimal places;
  remaining acquisition cost uses half-even rounding.
- Cash transactions affect cash but do not create equity acquisition lots.

For PLN trades, acquisition cost consists of gross purchase amount and purchase fees under the
chosen tax-rule profile. A purchase `taxAmount` must not silently become a deductible cost.
Nonzero purchase tax or ambiguous cost events need an explicit supported rule or caller-supplied
validated acquisition basis.

Foreign purchases need a documented conversion basis. A Portfolio `fxRateToPln` is an accounting
input and does not prove that the applicable tax conversion convention was followed. Do not use
today's quote or a market-data fallback to fill missing historical tax FX. The implemented
`verifiedPurchaseCostsPln` override contains the purchase transaction ID, the complete original
purchase's verified PLN `costBasisPln`, and a nonempty `source` describing verification. The mapper
then consumes that original cost through FIFO sales. Every selected taxable foreign purchase,
including a fully consumed purchase, requires an override. Foreign sale FX is not used to infer
current-year realized gains; those belong to explicit `openingTaxState` input.

Reject unsupported ledger semantics such as `CORRECTION`, unexplained `REDEEM`, share transfers,
corporate actions, or a position whose history starts after acquisition. The current canonical
transaction API does not have explicit stock-split or in-kind transfer events. Do not infer them
from a quantity mismatch or invent an acquisition date/cost to make a snapshot balance.

Historical tax paid in Portfolio does not itself describe loss carryforwards, outstanding PIT,
or OKI tax accrued earlier in the current year. Those starting liabilities and tax attributes
must be supplied separately where the simulation profile needs them.

## Cash and reconciliation

`PortfolioAccountResponse.cashBalances` supplies native currency amounts and analytical PLN book
values; `cashBalancePln` is a current aggregate valuation. Book values are not current FX quotes.
The adapter supports nonnegative PLN cash outside OKI and rejects nonzero foreign cash. It also
rejects nonzero cash inside OKI because the current engine models equity on OKI, with cash kept
outside it. Opening cash is not automatically invested. Native cash and the PLN cash aggregate
are reconciled within PLN 0.01 for upstream display rounding; the reconstructed ledger supplies
the engine's cash value. An account with no equity may have upstream status `BOOK_ONLY` if its
supported PLN cash reconciles; equity accounts require `MARK_TO_MARKET` account status.

Require every remaining reconstructed position to appear exactly once in the captured holdings,
with the same account/instrument identifiers and quantity. Require the selected account summaries
to exist and cash to reconcile with supported canonical cash flows. An inconsistency is a failed
snapshot, not a zero-valued position.

## Transport and authentication

The manual capture helper accepts its upstream base URL explicitly on the command line and appends
only the documented `/v1` routes. The simulator API accepts supplied JSON and has no remote URL
input. A future always-on client should use deployment configuration for upstream addresses.

Portfolio's optional password authentication uses a session cookie established by
`POST /v1/auth/session`; its code does not currently define a bearer-token integration contract.
A read-only adapter can use a caller-provisioned session cookie or an existing trusted deployment
where upstream authentication is disabled. Authentication and deployment configuration must be
explicit; never bypass access controls or assume an exposed instance is unauthenticated.

The adapter returns source identifiers, observation dates, acquisition-cost verification sources,
and a SHA-256 hash of its decoded request. This digest covers the mapped request fields; extra
upstream fields ignored by decoding are not included. Keep the raw bundle and the comparison
request to reproduce a result after prices, transactions, or tax assumptions change.

Automated verification uses synthetic data and a loopback fixture server only:

```sh
./gradlew :portfolio-adapter:test
python3 -m unittest discover -s scripts -p 'test_*.py' -v
```
