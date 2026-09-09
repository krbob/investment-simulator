package net.bobinski.investmentsimulator.engine

import java.math.BigDecimal
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Financial examples are calculated independently of the engine implementation. */
class SimulationEngineTest {
    @Test
    fun `global ETF has no asset allowance and first year tax is outstanding until next May`() {
        val result = simulate(
            initial = InitialPortfolio(okiValuePln = money("100000"), okiOpenedOn = "2027-01-01"),
        )

        assertMoney("100000", result.yearly.single().okiAverageTaxBasePln)
        assertMoney("850", result.yearly.single().okiTaxAssessedPln)
        assertMoney("0", result.okiTaxPaidPln)
        assertMoney("850", result.outstandingTaxPln)
        assertMoney("100000", result.marketValuePln)
        assertMoney("99150", result.netLiquidationValuePln)
    }

    @Test
    fun `opening in July uses owned days while funding an existing account retains the full year denominator`() {
        val newlyOpened = simulate(
            start = "2027-07-01",
            initial = InitialPortfolio(
                okiValuePln = money("100000"), okiOpenedOn = "2027-07-01", taxState = OpeningTaxState(),
            ),
        )
        val previouslyOpened = simulate(
            start = "2027-07-01",
            initial = InitialPortfolio(
                okiValuePln = money("100000"), okiOpenedOn = "2027-01-01", taxState = OpeningTaxState(),
            ),
        )

        assertMoney("100000", newlyOpened.yearly.single().okiAverageTaxBasePln)
        assertMoney("850", newlyOpened.yearly.single().okiTaxAssessedPln)
        // There are 184 calendar days from 1 July through 31 December 2027.
        assertMoney("50410.958904", previouslyOpened.yearly.single().okiAverageTaxBasePln)
        assertMoney("428", previouslyOpened.yearly.single().okiTaxAssessedPln)
    }

    @Test
    fun `opening tax state carries daily values from before the simulation start`() {
        val result = simulate(
            start = "2027-07-01",
            initial = InitialPortfolio(
                okiValuePln = money("100000"),
                okiOpenedOn = "2027-01-01",
                taxState = OpeningTaxState(okiValueDaysPln = money("18100000")),
            ),
        )

        assertMoney("100000", result.yearly.single().okiAverageTaxBasePln)
        assertMoney("850", result.yearly.single().okiTaxAssessedPln)
    }

    @Test
    fun `OKI tax rounds the annual base and then the resulting tax to whole PLN`() {
        val result = simulate(
            initial = InitialPortfolio(okiValuePln = money("58.60"), okiOpenedOn = "2027-01-01"),
        )

        // Ordynacja podatkowa art. 63: 58.60 -> base 59 -> 0.5015 tax -> one PLN.
        assertMoney("58.60", result.yearly.single().okiAverageTaxBasePln)
        assertMoney("1", result.yearly.single().okiTaxAssessedPln)
        assertMoney("57.60", result.netLiquidationValuePln)
    }

    @Test
    fun `moving appreciated assets transfers sale proceeds and records deferred tax`() {
        val result = simulate(
            initial = InitialPortfolio(taxableLots = listOf(lot("100000", "60000"))),
            strategy = Strategy(
                "move", contributionToOkiFraction = 1.0,
                initialTransfer = TransferPlan(TransferDirection.TAXABLE_TO_OKI, 1.0),
            ),
        )
        val transfer = requireNotNull(result.initialTransfer)

        assertMoney("100000", transfer.grossSoldPln)
        assertMoney("40000", transfer.realizedGainPln)
        assertMoney("7600", transfer.estimatedAdditionalCapitalGainsTaxPln)
        assertMoney("100000", transfer.proceedsTransferredPln)
        assertMoney("100000", transfer.purchasedValuePln)
        assertMoney("0", result.capitalGainsTaxPaidPln)
        assertMoney("0", result.okiTaxPaidPln)
        assertMoney("8450", result.outstandingTaxPln)
        assertMoney("91550", result.netLiquidationValuePln)
    }

