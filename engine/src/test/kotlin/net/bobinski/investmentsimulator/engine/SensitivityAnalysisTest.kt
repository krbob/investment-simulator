package net.bobinski.investmentsimulator.engine

import java.math.BigDecimal
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Sensitivity must preserve accounting, decision thresholds and comparable household budgets. */
class SensitivityAnalysisTest {
    @Test
    fun `zero shifts reproduce the supplied comparison including deferred migration tax`() {
        val base = request().copy(includeLedger = true)
        val analysis = SensitivityAnalysis.analyze(SensitivityRequest(base))
        val scenario = analysis.scenarios.single()
        val comparison = SimulationEngine.compare(base)

        assertEquals(1, analysis.scenarioCount)
        assertEquals(730L, analysis.evaluatedStrategyDays)
        assertEquals(comparison.preferredStrategyId, scenario.preferredStrategyId)
        assertEquals(comparison.recommendation, scenario.recommendation)
        assertMatchesComparison(comparison, scenario)
        val migration = scenario.strategies.single { it.strategyId == "move" }
        assertMoney("380", requireNotNull(migration.initialTransfer).estimatedAdditionalCapitalGainsTaxPln)
        assertMoney("0", migration.capitalGainsTaxPaidPln)
        assertMoney("380", migration.outstandingTaxPln)
        assertMoney("38", migration.advantageVsBaselinePln)
    }

    @Test
    fun `additive shifts retain the annual path and preserve established OKI rates`() {
        val base = request().copy(
            endDate = "2028-12-31",
            assumptions = listOf(
                YearAssumptions(2027, 0.07, 0.02, 0.0085, AssumptionStatus.ESTABLISHED),
                YearAssumptions(2028, 0.09, 0.04, 0.01, AssumptionStatus.ASSUMED),
            ),
            monthlyPlan = MonthlyPlan(
                contributionPln = money("100"), withdrawalPln = money("10"),
                indexContributions = true, indexWithdrawals = true,
            ),
        )
        val axes = SensitivityAxes(
            equityReturnRateShifts = listOf(0.02),
            inflationRateShifts = listOf(-0.01),
            assumedOkiTaxRateShifts = listOf(0.003),
        )
        val scenario = SensitivityAnalysis.analyze(SensitivityRequest(base, axes)).scenarios.single()
        val expected = base.copy(assumptions = base.assumptions.map {
            it.copy(
                equityReturnRate = (BigDecimal.valueOf(it.equityReturnRate) + BigDecimal("0.02")).toDouble(),
                inflationRate = (BigDecimal.valueOf(it.inflationRate) - BigDecimal("0.01")).toDouble(),
                okiTaxRate = if (it.okiRateStatus == AssumptionStatus.ASSUMED) (BigDecimal.valueOf(it.okiTaxRate) + BigDecimal("0.003")).toDouble() else it.okiTaxRate,
            )
        })

        assertEquals(listOf(2027), scenario.preservedEstablishedOkiYears)
        assertEquals(listOf(2028), scenario.shiftedAssumedOkiYears)
        assertMatchesComparison(SimulationEngine.compare(expected), scenario)
    }

    @Test
    fun `shortening the horizon omits later dated flows but retains the monthly schedule and tax debts`() {
        val initial = request().initial.copy(
            taxState = OpeningTaxState(
                liabilities = listOf(TaxLiability(TaxKind.CAPITAL_GAINS, 2026, "2028-04-30", money("50"))),
            ),
        )
        val base = request().copy(
            endDate = "2028-12-31", initial = initial,
            assumptions = listOf(year(2027), year(2028)),
            monthlyPlan = MonthlyPlan(
                contributionPln = money("100"), contributionUntil = "2028-06-01",
                withdrawalPln = money("25"), withdrawalFrom = "2028-07-01",
            ),
            cashFlows = listOf(
                CashFlow("2027-12-31", contributionPln = money("30")),
                CashFlow("2028-01-01", contributionPln = money("200")),
                CashFlow("2028-12-31", withdrawalPln = money("70")),
            ),
        )
        val scenario = SensitivityAnalysis.analyze(
            SensitivityRequest(base, SensitivityAxes(endDates = listOf("2027-12-31"))),
        ).scenarios.single()
        val expected = base.copy(
            endDate = "2027-12-31", assumptions = listOf(year(2027)),
            cashFlows = base.cashFlows.take(1),
        )

        assertEquals(2, scenario.omittedCashFlowCount)
        assertMatchesComparison(SimulationEngine.compare(expected), scenario)
        scenario.strategies.forEach {
            assertMoney("1230", it.contributionsPln)
            assertMoney("0", it.withdrawalsPaidPln)
            assertTrue(it.outstandingTaxPln >= money("50"))
        }
    }

