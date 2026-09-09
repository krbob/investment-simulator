# Portfolio integration

Portfolio remains the source of truth for investor accounts and transactions. The simulator
consumes a read-only snapshot and returns a proposed scenario; it does not create trades, transfer
assets, or modify Portfolio settings. No change to the Portfolio repository is required.

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

Local verification uses synthetic data and a loopback fixture server only:

```sh
./gradlew :portfolio-adapter:test
python3 -m unittest discover -s scripts -p 'test_capture_portfolio.py' -v
```
