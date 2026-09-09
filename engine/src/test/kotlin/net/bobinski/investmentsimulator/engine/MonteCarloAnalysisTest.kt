package net.bobinski.investmentsimulator.engine

import java.math.BigDecimal
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Reproducible statistical contracts and independently calculated retirement income examples. */
class MonteCarloAnalysisTest {
    @Test
    fun `zero volatility exactly reproduces the supplied deterministic annual path and accounting`() {
        val base = base().copy(
            endDate = "2028-12-31", includeLedger = true,
            assumptions = listOf(
                year(2027).copy(equityReturnRate = 0.07, inflationRate = 0.025, okiTaxRate = 0.0085, okiRateStatus = AssumptionStatus.ESTABLISHED),
                year(2028).copy(equityReturnRate = 0.09, inflationRate = 0.03, okiTaxRate = 0.01),
            ),
            strategies = listOf(
                Strategy("baseline", 0.0),
                Strategy("move", 1.0, initialTransfer = TransferPlan(TransferDirection.TAXABLE_TO_OKI, 1.0)),
            ),
        )
        val input = MonteCarloRequest(base, seed = 42, annualLogReturnVolatility = 0.0, pathCount = 3)
        val result = MonteCarloAnalysis.analyze(input)
        val comparison = SimulationEngine.compare(base)

        assertEquals(3, result.pathCount)
        assertEquals(731L * 2 * 3, result.evaluatedStrategyDays)
        assertFalse(result.request.baseRequest.includeLedger)
        result.paths.forEach { path ->
            assertEquals(base.assumptions.map { it.equityReturnRate }, path.annualReturns.map { it.equityReturnRate })
            assertEquals(comparison.preferredStrategyId, path.preferredStrategyId)
            assertEquals(comparison.recommendation, path.recommendation)
            assertMatchesComparison(comparison, path)
            val resolved = MonteCarloAnalysis.resolvePath(result.request, path.id)
            assertEquals(base.assumptions, resolved.assumptions)
            assertFalse(resolved.includeLedger)
        }
    }

    @Test
    fun `seeded lognormal annual returns match independently computed reference values`() {
        val base = base().copy(
            endDate = "2029-12-31",
            assumptions = listOf(
                year(2027).copy(equityReturnRate = 0.07),
                year(2028).copy(equityReturnRate = 0.09),
                year(2029).copy(equityReturnRate = 0.04),
            ),
        )
        val path = MonteCarloAnalysis.analyze(
            MonteCarloRequest(base, seed = 42, annualLogReturnVolatility = 0.2, pathCount = 1),
        ).paths.single()

        // Independent Java 48-bit LCG + two-draw Box-Muller reference, arithmetic-mean drift.
        val expected = listOf(-0.08032139496757522, 0.03777396381297095, 0.2999546523647085)
        assertEquals(listOf(2027, 2028, 2029), path.annualReturns.map { it.year })
        expected.zip(path.annualReturns).forEach { (value, actual) ->
            assertEquals(value, actual.equityReturnRate, 1e-14)
        }
    }

    @Test
    fun `repeated seeds preserve prefixes and chronological draws independently of input ordering`() {
        val base = base().copy(
            endDate = "2029-12-31", assumptions = (2027..2029).map { year(it).copy(equityReturnRate = 0.07) },
            strategies = listOf(Strategy("baseline", 0.0), Strategy("other", 0.0)),
        )
        val input = MonteCarloRequest(base, seed = 7, annualLogReturnVolatility = 0.2, pathCount = 2)
        val small = MonteCarloAnalysis.analyze(input)
        val extended = MonteCarloAnalysis.analyze(input.copy(pathCount = 4))
        val reordered = MonteCarloAnalysis.analyze(input.copy(baseRequest = base.copy(
            assumptions = base.assumptions.reversed(), strategies = base.strategies.reversed(),
        )))

        assertEquals(small, MonteCarloAnalysis.analyze(input))
        assertEquals(small.paths, extended.paths.take(2))
        small.paths.zip(reordered.paths).forEach { (original, changed) ->
            assertEquals(original.annualReturns, changed.annualReturns)
            assertEquals(original.strategies.associateBy { it.strategyId }, changed.strategies.associateBy { it.strategyId })
        }
        assertNotEquals(small.paths.first().annualReturns, MonteCarloAnalysis.analyze(input.copy(seed = 8)).paths.first().annualReturns)
    }