    @Test
    fun `PIT is paid in April and OKI in May of the following year`() {
        val initial = InitialPortfolio(
            taxableLots = listOf(lot("100000", "60000")), cashPln = money("10000"),
        )
        val strategy = Strategy(
            "move", contributionToOkiFraction = 1.0,
            initialTransfer = TransferPlan(TransferDirection.TAXABLE_TO_OKI, 1.0),
        )
        fun through(date: String) = simulate(
            initial = initial, strategy = strategy, end = date,
            assumptions = listOf(year(2027), year(2028)),
        )

        assertMoney("0", through("2028-04-29").capitalGainsTaxPaidPln)
        val aprilPayment = through("2028-04-30")
        assertMoney("7600", aprilPayment.capitalGainsTaxPaidPln)
        assertMoney("0", aprilPayment.okiTaxPaidPln)
        assertMoney("2400", aprilPayment.yearly.last().cashPln)
        assertMoney("0", through("2028-05-30").okiTaxPaidPln)
        val mayPayment = through("2028-05-31")
        assertMoney("850", mayPayment.okiTaxPaidPln)
        assertMoney("1550", mayPayment.yearly.last().cashPln)
    }

    @Test
    fun `FIFO uses acquisition order and carries the unsold lot basis`() {
        val result = simulate(
            initial = InitialPortfolio(
                // Both lots represent two units at a current unit price of PLN 300.
                // Deliberately reversed input order: the acquisition dates determine FIFO.
                taxableLots = listOf(
                    lot("600", "400", id = "newer", acquired = "2026-06-01"),
                    lot("600", "200", id = "older", acquired = "2026-01-01"),
                ),
            ),
            cashFlows = listOf(CashFlow("2027-01-01", withdrawalPln = money("900"))),
        )
        val annual = result.yearly.single()

        // Sell three units for 900; cost = 2 * 100 + 1 * 200 = 400; gain = 500.
        assertMoney("900", result.withdrawalsPaidPln)
        assertMoney("500", annual.realizedGainPln)
        assertMoney("200", annual.taxableCostBasisPln)
        assertMoney("300", annual.taxableValuePln)
        assertMoney("95", annual.capitalGainsTaxAssessedPln)
        // The remaining unit carries another 100 of gain; combined PIT is 114.
        assertMoney("186", result.netLiquidationValuePln)
    }

    @Test
    fun `realized losses offset earlier gains in the same tax year`() {
        val result = simulate(
            start = "2027-07-01",
            initial = InitialPortfolio(
                taxableLots = listOf(lot("2000", "3200")),
                taxState = OpeningTaxState(realizedGainPln = money("1000")),
            ),
            cashFlows = listOf(CashFlow("2027-07-01", withdrawalPln = money("1000"))),
        )

        assertMoney("400", result.yearly.single().realizedGainPln)
        assertMoney("76", result.yearly.single().capitalGainsTaxAssessedPln)
        assertMoney("0", result.capitalGainsTaxPaidPln)
    }

    @Test
    fun `capital gains tax rounds the annual taxable base before multiplying by the rate`() {
        val initial = InitialPortfolio(taxableLots = listOf(lot("102.60", "100")))
        val held = simulate(initial = initial)
        val transferred = simulate(
            initial = initial,
            strategy = Strategy(
                "move", 1.0, initialTransfer = TransferPlan(TransferDirection.TAXABLE_TO_OKI, 1.0),
            ),
        )

        // PIT base 2.60 rounds to 3; 3 * 19% = 0.57 rounds to one PLN.
        // Multiplying 2.60 directly would incorrectly round 0.494 to zero PLN.
        assertMoney("1", held.liquidationTaxPln)
        assertMoney("101.60", held.netLiquidationValuePln)
        assertMoney("1", transferred.yearly.single().capitalGainsTaxAssessedPln)
    }

    @Test
    fun `terminal liquidation loss reduces same year realized tax instead of being ignored`() {
        val result = simulate(
            start = "2027-07-01",
            initial = InitialPortfolio(
                taxableLots = listOf(lot("1000", "1600")),
                taxState = OpeningTaxState(realizedGainPln = money("1000")),
            ),
        )

        assertMoney("190", result.yearly.single().capitalGainsTaxAssessedPln)
        // Hypothetical sale loss of 600 offsets earlier gain of 1000: PIT = 400 * 19%.
        assertMoney("924", result.netLiquidationValuePln)
        assertMoney("0", result.capitalGainsTaxPaidPln)
    }

