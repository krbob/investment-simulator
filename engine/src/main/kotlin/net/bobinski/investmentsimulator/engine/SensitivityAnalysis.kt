package net.bobinski.investmentsimulator.engine

import java.math.BigDecimal
import java.time.DateTimeException
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** Bounded deterministic grid evaluation; all financial calculations stay in SimulationEngine. */
object SensitivityAnalysis {
    const val VERSION = "0.1.0"
    const val MAX_AXIS_VALUES = 16
    const val MAX_SCENARIOS = 128
    const val MAX_STRATEGY_DAYS = 2_000_000L
    const val MAX_OPENING_LOT_REPLAYS = 250_000L

    fun analyze(request: SensitivityRequest): SensitivityResult {
        val prepared = prepare(request)
        var engineLimitations = emptyList<String>()
        val scenarios = prepared.cases.map { case ->
            val comparison = SimulationEngine.compare(case.request)
            if (engineLimitations.isEmpty()) engineLimitations = comparison.limitations
            val feasible = comparison.results.filter(::isFeasible)
            val best = feasible.sortedWith(compareByDescending<StrategyResult> { it.realNetLiquidationValuePln }
                .thenBy { if (it.strategyId == comparison.baselineStrategyId) 0 else 1 }
                .thenBy { it.strategyId }).firstOrNull()
            SensitivityScenario(
                id = case.id,
                coordinates = case.coordinates,
                preservedEstablishedOkiYears = case.request.assumptions.filter { it.okiRateStatus == AssumptionStatus.ESTABLISHED }.map { it.year },
                shiftedAssumedOkiYears = case.request.assumptions.filter {
                    it.okiRateStatus == AssumptionStatus.ASSUMED && case.coordinates.assumedOkiTaxRateShift != 0.0
                }.map { it.year },
                omittedCashFlowCount = prepared.request.baseRequest.cashFlows.size - case.request.cashFlows.size,
                preferredStrategyId = comparison.preferredStrategyId.takeIf { best != null },
                highestValueStrategyId = best?.strategyId,
                recommendation = comparison.recommendation,
                strategies = comparison.results.map { value ->
                    SensitivityStrategyResult(
                        value.strategyId, isFeasible(value), value.realNetLiquidationValuePln,
                        value.netLiquidationValuePln, value.advantageVsBaselinePln,
                        if (best != null && isFeasible(value)) best.realNetLiquidationValuePln - value.realNetLiquidationValuePln else null,
                        value.contributionsPln, value.withdrawalsPaidPln, value.withdrawalShortfallPln,
                        value.unpaidTaxPln, value.outstandingTaxPln, value.liquidationTaxPln, value.liquidationFeesPln,
                        value.capitalGainsTaxPaidPln, value.okiTaxPaidPln, value.tradingFeesPln, value.initialTransfer,
                    )
                },
            )
        }
        val baselineId = prepared.request.baseRequest.baselineStrategyId
        return SensitivityResult(
            VERSION, SimulationEngine.VERSION, SimulationEngine.TAX_RULES_VERSION,
            prepared.request, scenarios.size, prepared.strategyDays,
            scenarios.count { it.preferredStrategyId == null }, scenarios,
            summarize(scenarios, prepared.request.baseRequest),
            transitions(scenarios, prepared.request.axes, baselineId, prepared.request.baseRequest.minimumAdvantagePln),
            listOf(
                "This is a finite deterministic grid, not a forecast or a probability distribution. Counts weight every requested grid cell equally, including cells whose OKI shift has no effect before the first ASSUMED year.",
                "Rate shifts are additive fractional units: 0.01 means one percentage point. Return and inflation shifts apply to each retained year; OKI shifts apply only to years declared ASSUMED. ESTABLISHED OKI rates remain unchanged. No rate is clamped or derived from inflation.",
                "Summaries are grouped by end date. Do not rank different horizons by pooled terminal wealth. Indexed contributions and withdrawals change with inflation under the supplied household plan.",
                "Horizon endpoints are 31 December and cannot extend the supplied base path. Monthly plan dates stay fixed, dated cash flows beyond each horizon are omitted, and opening tax liabilities are retained even if due after the horizon.",
                "Preferred counts include the engine's minimum real-PLN advantage threshold and baseline tie policy. Highest-value counts use feasible terminal value before that threshold; baseline then strategy ID break ties. An all-infeasible scenario has no winner.",
                "Advantage ranges include only cells where both strategy and baseline are feasible. Regret is the difference from the highest feasible real terminal value in the same cell and is absent for infeasible strategies.",
                "Transitions join adjacent sampled coordinates with other axes fixed. They bracket observed changes, not exact thresholds, monotonic behavior or all possible crossings between samples. Baseline and minimum-advantage crossings require both compared strategies to be feasible at both endpoints.",
                "The normalized request and stable scenario IDs reproduce each cell with sensitivity-scenario. Ledgers are disabled for grid evaluation; use compare on a resolved scenario for yearly details or an explicitly enabled ledger.",
            ) + engineLimitations,
        )
    }