    @Test
    fun `paired strategies share every market draw and keep exact zero baseline advantages`() {
        val base = base().copy(strategies = listOf(Strategy("baseline", 0.0), Strategy("identical", 0.0)))
        val result = MonteCarloAnalysis.analyze(
            MonteCarloRequest(base, seed = 42, annualLogReturnVolatility = 0.2, pathCount = 7),
        )

        assertTrue(result.paths.map { it.annualReturns }.distinct().size > 1)
        result.paths.forEach { path ->
            val baseline = path.strategies.single { it.strategyId == "baseline" }
            val identical = path.strategies.single { it.strategyId == "identical" }
            assertEquals(baseline.copy(strategyId = "identical"), identical)
            assertEquals("baseline", path.preferredStrategyId)
            assertEquals("baseline", path.highestObjectiveStrategyId)
        }
        result.strategySummaries.forEach { summary ->
            val advantage = requireNotNull(summary.objectiveAdvantageVsBaselinePln)
            assertEquals(7, summary.comparableToBaselinePathCount)
            assertEquals(7, advantage.sampleCount)
            listOf(advantage.minimum, advantage.p10, advantage.p50, advantage.p90, advantage.maximum).forEach {
                assertMoney("0", it)
            }
        }
    }

    @Test
    fun `winner counts use the declared objective and distinguish its threshold from highest value`() {
        val base = base().copy(
            endDate = "2029-12-31", assumptions = (2027..2029).map(::year),
            initial = InitialPortfolio(taxableLots = listOf(TaxLot("lot", "2026-01-01", money("10000"), money("0")))),
            strategies = listOf(
                Strategy("baseline", 0.0),
                Strategy("move", 1.0, initialTransfer = TransferPlan(TransferDirection.TAXABLE_TO_OKI, 1.0)),
            ),
        )
        val input = MonteCarloRequest(base, 42, 0.0, pathCount = 3)
        val total = MonteCarloAnalysis.analyze(input)
        total.paths.forEach { path ->
            val baseline = path.strategies.single { it.strategyId == "baseline" }
            val moved = path.strategies.single { it.strategyId == "move" }
            assertMoney("72.96", moved.realNetLiquidationValuePln - baseline.realNetLiquidationValuePln)
            assertMoney("0", moved.objectiveAdvantageVsBaselinePln)
            assertMoney("8100", moved.comparisonValuePln)
            assertEquals("baseline", path.preferredStrategyId)
            assertEquals("baseline", path.highestObjectiveStrategyId)
        }
        assertEquals(3, total.strategySummaries.single { it.strategyId == "baseline" }.highestObjectivePathCount)
        assertEquals(0, total.strategySummaries.single { it.strategyId == "move" }.highestObjectivePathCount)

        val threshold = MonteCarloAnalysis.analyze(input.copy(baseRequest = base.copy(
            comparisonObjective = ComparisonObjective.REAL_TERMINAL_WEALTH, minimumAdvantagePln = money("72.96"),
        )))
        threshold.paths.forEach {
            assertEquals("baseline", it.preferredStrategyId)
            assertEquals("move", it.highestObjectiveStrategyId)
            assertEquals(Recommendation.NO_CLEAR_ADVANTAGE, it.recommendation)
        }
        assertEquals(0, threshold.strategySummaries.single { it.strategyId == "move" }.preferredPathCount)
        assertEquals(3, threshold.strategySummaries.single { it.strategyId == "move" }.highestObjectivePathCount)
    }