    @Test
    fun `advantage equal to the real PLN threshold keeps the baseline`() {
        val base = request().copy(minimumAdvantagePln = money("38"))
        val scenario = SensitivityAnalysis.analyze(SensitivityRequest(base)).scenarios.single()

        assertEquals(Recommendation.NO_CLEAR_ADVANTAGE, scenario.recommendation)
        assertEquals("baseline", scenario.preferredStrategyId)
        assertEquals("move", scenario.highestValueStrategyId)
        assertMoney("38", scenario.strategies.single { it.strategyId == "move" }.advantageVsBaselinePln)
    }

    @Test
    fun `adjacent samples bracket both break even and the recommendation threshold`() {
        val base = request().copy(minimumAdvantagePln = money("37.99"))
        val result = SensitivityAnalysis.analyze(
            SensitivityRequest(base, SensitivityAxes(assumedOkiTaxRateShifts = listOf(0.0, 0.02))),
        )
        val low = result.scenarios.single { it.coordinates.assumedOkiTaxRateShift == 0.0 }
        val high = result.scenarios.single { it.coordinates.assumedOkiTaxRateShift == 0.02 }

        assertEquals("move", low.preferredStrategyId)
        assertEquals("baseline", high.preferredStrategyId)
        assertMoney("38", low.strategies.single { it.strategyId == "move" }.advantageVsBaselinePln)
        assertMoney("-4", high.strategies.single { it.strategyId == "move" }.advantageVsBaselinePln)
        for (kind in listOf(
            SensitivityTransitionKind.PREFERRED_STRATEGY_CHANGE,
            SensitivityTransitionKind.BASELINE_BREAK_EVEN,
            SensitivityTransitionKind.MINIMUM_ADVANTAGE_CROSSING,
        )) {
            assertTrue(result.transitions.any {
                it.axis == SensitivityAxis.ASSUMED_OKI_TAX_RATE_SHIFT && it.kind == kind &&
                    it.fromScenarioId == low.id && it.toScenarioId == high.id
            }, "Expected $kind between adjacent sampled OKI shifts")
        }
    }

    @Test
    fun `threshold equality does not cross until the next adjacent sample exceeds it`() {
        val result = SensitivityAnalysis.analyze(SensitivityRequest(
            request().copy(minimumAdvantagePln = money("38")),
            SensitivityAxes(equityReturnRateShifts = listOf(-0.1, 0.0, 0.1)),
        ))
        val zero = result.scenarios.single { it.coordinates.equityReturnRateShift == -0.1 }
        val equal = result.scenarios.single { it.coordinates.equityReturnRateShift == 0.0 }
        val above = result.scenarios.single { it.coordinates.equityReturnRateShift == 0.1 }
        val threshold = result.transitions.single { it.kind == SensitivityTransitionKind.MINIMUM_ADVANTAGE_CROSSING }
        val breakEven = result.transitions.single { it.kind == SensitivityTransitionKind.BASELINE_BREAK_EVEN }

        assertEquals(equal.id, threshold.fromScenarioId)
        assertEquals(above.id, threshold.toScenarioId)
        assertEquals(zero.id, breakEven.fromScenarioId)
        assertEquals(equal.id, breakEven.toScenarioId)
        assertEquals("baseline", equal.preferredStrategyId)
        assertEquals("move", above.preferredStrategyId)
    }

