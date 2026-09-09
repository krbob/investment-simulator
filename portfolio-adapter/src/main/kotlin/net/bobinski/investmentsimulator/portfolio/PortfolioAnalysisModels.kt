@file:kotlinx.serialization.UseSerializers(net.bobinski.investmentsimulator.engine.DecimalSerializer::class)

package net.bobinski.investmentsimulator.portfolio

import java.math.BigDecimal
import kotlinx.serialization.Serializable
import net.bobinski.investmentsimulator.engine.AnnualWithdrawalPlan
import net.bobinski.investmentsimulator.engine.CashFlow
import net.bobinski.investmentsimulator.engine.ComparisonObjective
import net.bobinski.investmentsimulator.engine.ComparisonRequest
import net.bobinski.investmentsimulator.engine.ComparisonResult
import net.bobinski.investmentsimulator.engine.MonthlyPlan
import net.bobinski.investmentsimulator.engine.WithdrawalOrder
import net.bobinski.investmentsimulator.engine.YearAssumptions

@Serializable
data class PortfolioAnalysisRequest(val portfolio: PortfolioSnapshotRequest, val plan: PortfolioAnalysisPlan)

/** A plan deliberately has no opening balances; those must come from the selected Portfolio data. */
@Serializable
data class PortfolioAnalysisPlan(
    val startDate: String,
    val endDate: String,
    val assumptions: List<YearAssumptions>,
    val monthlyPlan: MonthlyPlan = MonthlyPlan(),
    val cashFlows: List<CashFlow> = emptyList(),
    val withdrawalOrder: WithdrawalOrder = WithdrawalOrder.TAXABLE_FIRST,
    val tradingFeeRate: Double = 0.0,
    val capitalGainsTaxRate: Double = 0.19,
    val minimumAdvantagePln: BigDecimal = BigDecimal("100"),
    val includeLedger: Boolean = false,
    val openingValuationPolicy: OpeningValuationPolicy = OpeningValuationPolicy.REQUIRE_PREVIOUS_DAY_VALUES,
    // The supplied portfolio.openingTaxState describes the start of this date, not the capture date.
    val taxStateAsOfDate: String? = null,
    // Nonzero losses cannot be silently ignored by this version of the simulation engine.
    val lossCarryforwardPln: BigDecimal = BigDecimal.ZERO,
    val annualWithdrawalPlan: AnnualWithdrawalPlan? = null,
    val comparisonObjective: ComparisonObjective = ComparisonObjective.AUTO,
)

@Serializable
enum class OpeningValuationPolicy { REQUIRE_PREVIOUS_DAY_VALUES, USE_CAPTURED_VALUES_UNCHANGED }

@Serializable
enum class PortfolioAnalysisStatus { COMPLETE, NEEDS_INPUT, UNSUPPORTED }

@Serializable
enum class PortfolioDataGapCode {
    MISSING_TAX_STATE,
    TAX_STATE_DATE_MISMATCH,
    MISSING_VERIFIED_PURCHASE_COST,
    MISSING_OKI_OPENING_DATE,
    SNAPSHOT_START_MISMATCH,
    INVALID_SCENARIO,
    UNSUPPORTED_LOSS_CARRYFORWARD,
    UNSUPPORTED_FINAL_PARTIAL_YEAR,
    UNSUPPORTED_SNAPSHOT,
}

@Serializable
data class PortfolioDataGap(
    val code: PortfolioDataGapCode,
    val path: String,
    val message: String,
    val transactionId: String? = null,
    val accountId: String? = null,
)

@Serializable
data class StrategyDescription(val id: String, val title: String, val description: String)

@Serializable
data class PortfolioAnalysisResult(
    val status: PortfolioAnalysisStatus,
    val dataGaps: List<PortfolioDataGap>,
    val strategyDescriptions: List<StrategyDescription>,
    val assumptions: List<String>,
    val source: PortfolioSnapshotProvenance? = null,
    val resolvedRequest: ComparisonRequest? = null,
    val comparison: ComparisonResult? = null,
)