    @Test
    fun `a resolved path reproduces accounting while leaving taxes inflation and household plans unchanged`() {
        val base = base().copy(
            endDate = "2028-12-31",
            assumptions = listOf(
                year(2027).copy(equityReturnRate = 0.07, inflationRate = 0.025, okiTaxRate = 0.0085, okiRateStatus = AssumptionStatus.ESTABLISHED),
                year(2028).copy(equityReturnRate = 0.08, inflationRate = 0.03, okiTaxRate = 0.01),
            ),
            annualWithdrawalPlan = AnnualWithdrawalPlan("2028-01-01"),
            monthlyPlan = MonthlyPlan(contributionPln = money("100")),
            cashFlows = listOf(CashFlow("2027-07-01", contributionPln = money("50"))),
        )
        val result = MonteCarloAnalysis.analyze(MonteCarloRequest(base, 42, 0.2, pathCount = 3))
        val path = result.paths[1]
        val resolved = MonteCarloAnalysis.resolvePath(result.request, path.id)

        assertEquals(base.initial, resolved.initial)
        assertEquals(base.monthlyPlan, resolved.monthlyPlan)
        assertEquals(base.annualWithdrawalPlan, resolved.annualWithdrawalPlan)
        assertEquals(base.cashFlows, resolved.cashFlows)
        assertEquals(base.assumptions.map { it.inflationRate }, resolved.assumptions.map { it.inflationRate })
        assertEquals(base.assumptions.map { it.okiTaxRate }, resolved.assumptions.map { it.okiTaxRate })
        assertEquals(base.assumptions.map { it.okiRateStatus }, resolved.assumptions.map { it.okiRateStatus })
        assertMatchesComparison(SimulationEngine.compare(resolved), path)
        assertThrows(IllegalArgumentException::class.java) { MonteCarloAnalysis.resolvePath(result.request, "p9999") }
    }

    @Test
    fun `nearest rank quantiles return observed values at ceiling ranks rather than interpolating`() {
        val result = MonteCarloAnalysis.analyze(MonteCarloRequest(base(), 42, 0.2, pathCount = 11))
        val distribution = result.strategySummaries.single().comparisonValuePln
        val values = result.paths.map { it.strategies.single().comparisonValuePln }.sorted()

        assertEquals(11, values.distinct().size)
        assertEquals(11, distribution.sampleCount)
        assertEquals(values.first(), distribution.minimum)
        assertEquals(values[1], distribution.p10)
        assertEquals(values[5], distribution.p50)
        assertEquals(values[9], distribution.p90)
        assertEquals(values.last(), distribution.maximum)
    }

    @Test
    fun `infeasible paths remain in outcome distributions but have no winner or paired advantage sample`() {
        val base = base().copy(
            initial = InitialPortfolio(),
            cashFlows = listOf(CashFlow("2027-01-01", withdrawalPln = money("100"))),
        )
        val result = MonteCarloAnalysis.analyze(MonteCarloRequest(base, 42, 0.1, pathCount = 3))
        val summary = result.strategySummaries.single()

        assertEquals(3, result.allInfeasiblePathCount)
        result.paths.forEach {
            assertNull(it.preferredStrategyId)
            assertNull(it.highestObjectiveStrategyId)
            assertEquals(Recommendation.WITHDRAWAL_SHORTFALL, it.recommendation)
            assertFalse(it.strategies.single().feasible)
        }
        assertEquals(0, summary.feasiblePathCount)
        assertEquals(0, summary.preferredPathCount)
        assertEquals(0, summary.highestObjectivePathCount)
        assertEquals(0, summary.comparableToBaselinePathCount)
        assertNull(summary.objectiveAdvantageVsBaselinePln)
        assertEquals(3, summary.realNetLiquidationValuePln.sampleCount)
        assertEquals(3, summary.comparisonValuePln.sampleCount)
    }

