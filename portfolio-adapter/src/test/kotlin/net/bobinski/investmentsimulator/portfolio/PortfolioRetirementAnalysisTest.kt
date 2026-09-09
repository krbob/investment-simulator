package net.bobinski.investmentsimulator.portfolio

import java.math.BigDecimal
import kotlinx.serialization.json.Json
import net.bobinski.investmentsimulator.engine.AnnualWithdrawalPlan
import net.bobinski.investmentsimulator.engine.ComparisonObjective
import net.bobinski.investmentsimulator.engine.ComparisonRequest
import net.bobinski.investmentsimulator.engine.MonthlyPlan
import net.bobinski.investmentsimulator.engine.OpeningTaxState
import net.bobinski.investmentsimulator.engine.SimulationEngine
import net.bobinski.investmentsimulator.engine.YearAssumptions
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PortfolioRetirementAnalysisTest {
    @Test
    fun `portfolio plan preserves annual withdrawals and selected objective in replayable comparisons`() {
        for (objective in listOf(ComparisonObjective.AUTO, ComparisonObjective.REAL_TERMINAL_WEALTH)) {
            val input = fixture().let { it.copy(plan = it.plan.copy(comparisonObjective = objective)) }
            val request = Json.decodeFromString<PortfolioAnalysisRequest>(Json.encodeToString(input))
            val report = PortfolioAnalysisService.analyze(request)
            assertEquals(PortfolioAnalysisStatus.COMPLETE, report.status, report.dataGaps.toString())
            val resolved = requireNotNull(report.resolvedRequest)
            assertEquals(request.plan.annualWithdrawalPlan, resolved.annualWithdrawalPlan)
            assertEquals(objective, resolved.comparisonObjective)
            val comparison = requireNotNull(report.comparison)
            assertEquals(if (objective == ComparisonObjective.AUTO) ComparisonObjective.REAL_WITHDRAWALS_PLUS_TERMINAL_WEALTH else objective, comparison.comparisonObjective)
            assertEquals(comparison, SimulationEngine.compare(Json.decodeFromString<ComparisonRequest>(Json.encodeToString(resolved))))
            comparison.results.forEach {
                assertEquals(listOf("2028-01-01", "2029-01-01"), it.annualWithdrawals.map { payment -> payment.date })
                assertEquals(0, it.contributionsPln.compareTo(BigDecimal("1200")))
                assertTrue(it.realWithdrawalsPaidPln > BigDecimal.ZERO)
            }
            assertTrue(report.assumptions.any { it.contains("each strategy's current assets") })
        }
    }

    @Test
    fun `invalid annual plans become structured gaps without a recommendation`() {
        val valid = fixture()
        val plans = listOf(
            valid.plan.copy(annualWithdrawalPlan = AnnualWithdrawalPlan("2028-01-02")),
            valid.plan.copy(annualWithdrawalPlan = AnnualWithdrawalPlan("invalid-date")),
            valid.plan.copy(annualWithdrawalPlan = AnnualWithdrawalPlan("2028-01-01", -0.04)),
            valid.plan.copy(monthlyPlan = MonthlyPlan(withdrawalPln = BigDecimal("25"))),
        )
        for (plan in plans) {
            val report = PortfolioAnalysisService.analyze(valid.copy(plan = plan))
            assertEquals(PortfolioAnalysisStatus.NEEDS_INPUT, report.status)
            assertTrue(report.dataGaps.any { it.code == PortfolioDataGapCode.INVALID_SCENARIO })
            assertNull(report.comparison)
            assertNull(report.resolvedRequest)
        }
    }

    private fun fixture(): PortfolioAnalysisRequest {
        val source = Json.decodeFromString<PortfolioSnapshotRequest>(requireNotNull(javaClass.getResource("/portfolio-bundle.json")).readText())
        return PortfolioAnalysisRequest(
            portfolio = source.copy(
                snapshot = source.snapshot.copy(exportedAt = "2026-12-31T12:00:00Z"),
                holdings = source.holdings.map { it.copy(valuedAt = "2026-12-31") },
                openingTaxState = OpeningTaxState(),
            ),
            plan = PortfolioAnalysisPlan(
                startDate = "2027-01-01", endDate = "2029-12-31",
                assumptions = (2027..2029).map { YearAssumptions(it, 0.05, 0.025, 0.0085) },
                taxStateAsOfDate = "2027-01-01", monthlyPlan = MonthlyPlan(contributionPln = BigDecimal("100")),
                annualWithdrawalPlan = AnnualWithdrawalPlan("2028-01-01"),
            ),
        )
    }
}