    @Test
    fun `infeasible scenarios contribute no preferred or highest value wins`() {
        val base = request().copy(
            initial = InitialPortfolio(), monthlyPlan = MonthlyPlan(withdrawalPln = money("100")),
        )
        val result = SensitivityAnalysis.analyze(
            SensitivityRequest(base, SensitivityAxes(equityReturnRateShifts = listOf(-0.05, 0.05))),
        )

        assertEquals(2, result.allInfeasibleScenarioCount)
        result.scenarios.forEach { scenario ->
            assertEquals(Recommendation.WITHDRAWAL_SHORTFALL, scenario.recommendation)
            assertNull(scenario.preferredStrategyId)
            assertNull(scenario.highestValueStrategyId)
            scenario.strategies.forEach {
                assertFalse(it.feasible)
                assertNull(it.regretVsBestFeasiblePln)
                assertMoney("1200", it.withdrawalShortfallPln)
            }
        }
        result.strategySummaries.forEach {
            assertEquals(0, it.feasibleScenarioCount)
            assertEquals(0, it.preferredScenarioCount)
            assertEquals(0, it.highestValueScenarioCount)
            assertEquals(0, it.comparableToBaselineScenarioCount)
            assertNull(it.minimumAdvantageVsBaselinePln)
            assertNull(it.maximumAdvantageVsBaselinePln)
            assertNull(it.maximumRegretVsBestFeasiblePln)
        }
    }

    @Test
    fun `terminal insolvency takes priority over threshold and is excluded from baseline comparisons`() {
        val base = request().copy(
            initial = InitialPortfolio(okiValuePln = money("100"), okiOpenedOn = "2027-01-01"),
            assumptions = listOf(YearAssumptions(2027, 0.0, 0.0, 0.0)),
            strategies = listOf(
                Strategy("baseline", 0.0),
                Strategy("exit", 0.0, initialTransfer = TransferPlan(TransferDirection.OKI_TO_TAXABLE, 1.0)),
            ),
            cashFlows = listOf(CashFlow("2027-12-31", withdrawalPln = money("100"))),
            minimumAdvantagePln = money("100"),
        )
        val result = SensitivityAnalysis.analyze(
            SensitivityRequest(base, SensitivityAxes(assumedOkiTaxRateShifts = listOf(0.0, 0.0085))),
        )
        val taxed = result.scenarios.single { it.coordinates.assumedOkiTaxRateShift == 0.0085 }
        val baseline = taxed.strategies.single { it.strategyId == "baseline" }
        val summary = result.strategySummaries.single { it.strategyId == "exit" }

        assertEquals("exit", taxed.preferredStrategyId)
        assertEquals(Recommendation.CHANGE, taxed.recommendation)
        assertMoney("0", baseline.withdrawalShortfallPln)
        assertMoney("0", baseline.unpaidTaxPln)
        assertMoney("-1", baseline.netLiquidationValuePln)
        assertFalse(baseline.feasible)
        assertEquals(2, summary.feasibleScenarioCount)
        assertEquals(1, summary.comparableToBaselineScenarioCount)
        assertMoney("0", requireNotNull(summary.minimumAdvantageVsBaselinePln))
        assertMoney("0", requireNotNull(summary.maximumAdvantageVsBaselinePln))
        assertTrue(result.transitions.any {
            it.kind == SensitivityTransitionKind.FEASIBILITY_CHANGE && it.strategyId == "baseline"
        })
    }

    @Test
    fun `equal values favor the baseline independently of supplied strategy order`() {
        val base = request().copy(
            strategies = listOf(Strategy("aaa", 0.0), Strategy("baseline", 0.0)),
        )
        val result = SensitivityAnalysis.analyze(SensitivityRequest(base))
        val scenario = result.scenarios.single()

        assertEquals("baseline", scenario.preferredStrategyId)
        assertEquals("baseline", scenario.highestValueStrategyId)
        assertEquals(1, result.strategySummaries.single { it.strategyId == "baseline" }.highestValueScenarioCount)
        assertEquals(0, result.strategySummaries.single { it.strategyId == "aaa" }.highestValueScenarioCount)
    }

    @Test
    fun `summaries separate horizons instead of pooling final wealth across durations`() {
        val base = request().copy(endDate = "2028-12-31", assumptions = listOf(year(2027), year(2028)))
        val result = SensitivityAnalysis.analyze(
            SensitivityRequest(
                base,
                SensitivityAxes(equityReturnRateShifts = listOf(-0.02, 0.02), endDates = listOf("2028-12-31", "2027-12-31")),
            ),
        )

        assertEquals(4, result.scenarioCount)
        assertEquals(4, result.strategySummaries.size)
        assertEquals(setOf("2027-12-31", "2028-12-31"), result.strategySummaries.map { it.endDate }.toSet())
        assertTrue(result.strategySummaries.all { it.scenarioCount == 2 })
        assertEquals((365L + 731L) * 2 * 2, result.evaluatedStrategyDays)
    }