    @Test
    fun `paired advantages require both strategies feasible while wealth and income retain every path`() {
        val base = base().copy(
            initial = InitialPortfolio(okiValuePln = money("100"), okiOpenedOn = "2027-01-01"),
            assumptions = listOf(year(2027).copy(okiTaxRate = 0.0085)),
            annualWithdrawalPlan = AnnualWithdrawalPlan("2027-01-01", rate = 0.0),
            cashFlows = listOf(CashFlow("2027-12-31", withdrawalPln = money("100"))),
            strategies = listOf(
                Strategy("baseline", 0.0),
                Strategy("exit", 0.0, initialTransfer = TransferPlan(TransferDirection.OKI_TO_TAXABLE, 1.0)),
            ),
        )
        val result = MonteCarloAnalysis.analyze(MonteCarloRequest(base, 0, 0.1, pathCount = 4))
        val baseline = result.strategySummaries.single { it.strategyId == "baseline" }
        val exit = result.strategySummaries.single { it.strategyId == "exit" }

        // Seed0 gives +0.464%, -13.095%, -6.982%, +9.767% annual returns.
        // In the first path, the OKI's one-PLN tax exceeds its 0.46-PLN remainder.
        // The taxable alternative owes no rounded PIT there; both strategies fail paths2/3.
        assertEquals(listOf(false, false, false, true), result.paths.map { path ->
            path.strategies.single { it.strategyId == "baseline" }.feasible
        })
        assertEquals(listOf(true, false, false, true), result.paths.map { path ->
            path.strategies.single { it.strategyId == "exit" }.feasible
        })
        assertEquals(2, result.allInfeasiblePathCount)
        assertEquals(1, baseline.feasiblePathCount)
        assertEquals(2, exit.feasiblePathCount)
        for (summary in listOf(baseline, exit)) {
            assertEquals(1, summary.comparableToBaselinePathCount)
            assertEquals(1, requireNotNull(summary.objectiveAdvantageVsBaselinePln).sampleCount)
            assertEquals(4, summary.realNetLiquidationValuePln.sampleCount)
            assertEquals(4, summary.realWithdrawalsPaidPln.sampleCount)
            assertEquals(4, summary.annualIncome.single().realPaidPln.sampleCount)
            assertEquals(4, summary.annualIncome.single().zeroPaymentPathCount)
        }
        assertMoney("-1", requireNotNull(exit.objectiveAdvantageVsBaselinePln).p50)
    }

    @Test
    fun `income declines include equality while an absolute income floor is strictly below`() {
        val base = cashBase(2028).copy(assumptions = listOf(year(2027).copy(inflationRate = 0.92), year(2028)))
        val result = MonteCarloAnalysis.analyze(
            MonteCarloRequest(base, 42, 0.0, pathCount = 3, minimumRealAnnualIncomePln = money("200")),
        )
        val summary = result.strategySummaries.single()

        result.paths.forEach { path ->
            val income = path.strategies.single().income
            assertMoney("400", income.firstRealPaymentPln)
            assertMoney("200", income.minimumRealPaymentPln)
            assertTrue(income.declineEligible)
            assertEquals(true, income.declineAtLeast25Percent)
            assertEquals(true, income.declineAtLeast50Percent)
            assertEquals(0, income.yearsBelowMinimum)
            assertEquals(0, income.longestRunBelowMinimum)
        }
        assertEquals(3, summary.incomeDeclineEligiblePathCount)
        assertEquals(3, summary.declineAtLeast50PercentPathCount)
        assertEquals(0, summary.anyYearBelowMinimumPathCount)
        assertEquals(0, summary.annualIncome.last().belowMinimumPathCount)
    }

    @Test
    fun `an exact quarter income decline is eligible without becoming a half income decline`() {
        val base = cashBase(2028).copy(assumptions = listOf(year(2027).copy(inflationRate = 0.28), year(2028)))
        val result = MonteCarloAnalysis.analyze(MonteCarloRequest(base, 42, 0.0, pathCount = 1))
        val income = result.paths.single().strategies.single().income

        assertMoney("300", income.minimumRealPaymentPln)
        assertEquals(true, income.declineAtLeast25Percent)
        assertEquals(false, income.declineAtLeast50Percent)
        assertNull(income.yearsBelowMinimum)
        assertNull(income.longestRunBelowMinimum)
        assertNull(result.strategySummaries.single().anyYearBelowMinimumPathCount)
    }

    @Test
    fun `annual income metrics exclude dated spending while total real withdrawals include it`() {
        val base = cashBase(2028).copy(cashFlows = listOf(CashFlow("2027-06-01", withdrawalPln = money("100"))))
        val result = MonteCarloAnalysis.analyze(MonteCarloRequest(
            base, 42, 0.0, pathCount = 1, minimumRealAnnualIncomePln = money("390"),
        ))
        val strategy = result.paths.single().strategies.single()
        val summary = result.strategySummaries.single()

        assertMoney("880", strategy.realWithdrawalsPaidPln)
        assertMoney("400", strategy.income.firstRealPaymentPln)
        assertMoney("380", strategy.income.minimumRealPaymentPln)
        assertEquals(2, strategy.income.annualPaymentCount)
        assertEquals(1, strategy.income.yearsBelowMinimum)
        assertEquals(listOf("400.00", "380.00"), summary.annualIncome.map { it.realPaidPln.p50.toPlainString() })
        assertMoney("880", summary.realWithdrawalsPaidPln.p50)
    }

