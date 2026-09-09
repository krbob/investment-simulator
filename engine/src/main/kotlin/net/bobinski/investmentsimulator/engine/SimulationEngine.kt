package net.bobinski.investmentsimulator.engine

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.pow

/** Deterministic accounting for one accumulating global equity exposure, in PLN. */
object SimulationEngine {
    const val VERSION = "0.1.0"
    const val TAX_RULES_VERSION = "PL-2026-1098-global-equity-v1"

    fun compare(request: ComparisonRequest): ComparisonResult {
        validate(request)
        val runs = request.strategies.map { Runner(request, it).run() }
        val baseline = runs.first { it.strategyId == request.baselineStrategyId }
        val results = runs.map {
            it.copy(advantageVsBaselinePln = it.realNetLiquidationValuePln - baseline.realNetLiquidationValuePln)
        }
        fun isFeasible(result: StrategyResult) = result.withdrawalShortfallPln.signum() == 0 &&
            result.unpaidTaxPln.signum() == 0 && result.netLiquidationValuePln.signum() >= 0
        val feasible = results.filter(::isFeasible)
        val best = (feasible.ifEmpty { results }).sortedWith(
            compareBy<StrategyResult> { it.withdrawalShortfallPln + it.unpaidTaxPln + (-it.netLiquidationValuePln).positive() }
                .thenByDescending { it.realNetLiquidationValuePln }
                .thenBy { if (it.strategyId == request.baselineStrategyId) 0 else 1 }
                .thenBy { it.strategyId },
        ).first()
        val baselineFeasible = isFeasible(baseline)
        val recommendation = when {
            feasible.isEmpty() -> Recommendation.WITHDRAWAL_SHORTFALL
            best.strategyId == baseline.strategyId -> Recommendation.KEEP_BASELINE
            !baselineFeasible -> Recommendation.CHANGE
            best.advantageVsBaselinePln <= request.minimumAdvantagePln -> Recommendation.NO_CLEAR_ADVANTAGE
            else -> Recommendation.CHANGE
        }
        val preferred = if (recommendation == Recommendation.NO_CLEAR_ADVANTAGE) baseline.strategyId else best.strategyId
        return ComparisonResult(
            VERSION, TAX_RULES_VERSION, baseline.strategyId, preferred, recommendation,
            when (recommendation) {
                Recommendation.CHANGE -> "The selected strategy meets the specified cash needs and improves the objective under this deterministic scenario."
                Recommendation.KEEP_BASELINE -> "The baseline meets the specified cash needs and has the highest terminal net value among the supplied strategies."
                Recommendation.NO_CLEAR_ADVANTAGE -> "The terminal advantage does not exceed the requested threshold in today's PLN; keep the baseline."
                Recommendation.WITHDRAWAL_SHORTFALL -> "Every supplied strategy has unmet withdrawals, overdue tax or terminal insolvency after settling outstanding liabilities. No feasible strategy was found."
            },
            results,
            listOf(
                "One taxpayer, one taxable account with FIFO lots, one OKI, and one accumulating global equity exposure; no OKI asset allowance is applied.",
                "Annual returns, inflation and OKI rates are explicit scenario assumptions, not forecasts or independently verified rates.",
                "Both accounts follow the same smooth daily PLN total-return path, after fund costs. Trading fees are modeled separately.",
                "Household contributions are identical across strategies. Taxes and net spending use cash first, then portfolio sales; no unrecorded external tax funding is allowed.",
                "Realized gains and losses are netted within each calendar year. Prior-year loss carryforwards, other investment income and foreign investor-level withholding are not modeled.",
                "Annual taxable bases and taxes are each rounded to whole PLN. Payment is on 30 April (capital gains) or 31 May (OKI) of the next year, without holiday adjustments or late-payment interest.",
                "Terminal liquidation is an analytical valuation at the final price, netting disposal gains against the final open tax year. It adds no simulated day and keeps the accrued OKI assessment unchanged.",
                "For a partial final calendar year, OKI ownership is assumed to end at the horizon. Keeping an empty account until year-end would change the denominator and is not modeled; use a 31 December horizon for full-year comparisons.",
                "Cash earns zero interest. Initial cash remains a spending/tax buffer; only unused new contributions are invested.",
            ),
        )
    }
}

