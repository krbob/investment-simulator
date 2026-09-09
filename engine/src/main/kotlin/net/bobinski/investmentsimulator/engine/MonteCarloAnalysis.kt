package net.bobinski.investmentsimulator.engine

import java.math.BigDecimal
import java.time.DateTimeException
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.Random

/** Seeded annual market paths; the existing accounting engine evaluates every candidate on each path. */
object MonteCarloAnalysis {
    const val VERSION = "0.1.0"
    const val MAX_PATHS = 256
    const val MAX_STRATEGY_DAYS = 10_000_000L
    const val MAX_OPENING_LOT_REPLAYS = 500_000L
    const val MAX_SEED = 281_474_976_710_655L
    const val RETURN_MODEL = "IID_ANNUAL_LOGNORMAL_ARITHMETIC_MEAN_V1"
    const val QUANTILE_METHOD = "EMPIRICAL_NEAREST_RANK"

    fun analyze(request: MonteCarloRequest): MonteCarloResult {
        val prepared = prepare(request)
        var engineLimitations = emptyList<String>()
        val paths = prepared.cases.map { case ->
            val comparison = SimulationEngine.compare(case.request)
            if (engineLimitations.isEmpty()) engineLimitations = comparison.limitations
            val best = comparison.results.filter(::isFeasible).sortedWith(
                compareByDescending<StrategyResult> { it.comparisonValuePln }
                    .thenBy { if (it.strategyId == comparison.baselineStrategyId) 0 else 1 }
                    .thenBy { it.strategyId },
            ).firstOrNull()
            MonteCarloPath(
                id = case.id,
                annualReturns = case.request.assumptions.map { MonteCarloAnnualReturn(it.year, it.equityReturnRate) },
                preferredStrategyId = comparison.preferredStrategyId.takeIf { best != null },
                highestObjectiveStrategyId = best?.strategyId,
                recommendation = comparison.recommendation,
                strategies = comparison.results.map { value ->
                    MonteCarloStrategyResult(
                        value.strategyId, isFeasible(value), value.realNetLiquidationValuePln,
                        value.realWithdrawalsPaidPln, value.realTotalBenefitPln, value.comparisonValuePln,
                        value.objectiveAdvantageVsBaselinePln, value.withdrawalShortfallPln,
                        value.unpaidTaxPln, value.outstandingTaxPln, value.capitalGainsTaxPaidPln,
                        value.okiTaxPaidPln, value.tradingFeesPln, value.annualWithdrawals,
                        incomeMetrics(value.annualWithdrawals, prepared.request.minimumRealAnnualIncomePln),
                    )
                },
            )
        }
        return MonteCarloResult(
            VERSION, SimulationEngine.VERSION, SimulationEngine.TAX_RULES_VERSION,
            prepared.request, RETURN_MODEL, QUANTILE_METHOD,
            effectiveComparisonObjective(prepared.request.baseRequest), paths.size, prepared.strategyDays,
            paths.count { it.preferredStrategyId == null }, paths, summarize(paths, prepared.request),
            listOf(
                "These are model-conditional samples, not calibrated forecasts or guarantees. No aggregate strategy recommendation is inferred from win counts or quantiles. Only supplied candidate strategies are compared.",
                "For each calendar year, nominal PLN return is exp(log1p(base return) - sigma^2/2 + sigma*Z) - 1. The base equityReturnRate is an arithmetic expected simple return, not a CAGR or median; annualLogReturnVolatility is the standard deviation of log gross returns. Independent standard normal shocks are used across years and paths. At zero volatility each path equals the supplied deterministic path exactly.",
                "All strategies share each complete return path. Inflation and all OKI rates/statuses remain exactly as supplied; no inflation, tax-rate or FX process is sampled separately. Nominal PLN equity return must already include fund costs and currency effects.",
                "Only annual returns are random; the accounting engine spreads each annual return smoothly across its days, including partial first years. Monthly cash flows and daily OKI assessments therefore omit within-year market volatility. The model omits fat tails, regime changes and serial dependence.",
                "The versioned generator uses java.util.Random with a 48-bit seed and StrictMath Box-Muller, two nextDouble calls per year even at zero volatility. Paths are generated in path-ID order and years in chronological order. With the same base year schedule and seed, increasing pathCount preserves its prefix and strategy order does not change returns.",
                "Every generated path is validated before any comparison. A draw outside the engine's supported annual return range [-0.99, 5.0] rejects the whole request; no clipping, redrawing or partial report occurs. Repeatedly selecting seeds until a batch passes would condition the sample and bias its tails.",
                "Quantiles use empirical nearest rank: sorted sample at max(1, ceil(p*n)), for p=0.10, 0.50, 0.90. Monetary values are observed rounded engine outputs without interpolation. Small samples give coarse tail resolution; quantiles are not confidence intervals. Annual bands are marginal per-date summaries, not a single possible trajectory.",
                "Wealth, objective and income distributions include every path, including infeasible paths and zero payments. Objective advantage is computed against the baseline on the same path and summarized only where both are feasible; a missing paired distribution is null. Preferred counts apply the objective, baseline tie policy and minimum advantage threshold; highest-objective counts omit that threshold. All-infeasible paths have no winner.",
                "Annual income means the net annual percentage payment, deflated to simulation-start PLN. Income metrics exclude other dated withdrawals; realWithdrawalsPaidPln and the total-benefit objective include all withdrawals. Feasibility checks tax and requested-payment funding, not adequacy of a variable 4% income.",
                "Income declines compare later real annual payments with the first payment on that same strategy/path, not a rolling peak. At least two payments and a positive first payment are required. Declines of at least 25% and 50% include equality; zero first payments and insufficient histories are counted separately and may overlap. Zero payments remain in all-path income distributions.",
                "An optional minimumRealAnnualIncomePln is a constant real annual household floor in simulation-start PLN. A year counts only when its real annual payment is strictly below the floor. Null floor means not assessed; counts and longest runs include infeasible paths. No floor changes the withdrawal policy or ranking objective.",
                "The normalized request, version metadata and path IDs support monte-carlo-path replay. Batch ledgers are disabled; compare a resolved path for full yearly details or an explicitly enabled ledger. Synchronous request budgets limit paths, strategy-days and opening-lot replays.",
            ) + engineLimitations,
        )
    }

