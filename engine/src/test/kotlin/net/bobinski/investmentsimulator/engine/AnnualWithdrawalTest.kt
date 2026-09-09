package net.bobinski.investmentsimulator.engine

import java.math.BigDecimal
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Independently calculated examples for annual spending from current portfolio value. */
class AnnualWithdrawalTest {
    @Test
    fun `withdrawals are recalculated each January and monthly accumulation stops before retirement`() {
        val result = simulate(request().copy(
            endDate = "2029-12-31", initial = InitialPortfolio(),
            assumptions = (2027..2029).map(::year),
            monthlyPlan = MonthlyPlan(contributionPln = money("100"), contributionUntil = "2030-12-31"),
            annualWithdrawalPlan = AnnualWithdrawalPlan("2028-01-01"),
        ))

        assertMoney("1200", result.contributionsPln)
        assertEquals(listOf("2028-01-01", "2029-01-01"), result.annualWithdrawals.map { it.date })
        assertMoney("1200", result.annualWithdrawals[0].portfolioValuePln)
        assertMoney("48", result.annualWithdrawals[0].paidPln)
        assertMoney("1152", result.annualWithdrawals[1].portfolioValuePln)
        assertMoney("46.08", result.annualWithdrawals[1].paidPln)
        assertMoney("94.08", result.withdrawalsPaidPln)
        assertMoney("1105.92", result.netLiquidationValuePln)
    }

    @Test
    fun `an earlier explicit contribution cutoff remains effective`() {
        val result = simulate(request().copy(
            endDate = "2028-12-31", initial = InitialPortfolio(),
            assumptions = (2027..2028).map(::year),
            monthlyPlan = MonthlyPlan(contributionPln = money("100"), contributionUntil = "2027-06-01"),
            annualWithdrawalPlan = AnnualWithdrawalPlan("2028-01-01"),
        ))

        assertMoney("600", result.contributionsPln)
        assertMoney("24", result.annualWithdrawals.single().paidPln)
        assertMoney("576", result.netLiquidationValuePln)
    }

    @Test
    fun `annual value includes both accounts and cash after due taxes and dated household flows`() {
        val result = simulate(request().copy(
            initial = InitialPortfolio(
                taxableLots = listOf(lot("10000", "10000")),
                okiValuePln = money("2000"), okiOpenedOn = "2027-01-01", cashPln = money("1000"),
                taxState = OpeningTaxState(liabilities = listOf(
                    TaxLiability(TaxKind.CAPITAL_GAINS, 2026, "2027-01-01", money("100")),
                )),
            ),
            cashFlows = listOf(CashFlow("2027-01-01", contributionPln = money("500"), withdrawalPln = money("200"))),
        ))
        val draw = result.annualWithdrawals.single()

        // 10000 taxable + 2000 OKI + 1000 cash + 500 deposit - 100 tax - 200 spending.
        assertMoney("13200", draw.portfolioValuePln)
        assertMoney("528", draw.requestedPln)
        assertMoney("528", draw.paidPln)
        assertMoney("728", result.withdrawalsPaidPln)
        assertMoney("100", result.capitalGainsTaxPaidPln)
        assertMoney("12672", result.marketValuePln)
    }

    @Test
    fun `current value spending is rounded to cents and ignores monthly inflation indexing`() {
        val result = simulate(request().copy(
            initial = InitialPortfolio(cashPln = money("100.13")),
            assumptions = listOf(year(2027).copy(inflationRate = 0.1)),
            monthlyPlan = MonthlyPlan(indexWithdrawals = true),
        ))

        assertMoney("4.01", result.annualWithdrawals.single().requestedPln)
        assertMoney("4.01", result.annualWithdrawals.single().paidPln)
        assertMoney("96.12", result.marketValuePln)
        assertMoney("4.01", result.realWithdrawalsPaidPln)
    }

    @Test
    fun `tax payments consume additional assets and never reduce reported net annual spending`() {
        val result = simulate(request().copy(
            endDate = "2028-12-31",
            initial = InitialPortfolio(taxableLots = listOf(lot("10000", "0"))),
            assumptions = (2027..2028).map(::year),
        ))

        assertMoney("400", result.annualWithdrawals[0].paidPln)
        assertMoney("384", result.annualWithdrawals[1].paidPln)
        assertMoney("784", result.withdrawalsPaidPln)
        assertMoney("76", result.capitalGainsTaxPaidPln)
        assertMoney("9140", result.marketValuePln)
        assertMoney("460", result.yearly.last().realizedGainPln)
        assertMoney("0", result.withdrawalShortfallPln)
    }