private val MC = MathContext.DECIMAL128
private val ZERO = BigDecimal.ZERO
private val ONE = BigDecimal.ONE
private val EPSILON = BigDecimal("0.000000000001")
private fun BigDecimal.money(): BigDecimal = setScale(2, RoundingMode.HALF_UP)
private fun BigDecimal.positive(): BigDecimal = max(ZERO)
private fun ratio(a: BigDecimal, b: BigDecimal): BigDecimal = a.divide(b, MC)
private fun multiply(a: BigDecimal, b: BigDecimal): BigDecimal = a.multiply(b, MC)
private fun date(value: String): LocalDate = try {
    LocalDate.parse(value)
} catch (exception: java.time.DateTimeException) {
    throw IllegalArgumentException("Invalid ISO date: $value", exception)
}

private fun validate(request: ComparisonRequest) {
    val start = date(request.startDate)
    val end = date(request.endDate)
    require(start.year >= 2027 && end >= start && end <= start.plusYears(50).minusDays(1)) {
        "Simulation must start in 2027 or later and cover between one day and 50 years."
    }
    require(request.assumptions.map { it.year }.sorted() == (start.year..end.year).toList()) {
        "Provide exactly one set of assumptions for each simulated calendar year."
    }
    fun rate(value: Double, name: String, minimum: Double = 0.0, maximum: Double = 1.0) {
        require(value.isFinite() && value >= minimum && value <= maximum) { "$name is outside its supported range [$minimum, $maximum]." }
    }
    fun amount(value: BigDecimal, name: String, signed: Boolean = false) {
        require((signed || value.signum() >= 0) && value.abs() <= BigDecimal("1000000000000000") && value.scale() <= 12) {
            "$name must be a finite supported PLN decimal with at most 12 fractional digits."
        }
    }
    request.assumptions.forEach {
        rate(it.equityReturnRate, "equityReturnRate", -0.99, 5.0)
        rate(it.inflationRate, "inflationRate", -0.5, 1.0)
        rate(it.okiTaxRate, "okiTaxRate")
    }
    rate(request.tradingFeeRate, "tradingFeeRate", 0.0, 0.1)
    rate(request.capitalGainsTaxRate, "capitalGainsTaxRate")
    require(request.strategies.size in 1..32 && request.strategies.map { it.id }.distinct().size == request.strategies.size) {
        "Provide 1 to 32 strategies with unique IDs."
    }
    require(request.strategies.any { it.id == request.baselineStrategyId }) { "baselineStrategyId must identify a supplied strategy." }
    request.strategies.forEach {
        require(it.id.isNotBlank() && it.id.length <= 100) { "Strategy IDs must contain 1 to 100 characters." }
        rate(it.contributionToOkiFraction, "contributionToOkiFraction")
        it.initialTransfer?.let { transfer -> rate(transfer.fraction, "initialTransfer.fraction") }
    }
    amount(request.minimumAdvantagePln, "minimumAdvantagePln")
    val initial = request.initial
    require(initial.taxableLots.size <= 5000 && initial.taxableLots.map { it.id }.distinct().size == initial.taxableLots.size) {
        "Provide at most 5000 lots with unique IDs."
    }
    initial.taxableLots.forEach {
        require(it.id.isNotBlank() && date(it.acquiredOn) <= start) { "Each lot needs an ID and acquisition date no later than startDate." }
        amount(it.marketValuePln, "marketValuePln")
        amount(it.costBasisPln, "costBasisPln")
        require(it.marketValuePln.signum() > 0) { "Each opening lot must have positive market value." }
    }
    amount(initial.okiValuePln, "okiValuePln")
    amount(initial.cashPln, "cashPln")
    val opened = initial.okiOpenedOn?.let(::date)
    require(opened == null || opened <= start) { "okiOpenedOn must not be after startDate." }
    require(initial.okiValuePln.signum() == 0 || opened != null) { "okiOpenedOn is required for an existing OKI position." }
    require(start.dayOfYear == 1 || initial.taxState != null) { "Opening taxState is required when starting after 1 January; pass explicit zeros if applicable." }
    initial.taxState?.let { state ->
        amount(state.realizedGainPln, "realizedGainPln", signed = true)
        require(start.dayOfYear != 1 || state.realizedGainPln.signum() == 0) {
            "Opening realized gains must be zero on 1 January; prior-year tax belongs in opening liabilities."
        }
        amount(state.okiValueDaysPln, "okiValueDaysPln")
        require(state.okiValueDaysPln.signum() == 0 || (opened != null && opened < start && start.dayOfYear > 1)) {
            "Opening OKI value-days require account ownership before startDate in the same calendar year."
        }
        require(state.liabilities.size <= 100) { "Too many opening tax liabilities." }
        state.liabilities.forEach {
            amount(it.amountPln, "liability.amountPln")
            require(it.taxYear < start.year && date(it.dueDate).year > it.taxYear) { "Opening liabilities must refer to completed tax years." }
        }
    }
    require(request.monthlyPlan.dayOfMonth in 1..28) { "monthlyPlan.dayOfMonth must be between 1 and 28." }
    amount(request.monthlyPlan.contributionPln, "monthlyPlan.contributionPln")
    amount(request.monthlyPlan.withdrawalPln, "monthlyPlan.withdrawalPln")
    request.monthlyPlan.withdrawalFrom?.let(::date)
    request.monthlyPlan.contributionUntil?.let(::date)
    require(request.cashFlows.size <= 5000) { "Provide at most 5000 dated cash flows." }
    request.cashFlows.forEach {
        require(date(it.date) in start..end) { "Cash flow dates must fall within the simulation." }
        amount(it.contributionPln, "cashFlow.contributionPln")
        amount(it.withdrawalPln, "cashFlow.withdrawalPln")
    }
}

