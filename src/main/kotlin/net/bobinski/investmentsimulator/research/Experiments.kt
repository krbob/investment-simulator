package net.bobinski.investmentsimulator.research

import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import net.bobinski.investmentsimulator.engine.*

/** Reproducible illustrative sensitivity sweeps; this entry point performs no network IO. */
fun main(args: Array<String>) {
    require(args.size == 1) { "Usage: ExperimentsKt <output-directory>" }
    val directory = Path.of(args.single())
    Files.createDirectories(directory)
    val horizons = listOf(5, 10, 15, 20, 25, 30, 35, 40).map(Int::toDouble)
    writeSweep(directory.resolve("horizon-rate.csv"), "horizon_years", "oki_rate", horizons,
        listOf(0.002, 0.004, 0.006, 0.0085, 0.010, 0.012, 0.015)) { horizon, rate ->
        scenario(horizon.toInt(), 0.0, rate)
    }
    writeSweep(directory.resolve("horizon-gain.csv"), "horizon_years", "unrealized_gain_fraction", horizons,
        listOf(0.0, 0.1, 0.25, 0.4, 0.6, 0.8)) { horizon, gain ->
        scenario(horizon.toInt(), gain, 0.0085)
    }
    writeSweep(directory.resolve("withdrawal-start-amount.csv"), "withdrawal_start_after_years", "monthly_withdrawal_pln",
        listOf(0.0, 5.0, 10.0, 15.0, 20.0, 25.0), listOf(500.0, 1000.0, 2000.0, 3000.0, 4000.0)) { delay, amount ->
        scenario(30, 0.4, 0.0085, amount, delay.toInt())
    }
    println("Wrote three deterministic sensitivity CSVs to $directory")
}

private fun scenario(
    horizon: Int,
    unrealizedGainFraction: Double,
    okiRate: Double,
    monthlyWithdrawal: Double = 0.0,
    withdrawalDelay: Int = 0,
): ComparisonRequest = ComparisonRequest(
    startDate = "2027-01-01",
    endDate = "${2026 + horizon}-12-31",
    initial = InitialPortfolio(
        taxableLots = listOf(TaxLot("opening", "2026-01-01", BigDecimal("200000"),
            BigDecimal("200000") * (BigDecimal.ONE - BigDecimal.valueOf(unrealizedGainFraction)))),
    ),
    assumptions = (2027..2026 + horizon).map { YearAssumptions(it, 0.07, 0.025, okiRate) },
    strategies = listOf(
        Strategy("keep", 0.0),
        Strategy("migrate", 1.0, initialTransfer = TransferPlan(TransferDirection.TAXABLE_TO_OKI, 1.0)),
    ),
    monthlyPlan = MonthlyPlan(
        withdrawalPln = BigDecimal.valueOf(monthlyWithdrawal),
        withdrawalFrom = "${2027 + withdrawalDelay}-01-01",
    ),
    tradingFeeRate = 0.001,
    baselineStrategyId = "keep",
)

private fun writeSweep(
    path: Path,
    xName: String,
    yName: String,
    xValues: List<Double>,
    yValues: List<Double>,
    request: (Double, Double) -> ComparisonRequest,
) {
    Files.newBufferedWriter(path).use { writer ->
        writer.appendLine("$xName,$yName,baseline_real_net_pln,oki_real_net_pln,advantage_real_pln,baseline_shortfall_pln,oki_shortfall_pln,baseline_unpaid_tax_pln,oki_unpaid_tax_pln,recommendation")
        for (y in yValues) for (x in xValues) {
            val result = SimulationEngine.compare(request(x, y))
            val baseline = result.results.first { it.strategyId == "keep" }
            val migrated = result.results.first { it.strategyId == "migrate" }
            writer.appendLine(listOf(
                x, y, baseline.realNetLiquidationValuePln, migrated.realNetLiquidationValuePln,
                migrated.advantageVsBaselinePln, baseline.withdrawalShortfallPln, migrated.withdrawalShortfallPln,
                baseline.unpaidTaxPln, migrated.unpaidTaxPln, result.recommendation,
            ).joinToString(","))
        }
    }
}
