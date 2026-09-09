package net.bobinski.investmentsimulator

import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import java.io.InputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.system.exitProcess
import net.bobinski.investmentsimulator.engine.ComparisonRequest
import net.bobinski.investmentsimulator.engine.SensitivityAnalysis
import net.bobinski.investmentsimulator.engine.SensitivityRequest
import net.bobinski.investmentsimulator.engine.SimulationEngine
import net.bobinski.investmentsimulator.portfolio.PortfolioAnalysisRequest
import net.bobinski.investmentsimulator.portfolio.PortfolioAnalysisService
import net.bobinski.investmentsimulator.portfolio.PortfolioAnalysisStatus
import net.bobinski.investmentsimulator.portfolio.PortfolioSnapshotMapper
import net.bobinski.investmentsimulator.portfolio.PortfolioSnapshotRequest

fun main(args: Array<String>) {
    exitProcess(runCommand(args.toList()))
}

internal fun runCommand(
    args: List<String>,
    input: InputStream = System.`in`,
    output: PrintStream = System.out,
    error: PrintStream = System.err,
): Int = try {
    var exitCode = 0
    when (args.firstOrNull()) {
        null, "help", "--help", "-h" -> output.println(USAGE)
        "compare" -> {
            require(args.size == 2) { "Usage: compare <request.json|->" }
            val request = simulatorJson.decodeFromString<ComparisonRequest>(readInput(args[1], input))
            output.println(simulatorJson.encodeToString(SimulationEngine.compare(request)))
        }
        "sensitivity" -> {
            require(args.size == 2) { "Usage: sensitivity <request.json|->" }
            val request = simulatorJson.decodeFromString<SensitivityRequest>(readInput(args[1], input))
            output.println(simulatorJson.encodeToString(SensitivityAnalysis.analyze(request)))
        }
        "sensitivity-scenario" -> {
            require(args.size == 3) { "Usage: sensitivity-scenario <request.json|-> <scenario-id>" }
            val request = simulatorJson.decodeFromString<SensitivityRequest>(readInput(args[1], input))
            output.println(simulatorJson.encodeToString(SensitivityAnalysis.resolveScenario(request, args[2])))
        }
        "import-portfolio" -> {
            require(args.size == 2) { "Usage: import-portfolio <bundle.json|->" }
            val request = simulatorJson.decodeFromString<PortfolioSnapshotRequest>(readInput(args[1], input))
            output.println(simulatorJson.encodeToString(PortfolioSnapshotMapper.map(request)))
        }
        "analyze-portfolio" -> {
            require(args.size == 2) { "Usage: analyze-portfolio <request.json|->" }
            val request = simulatorJson.decodeFromString<PortfolioAnalysisRequest>(readInput(args[1], input))
            val result = PortfolioAnalysisService.analyze(request)
            output.println(simulatorJson.encodeToString(result))
            if (result.status != PortfolioAnalysisStatus.COMPLETE) exitCode = 3
        }
        "openapi" -> {
            require(args.size == 1) { "Usage: openapi" }
            output.println(openApiDocument())
        }
        "serve" -> {
            require(args.size == 1) { "Usage: serve (configure HOST and PORT through the environment)" }
            val host = System.getenv("HOST") ?: "127.0.0.1"
            val port = (System.getenv("PORT") ?: "8080").toInt()
            require(port in 1..65535) { "PORT must be between 1 and 65535." }
            embeddedServer(Netty, port = port, host = host) { simulatorModule() }.start(wait = true)
        }
        else -> throw IllegalArgumentException("Unknown command '${args.first()}'.\n$USAGE")
    }
    exitCode
} catch (exception: Exception) {
    error.println("Error: ${exception.message ?: exception::class.simpleName}")
    2
}

private fun readInput(path: String, input: InputStream): String =
    if (path == "-") input.bufferedReader().readText() else Files.readString(Path.of(path))

private val USAGE = """
    investment-simulator
      compare <request.json|->    Compare strategies; JSON result goes to stdout.
      sensitivity <request.json|->  Compare a bounded grid of assumptions and horizons.
      sensitivity-scenario <request.json|-> <scenario-id>  Print one grid scenario for replay with compare.
      import-portfolio <bundle.json|->  Map a portfolio API snapshot to opening balances.
      analyze-portfolio <request.json|->  Analyze a Portfolio bundle and plan; exit 3 for data gaps.
      serve                       Start the HTTP API (HOST=127.0.0.1, PORT=8080).
      openapi                     Print the OpenAPI contract as JSON.
      help                        Show this help.
""".trimIndent()
