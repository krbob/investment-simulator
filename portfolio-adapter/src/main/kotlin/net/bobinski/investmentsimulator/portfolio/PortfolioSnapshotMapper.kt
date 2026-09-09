package net.bobinski.investmentsimulator.portfolio

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.DateTimeException
import java.util.UUID
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import net.bobinski.investmentsimulator.engine.InitialPortfolio
import net.bobinski.investmentsimulator.engine.TaxLot

/** Read-only, offline import of a caller-captured Portfolio bundle. */
object PortfolioSnapshotMapper {
    private val zero = BigDecimal.ZERO
    private val cent = BigDecimal("0.01")
    private val context = MathContext.DECIMAL128

    fun map(request: PortfolioSnapshotRequest): PortfolioSnapshotResult = try {
        mapValidated(request)
    } catch (error: DateTimeException) {
        throw IllegalArgumentException("Invalid date in Portfolio snapshot: ${error.message}", error)
    }

    private fun mapValidated(request: PortfolioSnapshotRequest): PortfolioSnapshotResult {
        val state = request.snapshot
        require(state.schemaVersion == 5) { "Only Portfolio export schemaVersion 5 is supported." }
        val asOf = Instant.parse(state.exportedAt).atZone(ZoneId.of("Europe/Warsaw")).toLocalDate()
        val selected = setOfNotNull(request.selection.taxableAccountId, request.selection.okiAccountId)
        require(request.selection.taxableAccountId != request.selection.okiAccountId) {
            "Taxable and OKI account IDs must differ."
        }
        unique(state.accounts.map { it.id }, "accounts")
        unique(state.instruments.map { it.id }, "instruments")
        unique(state.transactions.map { it.id }, "transactions")
        selected.forEach { id ->
            UUID.fromString(id)
            val account = state.accounts.singleOrNull { it.id == id }
                ?: throw IllegalArgumentException("Selected account $id does not exist.")
            require(account.isActive && account.type == "BROKERAGE") {
                "Selected account $id must be an active BROKERAGE account."
            }
        }
        val instrument = state.instruments.singleOrNull { it.id == request.selection.instrumentId }
            ?: throw IllegalArgumentException("Selected instrument does not exist.")
        UUID.fromString(instrument.id)
        require(instrument.isActive && instrument.kind == "ETF" && instrument.assetClass == "EQUITIES") {
            "The selected instrument must be an active equity ETF."
        }
        require(!instrument.symbol.isNullOrBlank()) { "Selected ETF must have a listing symbol." }
        val transactions = state.transactions.filter { it.accountId in selected }
        transactions.forEach { validateTransaction(it, request.selection.instrumentId, asOf) }
        val ordered = transactions.sortedWith(
            compareBy<PortfolioTransaction>({ LocalDate.parse(it.tradeDate) }, { Instant.parse(it.createdAt) },
                { UUID.fromString(it.id) })
        )
        unique(request.verifiedPurchaseCostsPln.map { it.transactionId }, "purchase cost overrides")
        val overrides = request.verifiedPurchaseCostsPln.associateBy { it.transactionId }
        overrides.values.forEach { override ->
            require(override.costBasisPln >= zero && override.source.isNotBlank()) {
                "Purchase cost overrides need a nonnegative cost and a verification source."
            }
            require(transactions.any { it.id == override.transactionId && it.type == "BUY" &&
                it.accountId == request.selection.taxableAccountId }) {
                "Purchase cost override ${override.transactionId} must refer to a selected taxable BUY."
            }
        }

        val lots = selected.associateWith { mutableListOf<RemainingLot>() }
        val cash = selected.associateWith { mutableMapOf<String, BigDecimal>() }
        ordered.forEach { tx ->
            val accountLots = lots.getValue(tx.accountId)
            when (tx.type) {
                "BUY" -> {
                    val cost = if (tx.accountId == request.selection.taxableAccountId) {
                        overrides[tx.id]?.costBasisPln ?: run {
                            require(tx.currency == "PLN" && tx.taxAmount.signum() == 0) {
                                "BUY ${tx.id} requires a verified PLN acquisition-cost override; accounting FX is not tax FX."
                            }
                            tx.grossAmount + tx.feeAmount
                        }
                    } else zero
                    accountLots += RemainingLot(tx.id, tx.tradeDate, requireNotNull(tx.quantity), cost)
                }
                "SELL" -> consumeFifo(accountLots, requireNotNull(tx.quantity), tx.id)
            }
            val delta = when (tx.type) {
                "BUY" -> -(tx.grossAmount + tx.feeAmount + tx.taxAmount)
                "SELL" -> tx.grossAmount - tx.feeAmount - tx.taxAmount
                "DEPOSIT", "INTEREST" -> tx.grossAmount
                else -> -tx.grossAmount
            }
            val accountCash = cash.getValue(tx.accountId)
            accountCash[tx.currency] = accountCash.getOrDefault(tx.currency, zero) + delta
        }

        val selectedHoldings = request.holdings.filter { it.accountId in selected }
        require(selectedHoldings.all { it.instrumentId == instrument.id }) {
            "Selected accounts contain other holdings; the adapter supports exactly one selected ETF."
        }
        unique(selectedHoldings.map { it.accountId + ":" + it.instrumentId }, "selected holdings")
        val values = mutableMapOf<String, BigDecimal>()
        val valuationDates = mutableSetOf<String>()
        selected.forEach { accountId ->
            val quantity = lots.getValue(accountId).sumOf { it.quantity }
            val holding = selectedHoldings.singleOrNull { it.accountId == accountId }
            if (quantity.signum() == 0) {
                require(holding == null) { "Unexpected holding for an account with zero reconstructed units." }
                values[accountId] = zero
            } else {
                requireNotNull(holding) { "Missing holding for reconstructed position in account $accountId." }
                require(holding.quantity.compareTo(quantity) == 0) {
                    "Holding quantity differs from the canonical FIFO ledger in account $accountId."
                }
                require(holding.kind == "ETF" && holding.assetClass == "EQUITIES" && holding.currency == instrument.currency) {
                    "Holding metadata differs from the selected equity ETF."
                }
                require(holding.valuationStatus == "VALUED" && holding.valuationIssue == null) {
                    "Account $accountId needs a complete holding valuation with upstream status VALUED."
                }
                val value = requireNotNull(holding.currentValuePln) { "Market value is missing." }
                require(value > zero) { "Market value of a positive ETF position must be positive." }
                val date = LocalDate.parse(requireNotNull(holding.valuedAt) { "Valuation date is missing." })
                require(!date.isAfter(asOf)) { "Valuation date is after the source snapshot date." }
                require(ordered.filter { it.accountId == accountId && it.type in setOf("BUY", "SELL") }
                    .all { !LocalDate.parse(it.tradeDate).isAfter(date) }) {
                    "Holding valuation predates a trade in selected account $accountId."
                }
                values[accountId] = engineMoney(value)
                valuationDates += date.toString()
            }
            reconcileCash(accountId, cash.getValue(accountId), request.accountSummaries, quantity > zero)
        }
        val okiId = request.selection.okiAccountId
        if (okiId != null) {
            require(cash.getValue(okiId).getOrDefault("PLN", zero).signum() == 0) {
                "Opening cash inside OKI is unsupported; the engine's cash is outside OKI."
            }
            val opened = LocalDate.parse(requireNotNull(request.okiOpenedOn) { "A selected OKI requires okiOpenedOn." })
            require(!opened.isAfter(asOf)) { "OKI opening date is after the source snapshot date." }
            require(ordered.filter { it.accountId == okiId }.all { !LocalDate.parse(it.tradeDate).isBefore(opened) }) {
                "The selected OKI contains a transaction before okiOpenedOn."
            }
        } else {
            require(request.okiOpenedOn == null) { "okiOpenedOn requires a selected OKI account." }
        }
        val taxableId = request.selection.taxableAccountId
        val initial = InitialPortfolio(
            taxableLots = valueLots(lots.getValue(taxableId), values.getValue(taxableId)),
            okiValuePln = okiId?.let { values.getValue(it) } ?: zero,
            cashPln = engineMoney(cash.getValue(taxableId).getOrDefault("PLN", zero)),
            okiOpenedOn = request.okiOpenedOn,
            taxState = request.openingTaxState,
        )
        return PortfolioSnapshotResult(
            initial = initial,
            source = PortfolioSnapshotProvenance(
                exportedAt = state.exportedAt,
                sourceAsOfDate = asOf.toString(),
                snapshotSchemaVersion = state.schemaVersion,
                taxableAccountId = taxableId,
                okiAccountId = okiId,
                instrumentId = instrument.id,
                symbol = requireNotNull(instrument.symbol),
                valuationDates = valuationDates.sorted(),
                requestSha256 = sha256(Json.encodeToString(request)),
                acquisitionCostSources = request.verifiedPurchaseCostsPln,
            ),
            warnings = listOf(
                "Source snapshot date and simulation start are independent; any gap is a caller assumption.",
                "The caller identifies the ETF as accumulating broad equity; ticker equivalence is not inferred.",
                "Opening realized gains, OKI value-days and unpaid taxes are supplied explicitly, never inferred.",
                "The offline bundle is reconciled but cannot prove that its upstream reads were atomic.",
                "VALUED is an upstream status; inspect valuationDates to assess observation age against exportedAt.",
            ),
        )
    }