    @Test
    fun `axes reject duplicate empty nonfinite and out of range shifted values`() {
        val invalid = listOf(
            SensitivityAxes(equityReturnRateShifts = emptyList()),
            SensitivityAxes(inflationRateShifts = listOf(0.01, 0.01)),
            SensitivityAxes(assumedOkiTaxRateShifts = listOf(Double.NaN)),
            SensitivityAxes(equityReturnRateShifts = listOf(Double.POSITIVE_INFINITY)),
            SensitivityAxes(equityReturnRateShifts = listOf(-1.1)),
            SensitivityAxes(inflationRateShifts = listOf(-0.51)),
            SensitivityAxes(assumedOkiTaxRateShifts = listOf(-0.001)),
        )

        invalid.forEach { axes -> assertInvalid(SensitivityRequest(request(), axes)) }
    }

    @Test
    fun `horizons reject partial years extensions dates before start and duplicates`() {
        listOf(
            listOf("2027-06-30"), listOf("2028-12-31"), listOf("2026-12-31"),
            listOf("2027-12-31", "2027-12-31"), listOf("2027-02-30"),
        ).forEach { ends -> assertInvalid(SensitivityRequest(request(), SensitivityAxes(endDates = ends))) }
    }

    @Test
    fun `expanded calendar years are rejected as invalid input before arithmetic overflows`() {
        assertInvalid(SensitivityRequest(request().copy(
            startDate = "+999999999-01-01", endDate = "+999999999-12-31",
        )))
    }

    @Test
    fun `canonical scenarios are independent of axis order and replay without the ledger`() {
        val base = request().copy(includeLedger = true)
        val forward = SensitivityRequest(base, SensitivityAxes(equityReturnRateShifts = listOf(-0.02, 0.02)))
        val reverse = forward.copy(axes = forward.axes.copy(equityReturnRateShifts = listOf(0.02, -0.02)))
        val result = SensitivityAnalysis.analyze(reverse)

        assertEquals(SensitivityAnalysis.analyze(forward), result)
        assertFalse(result.request.baseRequest.includeLedger)
        result.scenarios.forEach { scenario ->
            val resolved = SensitivityAnalysis.resolveScenario(result.request, scenario.id)
            assertFalse(resolved.includeLedger)
            assertEquals(scenario.coordinates.endDate, resolved.endDate)
            assertMatchesComparison(SimulationEngine.compare(resolved), scenario)
        }
        assertThrows(IllegalArgumentException::class.java) {
            SensitivityAnalysis.resolveScenario(result.request, "missing")
        }
    }

    @Test
    fun `exact decimal shift to zero does not create an invalid negative OKI rate`() {
        val base = request().copy(assumptions = listOf(year(2027).copy(okiTaxRate = 0.0085)))
        val input = SensitivityRequest(base, SensitivityAxes(assumedOkiTaxRateShifts = listOf(-0.0085)))
        val result = SensitivityAnalysis.analyze(input)
        val resolved = SensitivityAnalysis.resolveScenario(result.request, result.scenarios.single().id)

        assertEquals(0.0, resolved.assumptions.single().okiTaxRate)
        assertMoney("38", result.scenarios.single().strategies.single { it.strategyId == "move" }.advantageVsBaselinePln)
    }

    @Test
    fun `an ineffective OKI sweep needs some assumed year in the base and identifies unchanged short horizons`() {
        val established = year(2027).copy(okiTaxRate = 0.0085, okiRateStatus = AssumptionStatus.ESTABLISHED)
        val base = request().copy(assumptions = listOf(established))
        assertInvalid(SensitivityRequest(base, SensitivityAxes(assumedOkiTaxRateShifts = listOf(0.01))))

        val extended = base.copy(endDate = "2028-12-31", assumptions = listOf(established, year(2028)))
        val result = SensitivityAnalysis.analyze(
            SensitivityRequest(
                extended,
                SensitivityAxes(assumedOkiTaxRateShifts = listOf(0.0, 0.01), endDates = listOf("2027-12-31")),
            ),
        )
        assertEquals(2, result.scenarioCount)
        assertTrue(result.scenarios.all { it.shiftedAssumedOkiYears.isEmpty() })
        assertEquals(result.scenarios[0].strategies, result.scenarios[1].strategies)
    }

