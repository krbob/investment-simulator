package net.bobinski.investmentsimulator.portfolio

import java.math.BigDecimal
import java.time.DateTimeException
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import net.bobinski.investmentsimulator.engine.ComparisonRequest
import net.bobinski.investmentsimulator.engine.SimulationEngine
import net.bobinski.investmentsimulator.engine.Strategy
import net.bobinski.investmentsimulator.engine.TransferDirection
import net.bobinski.investmentsimulator.engine.TransferPlan

/** Shared offline workflow. Transport may capture upstream data, but this service performs no IO. */
object PortfolioAnalysisService {
    private val descriptions = listOf(
        StrategyDescription("keep-taxable", "Keep positions; contribute to taxable brokerage",
            "Leave existing positions in place and invest unused new contributions on the taxable account."),
        StrategyDescription("new-money-oki", "Keep positions; contribute to OKI",
            "Leave existing positions in place and invest unused new contributions on OKI."),
        StrategyDescription("move-quarter-to-oki", "Move 25% of the taxable position to OKI",
            "Sell 25% of the existing taxable position, account for realized gain and costs, repurchase on OKI and direct new contributions there."),
        StrategyDescription("move-half-to-oki", "Move 50% of the taxable position to OKI",
            "Sell 50% of the existing taxable position, account for realized gain and costs, repurchase on OKI and direct new contributions there."),
        StrategyDescription("move-all-to-oki", "Move the entire taxable position to OKI",
            "Sell the entire existing taxable position, account for realized gain and costs, repurchase on OKI and direct new contributions there."),
    )