    fun resolveScenario(request: SensitivityRequest, scenarioId: String): ComparisonRequest =
        prepare(request).cases.firstOrNull { it.id == scenarioId }?.request
            ?: throw IllegalArgumentException("Unknown sensitivity scenario ID: $scenarioId")

    private data class Case(val id: String, val coordinates: SensitivityCoordinates, val request: ComparisonRequest)
    private data class Prepared(val request: SensitivityRequest, val cases: List<Case>, val strategyDays: Long)

    private fun prepare(input: SensitivityRequest): Prepared {
        val base = input.baseRequest.copy(includeLedger = false)
        val start = date(base.startDate)
        val baseEnd = date(base.endDate)
        validateComparisonRequest(base)
        require(baseEnd.monthValue == 12 && baseEnd.dayOfMonth == 31) { "Sensitivity base endDate must be 31 December." }
        fun shifts(values: List<Double>, name: String): List<Double> {
            require(values.size in 1..MAX_AXIS_VALUES && values.all { it.isFinite() }) {
                "$name must contain 1 to $MAX_AXIS_VALUES finite shifts."
            }
            val normalized = values.map { if (it == 0.0) 0.0 else it }
            require(normalized.distinct().size == normalized.size) { "$name must not contain duplicate shifts." }
            return normalized.sorted()
        }
        val returns = shifts(input.axes.equityReturnRateShifts, "equityReturnRateShifts")
        val inflation = shifts(input.axes.inflationRateShifts, "inflationRateShifts")
        val oki = shifts(input.axes.assumedOkiTaxRateShifts, "assumedOkiTaxRateShifts")
        require(oki.all { it == 0.0 } || base.assumptions.any { it.okiRateStatus == AssumptionStatus.ASSUMED }) {
            "A nonzero assumedOkiTaxRateShift requires at least one ASSUMED OKI year in the base path."
        }
        val ends = input.axes.endDates.ifEmpty { listOf(base.endDate) }
        require(ends.size in 1..MAX_AXIS_VALUES && ends.distinct().size == ends.size) {
            "endDates must contain at most $MAX_AXIS_VALUES unique dates."
        }
        ends.forEach {
            val end = date(it)
            require(end in start..baseEnd && end.monthValue == 12 && end.dayOfMonth == 31) {
                "Every sensitivity end date must be 31 December between startDate and the base endDate."
            }
        }
        val axes = SensitivityAxes(returns, inflation, oki, ends.sorted())
        val scenarioCount = returns.size.toLong() * inflation.size * oki.size * ends.size
        require(scenarioCount <= MAX_SCENARIOS) { "Sensitivity grid exceeds the $MAX_SCENARIOS scenario limit." }
        val strategyDays = ends.sumOf { ChronoUnit.DAYS.between(start, date(it)) + 1 } *
            returns.size * inflation.size * oki.size * base.strategies.size
        require(strategyDays <= MAX_STRATEGY_DAYS) { "Sensitivity grid exceeds the $MAX_STRATEGY_DAYS strategy-day limit; reduce axes, strategies or horizons." }
        require(scenarioCount * base.strategies.size * base.initial.taxableLots.size <= MAX_OPENING_LOT_REPLAYS) {
            "Sensitivity grid exceeds the $MAX_OPENING_LOT_REPLAYS opening-lot replay limit; reduce the grid or strategies."
        }
        val cases = buildList {
            for (end in axes.endDates) for (cpi in inflation) for (rate in oki) for (equity in returns) {
                val endDate = date(end)
                val coordinates = SensitivityCoordinates(equity, cpi, rate, end)
                val resolved = base.copy(
                    endDate = end,
                    assumptions = base.assumptions.filter { it.year <= endDate.year }.map { year ->
                        year.copy(
                            equityReturnRate = shifted(year.equityReturnRate, equity),
                            inflationRate = shifted(year.inflationRate, cpi),
                            okiTaxRate = if (year.okiRateStatus == AssumptionStatus.ASSUMED) shifted(year.okiTaxRate, rate) else year.okiTaxRate,
                        )
                    },
                    cashFlows = base.cashFlows.filter { date(it.date) <= endDate },
                )
                // Validate every transformed cell before any run; never return a partially valid grid.
                try {
                    validateComparisonRequest(resolved)
                } catch (error: IllegalArgumentException) {
                    throw IllegalArgumentException("Invalid sensitivity cell $coordinates: ${error.message}", error)
                }
                add(Case("s${(size + 1).toString().padStart(4, '0')}", coordinates, resolved))
            }
        }
        return Prepared(SensitivityRequest(base, axes), cases, strategyDays)
    }