    fun resolvePath(request: MonteCarloRequest, pathId: String): ComparisonRequest =
        prepare(request).cases.firstOrNull { it.id == pathId }?.request
            ?: throw IllegalArgumentException("Unknown Monte Carlo path ID: $pathId")

    private data class Case(val id: String, val request: ComparisonRequest)
    private data class Prepared(val request: MonteCarloRequest, val cases: List<Case>, val strategyDays: Long)

    private fun prepare(input: MonteCarloRequest): Prepared {
        require(input.pathCount in 1..MAX_PATHS) { "pathCount must be between 1 and $MAX_PATHS." }
        require(input.seed in 0..MAX_SEED) { "seed must be between 0 and $MAX_SEED (48 bits)." }
        val sigma = input.annualLogReturnVolatility
        require(sigma.isFinite() && sigma in 0.0..0.5) { "annualLogReturnVolatility must be finite and between 0 and 0.5." }
        input.minimumRealAnnualIncomePln?.let {
            require(it.signum() >= 0 && it <= BigDecimal("1000000000000000") && it.scale() <= 12) {
                "minimumRealAnnualIncomePln must be a nonnegative PLN decimal at most 1000000000000000 with at most 12 fractional digits."
            }
        }
        val base = input.baseRequest.copy(includeLedger = false, assumptions = input.baseRequest.assumptions.sortedBy { it.year })
        val start = date(base.startDate)
        val end = date(base.endDate)
        validateComparisonRequest(base)
        require(end.monthValue == 12 && end.dayOfMonth == 31) { "Monte Carlo base endDate must be 31 December." }
        val annualPlan = requireNotNull(base.annualWithdrawalPlan) { "Monte Carlo income analysis requires baseRequest.annualWithdrawalPlan." }
        require(LocalDate.parse(annualPlan.startDate) <= end) { "Monte Carlo annualWithdrawalPlan must start within the simulation." }
        val strategyDays = (ChronoUnit.DAYS.between(start, end) + 1) * base.strategies.size * input.pathCount
        require(strategyDays <= MAX_STRATEGY_DAYS) {
            "Monte Carlo request exceeds the $MAX_STRATEGY_DAYS strategy-day limit; reduce paths, strategies or horizon."
        }
        require(input.pathCount.toLong() * base.strategies.size * base.initial.taxableLots.size <= MAX_OPENING_LOT_REPLAYS) {
            "Monte Carlo request exceeds the $MAX_OPENING_LOT_REPLAYS opening-lot replay limit; reduce paths or strategies."
        }
        val normalized = input.copy(baseRequest = base, annualLogReturnVolatility = if (sigma == 0.0) 0.0 else sigma)
        val random = Random(input.seed)
        val cases = List(input.pathCount) { index ->
            val id = "p${(index + 1).toString().padStart(4, '0')}"
            val assumptions = base.assumptions.map { year ->
                // 1-nextDouble is in (0, 1], so log(0) is impossible. No cached second normal.
                val u1 = 1.0 - random.nextDouble()
                val u2 = random.nextDouble()
                val z = StrictMath.sqrt(-2.0 * StrictMath.log(u1)) * StrictMath.cos(2.0 * StrictMath.PI * u2)
                val rate = if (sigma == 0.0) year.equityReturnRate else
                    StrictMath.expm1(StrictMath.log1p(year.equityReturnRate) - sigma * sigma / 2.0 + sigma * z)
                require(rate.isFinite() && rate in -0.99..5.0) {
                    "Monte Carlo path $id year ${year.year} generated equityReturnRate=$rate outside supported range [-0.99, 5.0]; the entire request was rejected without clipping or redrawing."
                }
                year.copy(equityReturnRate = rate)
            }
            val resolved = base.copy(assumptions = assumptions)
            validateComparisonRequest(resolved)
            Case(id, resolved)
        }
        return Prepared(normalized, cases, strategyDays)
    }

