# Financial methodology

## Scope

The engine models the location of one accumulating global equity exposure. It does not compare
different assets. Both accounts use the same effective nominal annual return, expressed in PLN
after fund costs and currency effects. An annual factor is converted to a smooth daily compound
factor using the actual number of calendar days in the year. This is deliberately a deterministic
scenario; it contains no volatility or probability claim.

Amounts are computed with `BigDecimal` and DECIMAL128 arithmetic. Daily growth factors are
constructed from floating-point exponentiation once per year; results are projections, not a
broker tax statement. Output amounts are rounded to cents. Indexed household cash flows are
rounded to cents when they occur. Taxable annual bases and taxes are each rounded to whole PLN.

## Daily order

1. Add scheduled household contributions to outside-OKI cash.
2. On the start date only, execute the strategy's initial sale, cash transfer and repurchase.
3. Pay due taxes from cash; if necessary, sell assets in the strategy's withdrawal order.
4. Pay requested net household withdrawals using the same funding rule. Record unmet spending
   as a shortfall rather than borrowing or creating money.
5. Invest the unused part of today's contribution in the requested split. Opening cash remains
   an interest-free spending/tax buffer; it is not automatically invested.
6. Apply the common daily equity return and the daily CPI factor.
7. Accumulate OKI's end-of-day equity value and actual same-day deposit/withdrawal correction.
8. At calendar year-end, assess PIT and OKI tax. Before a subsequent simulated year, create
   dated liabilities and clear the year-to-date accumulators.

One-off cash flows occur on their given dates. Monthly flows occur on a day between 1 and 28.
`contributionUntil` is inclusive. `withdrawalFrom` applies from that date onward. Inflation-indexed
amounts are expressed in purchasing power at `startDate`, including withdrawals beginning later.
Two cash flows on the same day are netted in the household cash account before unused new money
is invested. They create an OKI round trip only if money actually crosses the OKI boundary.

## Taxable account and migrations

Opening lots carry current value, verified PLN acquisition cost and acquisition date. Lots are
consumed FIFO by acquisition date; input order breaks ties on the same date. This is an explicit
single-account policy. Equal exposure is represented by units of a shared price index rather
than by ticker-specific quantities. New purchase cost includes its trading fee. Sale income is
net proceeds after its trading fee less the cost allocated to the sold units.

Realized gains and losses are combined within the calendar year. The positive resulting base
is taxed at `capitalGainsTaxRate` (default 19%). There is no immediate refund for a loss and no
automatic use of losses from earlier years or income from outside the selected account.
PIT is settled on 30 April of the next year. A sale to fund that payment creates a gain or loss
in the year of payment, with its own later settlement; there is no immediate recursive gross-up.
[PIT sales guidance](https://podatki.gov.pl/podatki-osobiste/pit/informacje-podstawowe/co-jest-opodatkowane/zbycie-akcji).

Taxable-to-OKI migration is a taxable sale, a cash deposit and a purchase. Full proceeds after
fees are reinvested immediately; the PIT liability survives until payment. OKI-to-taxable
migration sells the OKI position and establishes a new taxable purchase cost. No free in-kind
transfer into an OKI is assumed. The legal definitions distinguish cash contributions/withdrawals
from transfers between OKI accounts. [OKI Act, Article 2](https://eli.gov.pl/api/acts/DU/2026/1098/text/T/D20261098L.pdf).

The additional tax shown for the initial transfer is the change in that year's estimated PIT
at the time of sale. Later gains or losses can alter it. It is a liability estimate, not an
immediate cash debit. Keeping the money invested until settlement makes the cost of tax deferral
explicit and preserves the shared household budget.

## OKI

This profile assumes the global equity ETF does not meet the qualification for the asset allowance.
The simulator never subtracts PLN 100,000 from this exposure. Eligibility for the allowance is
distinct from eligibility to hold an instrument on an OKI; the provider's actual product range
is outside this model. [OKI Act, Article 26](https://eli.gov.pl/api/acts/DU/2026/1098/text/T/D20261098L.pdf).

For each calendar year, the modeled average base is:

```text
(sum of end-of-day OKI values + sum of min(actual daily deposits, actual daily withdrawals))
/ calendar days of OKI ownership in that year
```

An empty account remains owned. Opening a funded account in July therefore differs from depositing
into an account already owned since January. An account is opened on the first simulated purchase
unless `okiOpenedOn` identifies an existing one. `OpeningTaxState.okiValueDaysPln` supplies the
pre-simulation numerator when starting during a year. The rate applies to the rounded average
base; settlement occurs on 31 May of the following year. [OKI Act, Articles 23–25 and 29](https://eli.gov.pl/api/acts/DU/2026/1098/text/T/D20261098L.pdf).

The explicit annual rate schedule avoids pretending that the current rate stays fixed. The 2027
rate is 0.85%. Later statutory rates depend on the previous 31 October NBP reference rate, subject
to the floor and rounding rule. The engine consumes supplied rates, including counterfactual
rates for research; `ESTABLISHED` is a caller declaration, not verification by the engine.
[OKI Act, Articles 25 and 43](https://eli.gov.pl/api/acts/DU/2026/1098/text/T/D20261098L.pdf).

The two-stage rounding of base and tax follows the general annual-tax convention; it is distinct
from interest withholding rounding. [Tax Ordinance, Article 63](https://eli.gov.pl/api/acts/DU/2026/622/text/U/D20260622Lj.pdf).

## Horizon and comparison

The result distinguishes assets at market value, outstanding taxes, extra tax on hypothetical
liquidation, sale fees and real terminal net value. Already paid taxes have reduced assets and
are not deducted again. The final year's hypothetical sale is netted against that same year's
realized gains and losses; a disposal loss may make `liquidationTaxPln` negative.

Terminal liquidation is a valuation convention at the final price. It adds no extra trading day
and keeps the accumulated OKI assessment unchanged. For a final partial calendar year, the
denominator assumes OKI ownership ends at the horizon. Maintaining an empty account until
31 December would change that denominator and is not modeled. **Use a 31 December horizon for
full-year comparisons**, as in the supplied experiments.
The integrated `analyze-portfolio` workflow enforces this horizon and returns `UNSUPPORTED`
for a final partial year or a declared nonzero prior-year loss carryforward. It also requires
explicit opening tax state, including on 1 January; see the [integration guide](portfolio-integration.md).

Current-year liabilities remain payable even when their due date is beyond the simulation. A
negative terminal net value is preserved; insolvency is never clamped to zero. Unmet spending
and overdue tax identify an infeasible strategy, as does a negative terminal value after liabilities
and hypothetical liquidation costs. Among feasible strategies, ranking maximizes
real terminal net value. The baseline wins ties and remains preferred when an improvement does
not exceed `minimumAdvantagePln` in start-date purchasing power. If no strategy is feasible,
the result reports the shortfall instead of presenting an investment recommendation as feasible.

This first version does not estimate probabilities, parameter uncertainty or future changes to
law. It omits carryforward losses, other investment income, foreign investor-level withholding,
FX transaction spreads, account fees, late-payment interest and holiday-shifted deadlines.
Paying on 30 April/31 May is an explicit earlier-payment convention when a statutory deadline
would move to a later working day. Tax cash comes exclusively from the modeled household budget.
