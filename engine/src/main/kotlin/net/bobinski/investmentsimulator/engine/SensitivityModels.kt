@file:kotlinx.serialization.UseSerializers(net.bobinski.investmentsimulator.engine.DecimalSerializer::class)

package net.bobinski.investmentsimulator.engine

import java.math.BigDecimal
import kotlinx.serialization.Serializable

@Serializable
data class SensitivityRequest(val baseRequest: ComparisonRequest, val axes: SensitivityAxes = SensitivityAxes())

/** Additive shifts in fractional rate units: 0.01 is one percentage point, not one percent. */
@Serializable
data class SensitivityAxes(
    val equityReturnRateShifts: List<Double> = listOf(0.0),
    val inflationRateShifts: List<Double> = listOf(0.0),
    val assumedOkiTaxRateShifts: List<Double> = listOf(0.0),
    // Empty means the base end date. Horizons can only shorten the supplied path.
    val endDates: List<String> = emptyList(),
)

@Serializable
data class SensitivityCoordinates(
    val equityReturnRateShift: Double,
    val inflationRateShift: Double,
    val assumedOkiTaxRateShift: Double,
    val endDate: String,
)

@Serializable
data class SensitivityResult(
    val sensitivityVersion: String,
    val engineVersion: String,
    val taxRulesVersion: String,
    val request: SensitivityRequest,
    val scenarioCount: Int,
    val evaluatedStrategyDays: Long,
    val allInfeasibleScenarioCount: Int,
    val scenarios: List<SensitivityScenario>,
    val strategySummaries: List<SensitivityStrategySummary>,
    val transitions: List<SensitivityTransition>,
    val limitations: List<String>,
)

@Serializable
data class SensitivityScenario(
    val id: String,
    val coordinates: SensitivityCoordinates,
    val preservedEstablishedOkiYears: List<Int>,
    val shiftedAssumedOkiYears: List<Int>,
    val omittedCashFlowCount: Int,
    // Null when every candidate is infeasible; the least-bad result is not a win.
    val preferredStrategyId: String?,
    val highestValueStrategyId: String?,
    val recommendation: Recommendation,
    val strategies: List<SensitivityStrategyResult>,
)

@Serializable
data class SensitivityStrategyResult(
    val strategyId: String,
    val feasible: Boolean,
    val realNetLiquidationValuePln: BigDecimal,
    val netLiquidationValuePln: BigDecimal,
    val advantageVsBaselinePln: BigDecimal,
    val regretVsBestFeasiblePln: BigDecimal?,
    val contributionsPln: BigDecimal,
    val withdrawalsPaidPln: BigDecimal,
    val withdrawalShortfallPln: BigDecimal,
    val unpaidTaxPln: BigDecimal,
    val outstandingTaxPln: BigDecimal,
    val liquidationTaxPln: BigDecimal,
    val liquidationFeesPln: BigDecimal,
    val capitalGainsTaxPaidPln: BigDecimal,
    val okiTaxPaidPln: BigDecimal,
    val tradingFeesPln: BigDecimal,
    val initialTransfer: TransferResult?,
)

/** Summaries group by horizon so that different durations are never ranked by pooled wealth. */
@Serializable
data class SensitivityStrategySummary(
    val endDate: String,
    val strategyId: String,
    val scenarioCount: Int,
    val feasibleScenarioCount: Int,
    val preferredScenarioCount: Int,
    val highestValueScenarioCount: Int,
    val comparableToBaselineScenarioCount: Int,
    val minimumAdvantageVsBaselinePln: BigDecimal?,
    val maximumAdvantageVsBaselinePln: BigDecimal?,
    val maximumRegretVsBestFeasiblePln: BigDecimal?,
)

@Serializable
enum class SensitivityAxis { EQUITY_RETURN_RATE_SHIFT, INFLATION_RATE_SHIFT, ASSUMED_OKI_TAX_RATE_SHIFT, END_DATE }

@Serializable
enum class SensitivityTransitionKind {
    PREFERRED_STRATEGY_CHANGE, BASELINE_BREAK_EVEN, MINIMUM_ADVANTAGE_CROSSING, FEASIBILITY_CHANGE,
}

/** Observed adjacent samples, never an interpolated exact threshold or monotonicity claim. */
@Serializable
data class SensitivityTransition(
    val axis: SensitivityAxis,
    val kind: SensitivityTransitionKind,
    val fromScenarioId: String,
    val toScenarioId: String,
    val strategyId: String? = null,
)
