@file:kotlinx.serialization.UseSerializers(net.bobinski.investmentsimulator.engine.DecimalSerializer::class)

package net.bobinski.investmentsimulator.engine

import java.math.BigDecimal
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/** Decimal money is represented as a JSON string to preserve precision. */
object DecimalSerializer : KSerializer<BigDecimal> {
    override val descriptor = PrimitiveSerialDescriptor("Decimal", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: BigDecimal) = encoder.encodeString(value.toPlainString())
    override fun deserialize(decoder: Decoder): BigDecimal {
        val text = decoder.decodeString()
        if (text.length > 128) throw kotlinx.serialization.SerializationException("Decimal input is too long.")
        val value = try {
            text.toBigDecimal()
        } catch (exception: NumberFormatException) {
            throw kotlinx.serialization.SerializationException("Expected a valid decimal string.", exception)
        }
        if (value.precision() > 64 || value.scale() !in -18..36) {
            throw kotlinx.serialization.SerializationException("Decimal precision or exponent is outside the supported range.")
        }
        return value
    }
}

@Serializable
data class ComparisonRequest(
    val startDate: String,
    val endDate: String,
    val initial: InitialPortfolio,
    val assumptions: List<YearAssumptions>,
    val strategies: List<Strategy>,
    val monthlyPlan: MonthlyPlan = MonthlyPlan(),
    val cashFlows: List<CashFlow> = emptyList(),
    val tradingFeeRate: Double = 0.0,
    val capitalGainsTaxRate: Double = 0.19,
    val baselineStrategyId: String,
    val minimumAdvantagePln: BigDecimal = BigDecimal("100"),
    val includeLedger: Boolean = false,
)

/** A single accumulating global equity exposure, one taxable account and at most one OKI. */
@Serializable
data class InitialPortfolio(
    val taxableLots: List<TaxLot> = emptyList(),
    val okiValuePln: BigDecimal = BigDecimal.ZERO,
    val cashPln: BigDecimal = BigDecimal.ZERO,
    val okiOpenedOn: String? = null,
    // Required for simulations beginning after 1 January, even when all values are zero.
    val taxState: OpeningTaxState? = null,
)

@Serializable
data class TaxLot(
    val id: String,
    val acquiredOn: String,
    val marketValuePln: BigDecimal,
    val costBasisPln: BigDecimal,
)

@Serializable
data class OpeningTaxState(
    val realizedGainPln: BigDecimal = BigDecimal.ZERO,
    // Sum of EOD OKI equity values and same-day round-trip corrections before startDate.
    val okiValueDaysPln: BigDecimal = BigDecimal.ZERO,
    val liabilities: List<TaxLiability> = emptyList(),
)

@Serializable
enum class TaxKind { CAPITAL_GAINS, OKI_ASSETS }

@Serializable
data class TaxLiability(val kind: TaxKind, val taxYear: Int, val dueDate: String, val amountPln: BigDecimal)

@Serializable
data class YearAssumptions(
    val year: Int,
    // Effective nominal total return in PLN, after fund costs; shared by both accounts.
    val equityReturnRate: Double,
    val inflationRate: Double,
    val okiTaxRate: Double,
    val okiRateStatus: AssumptionStatus = AssumptionStatus.ASSUMED,
)

@Serializable
enum class AssumptionStatus { ESTABLISHED, ASSUMED }

@Serializable
data class MonthlyPlan(
    val contributionPln: BigDecimal = BigDecimal.ZERO,
    val withdrawalPln: BigDecimal = BigDecimal.ZERO,
    val withdrawalFrom: String? = null,
    val dayOfMonth: Int = 1,
    val indexContributions: Boolean = false,
    val indexWithdrawals: Boolean = true,
    val contributionUntil: String? = null,
)

@Serializable
data class CashFlow(val date: String, val contributionPln: BigDecimal = BigDecimal.ZERO, val withdrawalPln: BigDecimal = BigDecimal.ZERO)

@Serializable
enum class TransferDirection { TAXABLE_TO_OKI, OKI_TO_TAXABLE }

@Serializable
data class TransferPlan(val direction: TransferDirection, val fraction: Double)

@Serializable
enum class WithdrawalOrder { TAXABLE_FIRST, OKI_FIRST, PROPORTIONAL }

@Serializable
data class Strategy(
    val id: String,
    val contributionToOkiFraction: Double,
    val withdrawalOrder: WithdrawalOrder = WithdrawalOrder.TAXABLE_FIRST,
    val initialTransfer: TransferPlan? = null,
)

@Serializable
data class ComparisonResult(
    val engineVersion: String,
    val taxRulesVersion: String,
    val baselineStrategyId: String,
    val preferredStrategyId: String,
    val recommendation: Recommendation,
    val explanation: String,
    val results: List<StrategyResult>,
    val limitations: List<String>,
)

@Serializable
enum class Recommendation { CHANGE, KEEP_BASELINE, NO_CLEAR_ADVANTAGE, WITHDRAWAL_SHORTFALL }

@Serializable
data class StrategyResult(
    val strategyId: String,
    val marketValuePln: BigDecimal,
    val outstandingTaxPln: BigDecimal,
    val liquidationTaxPln: BigDecimal,
    val liquidationFeesPln: BigDecimal,
    val netLiquidationValuePln: BigDecimal,
    val realNetLiquidationValuePln: BigDecimal,
    val advantageVsBaselinePln: BigDecimal,
    val contributionsPln: BigDecimal,
    val withdrawalsPaidPln: BigDecimal,
    val withdrawalShortfallPln: BigDecimal,
    val unpaidTaxPln: BigDecimal,
    val capitalGainsTaxPaidPln: BigDecimal,
    val okiTaxPaidPln: BigDecimal,
    val tradingFeesPln: BigDecimal,
    val initialTransfer: TransferResult?,
    val yearly: List<YearResult>,
    val ledger: List<LedgerEvent>,
)

@Serializable
data class TransferResult(
    val direction: TransferDirection,
    val grossSoldPln: BigDecimal,
    val realizedGainPln: BigDecimal,
    val estimatedAdditionalCapitalGainsTaxPln: BigDecimal,
    val proceedsTransferredPln: BigDecimal,
    val purchasedValuePln: BigDecimal,
    val feesPln: BigDecimal,
)

@Serializable
data class YearResult(
    val year: Int,
    val throughDate: String,
    val taxableValuePln: BigDecimal,
    val taxableCostBasisPln: BigDecimal,
    val okiValuePln: BigDecimal,
    val cashPln: BigDecimal,
    val realizedGainPln: BigDecimal,
    val okiAverageTaxBasePln: BigDecimal,
    val capitalGainsTaxAssessedPln: BigDecimal,
    val okiTaxAssessedPln: BigDecimal,
)

@Serializable
data class LedgerEvent(
    val date: String,
    val type: String,
    val account: String,
    val amountPln: BigDecimal,
    val realizedGainPln: BigDecimal = BigDecimal.ZERO,
    val note: String = "",
)