    @Test
    fun `below floor years and the longest consecutive run are distinct income risks`() {
        val base = cashBase(2031).copy(assumptions = listOf(
            year(2027).copy(inflationRate = 1.0), year(2028).copy(inflationRate = -0.5),
            year(2029).copy(inflationRate = 1.0), year(2030), year(2031),
        ))
        val result = MonteCarloAnalysis.analyze(
            MonteCarloRequest(base, 42, 0.0, pathCount = 2, minimumRealAnnualIncomePln = money("200")),
        )
        val income = result.paths.first().strategies.single().income
        val summary = result.strategySummaries.single()

        // Real annual spending: 400, 192, 368.64, 176.95, 169.87.
        assertEquals(3, income.yearsBelowMinimum)
        assertEquals(2, income.longestRunBelowMinimum)
        assertEquals(2, summary.anyYearBelowMinimumPathCount)
        assertEquals(3, summary.maximumYearsBelowMinimum)
        assertEquals(2, summary.maximumConsecutiveYearsBelowMinimum)
        assertEquals(listOf(0, 2, 0, 2, 2), summary.annualIncome.map { it.belowMinimumPathCount })
    }

    @Test
    fun `zero initial income and short histories are excluded from relative decline denominators`() {
        val zero = MonteCarloAnalysis.analyze(MonteCarloRequest(
            cashBase(2028).copy(initial = InitialPortfolio()), 42, 0.0, pathCount = 3,
            minimumRealAnnualIncomePln = money("1"),
        ))
        val zeroSummary = zero.strategySummaries.single()
        assertEquals(3, zeroSummary.feasiblePathCount)
        assertEquals(0, zeroSummary.incomeDeclineEligiblePathCount)
        assertEquals(3, zeroSummary.zeroFirstPaymentPathCount)
        assertEquals(0, zeroSummary.insufficientIncomeHistoryPathCount)
        assertEquals(3, zeroSummary.anyZeroPaymentPathCount)
        assertEquals(3, zeroSummary.anyYearBelowMinimumPathCount)
        zero.paths.forEach {
            val income = it.strategies.single().income
            assertEquals(2, income.zeroPaymentYearCount)
            assertFalse(income.declineEligible)
            assertNull(income.declineAtLeast25Percent)
            assertNull(income.declineAtLeast50Percent)
        }

        val short = MonteCarloAnalysis.analyze(MonteCarloRequest(
            cashBase(2028).copy(annualWithdrawalPlan = AnnualWithdrawalPlan("2028-01-01")),
            42, 0.0, pathCount = 3,
        )).strategySummaries.single()
        assertEquals(0, short.incomeDeclineEligiblePathCount)
        assertEquals(0, short.zeroFirstPaymentPathCount)
        assertEquals(3, short.insufficientIncomeHistoryPathCount)
        assertEquals(0, short.declineAtLeast25PercentPathCount)
        assertEquals(0, short.declineAtLeast50PercentPathCount)
    }

    @Test
    fun `an out of range later draw rejects the whole batch instead of clipping or redrawing`() {
        val input = MonteCarloRequest(
            base().copy(assumptions = listOf(year(2027).copy(equityReturnRate = 5.0))),
            seed = 7, annualLogReturnVolatility = 0.5, pathCount = 2,
        )
        // This seed's first draw is below 500%; the second exceeds the engine range.
        assertEquals(1, MonteCarloAnalysis.analyze(input.copy(pathCount = 1)).pathCount)
        val failure = assertThrows(IllegalArgumentException::class.java) { MonteCarloAnalysis.analyze(input) }
        assertTrue(failure.message.orEmpty().contains("p0002"), failure.message)
        assertTrue(failure.message.orEmpty().contains("2027"), failure.message)
        assertThrows(IllegalArgumentException::class.java) {
            MonteCarloAnalysis.analyze(MonteCarloRequest(
                base().copy(assumptions = listOf(year(2027).copy(equityReturnRate = -0.99))),
                seed = 1, annualLogReturnVolatility = 0.2, pathCount = 1,
            ))
        }
    }