    @Test
    fun `sales fees are funded on top of the four percent net payment`() {
        val result = simulate(request().copy(tradingFeeRate = 0.01))

        assertMoney("10000", result.annualWithdrawals.single().portfolioValuePln)
        assertMoney("400", result.withdrawalsPaidPln)
        // Selling 404.0404... pays the 400 PLN household draw plus its one-percent fee.
        assertMoney("4.04", result.tradingFeesPln)
        assertMoney("9595.96", result.marketValuePln)
        assertMoney("95.96", result.liquidationFeesPln)
        assertMoney("9500", result.netLiquidationValuePln)
    }

    @Test
    fun `January spending precedes daily returns and next January uses the grown balance`() {
        val result = simulate(request().copy(
            endDate = "2028-12-31",
            assumptions = (2027..2028).map { year(it).copy(equityReturnRate = 0.1) },
        ))

        assertMoney("400", result.annualWithdrawals[0].paidPln)
        // (10000 - 400) * 1.10 = 10560 before the next year's first daily return.
        assertMoney("10560", result.annualWithdrawals[1].portfolioValuePln)
        assertMoney("422.40", result.annualWithdrawals[1].paidPln)
    }

    @Test
    fun `retirement begins partial sales without liquidating the accumulated portfolio`() {
        val result = simulate(request().copy(
            endDate = "2029-12-31",
            initial = InitialPortfolio(taxableLots = listOf(lot("10000", "5000"))),
            assumptions = (2027..2029).map(::year),
            annualWithdrawalPlan = AnnualWithdrawalPlan("2028-01-01"), includeLedger = true,
        ))
        val accumulation = result.yearly.first { it.year == 2027 }
        val firstRetirementYear = result.yearly.first { it.year == 2028 }
        val retirementSales = result.ledger.filter { it.type == "SELL" && it.date == "2028-01-01" }

        assertMoney("10000", accumulation.taxableValuePln)
        assertMoney("5000", accumulation.taxableCostBasisPln)
        assertMoney("0", accumulation.realizedGainPln)
        assertMoney("9600", firstRetirementYear.taxableValuePln)
        assertMoney("4800", firstRetirementYear.taxableCostBasisPln)
        assertMoney("200", firstRetirementYear.realizedGainPln)
        assertMoney("38", firstRetirementYear.capitalGainsTaxAssessedPln)
        assertMoney("400", retirementSales.single().amountPln)
        assertNull(result.initialTransfer)
    }

    @Test
    fun `real cumulative spending uses CPI at each payment rather than final CPI`() {
        val result = simulate(request().copy(
            endDate = "2028-12-31", initial = InitialPortfolio(cashPln = money("10000")),
            assumptions = listOf(year(2027).copy(inflationRate = 0.1), year(2028)),
        ))

        assertMoney("400", result.annualWithdrawals[0].realPaidPln)
        assertMoney("349.09", result.annualWithdrawals[1].realPaidPln)
        assertMoney("784", result.withdrawalsPaidPln)
        assertMoney("749.09", result.realWithdrawalsPaidPln)
        assertMoney("8378.18", result.realNetLiquidationValuePln)
        assertMoney("9127.27", result.realTotalBenefitPln)
        assertEquals(result.realWithdrawalsPaidPln + result.realNetLiquidationValuePln, result.realTotalBenefitPln)
    }

    @Test
    fun `ranking includes paid income so smaller withdrawals do not masquerade as a better strategy`() {
        val base = request().copy(
            endDate = "2029-12-31", initial = InitialPortfolio(taxableLots = listOf(lot("10000", "0"))),
            assumptions = (2027..2029).map(::year),
            strategies = listOf(
                Strategy("baseline", 0.0),
                Strategy("move", 1.0, initialTransfer = TransferPlan(TransferDirection.TAXABLE_TO_OKI, 1.0)),
            ),
        )
        val totalComparison = SimulationEngine.compare(base)
        val terminalComparison = SimulationEngine.compare(base.copy(comparisonObjective = ComparisonObjective.REAL_TERMINAL_WEALTH))
        val baseline = totalComparison.results.single { it.strategyId == "baseline" }
        val migration = totalComparison.results.single { it.strategyId == "move" }

        // Both begin with 10000 minus 1900 total PIT; no return, inflation or OKI tax.
        assertMoney("365.60", baseline.annualWithdrawals.last().paidPln)
        assertMoney("292.64", migration.annualWithdrawals.last().paidPln)
        assertMoney("1149.60", baseline.withdrawalsPaidPln)
        assertMoney("1076.64", migration.withdrawalsPaidPln)
        assertMoney("6950.40", baseline.netLiquidationValuePln)
        assertMoney("7023.36", migration.netLiquidationValuePln)
        assertMoney("8100", baseline.realTotalBenefitPln)
        assertMoney("8100", migration.realTotalBenefitPln)
        assertMoney("72.96", migration.advantageVsBaselinePln)
        assertMoney("0", migration.objectiveAdvantageVsBaselinePln)
        assertEquals(ComparisonObjective.REAL_WITHDRAWALS_PLUS_TERMINAL_WEALTH, totalComparison.comparisonObjective)
        assertEquals("baseline", totalComparison.preferredStrategyId)
        assertEquals("move", terminalComparison.preferredStrategyId)
        assertEquals(ComparisonObjective.REAL_TERMINAL_WEALTH, terminalComparison.comparisonObjective)
    }