private data class PositionLot(var units: BigDecimal, var basis: BigDecimal)
private data class PendingTax(val kind: TaxKind, val taxYear: Int, val due: LocalDate, var amount: BigDecimal)
private data class Sale(val gross: BigDecimal, val net: BigDecimal, val gain: BigDecimal, val fee: BigDecimal)

private class Runner(private val request: ComparisonRequest, private val strategy: Strategy) {
    private val start = date(request.startDate)
    private val end = date(request.endDate)
    private val assumptions = request.assumptions.associateBy { it.year }
    private val cashFlows = request.cashFlows.groupBy { date(it.date) }
    private val monthly = request.monthlyPlan
    private val withdrawalFrom = monthly.withdrawalFrom?.let(::date) ?: start
    private val contributionUntil = monthly.contributionUntil?.let(::date) ?: end
    private val feeRate = BigDecimal.valueOf(request.tradingFeeRate)
    private val gainsRate = BigDecimal.valueOf(request.capitalGainsTaxRate)
    private val okiFraction = BigDecimal.valueOf(strategy.contributionToOkiFraction)
    private val lots = ArrayDeque(request.initial.taxableLots.sortedBy { date(it.acquiredOn) }.map { PositionLot(it.marketValuePln, it.costBasisPln) })
    private var taxableUnits = lots.fold(ZERO) { sum, lot -> sum + lot.units }
    private var okiUnits = request.initial.okiValuePln
    private var price = ONE
    private var cpi = ONE
    private var cash = request.initial.cashPln
    private var opened = request.initial.okiOpenedOn?.let(::date)
    private var gainYtd = request.initial.taxState?.realizedGainPln ?: ZERO
    private var okiValueDays = request.initial.taxState?.okiValueDaysPln ?: ZERO
    private val pending = request.initial.taxState?.liabilities.orEmpty().map {
        PendingTax(it.kind, it.taxYear, date(it.dueDate), it.amountPln)
    }.toMutableList()
    private var contributionTotal = ZERO
    private var withdrawalTotal = ZERO
    private var shortfallTotal = ZERO
    private var gainsTaxPaid = ZERO
    private var okiTaxPaid = ZERO
    private var feeTotal = ZERO
    private var dayOkiDeposits = ZERO
    private var dayOkiWithdrawals = ZERO
    private var today = start
    private val events = mutableListOf<LedgerEvent>()
    private val years = mutableListOf<YearResult>()
    private var transferResult: TransferResult? = null