    @Test
    fun `request validation rejects unsupported seeds volatility floors and missing retirement observations`() {
        val input = MonteCarloRequest(base(), 42, 0.0, pathCount = 1)
        listOf(
            input.copy(seed = -1), input.copy(seed = 281474976710656L),
            input.copy(pathCount = 0), input.copy(pathCount = 257),
            input.copy(annualLogReturnVolatility = -0.01), input.copy(annualLogReturnVolatility = 0.5001),
            input.copy(annualLogReturnVolatility = Double.NaN), input.copy(annualLogReturnVolatility = Double.POSITIVE_INFINITY),
            input.copy(minimumRealAnnualIncomePln = money("-0.01")),
            input.copy(minimumRealAnnualIncomePln = money("1000000000000001")),
            input.copy(minimumRealAnnualIncomePln = money("0.0000000000001")),
            input.copy(baseRequest = base().copy(annualWithdrawalPlan = null)),
            input.copy(baseRequest = base().copy(annualWithdrawalPlan = AnnualWithdrawalPlan("2028-01-01"))),
            input.copy(baseRequest = base().copy(endDate = "2027-06-30")),
        ).forEach(::assertInvalid)
    }

    @Test
    fun `expanded dates are validation errors before calendar arithmetic overflows`() {
        assertInvalid(MonteCarloRequest(base().copy(
            startDate = "+999999999-01-01", endDate = "+999999999-12-31",
        ), 42, 0.0, pathCount = 1))
    }

    @Test
    fun `aggregate strategy days and opening lot replays are bounded before simulation`() {
        val strategies = listOf(Strategy("baseline", 0.0)) + (1..31).map { Strategy("other-$it", 0.0) }
        val longBase = base().copy(
            endDate = "2076-12-31", assumptions = (2027..2076).map(::year), strategies = strategies,
        )
        assertInvalid(MonteCarloRequest(longBase, 42, 0.0, pathCount = 18))
        val manyLots = base().copy(
            initial = InitialPortfolio(taxableLots = (1..4000).map {
                TaxLot("lot-$it", "2026-01-01", money("1"), money("1"))
            }),
            strategies = strategies,
        )
        assertInvalid(MonteCarloRequest(manyLots, 42, 0.0, pathCount = 4))
    }

    private fun assertMatchesComparison(expected: ComparisonResult, actual: MonteCarloPath) {
        expected.results.forEach { value ->
            val sampled = actual.strategies.single { it.strategyId == value.strategyId }
            assertEquals(value.realNetLiquidationValuePln, sampled.realNetLiquidationValuePln)
            assertEquals(value.realWithdrawalsPaidPln, sampled.realWithdrawalsPaidPln)
            assertEquals(value.realTotalBenefitPln, sampled.realTotalBenefitPln)
            assertEquals(value.comparisonValuePln, sampled.comparisonValuePln)
            assertEquals(value.objectiveAdvantageVsBaselinePln, sampled.objectiveAdvantageVsBaselinePln)
            assertEquals(value.outstandingTaxPln, sampled.outstandingTaxPln)
            assertEquals(value.capitalGainsTaxPaidPln, sampled.capitalGainsTaxPaidPln)
            assertEquals(value.okiTaxPaidPln, sampled.okiTaxPaidPln)
            assertEquals(value.annualWithdrawals, sampled.annualWithdrawals)
        }
    }

    private fun assertInvalid(request: MonteCarloRequest) {
        assertThrows(IllegalArgumentException::class.java) { MonteCarloAnalysis.analyze(request) }
    }

    private fun base() = ComparisonRequest(
        startDate = "2027-01-01", endDate = "2027-12-31",
        initial = InitialPortfolio(taxableLots = listOf(TaxLot("lot", "2026-01-01", money("10000"), money("10000")))),
        assumptions = listOf(year(2027)), strategies = listOf(Strategy("baseline", 0.0)),
        baselineStrategyId = "baseline", minimumAdvantagePln = money("0"),
        annualWithdrawalPlan = AnnualWithdrawalPlan("2027-01-01"),
    )
    private fun cashBase(endYear: Int) = base().copy(
        endDate = "$endYear-12-31", initial = InitialPortfolio(cashPln = money("10000")),
        assumptions = (2027..endYear).map(::year),
    )
    private fun year(year: Int) = YearAssumptions(year, 0.0, 0.0, 0.0)
    private fun money(value: String) = value.toBigDecimal()
    private fun assertMoney(expected: String, actual: BigDecimal) {
        assertEquals(0, money(expected).compareTo(actual), "Expected $expected PLN, got $actual PLN")
    }
}