    private fun validateTransaction(tx: PortfolioTransaction, instrumentId: String, asOf: LocalDate) {
        UUID.fromString(tx.id)
        Instant.parse(tx.createdAt)
        require(!LocalDate.parse(tx.tradeDate).isAfter(asOf)) { "Transaction ${tx.id} is after the snapshot date." }
        require(tx.grossAmount >= zero && tx.feeAmount >= zero && tx.taxAmount >= zero) {
            "Transaction ${tx.id} contains a negative monetary amount."
        }
        require(tx.currency.matches(Regex("[A-Z]{3}"))) { "Invalid transaction currency for ${tx.id}." }
        when (tx.type) {
            "BUY", "SELL" -> {
                require(tx.instrumentId == instrumentId) { "Unsupported instrument in selected account transaction ${tx.id}." }
                require(tx.quantity != null && tx.quantity > zero) { "Trade ${tx.id} needs positive quantity." }
            }
            "DEPOSIT", "WITHDRAWAL", "FEE", "TAX", "INTEREST" -> {
                require(tx.quantity == null && tx.instrumentId == null && tx.feeAmount.signum() == 0 && tx.taxAmount.signum() == 0) {
                    "Cash transaction ${tx.id} has unsupported instrument, quantity, fee or tax semantics."
                }
            }
            else -> throw IllegalArgumentException("Unsupported transaction type ${tx.type} for ${tx.id}.")
        }
    }