    private fun taxableValue() = multiply(taxableUnits, price)
    private fun okiValue() = multiply(okiUnits, price)
    private fun basis() = lots.fold(ZERO) { sum, lot -> sum + lot.basis }
    private fun assessedTax(base: BigDecimal, rate: BigDecimal) = multiply(base.positive().setScale(0, RoundingMode.HALF_UP), rate).setScale(0, RoundingMode.HALF_UP)
    private fun gainsTax(gain: BigDecimal) = assessedTax(gain, gainsRate)
    private fun log(type: String, account: String, amount: BigDecimal, gain: BigDecimal = ZERO, note: String = "") {
        if (request.includeLedger) events += LedgerEvent(today.toString(), type, account, amount.money(), gain.money(), note)
    }

    fun run(): StrategyResult {
        val factors = assumptions.mapValues { (year, assumption) ->
            val days = LocalDate.of(year, 1, 1).lengthOfYear().toDouble()
            BigDecimal.valueOf((1.0 + assumption.equityReturnRate).pow(1.0 / days)) to
                BigDecimal.valueOf((1.0 + assumption.inflationRate).pow(1.0 / days))
        }
        while (today <= end) {
            dayOkiDeposits = ZERO
            dayOkiWithdrawals = ZERO
            val oldCash = cash
            val (contribution, withdrawal) = flowForToday()
            cash += contribution
            contributionTotal += contribution
            if (contribution.signum() > 0) log("CONTRIBUTION", "HOUSEHOLD", contribution)
            if (today == start) executeInitialTransfer()
            payTaxes()
            if (withdrawal.signum() > 0) {
                val paid = fundOutflow(withdrawal)
                withdrawalTotal += paid
                shortfallTotal += (withdrawal - paid).positive()
                log("WITHDRAWAL", "HOUSEHOLD", paid)
                if (withdrawal - paid > EPSILON) log("WITHDRAWAL_SHORTFALL", "HOUSEHOLD", withdrawal - paid)
            }
            val investable = (cash - oldCash).positive()
            if (investable.signum() > 0) {
                cash -= investable
                val toOki = multiply(investable, okiFraction)
                buyOki(toOki)
                buyTaxable(investable - toOki)
            }
            val (growth, inflation) = factors.getValue(today.year)
            price = multiply(price, growth)
            cpi = multiply(cpi, inflation)
            // EOD valuations plus anti-round-trip adjustment for actual OKI cash crossings.
            okiValueDays += okiValue() + dayOkiDeposits.min(dayOkiWithdrawals)
            if (today.dayOfYear == today.lengthOfYear() || today == end) {
                val summary = yearSummary()
                years += summary
                if (today < end) {
                    assess(TaxKind.CAPITAL_GAINS, summary.capitalGainsTaxAssessedPln, LocalDate.of(today.year + 1, 4, 30))
                    assess(TaxKind.OKI_ASSETS, summary.okiTaxAssessedPln, LocalDate.of(today.year + 1, 5, 31))
                    gainYtd = ZERO
                    okiValueDays = ZERO
                }
            }
            today = today.plusDays(1)
        }
        today = end
        val final = years.last()
        val oldLiabilities = pending.fold(ZERO) { sum, liability -> sum + liability.amount }
        val currentGainsTax = final.capitalGainsTaxAssessedPln
        val outstanding = oldLiabilities + currentGainsTax + final.okiTaxAssessedPln
        val marketValue = taxableValue() + okiValue() + cash
        val exitFees = multiply(taxableValue() + okiValue(), feeRate)
        val terminalGain = multiply(taxableValue(), ONE - feeRate) - basis()
        val extraExitTax = gainsTax(gainYtd + terminalGain) - currentGainsTax
        val netValue = marketValue - outstanding - extraExitTax - exitFees
        return StrategyResult(
            strategy.id, marketValue.money(), outstanding.money(), extraExitTax.money(), exitFees.money(),
            netValue.money(), ratio(netValue, cpi).money(), ZERO.money(), contributionTotal.money(),
            withdrawalTotal.money(), shortfallTotal.money(),
            pending.filter { it.due <= end }.fold(ZERO) { sum, liability -> sum + liability.amount }.money(),
            gainsTaxPaid.money(), okiTaxPaid.money(), feeTotal.money(), transferResult, years, events,
        )
    }

