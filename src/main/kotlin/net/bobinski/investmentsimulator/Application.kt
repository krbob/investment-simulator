package net.bobinski.investmentsimulator

import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import java.io.InputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.system.exitProcess
import net.bobinski.investmentsimulator.engine.ComparisonRequest
import net.bobinski.investmentsimulator.engine.SimulationEngine
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
    when (args.firstOrNull()) {
        null, "help", "--help", "-h" -> output.println(USAGE)
        "compare" -> {
            require(args.size == 2) { "Usage: compare <request.json|->" }
            val request = simulatorJson.decodeFromString<ComparisonRequest>(readInput(args[1], input))
            output.println(simulatorJson.encodeToString(SimulationEngine.compare(request)))
        }
        "import-portfolio" -> {
            require(args.size == 2) { "Usage: import-portfolio <bundle.json|->" }
            val request = simulatorJson.decodeFromString<PortfolioSnapshotRequest>(readInput(args[1], input))
            output.println(simulatorJson.encodeToString(PortfolioSnapshotMapper.map(request)))
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
    0
} catch (exception: Exception) {
    error.println("Error: ${exception.message ?: exception::class.simpleName}")
    2
}

private fun readInput(path: String, input: InputStream): String =
    if (path == "-") input.bufferedReader().readText() else Files.readString(Path.of(path))

private val USAGE = """
    investment-simulator
      compare <request.json|->    Compare strategies; JSON result goes to stdout.
      import-portfolio <bundle.json|->  Map a portfolio API snapshot to opening balances.
      serve                       Start the HTTP API (HOST=127.0.0.1, PORT=8080).
      openapi                     Print the OpenAPI contract as JSON.
      help                        Show this help.
""".trimIndent()