    @Test
    fun `the full base path must be valid even when the requested horizon omits a bad year`() {
        val base = request().copy(
            endDate = "2028-12-31", assumptions = listOf(year(2027), year(2028).copy(okiTaxRate = 1.1)),
        )
        assertInvalid(SensitivityRequest(base, SensitivityAxes(endDates = listOf("2027-12-31"))))
    }

    @Test
    fun `axis scenario and aggregate strategy day budgets reject excessive work`() {
        assertInvalid(SensitivityRequest(
            request(), SensitivityAxes(equityReturnRateShifts = (0..16).map { it / 1000.0 }),
        ))
        assertInvalid(SensitivityRequest(
            request(),
            SensitivityAxes(
                equityReturnRateShifts = (0..8).map { it / 1000.0 },
                inflationRateShifts = (0..3).map { it / 1000.0 },
                assumedOkiTaxRateShifts = (0..3).map { it / 1000.0 },
            ),
        ))
        val longBase = request().copy(
            endDate = "2076-12-31", assumptions = (2027..2076).map(::year),
            strategies = listOf(Strategy("baseline", 0.0)) + (1..31).map { Strategy("strategy-$it", 0.0) },
        )
        assertInvalid(SensitivityRequest(
            longBase, SensitivityAxes(equityReturnRateShifts = listOf(0.0, 0.01, 0.02, 0.03)),
        ))
        val manyLots = request().copy(
            initial = InitialPortfolio(taxableLots = (1..4000).map {
                TaxLot("lot-$it", "2026-01-01", money("1"), money("0"))
            }),
            strategies = longBase.strategies,
        )
        assertInvalid(SensitivityRequest(
            manyLots, SensitivityAxes(equityReturnRateShifts = listOf(0.0, 0.01)),
        ))
    }

    private fun assertMatchesComparison(expected: ComparisonResult, actual: SensitivityScenario) {
        assertEquals(expected.results.map { it.strategyId }.toSet(), actual.strategies.map { it.strategyId }.toSet())
        expected.results.forEach { expectedStrategy ->
            val actualStrategy = actual.strategies.single { it.strategyId == expectedStrategy.strategyId }
            assertEquals(expectedStrategy.netLiquidationValuePln, actualStrategy.netLiquidationValuePln)
            assertEquals(expectedStrategy.realNetLiquidationValuePln, actualStrategy.realNetLiquidationValuePln)
            assertEquals(expectedStrategy.advantageVsBaselinePln, actualStrategy.advantageVsBaselinePln)
            assertEquals(expectedStrategy.contributionsPln, actualStrategy.contributionsPln)
            assertEquals(expectedStrategy.withdrawalsPaidPln, actualStrategy.withdrawalsPaidPln)
            assertEquals(expectedStrategy.outstandingTaxPln, actualStrategy.outstandingTaxPln)
            assertEquals(expectedStrategy.liquidationTaxPln, actualStrategy.liquidationTaxPln)
            assertEquals(expectedStrategy.initialTransfer, actualStrategy.initialTransfer)
        }
    }

    private fun assertInvalid(request: SensitivityRequest) {
        assertThrows(IllegalArgumentException::class.java) { SensitivityAnalysis.analyze(request) }
    }

    private fun request() = ComparisonRequest(
        startDate = "2027-01-01", endDate = "2027-12-31",
        initial = InitialPortfolio(
            taxableLots = listOf(TaxLot("lot", "2026-01-01", money("2000"), money("0"))),
        ),
        assumptions = listOf(year(2027)),
        strategies = listOf(
            Strategy("baseline", 0.0),
            Strategy("move", 1.0, initialTransfer = TransferPlan(TransferDirection.TAXABLE_TO_OKI, 1.0)),
        ),
        baselineStrategyId = "baseline", minimumAdvantagePln = money("0"),
    )

    private fun year(year: Int) = YearAssumptions(year, 0.1, 0.0, 0.0)
    private fun money(value: String) = value.toBigDecimal()
    private fun assertMoney(expected: String, actual: BigDecimal) {
        assertEquals(0, money(expected).compareTo(actual), "Expected $expected PLN, got $actual PLN")
    }
}