    private fun flowForToday(): Pair<BigDecimal, BigDecimal> {
        var contribution = ZERO
        var withdrawal = ZERO
        if (today.dayOfMonth == monthly.dayOfMonth) {
            if (today <= contributionUntil) contribution = multiply(monthly.contributionPln, if (monthly.indexContributions) cpi else ONE).money()
            if (today >= withdrawalFrom) withdrawal = multiply(monthly.withdrawalPln, if (monthly.indexWithdrawals) cpi else ONE).money()
        }
        cashFlows[today].orEmpty().forEach {
            contribution += it.contributionPln
            withdrawal += it.withdrawalPln
        }
        return contribution to withdrawal
    }

    private fun executeInitialTransfer() {
        val plan = strategy.initialTransfer ?: return
        val fraction = BigDecimal.valueOf(plan.fraction)
        val beforeTax = gainsTax(gainYtd)
        val oldFees = feeTotal
        val sale: Sale
        val purchased: BigDecimal
        when (plan.direction) {
            TransferDirection.TAXABLE_TO_OKI -> {
                sale = sellTaxable(multiply(taxableValue(), fraction))
                purchased = buyOki(sale.net)
            }
            TransferDirection.OKI_TO_TAXABLE -> {
                sale = sellOki(multiply(okiValue(), fraction))
                purchased = buyTaxable(sale.net)
            }
        }
        transferResult = TransferResult(
            plan.direction, sale.gross.money(), sale.gain.money(), (gainsTax(gainYtd) - beforeTax).money(),
            sale.net.money(), purchased.money(), (feeTotal - oldFees).money(),
        )
        log("TRANSFER", plan.direction.name, sale.net, sale.gain, "Capital gains liability is settled in the following tax year; all net proceeds are reinvested now.")
    }

    private fun buyTaxable(budget: BigDecimal): BigDecimal {
        if (budget.signum() <= 0) return ZERO
        val value = ratio(budget, ONE + feeRate)
        val units = ratio(value, price)
        lots.addLast(PositionLot(units, budget))
        taxableUnits += units
        feeTotal += budget - value
        log("BUY", "TAXABLE", value, note = "Acquisition basis includes purchase fee ${ (budget - value).money() } PLN.")
        return value
    }

    private fun buyOki(budget: BigDecimal): BigDecimal {
        if (budget.signum() <= 0) return ZERO
        if (opened == null) opened = today
        val value = ratio(budget, ONE + feeRate)
        okiUnits += ratio(value, price)
        feeTotal += budget - value
        dayOkiDeposits += budget
        log("BUY", "OKI", value, note = "Cash deposit ${budget.money()} PLN; purchase fee ${(budget - value).money()} PLN.")
        return value
    }

    private fun sellTaxable(requestedGross: BigDecimal): Sale {
        val units = ratio(requestedGross.min(taxableValue()).positive(), price).min(taxableUnits)
        if (units.signum() <= 0) return Sale(ZERO, ZERO, ZERO, ZERO)
        var remaining = units
        var cost = ZERO
        while (remaining.signum() > 0 && lots.isNotEmpty()) {
            val lot = lots.first()
            val taken = remaining.min(lot.units)
            val allocatedBasis = if (taken == lot.units) lot.basis else multiply(lot.basis, ratio(taken, lot.units))
            cost += allocatedBasis
            lot.units -= taken
            lot.basis -= allocatedBasis
            remaining -= taken
            if (lot.units.signum() == 0) lots.removeFirst()
        }
        taxableUnits -= units
        val gross = multiply(units, price)
        val fee = multiply(gross, feeRate)
        val net = gross - fee
        val gain = net - cost
        gainYtd += gain
        feeTotal += fee
        log("SELL", "TAXABLE", gross, gain, "FIFO; net proceeds ${net.money()} PLN; sale fee ${fee.money()} PLN.")
        return Sale(gross, net, gain, fee)
    }

