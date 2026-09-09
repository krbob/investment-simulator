@file:kotlinx.serialization.UseSerializers(net.bobinski.investmentsimulator.engine.DecimalSerializer::class)
@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package net.bobinski.investmentsimulator.portfolio

import java.math.BigDecimal
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonIgnoreUnknownKeys
import net.bobinski.investmentsimulator.engine.InitialPortfolio
import net.bobinski.investmentsimulator.engine.OpeningTaxState

@Serializable
data class PortfolioSnapshotRequest(
    val snapshot: PortfolioStateExport,
    val holdings: List<PortfolioHolding>,
    val accountSummaries: List<PortfolioAccountBalance>,
    val selection: PortfolioSelection,
    val verifiedPurchaseCostsPln: List<PurchaseCostOverride> = emptyList(),
    val okiOpenedOn: String? = null,
    val openingTaxState: OpeningTaxState? = null,
)

@Serializable
data class PortfolioSelection(
    val taxableAccountId: String,
    // The caller explicitly identifies a supported accumulating broad-equity ETF.
    val instrumentId: String,
    val okiAccountId: String? = null,
)

@Serializable
data class PurchaseCostOverride(
    val transactionId: String,
    // Verified total PLN tax acquisition cost of the original purchase, before FIFO disposals.
    val costBasisPln: BigDecimal,
    val source: String,
)

@Serializable
data class PortfolioSnapshotResult(
    val initial: InitialPortfolio,
    val source: PortfolioSnapshotProvenance,
    val warnings: List<String>,
)

@Serializable
data class PortfolioSnapshotProvenance(
    val exportedAt: String,
    val sourceAsOfDate: String,
    val snapshotSchemaVersion: Int,
    val taxableAccountId: String,
    val okiAccountId: String?,
    val instrumentId: String,
    val symbol: String,
    val valuationDates: List<String>,
    val requestSha256: String,
    val acquisitionCostSources: List<PurchaseCostOverride>,
)

@Serializable
@JsonIgnoreUnknownKeys
data class PortfolioStateExport(
    val schemaVersion: Int,
    val exportedAt: String,
    val accounts: List<PortfolioAccount>,
    val instruments: List<PortfolioInstrument>,
    val transactions: List<PortfolioTransaction>,
)

@Serializable
@JsonIgnoreUnknownKeys
data class PortfolioAccount(
    val id: String,
    val type: String,
    val baseCurrency: String,
    val isActive: Boolean,
)

@Serializable
@JsonIgnoreUnknownKeys
data class PortfolioInstrument(
    val id: String,
    val kind: String,
    val assetClass: String,
    val symbol: String?,
    val currency: String,
    val isActive: Boolean,
)

@Serializable
@JsonIgnoreUnknownKeys
data class PortfolioTransaction(
    val id: String,
    val accountId: String,
    val instrumentId: String? = null,
    val type: String,
    val tradeDate: String,
    val quantity: BigDecimal? = null,
    val grossAmount: BigDecimal,
    val feeAmount: BigDecimal,
    val taxAmount: BigDecimal,
    val currency: String,
    val fxRateToPln: BigDecimal? = null,
    val createdAt: String,
)

@Serializable
@JsonIgnoreUnknownKeys
data class PortfolioHolding(
    val accountId: String,
    val instrumentId: String,
    val kind: String,
    val assetClass: String,
    val currency: String,
    val quantity: BigDecimal,
    val currentValuePln: BigDecimal?,
    val valuedAt: String?,
    val valuationStatus: String,
    val valuationIssue: String? = null,
)

@Serializable
@JsonIgnoreUnknownKeys
data class PortfolioAccountBalance(
    val accountId: String,
    val valuationState: String,
    val cashBalancePln: BigDecimal,
    val cashBalances: List<PortfolioCurrencyBalance>,
)

@Serializable
@JsonIgnoreUnknownKeys
data class PortfolioCurrencyBalance(val currency: String, val amount: BigDecimal)
