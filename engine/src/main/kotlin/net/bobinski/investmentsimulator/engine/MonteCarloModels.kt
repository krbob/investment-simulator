@file:kotlinx.serialization.UseSerializers(net.bobinski.investmentsimulator.engine.DecimalSerializer::class)

package net.bobinski.investmentsimulator.engine

import java.math.BigDecimal
import kotlinx.serialization.Serializable

@Serializable
data class MonteCarloRequest(
    val baseRequest: ComparisonRequest,
    val seed: Long,
    val annualLogReturnVolatility: Double,
    val pathCount: Int = 100,
    // Annual household cash in purchasing power at baseRequest.startDate. Null means not assessed.
    val minimumRealAnnualIncomePln: BigDecimal? = null,
)

@Serializable
data class MonteCarloResult(
    val monteCarloVersion: String,
    val engineVersion: String,
    val taxRulesVersion: String,
    val request: MonteCarloRequest,
    val returnModel: String,
    val quantileMethod: String,
    val comparisonObjective: ComparisonObjective,
    val pathCount: Int,
    val evaluatedStrategyDays: Long,
    val allInfeasiblePathCount: Int,
    val paths: List<MonteCarloPath>,
    val strategySummaries: List<MonteCarloStrategySummary>,
    val limitations: List<String>,
)

@Serializable
data class MonteCarloPath(
    val id: String,
    val annualReturns: List<MonteCarloAnnualReturn>,
    val preferredStrategyId: String?,
    val highestObjectiveStrategyId: String?,
    val recommendation: Recommendation,
    val strategies: List<MonteCarloStrategyResult>,
)

@Serializable
data class MonteCarloAnnualReturn(val year: Int, val equityReturnRate: Double)

@Serializable
data class MonteCarloStrategyResult(
    val strategyId: String,
    val feasible: Boolean,
    val realNetLiquidationValuePln: BigDecimal,
    val realWithdrawalsPaidPln: BigDecimal,
    val realTotalBenefitPln: BigDecimal,
    val comparisonValuePln: BigDecimal,
    val objectiveAdvantageVsBaselinePln: BigDecimal,
    val withdrawalShortfallPln: BigDecimal,
    val unpaidTaxPln: BigDecimal,
    val outstandingTaxPln: BigDecimal,
    val capitalGainsTaxPaidPln: BigDecimal,
    val okiTaxPaidPln: BigDecimal,
    val tradingFeesPln: BigDecimal,
    val annualWithdrawals: List<AnnualWithdrawalResult>,
    val income: MonteCarloIncomeMetrics,
)

@Serializable
data class MonteCarloIncomeMetrics(
    val annualPaymentCount: Int,
    val firstRealPaymentPln: BigDecimal,
    val minimumRealPaymentPln: BigDecimal,
    val zeroPaymentYearCount: Int,
    // Declines are measured against the first real annual payment, not a rolling peak.
    val declineEligible: Boolean,
    val declineAtLeast25Percent: Boolean?,
    val declineAtLeast50Percent: Boolean?,
    val yearsBelowMinimum: Int?,
    val longestRunBelowMinimum: Int?,
)

/** Empirical nearest-rank quantiles; values are observed samples, without interpolation. */
@Serializable
data class MonteCarloMoneyDistribution(
    val sampleCount: Int,
    val minimum: BigDecimal,
    val p10: BigDecimal,
    val p50: BigDecimal,
    val p90: BigDecimal,
    val maximum: BigDecimal,
)

@Serializable
data class MonteCarloAnnualIncome(
    val date: String,
    val realPaidPln: MonteCarloMoneyDistribution,
    val zeroPaymentPathCount: Int,
    val belowMinimumPathCount: Int?,
)

@Serializable
data class MonteCarloStrategySummary(
    val strategyId: String,
    val pathCount: Int,
    val feasiblePathCount: Int,
    val preferredPathCount: Int,
    val highestObjectivePathCount: Int,
    val comparableToBaselinePathCount: Int,
    val objectiveAdvantageVsBaselinePln: MonteCarloMoneyDistribution?,
    // The following distributions include all paths, including infeasible paths.
    val realNetLiquidationValuePln: MonteCarloMoneyDistribution,
    val realWithdrawalsPaidPln: MonteCarloMoneyDistribution,
    val comparisonValuePln: MonteCarloMoneyDistribution,
    val incomeDeclineEligiblePathCount: Int,
    val zeroFirstPaymentPathCount: Int,
    val insufficientIncomeHistoryPathCount: Int,
    val declineAtLeast25PercentPathCount: Int,
    val declineAtLeast50PercentPathCount: Int,
    val anyZeroPaymentPathCount: Int,
    val anyYearBelowMinimumPathCount: Int?,
    val maximumYearsBelowMinimum: Int?,
    val maximumConsecutiveYearsBelowMinimum: Int?,
    val annualIncome: List<MonteCarloAnnualIncome>,
)