    @Test
    fun `tax payment funded by a taxable sale creates next year liability without immediate gross up`() {
        val result = simulate(
            start = "2028-01-01", end = "2028-12-31",
            initial = InitialPortfolio(
                taxableLots = listOf(lot("10000", "6000")),
                taxState = OpeningTaxState(
                    liabilities = listOf(TaxLiability(TaxKind.CAPITAL_GAINS, 2027, "2028-04-30", money("1000"))),
                ),
            ),
            assumptions = listOf(year(2028)),
        )

        assertMoney("1000", result.capitalGainsTaxPaidPln)
        assertMoney("9000", result.marketValuePln)
        assertMoney("400", result.yearly.single().realizedGainPln)
        assertMoney("76", result.yearly.single().capitalGainsTaxAssessedPln)
    }

    @Test
    fun `constant prices preserve the household budget for taxable and split contribution policies`() {
        val request = request(
            initial = InitialPortfolio(taxableLots = listOf(lot("1000", "1000")), cashPln = money("200")),
            monthlyPlan = MonthlyPlan(contributionPln = money("100"), withdrawalPln = money("30")),
            cashFlows = listOf(CashFlow("2027-06-15", contributionPln = money("50"), withdrawalPln = money("70"))),
        ).copy(
            strategies = listOf(Strategy("baseline", 0.0), Strategy("split", 0.5)),
        )
        val results = SimulationEngine.compare(request).results

        for (result in results) {
            assertMoney("1250", result.contributionsPln)
            assertMoney("430", result.withdrawalsPaidPln)
            assertMoney("2020", result.marketValuePln)
            assertMoney("0", result.withdrawalShortfallPln)
            assertMoney("0", result.capitalGainsTaxPaidPln)
            assertMoney("0", result.okiTaxPaidPln)
            // Deferred tax reduces net wealth, but it must not reduce market assets before payment.
            assertMoney("2020", result.netLiquidationValuePln + result.outstandingTaxPln + result.liquidationTaxPln)
        }
    }

    @Test
    fun `cash contributions used for spending never pass through OKI and create no round trip correction`() {
        val result = simulate(
            initial = InitialPortfolio(okiValuePln = money("100000"), okiOpenedOn = "2027-01-01"),
            strategy = Strategy("oki", 1.0, withdrawalOrder = WithdrawalOrder.OKI_FIRST),
            cashFlows = listOf(CashFlow("2027-01-01", contributionPln = money("36500"), withdrawalPln = money("36500"))),
        )

        assertMoney("100000", result.yearly.single().okiAverageTaxBasePln)
        assertMoney("850", result.yearly.single().okiTaxAssessedPln)
    }

    @Test
    fun `actual same day OKI transfer and withdrawal add the smaller cash flow to value days`() {
        val result = simulate(
            initial = InitialPortfolio(
                taxableLots = listOf(lot("36500", "36500")),
                okiValuePln = money("100000"), okiOpenedOn = "2027-01-01",
            ),
            strategy = Strategy(
                "roundtrip", 1.0, withdrawalOrder = WithdrawalOrder.OKI_FIRST,
                initialTransfer = TransferPlan(TransferDirection.TAXABLE_TO_OKI, 1.0),
            ),
            cashFlows = listOf(CashFlow("2027-01-01", withdrawalPln = money("36500"))),
        )

        assertMoney("100000", result.marketValuePln)
        assertMoney("100100", result.yearly.single().okiAverageTaxBasePln)
        assertMoney("851", result.yearly.single().okiTaxAssessedPln)
    }

    @Test
    fun `initial cash remains a buffer and pays spending before either stock account`() {
        val result = simulate(
            initial = InitialPortfolio(taxableLots = listOf(lot("1000", "600")), cashPln = money("500")),
            cashFlows = listOf(CashFlow("2027-02-01", withdrawalPln = money("200"))),
            strategy = Strategy("oki", 1.0, withdrawalOrder = WithdrawalOrder.OKI_FIRST),
        )

        assertMoney("300", result.yearly.single().cashPln)
        assertMoney("1000", result.yearly.single().taxableValuePln)
        assertMoney("0", result.yearly.single().okiValuePln)
        assertMoney("0", result.yearly.single().realizedGainPln)
    }