    private fun sellOki(requestedGross: BigDecimal): Sale {
        val units = ratio(requestedGross.min(okiValue()).positive(), price).min(okiUnits)
        if (units.signum() <= 0) return Sale(ZERO, ZERO, ZERO, ZERO)
        okiUnits -= units
        val gross = multiply(units, price)
        val fee = multiply(gross, feeRate)
        val net = gross - fee
        dayOkiWithdrawals += net
        feeTotal += fee
        log("SELL", "OKI", gross, note = "Cash withdrawal ${net.money()} PLN; sale fee ${fee.money()} PLN.")
        return Sale(gross, net, ZERO, fee)
    }

    private fun fundOutflow(amount: BigDecimal): BigDecimal {
        val fromCash = cash.min(amount)
        cash -= fromCash
        var remaining = amount - fromCash
        if (remaining <= EPSILON) return amount
        val taxable = taxableValue()
        val oki = okiValue()
        fun fromTaxable(target: BigDecimal) = sellTaxable(ratio(target, ONE - feeRate)).net
        fun fromOki(target: BigDecimal) = sellOki(ratio(target, ONE - feeRate)).net
        when (strategy.withdrawalOrder) {
            WithdrawalOrder.TAXABLE_FIRST -> {
                remaining -= fromTaxable(remaining)
                remaining -= fromOki(remaining.positive())
            }
            WithdrawalOrder.OKI_FIRST -> {
                remaining -= fromOki(remaining)
                remaining -= fromTaxable(remaining.positive())
            }
            WithdrawalOrder.PROPORTIONAL -> if (taxable + oki > ZERO) {
                val taxableTarget = multiply(remaining, ratio(taxable, taxable + oki))
                remaining -= fromTaxable(taxableTarget)
                remaining -= fromOki(remaining.positive())
                remaining -= fromTaxable(remaining.positive())
            }
        }
        return if (remaining <= EPSILON) amount else amount - remaining.positive()
    }

    private fun payTaxes() {
        pending.sortedWith(compareBy<PendingTax> { it.due }.thenBy { it.kind.name }).forEach {
            if (it.due <= today && it.amount.signum() > 0) {
                val paid = fundOutflow(it.amount)
                it.amount = (it.amount - paid).positive()
                when (it.kind) {
                    TaxKind.CAPITAL_GAINS -> gainsTaxPaid += paid
                    TaxKind.OKI_ASSETS -> okiTaxPaid += paid
                }
                if (paid.signum() > 0) log("TAX_PAYMENT", it.kind.name, paid, note = "Tax year ${it.taxYear}; due ${it.due}.")
            }
        }
        pending.removeAll { it.amount <= EPSILON }
    }

    private fun okiAverage(): BigDecimal {
        val opening = opened ?: return ZERO
        val first = maxOf(opening, LocalDate.of(today.year, 1, 1))
        val days = ChronoUnit.DAYS.between(first, today) + 1
        return if (days <= 0) ZERO else ratio(okiValueDays, BigDecimal.valueOf(days))
    }

    private fun yearSummary(): YearResult {
        val average = okiAverage()
        return YearResult(
            today.year, today.toString(), taxableValue().money(), basis().money(), okiValue().money(), cash.money(),
            gainYtd.money(), average.money(), gainsTax(gainYtd).money(),
            assessedTax(average, BigDecimal.valueOf(assumptions.getValue(today.year).okiTaxRate)).money(),
        )
    }

    private fun assess(kind: TaxKind, amount: BigDecimal, due: LocalDate) {
        if (amount.signum() > 0) {
            pending += PendingTax(kind, today.year, due, amount)
            log("TAX_ASSESSED", kind.name, amount, note = "Due $due.")
        }
    }
}
