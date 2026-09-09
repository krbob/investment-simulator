package net.bobinski.investmentsimulator.engine

import java.math.BigDecimal
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RetirementSensitivityTest {
    @Test
    fun `accumulation horizons move the annual start and keep exactly two withdrawal years`() {
        val base = base().copy(
            endDate = "2030-12-31", assumptions = (2027..2030).map(::year),
            annualWithdrawalPlan = AnnualWithdrawalPlan("2029-01-01"),
            monthlyPlan = MonthlyPlan(contributionPln = money("100")),
        )
        val request = SensitivityRequest(base, SensitivityAxes(
            accumulationEndDates = listOf("2028-12-31", "2027-12-31"), withdrawalYears = 2,
        ))
        val result = SensitivityAnalysis.analyze(request)
        assertEquals(2, result.scenarioCount)
        assertTrue(result.request.axes.endDates.isEmpty())
        assertEquals(listOf("2027-12-31", "2028-12-31"), result.request.axes.accumulationEndDates)
        assertEquals(2, result.request.axes.withdrawalYears)
        assertEquals(ComparisonObjective.REAL_WITHDRAWALS_PLUS_TERMINAL_WEALTH, result.comparisonObjective)
        var expectedDays = 0L
        for (scenario in result.scenarios) {
            val accumulationEnd = LocalDate.parse(scenario.coordinates.accumulationEndDate)
            val resolved = SensitivityAnalysis.resolveScenario(result.request, scenario.id)
            assertEquals(accumulationEnd.plusDays(1).toString(), resolved.annualWithdrawalPlan!!.startDate)
            assertEquals(accumulationEnd.plusYears(2).toString(), resolved.endDate)
            assertEquals(0.04, resolved.annualWithdrawalPlan.rate)
            expectedDays += (ChronoUnit.DAYS.between(LocalDate.parse(base.startDate), LocalDate.parse(resolved.endDate)) + 1) * base.strategies.size
            for (strategy in scenario.strategies) {
                assertEquals(listOf(accumulationEnd.plusDays(1).toString(), accumulationEnd.plusDays(1).plusYears(1).toString()), strategy.annualWithdrawals.map { it.date })
                assertMoney(if (accumulationEnd.year == 2027) "1200" else "2400", strategy.contributionsPln)
            }
            assertReplay(scenario, SimulationEngine.compare(resolved))
        }
        assertEquals(expectedDays, result.evaluatedStrategyDays)
        assertEquals(4, result.strategySummaries.size)
        assertTrue(result.strategySummaries.all { it.scenarioCount == 1 && it.accumulationEndDate != null })
        assertEquals(result, SensitivityAnalysis.analyze(request.copy(axes = request.axes.copy(accumulationEndDates = request.axes.accumulationEndDates.reversed()))))

        val earlierContributionLimit = request.copy(baseRequest = base.copy(monthlyPlan = base.monthlyPlan.copy(contributionUntil = "2027-06-30")))
        SensitivityAnalysis.analyze(earlierContributionLimit).scenarios.forEach { scenario ->
            scenario.strategies.forEach { assertMoney("600", it.contributionsPln) }
        }
    }

    @Test
    fun `retirement objective wins and regrets differ from preserved terminal metrics`() {
        val result = SensitivityAnalysis.analyze(SensitivityRequest(base()))
        val scenario = result.scenarios.single()
        val moved = scenario.strategies.single { it.strategyId == "move" }
        val baseline = scenario.strategies.single { it.strategyId == "baseline" }
        assertEquals(ComparisonObjective.REAL_WITHDRAWALS_PLUS_TERMINAL_WEALTH, result.comparisonObjective)
        assertEquals("baseline", scenario.preferredStrategyId)
        assertEquals("baseline", scenario.highestObjectiveStrategyId)
        assertEquals("move", scenario.highestValueStrategyId)
        assertMoney("72.96", moved.advantageVsBaselinePln)
        assertMoney("0", moved.objectiveAdvantageVsBaselinePln)
        assertMoney("8100", moved.comparisonValuePln)
        assertMoney("72.96", requireNotNull(baseline.regretVsBestFeasiblePln))
        assertMoney("0", requireNotNull(baseline.objectiveRegretVsBestFeasiblePln))
        assertMoney("1076.64", moved.realWithdrawalsPaidPln)
        assertMoney("8100", moved.realTotalBenefitPln)
        val summary = result.strategySummaries.single { it.strategyId == "move" }
        assertEquals(1, summary.highestValueScenarioCount)
        assertEquals(0, summary.highestObjectiveScenarioCount)
        assertMoney("72.96", requireNotNull(summary.minimumAdvantageVsBaselinePln))
        assertMoney("0", requireNotNull(summary.minimumObjectiveAdvantageVsBaselinePln))
        assertMoney("0", requireNotNull(summary.maximumObjectiveAdvantageVsBaselinePln))
        assertMoney("0", requireNotNull(summary.maximumObjectiveRegretVsBestFeasiblePln))
        assertReplay(scenario, SimulationEngine.compare(base()))

        val terminal = SensitivityAnalysis.analyze(SensitivityRequest(base().copy(comparisonObjective = ComparisonObjective.REAL_TERMINAL_WEALTH)))
        assertEquals(ComparisonObjective.REAL_TERMINAL_WEALTH, terminal.comparisonObjective)
        assertEquals("move", terminal.scenarios.single().preferredStrategyId)
        assertEquals("move", terminal.scenarios.single().highestObjectiveStrategyId)
        assertMoney("72.96", terminal.scenarios.single().strategies.single { it.strategyId == "move" }.objectiveAdvantageVsBaselinePln)
    }

    @Test
    fun `legacy end dates retain the annual start even when no withdrawals fit the horizon`() {
        val base = base().copy(annualWithdrawalPlan = AnnualWithdrawalPlan("2029-01-01"))
        val request = SensitivityRequest(base, SensitivityAxes(endDates = listOf("2027-12-31")))
        val result = SensitivityAnalysis.analyze(request)
        val scenario = result.scenarios.single()
        assertNull(scenario.coordinates.accumulationEndDate)
        assertTrue(result.request.axes.accumulationEndDates.isEmpty())
        assertNull(result.request.axes.withdrawalYears)
        assertEquals("2029-01-01", SensitivityAnalysis.resolveScenario(result.request, scenario.id).annualWithdrawalPlan!!.startDate)
        assertTrue(scenario.strategies.all { it.annualWithdrawals.isEmpty() })
        assertTrue(result.strategySummaries.all { it.accumulationEndDate == null })
    }

    @Test
    fun `accumulation transitions connect complete horizons and infeasible cells have no objective winner`() {
        val request = SensitivityRequest(
            base().copy(
                endDate = "2030-12-31", assumptions = (2027..2030).map(::year),
                cashFlows = listOf(CashFlow("2030-12-31", withdrawalPln = money("100000"))),
            ),
            SensitivityAxes(accumulationEndDates = listOf("2027-12-31", "2028-12-31"), withdrawalYears = 2),
        )
        val result = SensitivityAnalysis.analyze(request)
        val early = result.scenarios.first()
        val late = result.scenarios.last()
        assertEquals(1, early.omittedCashFlowCount)
        assertEquals(0, late.omittedCashFlowCount)
        assertNull(late.preferredStrategyId)
        assertNull(late.highestObjectiveStrategyId)
        assertTrue(late.strategies.all { it.objectiveRegretVsBestFeasiblePln == null })
        assertEquals(1, result.allInfeasibleScenarioCount)
        assertTrue(result.transitions.isNotEmpty())
        assertTrue(result.transitions.all {
            it.axis == SensitivityAxis.ACCUMULATION_END_DATE && it.fromScenarioId == early.id && it.toScenarioId == late.id &&
                it.kind == SensitivityTransitionKind.FEASIBILITY_CHANGE
        })
        assertFalse(early.coordinates.endDate == late.coordinates.endDate)
        assertFalse(early.coordinates.accumulationEndDate == late.coordinates.accumulationEndDate)
    }

    @Test
    fun `objective transitions do not mistake a terminal advantage for total benefit`() {
        val result = SensitivityAnalysis.analyze(SensitivityRequest(
            base(), SensitivityAxes(endDates = listOf("2027-12-31", "2029-12-31")),
        ))
        assertTrue(result.scenarios.all { it.strategies.single { value -> value.strategyId == "move" }.objectiveAdvantageVsBaselinePln.signum() == 0 })
        assertTrue(result.transitions.none { it.kind == SensitivityTransitionKind.BASELINE_BREAK_EVEN || it.kind == SensitivityTransitionKind.MINIMUM_ADVANTAGE_CROSSING })
    }

    @Test
    fun `invalid accumulation modes and incomplete retirement paths reject the entire grid`() {
        val base = base()
        val validAxes = SensitivityAxes(accumulationEndDates = listOf("2027-12-31"), withdrawalYears = 2)
        val invalidAxes = listOf(
            validAxes.copy(withdrawalYears = null),
            validAxes.copy(accumulationEndDates = emptyList()),
            validAxes.copy(endDates = listOf("2029-12-31")),
            validAxes.copy(withdrawalYears = 0),
            validAxes.copy(withdrawalYears = 51),
            validAxes.copy(accumulationEndDates = listOf("2027-12-31", "2027-12-31")),
            validAxes.copy(accumulationEndDates = listOf("2027-06-30")),
            validAxes.copy(accumulationEndDates = listOf("2026-12-31")),
            validAxes.copy(accumulationEndDates = listOf("2028-12-31")),
            validAxes.copy(accumulationEndDates = listOf("not-a-date")),
        )
        for (axes in invalidAxes) assertInvalid(SensitivityRequest(base, axes))
        assertInvalid(SensitivityRequest(base.copy(annualWithdrawalPlan = null), validAxes))
        assertInvalid(SensitivityRequest(base.copy(assumptions = base.assumptions.dropLast(1)), validAxes))
        val overBudget = base.copy(
            endDate = "2076-12-31", assumptions = (2027..2076).map(::year),
            strategies = (0 until 32).map { Strategy(if (it == 0) "baseline" else "strategy-$it", 0.0) },
        )
        assertInvalid(SensitivityRequest(overBudget, SensitivityAxes(
            equityReturnRateShifts = listOf(0.0, 0.01, 0.02, 0.03),
            accumulationEndDates = listOf("2027-12-31"), withdrawalYears = 49,
        )))
    }

    private fun assertReplay(scenario: SensitivityScenario, comparison: ComparisonResult) {
        assertEquals(comparison.preferredStrategyId, scenario.preferredStrategyId)
        for (summary in scenario.strategies) {
            val full = comparison.results.single { it.strategyId == summary.strategyId }
            assertEquals(full.comparisonValuePln, summary.comparisonValuePln)
            assertEquals(full.objectiveAdvantageVsBaselinePln, summary.objectiveAdvantageVsBaselinePln)
            assertEquals(full.advantageVsBaselinePln, summary.advantageVsBaselinePln)
            assertEquals(full.realWithdrawalsPaidPln, summary.realWithdrawalsPaidPln)
            assertEquals(full.realTotalBenefitPln, summary.realTotalBenefitPln)
            assertEquals(full.annualWithdrawals, summary.annualWithdrawals)
        }
    }

    private fun assertInvalid(request: SensitivityRequest) {
        assertThrows(IllegalArgumentException::class.java) { SensitivityAnalysis.analyze(request) }
    }

    private fun base() = ComparisonRequest(
        startDate = "2027-01-01", endDate = "2029-12-31",
        initial = InitialPortfolio(taxableLots = listOf(TaxLot("lot", "2026-01-01", money("10000"), BigDecimal.ZERO))),
        assumptions = (2027..2029).map(::year),
        strategies = listOf(Strategy("baseline", 0.0), Strategy("move", 1.0, initialTransfer = TransferPlan(TransferDirection.TAXABLE_TO_OKI, 1.0))),
        baselineStrategyId = "baseline", minimumAdvantagePln = BigDecimal.ZERO,
        annualWithdrawalPlan = AnnualWithdrawalPlan("2027-01-01"),
    )

    private fun year(year: Int) = YearAssumptions(year, 0.0, 0.0, 0.0)
    private fun money(value: String) = value.toBigDecimal()
    private fun assertMoney(expected: String, actual: BigDecimal) = assertEquals(0, money(expected).compareTo(actual), "Expected $expected, got $actual")
}