    private fun date(value: String): LocalDate = try {
        require(value.matches(Regex("\\d{4}-\\d{2}-\\d{2}"))) { "Monte Carlo dates must use YYYY-MM-DD form." }
        LocalDate.parse(value)
    } catch (error: DateTimeException) {
        throw IllegalArgumentException("Invalid Monte Carlo calendar date: $value", error)
    }

    private fun isFeasible(value: StrategyResult): Boolean = value.withdrawalShortfallPln.signum() == 0 &&
        value.unpaidTaxPln.signum() == 0 && value.netLiquidationValuePln.signum() >= 0

    private fun incomeMetrics(payments: List<AnnualWithdrawalResult>, floor: BigDecimal?): MonteCarloIncomeMetrics {
        val first = payments.first().realPaidPln
        val eligible = payments.size >= 2 && first.signum() > 0
        fun decline(remaining: String): Boolean? = if (eligible)
            payments.drop(1).any { it.realPaidPln <= first * BigDecimal(remaining) } else null
        var currentRun = 0
        var longestRun = 0
        var belowCount = 0
        if (floor != null) for (payment in payments) {
            if (payment.realPaidPln < floor) {
                belowCount++
                currentRun++
                longestRun = maxOf(longestRun, currentRun)
            } else currentRun = 0
        }
        return MonteCarloIncomeMetrics(
            payments.size, first, payments.minOf { it.realPaidPln }, payments.count { it.realPaidPln.signum() == 0 },
            eligible, decline("0.75"), decline("0.50"), belowCount.takeIf { floor != null }, longestRun.takeIf { floor != null },
        )
    }

    private fun distribution(values: List<BigDecimal>): MonteCarloMoneyDistribution {
        val sorted = values.sorted()
        fun quantile(percent: Int) = sorted[maxOf(1, (percent * sorted.size + 99) / 100) - 1]
        return MonteCarloMoneyDistribution(sorted.size, sorted.first(), quantile(10), quantile(50), quantile(90), sorted.last())
    }

    private fun summarize(paths: List<MonteCarloPath>, request: MonteCarloRequest): List<MonteCarloStrategySummary> =
        request.baseRequest.strategies.map { strategy ->
            val values = paths.map { path -> path.strategies.first { it.strategyId == strategy.id } }
            val paired = paths.filter { path ->
                path.strategies.first { it.strategyId == strategy.id }.feasible &&
                    path.strategies.first { it.strategyId == request.baseRequest.baselineStrategyId }.feasible
            }.map { path -> path.strategies.first { it.strategyId == strategy.id }.objectiveAdvantageVsBaselinePln }
            val floor = request.minimumRealAnnualIncomePln
            MonteCarloStrategySummary(
                strategyId = strategy.id,
                pathCount = paths.size,
                feasiblePathCount = values.count { it.feasible },
                preferredPathCount = paths.count { it.preferredStrategyId == strategy.id },
                highestObjectivePathCount = paths.count { it.highestObjectiveStrategyId == strategy.id },
                comparableToBaselinePathCount = paired.size,
                objectiveAdvantageVsBaselinePln = paired.takeIf { it.isNotEmpty() }?.let(::distribution),
                realNetLiquidationValuePln = distribution(values.map { it.realNetLiquidationValuePln }),
                realWithdrawalsPaidPln = distribution(values.map { it.realWithdrawalsPaidPln }),
                comparisonValuePln = distribution(values.map { it.comparisonValuePln }),
                incomeDeclineEligiblePathCount = values.count { it.income.declineEligible },
                zeroFirstPaymentPathCount = values.count { it.income.firstRealPaymentPln.signum() == 0 },
                insufficientIncomeHistoryPathCount = values.count { it.income.annualPaymentCount < 2 },
                declineAtLeast25PercentPathCount = values.count { it.income.declineAtLeast25Percent == true },
                declineAtLeast50PercentPathCount = values.count { it.income.declineAtLeast50Percent == true },
                anyZeroPaymentPathCount = values.count { it.income.zeroPaymentYearCount > 0 },
                anyYearBelowMinimumPathCount = if (floor != null) values.count { requireNotNull(it.income.yearsBelowMinimum) > 0 } else null,
                maximumYearsBelowMinimum = values.mapNotNull { it.income.yearsBelowMinimum }.maxOrNull(),
                maximumConsecutiveYearsBelowMinimum = values.mapNotNull { it.income.longestRunBelowMinimum }.maxOrNull(),
                annualIncome = values.flatMap { it.annualWithdrawals }.groupBy { it.date }.toSortedMap().map { (date, payments) ->
                    MonteCarloAnnualIncome(
                        date, distribution(payments.map { it.realPaidPln }), payments.count { it.realPaidPln.signum() == 0 },
                        if (floor != null) payments.count { it.realPaidPln < floor } else null,
                    )
                },
            )
        }
}