    @Test
    fun `migration includes both trading fees and only the taxable sale creates income`() {
        val result = SimulationEngine.compare(
            request(
                initial = InitialPortfolio(taxableLots = listOf(lot("1000", "600"))),
                strategy = Strategy(
                    "move", 1.0,
                    initialTransfer = TransferPlan(TransferDirection.TAXABLE_TO_OKI, 1.0),
                ),
            ).copy(tradingFeeRate = 0.01),
        ).results.single()
        val transfer = requireNotNull(result.initialTransfer)

        // Sell for 1000 less 10 fee; the 990 purchase budget buys 990 / 1.01 of equity.
        assertMoney("990", transfer.proceedsTransferredPln)
        assertMoney("980.198020", transfer.purchasedValuePln)
        assertMoney("19.801980", transfer.feesPln)
        assertMoney("390", transfer.realizedGainPln)
        assertMoney("74", transfer.estimatedAdditionalCapitalGainsTaxPln)
        assertMoney("8", result.yearly.single().okiTaxAssessedPln)
        assertMoney("9.801980", result.liquidationFeesPln)
        assertMoney("888.396040", result.netLiquidationValuePln)
    }

    @Test
    fun `moving from OKI purchases a new taxable lot at the current cost basis`() {
        val result = simulate(
            initial = InitialPortfolio(okiValuePln = money("1000"), okiOpenedOn = "2027-01-01"),
            strategy = Strategy(
                "leave", 0.0, initialTransfer = TransferPlan(TransferDirection.OKI_TO_TAXABLE, 1.0),
            ),
            assumptions = listOf(year(2027, returns = 0.10)),
        )

        assertMoney("0", requireNotNull(result.initialTransfer).estimatedAdditionalCapitalGainsTaxPln)
        assertMoney("1000", result.yearly.single().taxableCostBasisPln)
        assertMoney("1100", result.marketValuePln)
        assertMoney("0", result.yearly.single().okiTaxAssessedPln)
        assertMoney("19", result.liquidationTaxPln)
        assertMoney("1081", result.netLiquidationValuePln)
    }

    @Test
    fun `unfunded withdrawals are reported without creating negative holdings`() {
        val result = simulate(
            initial = InitialPortfolio(taxableLots = listOf(lot("100", "100"))),
            cashFlows = listOf(CashFlow("2027-01-01", withdrawalPln = money("150"))),
        )

        assertMoney("100", result.withdrawalsPaidPln)
        assertMoney("50", result.withdrawalShortfallPln)
        assertMoney("0", result.marketValuePln)
        assertMoney("0", result.yearly.single().taxableCostBasisPln)
    }

    @Test
    fun `unpaid future tax makes an exhausted portfolio infeasible before the payment deadline`() {
        val comparison = SimulationEngine.compare(
            request(
                initial = InitialPortfolio(taxableLots = listOf(lot("100", "0"))),
                cashFlows = listOf(CashFlow("2027-01-01", withdrawalPln = money("100"))),
            ),
        )
        val result = comparison.results.single()

        assertMoney("100", result.withdrawalsPaidPln)
        assertMoney("0", result.withdrawalShortfallPln)
        assertMoney("0", result.unpaidTaxPln)
        assertMoney("19", result.outstandingTaxPln)
        assertMoney("-19", result.netLiquidationValuePln)
        assertEquals(Recommendation.WITHDRAWAL_SHORTFALL, comparison.recommendation)
        assertTrue(comparison.explanation.contains("terminal", ignoreCase = true), comparison.explanation)
    }