    fun analyze(request: PortfolioAnalysisRequest): PortfolioAnalysisResult {
        val portfolio = request.portfolio
        val plan = request.plan
        val gaps = mutableListOf<PortfolioDataGap>()
        fun gap(code: PortfolioDataGapCode, path: String, message: String, transactionId: String? = null, accountId: String? = null) {
            gaps += PortfolioDataGap(code, path, message, transactionId, accountId)
        }
        fun planDate(value: String, path: String): LocalDate? = try {
            require(value.matches(Regex("\\d{4}-\\d{2}-\\d{2}")))
            LocalDate.parse(value)
        } catch (_: IllegalArgumentException) {
            gap(PortfolioDataGapCode.INVALID_SCENARIO, path, "Provide an ISO calendar date in YYYY-MM-DD form.")
            null
        } catch (_: DateTimeException) {
            gap(PortfolioDataGapCode.INVALID_SCENARIO, path, "Provide a valid ISO calendar date in YYYY-MM-DD form.")
            null
        }
        val start = planDate(plan.startDate, "/plan/startDate")
        val end = planDate(plan.endDate, "/plan/endDate")
        if (start != null && end != null && (start.year < 2027 || end < start || end >= start.plusYears(50))) {
            gap(PortfolioDataGapCode.INVALID_SCENARIO, "/plan", "Start in 2027 or later and use a horizon of at most 50 years ending no earlier than its start.")
        }
        if (end != null && (end.monthValue != 12 || end.dayOfMonth != 31)) {
            gap(PortfolioDataGapCode.UNSUPPORTED_FINAL_PARTIAL_YEAR, "/plan/endDate",
                "Use 31 December as the analysis horizon. This workflow does not recommend strategies under the engine's partial-year OKI closure convention.")
        }
        if (plan.lossCarryforwardPln.signum() > 0) {
            gap(PortfolioDataGapCode.UNSUPPORTED_LOSS_CARRYFORWARD, "/plan/lossCarryforwardPln",
                "The engine cannot yet apply losses from earlier years. Preserve this amount; no recommendation is calculated while it would be omitted.")
        } else if (plan.lossCarryforwardPln.signum() < 0) {
            gap(PortfolioDataGapCode.INVALID_SCENARIO, "/plan/lossCarryforwardPln", "Loss carryforward must be nonnegative.")
        }

        val sourceDate = try {
            Instant.parse(portfolio.snapshot.exportedAt).atZone(ZoneId.of("Europe/Warsaw")).toLocalDate()
        } catch (_: DateTimeException) {
            gap(PortfolioDataGapCode.UNSUPPORTED_SNAPSHOT, "/portfolio/snapshot/exportedAt", "The Portfolio export must contain a valid capture timestamp.")
            null
        }
        val selectedIds = setOfNotNull(portfolio.selection.taxableAccountId, portfolio.selection.okiAccountId)
        val selectedHoldings = portfolio.holdings.filter { it.accountId in selectedIds }
        if (sourceDate != null && start != null) {
            when {
                start <= sourceDate -> gap(PortfolioDataGapCode.SNAPSHOT_START_MISMATCH, "/plan/startDate",
                    "Start after the captured calendar date $sourceDate. Captured transactions cannot be backdated or replayed as the beginning of the same day.")
                plan.openingValuationPolicy == OpeningValuationPolicy.REQUIRE_PREVIOUS_DAY_VALUES &&
                    (sourceDate != start.minusDays(1) || selectedHoldings.any { it.valuedAt != start.minusDays(1).toString() }) ->
                    gap(PortfolioDataGapCode.SNAPSHOT_START_MISMATCH, "/plan/openingValuationPolicy",
                        "The capture and selected holding valuations must be dated ${start.minusDays(1)}. Choose USE_CAPTURED_VALUES_UNCHANGED only if those observed values are the intended opening scenario balances; no intervening returns or cash flows are projected.")
            }
        }
        if (portfolio.openingTaxState == null) {
            gap(PortfolioDataGapCode.MISSING_TAX_STATE, "/portfolio/openingTaxState",
                "Supply realized gains, accumulated OKI value-days and outstanding taxes at the simulation start. An empty object explicitly declares zero values and no liabilities; omission means unknown, including on 1 January.")
        } else if (plan.taxStateAsOfDate != plan.startDate) {
            gap(PortfolioDataGapCode.TAX_STATE_DATE_MISMATCH, "/plan/taxStateAsOfDate",
                "Opening tax state must describe the beginning of ${plan.startDate}; set this date only after updating the year-to-date values and prior-year liabilities for that start.")
        }
        if (start?.dayOfYear == 1 && portfolio.openingTaxState != null &&
            (portfolio.openingTaxState.realizedGainPln.signum() != 0 || portfolio.openingTaxState.okiValueDaysPln.signum() != 0)) {
            gap(PortfolioDataGapCode.TAX_STATE_DATE_MISMATCH, "/portfolio/openingTaxState",
                "Year-to-date gains and OKI value-days must be zero at the beginning of 1 January. Carry prior-year tax as dated liabilities, not as this year's gains or values.")
        }
        if (portfolio.selection.okiAccountId != null && portfolio.okiOpenedOn == null) {
            gap(PortfolioDataGapCode.MISSING_OKI_OPENING_DATE, "/portfolio/okiOpenedOn",
                "Provide the selected OKI account's opening date; the asset-tax denominator depends on account ownership, not the first known purchase.",
                accountId = portfolio.selection.okiAccountId)
        }
        val overrides = portfolio.verifiedPurchaseCostsPln.associateBy { it.transactionId }
        portfolio.snapshot.transactions.forEach { transaction ->
            if (transaction.accountId == portfolio.selection.taxableAccountId && transaction.type == "BUY" &&
                (transaction.currency != "PLN" || transaction.taxAmount.signum() != 0) && transaction.id !in overrides) {
                gap(PortfolioDataGapCode.MISSING_VERIFIED_PURCHASE_COST, "/portfolio/verifiedPurchaseCostsPln",
                    "Provide the original purchase's complete verified PLN acquisition cost and its source. Portfolio accounting FX and average cost are not used as tax cost.",
                    transactionId = transaction.id, accountId = transaction.accountId)
            }
        }
        if (gaps.isNotEmpty()) return blocked(gaps)

        val mapped = try {
            PortfolioSnapshotMapper.map(portfolio)
        } catch (error: IllegalArgumentException) {
            return blocked(listOf(PortfolioDataGap(PortfolioDataGapCode.UNSUPPORTED_SNAPSHOT, "/portfolio",
                error.message ?: "The captured portfolio is unsupported or cannot be reconciled.")))
        }
        val initialTaxableValue = mapped.initial.taxableLots.fold(BigDecimal.ZERO) { total, lot -> total + lot.marketValuePln }
        val strategyDescriptions = if (initialTaxableValue.signum() == 0) descriptions.take(2) else descriptions
        val strategies = strategyDescriptions.map { description ->
            val transfer = when (description.id) {
                "move-quarter-to-oki" -> TransferPlan(TransferDirection.TAXABLE_TO_OKI, 0.25)
                "move-half-to-oki" -> TransferPlan(TransferDirection.TAXABLE_TO_OKI, 0.5)
                "move-all-to-oki" -> TransferPlan(TransferDirection.TAXABLE_TO_OKI, 1.0)
                else -> null
            }
            Strategy(description.id, if (description.id == "keep-taxable") 0.0 else 1.0, plan.withdrawalOrder, transfer)
        }
        val resolved = ComparisonRequest(
            startDate = plan.startDate,
            endDate = plan.endDate,
            initial = mapped.initial,
            assumptions = plan.assumptions,
            strategies = strategies,
            monthlyPlan = plan.monthlyPlan,
            cashFlows = plan.cashFlows,
            tradingFeeRate = plan.tradingFeeRate,
            capitalGainsTaxRate = plan.capitalGainsTaxRate,
            baselineStrategyId = "keep-taxable",
            minimumAdvantagePln = plan.minimumAdvantagePln,
            includeLedger = plan.includeLedger,
        )
        val comparison = try {
            SimulationEngine.compare(resolved)
        } catch (error: IllegalArgumentException) {
            return blocked(listOf(PortfolioDataGap(PortfolioDataGapCode.INVALID_SCENARIO, "/plan",
                error.message ?: "Correct the dates, rates, plan or opening tax state.")), mapped.source)
        }
        val assumptions = listOf(
            "The selected account roles and accumulating global equity exposure are declared by the caller; the engine applies no OKI asset allowance.",
            "Opening tax state describes the beginning of ${plan.taxStateAsOfDate}. Missing upstream tax attributes have not been inferred from accounting FX or taxes already paid.",
            "Every strategy uses the same ${plan.withdrawalOrder} funding order, contributions and requested net spending. Unused opening cash remains a spending/tax buffer.",
            if (plan.openingValuationPolicy == OpeningValuationPolicy.USE_CAPTURED_VALUES_UNCHANGED)
                "Values from capture $sourceDate and holding observations ${mapped.source.valuationDates.joinToString()} are reused unchanged at ${plan.startDate}. No prices, contributions or taxes between capture and start are projected; tax state is supplied separately for the start."
            else "Opening balances use the preceding calendar day's capture and holding observations; no extra day of returns is inserted before the simulation.",
        ) + mapped.warnings
        return PortfolioAnalysisResult(PortfolioAnalysisStatus.COMPLETE, emptyList(), strategyDescriptions, assumptions,
            mapped.source, resolved, comparison)
    }

    private fun blocked(gaps: List<PortfolioDataGap>, source: PortfolioSnapshotProvenance? = null) = PortfolioAnalysisResult(
        status = if (gaps.any { it.code.name.startsWith("UNSUPPORTED_") }) PortfolioAnalysisStatus.UNSUPPORTED else PortfolioAnalysisStatus.NEEDS_INPUT,
        dataGaps = gaps,
        strategyDescriptions = emptyList(),
        assumptions = emptyList(),
        source = source,
    )
}