    private fun consumeFifo(lots: MutableList<RemainingLot>, requested: BigDecimal, id: String) {
        require(lots.sumOf { it.quantity } >= requested) { "SELL $id exceeds available FIFO units." }
        var remaining = requested
        while (remaining > zero) {
            val lot = lots.first()
            val taken = minOf(remaining, lot.quantity)
            if (taken.compareTo(lot.quantity) == 0) {
                lots.removeAt(0)
            } else {
                val consumedCost = lot.cost.multiply(taken).divide(lot.quantity, context)
                lot.quantity -= taken
                lot.cost -= consumedCost
            }
            remaining -= taken
        }
    }

    private fun valueLots(lots: List<RemainingLot>, value: BigDecimal): List<TaxLot> {
        val totalQuantity = lots.sumOf { it.quantity }
        var allocated = zero
        return lots.mapIndexed { index, lot ->
            // Upstream currentPricePln is rounded to cents; use the complete position value.
            val lotValue = if (index == lots.lastIndex) value - allocated
                else value.multiply(lot.quantity).divide(totalQuantity, 12, RoundingMode.HALF_EVEN)
            allocated += lotValue
            TaxLot(lot.id, lot.acquiredOn, lotValue, engineMoney(lot.cost))
        }
    }

    private fun reconcileCash(
        accountId: String,
        ledger: Map<String, BigDecimal>,
        summaries: List<PortfolioAccountBalance>,
        hasHoldings: Boolean,
    ) {
        val matching = summaries.filter { it.accountId == accountId }
        require(matching.size == 1) { "Selected account $accountId needs exactly one account summary." }
        val summary = matching.single()
        require(summary.valuationState == "MARK_TO_MARKET" || (!hasHoldings && summary.valuationState == "BOOK_ONLY")) {
            "Selected account $accountId needs a complete market valuation."
        }
        unique(summary.cashBalances.map { it.currency }, "cash currencies in account $accountId")
        val captured = summary.cashBalances.associate { it.currency to it.amount }
        (ledger.keys + captured.keys).forEach { currency ->
            val ledgerAmount = ledger.getOrDefault(currency, zero)
            val capturedAmount = captured.getOrDefault(currency, zero)
            require((ledgerAmount - capturedAmount).abs() <= cent) { "Cash does not reconcile for $accountId/$currency." }
            require(currency == "PLN" || (ledgerAmount.signum() == 0 && capturedAmount.signum() == 0)) {
                "Nonzero foreign cash in account $accountId is unsupported."
            }
            require(ledgerAmount >= zero && capturedAmount >= zero) { "Negative cash is unsupported." }
        }
        val pln = ledger.getOrDefault("PLN", zero)
        require((summary.cashBalancePln - pln).abs() <= cent) { "PLN cash aggregate does not reconcile in $accountId." }
    }

    private fun unique(ids: List<String>, label: String) {
        require(ids.distinct().size == ids.size) { "Duplicate $label are not supported." }
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun engineMoney(value: BigDecimal): BigDecimal = value.setScale(12, RoundingMode.HALF_EVEN).stripTrailingZeros()

    private data class RemainingLot(val id: String, val acquiredOn: String, var quantity: BigDecimal, var cost: BigDecimal)
}