    @Test
    fun `solvency takes priority over the minimum advantage threshold when selecting a strategy`() {
        val comparison = SimulationEngine.compare(
            request(
                initial = InitialPortfolio(okiValuePln = money("100"), okiOpenedOn = "2027-01-01"),
                cashFlows = listOf(CashFlow("2027-12-31", withdrawalPln = money("100"))),
            ).copy(
                strategies = listOf(
                    Strategy("baseline", 0.0),
                    Strategy(
                        "exit-oki", 0.0,
                        initialTransfer = TransferPlan(TransferDirection.OKI_TO_TAXABLE, 1.0),
                    ),
                ),
                minimumAdvantagePln = money("100"),
            ),
        )
        val baseline = comparison.results.first { it.strategyId == "baseline" }
        val solvent = comparison.results.first { it.strategyId == "exit-oki" }

        // Holding the OKI until the final withdrawal leaves a one-PLN asset tax bill.
        // Moving its cash to a taxable account on day one produces no gain and no asset tax.
        for (result in comparison.results) {
            assertMoney("100", result.withdrawalsPaidPln)
            assertMoney("0", result.withdrawalShortfallPln)
            assertMoney("0", result.unpaidTaxPln)
        }
        assertMoney("-1", baseline.netLiquidationValuePln)
        assertMoney("0", solvent.netLiquidationValuePln)
        assertMoney("1", solvent.advantageVsBaselinePln)
        assertEquals(Recommendation.CHANGE, comparison.recommendation)
        assertEquals("exit-oki", comparison.preferredStrategyId)
    }

    @Test
    fun `nominal annual return and inflation compound consistently over a full calendar year`() {
        val result = simulate(
            initial = InitialPortfolio(taxableLots = listOf(lot("10000", "10000"))),
            assumptions = listOf(year(2027, returns = 0.10, inflation = 0.05)),
        )

        assertMoney("11000", result.marketValuePln)
        assertMoney("10810", result.netLiquidationValuePln)
        assertMoney("10295.238095", result.realNetLiquidationValuePln)
    }

    @Test
    fun `monthly cashflows on day 28 are applied once in both January and February`() {
        val result = simulate(
            end = "2027-02-28",
            monthlyPlan = MonthlyPlan(contributionPln = money("100"), dayOfMonth = 28),
        )

        assertMoney("200", result.contributionsPln)
        assertMoney("200", result.marketValuePln)
    }

    @Test
    fun `monthly contributions stop at the inclusive cutoff before the withdrawal phase`() {
        val result = simulate(
            initial = InitialPortfolio(taxableLots = listOf(lot("1000", "1000"))),
            monthlyPlan = MonthlyPlan(
                contributionPln = money("100"), contributionUntil = "2027-06-01",
                withdrawalPln = money("50"), withdrawalFrom = "2027-07-01",
            ),
        )

        assertMoney("600", result.contributionsPln)
        assertMoney("300", result.withdrawalsPaidPln)
        assertMoney("1300", result.netLiquidationValuePln)
        assertMoney("0", result.withdrawalShortfallPln)
    }

    @Test
    fun `monthly scheduling rejects days not present in every month`() {
        assertInvalid(request().copy(monthlyPlan = MonthlyPlan(dayOfMonth = 31)))
        assertInvalid(request().copy(monthlyPlan = MonthlyPlan(dayOfMonth = 0)))
    }

    @Test
    fun `midyear simulations require explicit opening tax history`() {
        assertInvalid(request().copy(startDate = "2027-07-01"))
    }

    @Test
    fun `January first cannot have realized gains or losses from earlier in the same calendar year`() {
        for (gain in listOf("100", "-100")) {
            assertInvalid(
                request(initial = InitialPortfolio(taxState = OpeningTaxState(realizedGainPln = money(gain)))),
            )
        }

        val validZeroState = simulate(initial = InitialPortfolio(taxState = OpeningTaxState()))
        assertMoney("0", validZeroState.netLiquidationValuePln)
    }

    @Test
    fun `explicit realized gains from earlier in the year remain valid for a midyear start`() {
        val result = simulate(
            start = "2027-07-01",
            initial = InitialPortfolio(
                taxableLots = listOf(lot("100", "100")),
                taxState = OpeningTaxState(realizedGainPln = money("100")),
            ),
        )

        assertMoney("100", result.yearly.single().realizedGainPln)
        assertMoney("19", result.outstandingTaxPln)
        assertMoney("81", result.netLiquidationValuePln)
    }

