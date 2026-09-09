package net.bobinski.investmentsimulator

import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.math.BigDecimal
import java.nio.file.Files
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.bobinski.investmentsimulator.engine.ComparisonRequest
import net.bobinski.investmentsimulator.engine.ComparisonResult
import net.bobinski.investmentsimulator.engine.InitialPortfolio
import net.bobinski.investmentsimulator.engine.SensitivityAnalysis
import net.bobinski.investmentsimulator.engine.SensitivityAxes
import net.bobinski.investmentsimulator.engine.SensitivityRequest
import net.bobinski.investmentsimulator.engine.SensitivityResult
import net.bobinski.investmentsimulator.engine.Strategy
import net.bobinski.investmentsimulator.engine.TaxLot
import net.bobinski.investmentsimulator.engine.TransferDirection
import net.bobinski.investmentsimulator.engine.TransferPlan
import net.bobinski.investmentsimulator.engine.YearAssumptions
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SensitivityTransportTest {
    @Test
    fun `HTTP and CLI return the same complete sensitivity report`() = testApplication {
        application { simulatorModule() }
        val request = request()
        val response = client.post("/v1/sensitivity-analyses") {
            contentType(ContentType.Application.Json)
            setBody(simulatorJson.encodeToString(request))
        }
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        val httpReport = simulatorJson.decodeFromString<SensitivityResult>(response.bodyAsText())
        val cli = command(listOf("sensitivity", "-"), simulatorJson.encodeToString(request))
        assertEquals(0, cli.exitCode, cli.error)
        assertEquals("", cli.error)
        assertEquals(httpReport, simulatorJson.decodeFromString<SensitivityResult>(cli.output))
        assertEquals(4, httpReport.scenarioCount)
        assertEquals(4, httpReport.scenarios.size)
        assertTrue(httpReport.scenarios.all { scenario ->
            scenario.strategies.single { it.strategyId == "move" }.initialTransfer!!.estimatedAdditionalCapitalGainsTaxPln > BigDecimal.ZERO
        })
    }

    @Test
    fun `CLI resolves a scenario from file and comparison reproduces its financial result`() {
        val request = request()
        val analysis = command(listOf("sensitivity", "-"), simulatorJson.encodeToString(request))
        assertEquals(0, analysis.exitCode, analysis.error)
        val scenario = simulatorJson.decodeFromString<SensitivityResult>(analysis.output).scenarios.last()
        val file = Files.createTempFile("sensitivity-transport-", ".json")
        try {
            Files.writeString(file, simulatorJson.encodeToString(request))
            val resolved = command(listOf("sensitivity-scenario", file.toString(), scenario.id))
            assertEquals(0, resolved.exitCode, resolved.error)
            assertEquals("", resolved.error)
            val replayRequest = simulatorJson.decodeFromString<ComparisonRequest>(resolved.output)
            assertEquals(scenario.coordinates.endDate, replayRequest.endDate)
            val replay = command(listOf("compare", "-"), resolved.output)
            assertEquals(0, replay.exitCode, replay.error)
            val comparison = simulatorJson.decodeFromString<ComparisonResult>(replay.output)
            assertEquals(scenario.preferredStrategyId, comparison.preferredStrategyId)
            assertEquals(scenario.recommendation, comparison.recommendation)
            for (summary in scenario.strategies) {
                val full = comparison.results.single { it.strategyId == summary.strategyId }
                assertEquals(summary.netLiquidationValuePln, full.netLiquidationValuePln)
                assertEquals(summary.realNetLiquidationValuePln, full.realNetLiquidationValuePln)
                assertEquals(summary.advantageVsBaselinePln, full.advantageVsBaselinePln)
                assertEquals(summary.outstandingTaxPln, full.outstandingTaxPln)
                assertEquals(summary.liquidationTaxPln, full.liquidationTaxPln)
                assertEquals(summary.capitalGainsTaxPaidPln, full.capitalGainsTaxPaidPln)
                assertEquals(summary.okiTaxPaidPln, full.okiTaxPaidPln)
                assertEquals(summary.initialTransfer, full.initialTransfer)
            }
        } finally {
            Files.deleteIfExists(file)
        }
    }

    @Test
    fun `HTTP rejects an invalid scenario the oversized grid and excessive work without partial reports`() = testApplication {
        application { simulatorModule() }
        val base = request()
        val tooMuchWork = base.copy(
            baseRequest = base.baseRequest.copy(
                endDate = "2076-12-31",
                assumptions = (2027..2076).map { YearAssumptions(it, 0.05, 0.025, 0.0085) },
                strategies = (0 until 32).map { Strategy(if (it == 0) "baseline" else "strategy-$it", 0.0) },
            ),
            axes = SensitivityAxes(equityReturnRateShifts = listOf(0.0, 0.01, 0.02, 0.03)),
        )
        val invalidRequests = listOf(
            base.copy(axes = SensitivityAxes(assumedOkiTaxRateShifts = listOf(0.0, -0.1))),
            base.copy(axes = SensitivityAxes(
                equityReturnRateShifts = (0 until 9).map { it * 0.001 },
                inflationRateShifts = (0 until 16).map { it * 0.001 },
            )),
            base.copy(axes = SensitivityAxes(equityReturnRateShifts = (0..SensitivityAnalysis.MAX_AXIS_VALUES).map { it * 0.001 })),
            tooMuchWork,
        )
        for (request in invalidRequests) {
            val response = client.post("/v1/sensitivity-analyses") {
                contentType(ContentType.Application.Json)
                setBody(simulatorJson.encodeToString(request))
            }
            assertEquals(HttpStatusCode.BadRequest, response.status, response.bodyAsText())
            val error = simulatorJson.decodeFromString<ErrorResponse>(response.bodyAsText())
            assertEquals("invalid_request", error.code)
            assertTrue(error.message.isNotBlank())
            assertTrue(!simulatorJson.parseToJsonElement(response.bodyAsText()).jsonObject.containsKey("scenarios"))
        }
    }

    @Test
    fun `HTTP sensitivity requires a bounded JSON body and rejects unknown fields`() = testApplication {
        application { simulatorModule() }
        for (body in listOf("{", "{}", simulatorJson.encodeToString(request()).replaceFirst("{", "{\"unexpected\":true,"))) {
            val response = client.post("/v1/sensitivity-analyses") {
                contentType(ContentType.Application.Json)
                setBody(body)
            }
            assertEquals(HttpStatusCode.BadRequest, response.status, response.bodyAsText())
        }
        val wrongMedia = client.post("/v1/sensitivity-analyses") {
            contentType(ContentType.Text.Plain)
            setBody("{}")
        }
        assertEquals(HttpStatusCode.UnsupportedMediaType, wrongMedia.status)
        val oversized = client.post("/v1/sensitivity-analyses") {
            contentType(ContentType.Application.Json)
            setBody(" ".repeat(MAX_REQUEST_BYTES + 1))
        }
        assertEquals(HttpStatusCode.PayloadTooLarge, oversized.status)
    }

    @Test
    fun `CLI invalid grids unknown scenarios malformed input and invalid usage produce no report`() {
        val valid = simulatorJson.encodeToString(request())
        val invalid = simulatorJson.encodeToString(request().copy(axes = SensitivityAxes(equityReturnRateShifts = emptyList())))
        for ((arguments, body) in listOf(
            listOf("sensitivity", "-") to invalid,
            listOf("sensitivity", "-") to "{",
            listOf("sensitivity") to valid,
            listOf("sensitivity-scenario", "-", "unknown-scenario") to valid,
            listOf("sensitivity-scenario", "-") to valid,
        )) {
            val result = command(arguments, body)
            assertEquals(2, result.exitCode)
            assertEquals("", result.output)
            assertTrue(result.error.startsWith("Error:"))
        }
    }

    @Test
    fun `OpenAPI publishes sensitivity schemas and limits with no partial response`() {
        val document = simulatorJson.parseToJsonElement(openApiDocument()).jsonObject
        val operation = document["paths"]!!.jsonObject["/v1/sensitivity-analyses"]!!.jsonObject["post"]!!.jsonObject
        assertEquals("analyzeSensitivity", operation["operationId"]!!.jsonPrimitive.content)
        val description = operation["description"]!!.jsonPrimitive.content
        assertTrue(description.contains(SensitivityAnalysis.MAX_SCENARIOS.toString()))
        assertTrue(description.contains(SensitivityAnalysis.MAX_STRATEGY_DAYS.toString()))
        assertTrue(operation["responses"]!!.jsonObject["400"]!!.jsonObject["description"]!!.jsonPrimitive.content.contains("no partial result"))
        val schemas = document["components"]!!.jsonObject["schemas"]!!.jsonObject
        for (name in listOf("SensitivityRequest", "SensitivityAxes", "SensitivityResult", "SensitivityScenario", "SensitivityTransition")) {
            assertNotNull(schemas[name], name)
        }
    }

    private data class CommandResult(val exitCode: Int, val output: String, val error: String)

    private fun command(args: List<String>, body: String = ""): CommandResult {
        val output = ByteArrayOutputStream()
        val error = ByteArrayOutputStream()
        val code = runCommand(args, ByteArrayInputStream(body.toByteArray()), PrintStream(output), PrintStream(error))
        return CommandResult(code, output.toString(), error.toString())
    }

    private fun request() = SensitivityRequest(
        baseRequest = ComparisonRequest(
            startDate = "2027-01-01",
            endDate = "2028-12-31",
            initial = InitialPortfolio(taxableLots = listOf(TaxLot("purchase", "2026-01-01", BigDecimal("10000"), BigDecimal("8000")))),
            assumptions = (2027..2028).map { YearAssumptions(it, 0.05, 0.025, 0.0085) },
            strategies = listOf(
                Strategy("baseline", 0.0),
                Strategy("move", 1.0, initialTransfer = TransferPlan(TransferDirection.TAXABLE_TO_OKI, 1.0)),
            ),
            baselineStrategyId = "baseline",
        ),
        axes = SensitivityAxes(equityReturnRateShifts = listOf(-0.02, 0.02), endDates = listOf("2027-12-31", "2028-12-31")),
    )
}