    private fun shifted(value: Double, shift: Double): Double =
        BigDecimal.valueOf(value).add(BigDecimal.valueOf(shift)).toDouble()

    private fun date(value: String): LocalDate = try {
        require(value.matches(Regex("\\d{4}-\\d{2}-\\d{2}"))) { "Sensitivity dates must use YYYY-MM-DD form." }
        LocalDate.parse(value)
    } catch (error: DateTimeException) {
        throw IllegalArgumentException("Invalid sensitivity calendar date: $value", error)
    }

    private fun isFeasible(result: StrategyResult) = result.withdrawalShortfallPln.signum() == 0 &&
        result.unpaidTaxPln.signum() == 0 && result.netLiquidationValuePln.signum() >= 0

    private fun summarize(scenarios: List<SensitivityScenario>, base: ComparisonRequest): List<SensitivityStrategySummary> =
        scenarios.groupBy { it.coordinates.endDate }.flatMap { (end, cells) ->
            base.strategies.map { strategy ->
                val values = cells.map { it.strategies.first { value -> value.strategyId == strategy.id } }
                val comparable = cells.filter { cell ->
                    cell.strategies.first { it.strategyId == strategy.id }.feasible &&
                        cell.strategies.first { it.strategyId == base.baselineStrategyId }.feasible
                }.map { cell -> cell.strategies.first { it.strategyId == strategy.id }.advantageVsBaselinePln }
                SensitivityStrategySummary(
                    end, strategy.id, cells.size, values.count { it.feasible },
                    cells.count { it.preferredStrategyId == strategy.id },
                    cells.count { it.highestValueStrategyId == strategy.id }, comparable.size,
                    comparable.minOrNull(), comparable.maxOrNull(),
                    values.mapNotNull { it.regretVsBestFeasiblePln }.maxOrNull(),
                )
            }
        }

    private fun transitions(
        scenarios: List<SensitivityScenario>, axes: SensitivityAxes, baselineId: String, threshold: BigDecimal,
    ): List<SensitivityTransition> = buildList {
        val byCoordinate = scenarios.associateBy { it.coordinates }
        for (from in scenarios) {
            val coordinate = from.coordinates
            fun <T> next(values: List<T>, value: T): T? = values.getOrNull(values.indexOf(value) + 1)
            val neighbors = listOfNotNull(
                next(axes.equityReturnRateShifts, coordinate.equityReturnRateShift)?.let { SensitivityAxis.EQUITY_RETURN_RATE_SHIFT to coordinate.copy(equityReturnRateShift = it) },
                next(axes.inflationRateShifts, coordinate.inflationRateShift)?.let { SensitivityAxis.INFLATION_RATE_SHIFT to coordinate.copy(inflationRateShift = it) },
                next(axes.assumedOkiTaxRateShifts, coordinate.assumedOkiTaxRateShift)?.let { SensitivityAxis.ASSUMED_OKI_TAX_RATE_SHIFT to coordinate.copy(assumedOkiTaxRateShift = it) },
                next(axes.endDates, coordinate.endDate)?.let { SensitivityAxis.END_DATE to coordinate.copy(endDate = it) },
            )
            for ((axis, neighbor) in neighbors) {
                val to = byCoordinate.getValue(neighbor)
                fun transition(kind: SensitivityTransitionKind, strategyId: String? = null) {
                    add(SensitivityTransition(axis, kind, from.id, to.id, strategyId))
                }
                if (from.preferredStrategyId != null && to.preferredStrategyId != null && from.preferredStrategyId != to.preferredStrategyId) {
                    transition(SensitivityTransitionKind.PREFERRED_STRATEGY_CHANGE)
                }
                val baselineFrom = from.strategies.first { it.strategyId == baselineId }
                val baselineTo = to.strategies.first { it.strategyId == baselineId }
                for (value in from.strategies) {
                    val other = to.strategies.first { it.strategyId == value.strategyId }
                    if (value.feasible != other.feasible) transition(SensitivityTransitionKind.FEASIBILITY_CHANGE, value.strategyId)
                    if (value.strategyId == baselineId || !value.feasible || !other.feasible || !baselineFrom.feasible || !baselineTo.feasible) continue
                    if (value.advantageVsBaselinePln.signum() != other.advantageVsBaselinePln.signum()) {
                        transition(SensitivityTransitionKind.BASELINE_BREAK_EVEN, value.strategyId)
                    }
                    if ((value.advantageVsBaselinePln > threshold) != (other.advantageVsBaselinePln > threshold)) {
                        transition(SensitivityTransitionKind.MINIMUM_ADVANTAGE_CROSSING, value.strategyId)
                    }
                }
            }
        }
    }
}