    @Test
    fun `a percentage policy can remain feasible while the paid income collapses`() {
        val comparison = SimulationEngine.compare(request().copy(
            endDate = "2030-12-31", assumptions = (2027..2030).map { year(it).copy(equityReturnRate = -0.9) },
        ))
        val result = comparison.results.single()

        listOf("400", "38.40", "3.69", "0.35").zip(result.annualWithdrawals).forEach { (expected, draw) ->
            assertMoney(expected, draw.paidPln)
        }
        assertMoney("0", result.withdrawalShortfallPln)
        assertMoney("0", result.unpaidTaxPln)
        assertTrue(result.netLiquidationValuePln > money("0"))
        assertEquals(Recommendation.KEEP_BASELINE, comparison.recommendation)
    }

    @Test
    fun `retirement beyond a shortened sensitivity horizon leaves the accumulation phase intact`() {
        val base = request().copy(
            endDate = "2029-12-31", initial = InitialPortfolio(), assumptions = (2027..2029).map(::year),
            monthlyPlan = MonthlyPlan(contributionPln = money("100")),
            annualWithdrawalPlan = AnnualWithdrawalPlan("2029-01-01"),
        )
        val input = SensitivityRequest(base, SensitivityAxes(endDates = listOf("2027-12-31", "2028-12-31")))
        val analysis = SensitivityAnalysis.analyze(input)
        analysis.scenarios.forEach { scenario ->
            val resolved = SensitivityAnalysis.resolveScenario(analysis.request, scenario.id)
            val comparison = SimulationEngine.compare(resolved)
            val result = comparison.results.single()
            assertEquals("2029-01-01", requireNotNull(resolved.annualWithdrawalPlan).startDate)
            assertTrue(result.annualWithdrawals.isEmpty())
            assertMoney("0", result.withdrawalsPaidPln)
            val expected = if (scenario.coordinates.endDate == "2027-12-31") "1200" else "2400"
            assertMoney(expected, result.contributionsPln)
            assertMoney(expected, result.comparisonValuePln)
        }
    }

    @Test
    fun `annual plans reject invalid dates rates and simultaneous fixed monthly spending`() {
        listOf(
            AnnualWithdrawalPlan("2027-07-01"), AnnualWithdrawalPlan("2026-01-01"),
            AnnualWithdrawalPlan("2027-01-01", -0.01), AnnualWithdrawalPlan("2027-01-01", 1.01),
            AnnualWithdrawalPlan("2027-01-01", Double.NaN), AnnualWithdrawalPlan("2027-01-01", Double.POSITIVE_INFINITY),
        ).forEach { plan ->
            assertThrows(IllegalArgumentException::class.java) { SimulationEngine.compare(request().copy(annualWithdrawalPlan = plan)) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            SimulationEngine.compare(request().copy(monthlyPlan = MonthlyPlan(withdrawalPln = money("1"))))
        }
    }

    private fun simulate(request: ComparisonRequest) = SimulationEngine.compare(request).results.single()
    private fun request() = ComparisonRequest(
        startDate = "2027-01-01", endDate = "2027-12-31",
        initial = InitialPortfolio(taxableLots = listOf(lot("10000", "10000"))),
        assumptions = listOf(year(2027)), strategies = listOf(Strategy("baseline", 0.0)),
        baselineStrategyId = "baseline", minimumAdvantagePln = money("0"),
        annualWithdrawalPlan = AnnualWithdrawalPlan("2027-01-01"),
    )
    private fun lot(value: String, basis: String) = TaxLot("lot", "2026-01-01", money(value), money(basis))
    private fun year(year: Int) = YearAssumptions(year, 0.0, 0.0, 0.0)
    private fun money(value: String) = value.toBigDecimal()
    private fun assertMoney(expected: String, actual: BigDecimal) {
        assertEquals(0, money(expected).compareTo(actual), "Expected $expected PLN, got $actual PLN")
    }
}