    @Test
    fun `invalid dates and missing year assumptions are rejected`() {
        val valid = request()
        assertInvalid(valid.copy(startDate = "2027-02-30"))
        assertInvalid(valid.copy(endDate = "2026-12-31"))
        assertInvalid(valid.copy(endDate = "2028-12-31"))
        assertInvalid(valid.copy(cashFlows = listOf(CashFlow("2028-01-01", contributionPln = money("1")))))
        assertInvalid(valid.copy(assumptions = listOf(year(2027), year(2027))))
        assertInvalid(valid.copy(initial = InitialPortfolio(taxableLots = listOf(lot("100", "100", acquired = "2028-01-01")))))
        assertInvalid(valid.copy(initial = InitialPortfolio(okiValuePln = money("100"), okiOpenedOn = "2028-01-01")))
    }

    @Test
    fun `nonfinite or invalid rates and strategy fractions are rejected`() {
        val valid = request()
        for (rate in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, -1.0)) {
            assertInvalid(valid.copy(assumptions = listOf(year(2027, returns = rate))))
        }
        assertInvalid(valid.copy(assumptions = listOf(year(2027, inflation = -1.0))))
        assertInvalid(valid.copy(assumptions = listOf(year(2027).copy(okiTaxRate = -0.01))))
        assertInvalid(valid.copy(assumptions = listOf(year(2027).copy(okiTaxRate = Double.NaN))))
        assertInvalid(valid.copy(tradingFeeRate = 1.0))
        assertInvalid(valid.copy(capitalGainsTaxRate = -0.01))
        assertInvalid(valid.copy(strategies = listOf(Strategy("baseline", 1.01))))
        assertInvalid(valid.copy(strategies = listOf(Strategy("baseline", Double.NaN))))
    }

    @Test
    fun `negative cash balances and duplicate strategy identifiers are rejected`() {
        val valid = request()
        assertInvalid(valid.copy(initial = InitialPortfolio(cashPln = money("-1"))))
        assertInvalid(valid.copy(cashFlows = listOf(CashFlow("2027-01-01", withdrawalPln = money("-1")))))
        assertInvalid(valid.copy(strategies = listOf(Strategy("baseline", 0.0), Strategy("baseline", 1.0))))
        assertInvalid(valid.copy(baselineStrategyId = "missing"))
    }

    private fun simulate(
        start: String = "2027-01-01",
        end: String = "2027-12-31",
        initial: InitialPortfolio = InitialPortfolio(),
        strategy: Strategy = Strategy("baseline", 0.0),
        assumptions: List<YearAssumptions> = listOf(year(2027)),
        cashFlows: List<CashFlow> = emptyList(),
        monthlyPlan: MonthlyPlan = MonthlyPlan(),
    ): StrategyResult = SimulationEngine.compare(
        request(start, end, initial, strategy, assumptions, cashFlows, monthlyPlan),
    ).results.single()

    private fun request(
        start: String = "2027-01-01",
        end: String = "2027-12-31",
        initial: InitialPortfolio = InitialPortfolio(),
        strategy: Strategy = Strategy("baseline", 0.0),
        assumptions: List<YearAssumptions> = listOf(year(2027)),
        cashFlows: List<CashFlow> = emptyList(),
        monthlyPlan: MonthlyPlan = MonthlyPlan(),
    ) = ComparisonRequest(
        startDate = start, endDate = end, initial = initial, assumptions = assumptions,
        strategies = listOf(strategy), cashFlows = cashFlows, monthlyPlan = monthlyPlan,
        baselineStrategyId = strategy.id, includeLedger = true,
    )

    private fun year(year: Int, returns: Double = 0.0, inflation: Double = 0.0) =
        YearAssumptions(year, returns, inflation, okiTaxRate = 0.0085)

    private fun lot(
        market: String,
        basis: String,
        id: String = "initial",
        acquired: String = "2026-01-01",
    ) = TaxLot(id, acquired, money(market), money(basis))

    private fun money(value: String) = BigDecimal(value)

    private fun assertMoney(expected: String, actual: BigDecimal) {
        val difference = money(expected).subtract(actual).abs()
        assertTrue(difference <= money("0.011"), "Expected PLN $expected, got $actual (difference $difference)")
    }

    private fun assertInvalid(request: ComparisonRequest) {
        assertThrows(IllegalArgumentException::class.java) { SimulationEngine.compare(request) }
    }
}
