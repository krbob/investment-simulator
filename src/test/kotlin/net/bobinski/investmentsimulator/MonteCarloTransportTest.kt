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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.bobinski.investmentsimulator.engine.AnnualWithdrawalPlan
import net.bobinski.investmentsimulator.engine.ComparisonRequest
import net.bobinski.investmentsimulator.engine.ComparisonResult
import net.bobinski.investmentsimulator.engine.InitialPortfolio
import net.bobinski.investmentsimulator.engine.MonteCarloAnalysis
import net.bobinski.investmentsimulator.engine.MonteCarloPath
import net.bobinski.investmentsimulator.engine.MonteCarloRequest
import net.bobinski.investmentsimulator.engine.MonteCarloResult
import net.bobinski.investmentsimulator.engine.Strategy
import net.bobinski.investmentsimulator.engine.TaxLot
import net.bobinski.investmentsimulator.engine.TransferDirection
import net.bobinski.investmentsimulator.engine.TransferPlan
import net.bobinski.investmentsimulator.engine.YearAssumptions
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MonteCarloTransportTest {
    @Test
    fun `HTTP and CLI reproduce the same seeded analysis including income floor metrics`() = testApplication {
        application { simulatorModule() }
        val request = request()
        val response = client.post("/v1/monte-carlo-analyses") {
            contentType(ContentType.Application.Json)
            setBody(simulatorJson.encodeToString(request))
        }
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        val report = simulatorJson.decodeFromString<MonteCarloResult>(response.bodyAsText())
        val cli = command(listOf("monte-carlo", "-"), simulatorJson.encodeToString(request))
        assertEquals(0, cli.exitCode, cli.error)
        assertEquals("", cli.error)
        assertEquals(report, simulatorJson.decodeFromString<MonteCarloResult>(cli.output))
        assertEquals(4, report.pathCount)
        assertEquals(request.seed, report.request.seed)
        assertEquals(request.minimumRealAnnualIncomePln, report.request.minimumRealAnnualIncomePln)
        assertEquals(listOf("p0001", "p0002", "p0003", "p0004"), report.paths.map { it.id })
        for (path in report.paths) for (strategy in path.strategies) {
            assertEquals(3, strategy.income.annualPaymentCount)
            assertEquals(3, strategy.income.yearsBelowMinimum)
            assertEquals(3, strategy.income.longestRunBelowMinimum)
        }
        assertTrue(report.strategySummaries.all { it.annualIncome.size == 3 && it.anyYearBelowMinimumPathCount == 4 })
        val income = simulatorJson.parseToJsonElement(response.bodyAsText()).jsonObject["strategySummaries"]!!.jsonArray.first()
            .jsonObject["realWithdrawalsPaidPln"]!!.jsonObject["p50"]!!.jsonPrimitive
        assertTrue(income.isString, "Money distribution quantiles must remain exact decimal strings")
    }

    @Test
    fun `CLI resolves a sampled path from file and reproduces its taxes withdrawals and objective`() {
        val request = request()
        val report = analyze(request)
        val path = report.paths.last()
        val file = Files.createTempFile("monte-carlo-transport-", ".json")
        try {
            Files.writeString(file, simulatorJson.encodeToString(report.request))
            val resolved = command(listOf("monte-carlo-path", file.toString(), path.id))
            assertEquals(0, resolved.exitCode, resolved.error)
            assertEquals("", resolved.error)
            val replayRequest = simulatorJson.decodeFromString<ComparisonRequest>(resolved.output)
            assertEquals(path.annualReturns.map { it.equityReturnRate }, replayRequest.assumptions.map { it.equityReturnRate })
            assertEquals(request.baseRequest.assumptions.map { it.okiTaxRate }, replayRequest.assumptions.map { it.okiTaxRate })
            assertEquals(request.baseRequest.assumptions.map { it.inflationRate }, replayRequest.assumptions.map { it.inflationRate })
            val comparison = command(listOf("compare", "-"), resolved.output)
            assertEquals(0, comparison.exitCode, comparison.error)
            assertReplay(path, simulatorJson.decodeFromString<ComparisonResult>(comparison.output))
        } finally {
            Files.deleteIfExists(file)
        }
    }

    @Test
    fun `zero volatility CLI paths reproduce direct deterministic comparison`() {
        val request = request().copy(annualLogReturnVolatility = 0.0, minimumRealAnnualIncomePln = null)
        val report = analyze(request)
        val comparison = command(listOf("compare", "-"), simulatorJson.encodeToString(request.baseRequest))
        assertEquals(0, comparison.exitCode, comparison.error)
        val direct = simulatorJson.decodeFromString<ComparisonResult>(comparison.output)
        report.paths.forEach { path ->
            assertReplay(path, direct)
            path.strategies.forEach {
                assertEquals(null, it.income.yearsBelowMinimum)
                assertEquals(null, it.income.longestRunBelowMinimum)
            }
        }
        report.strategySummaries.forEach { summary ->
            val value = direct.results.single { it.strategyId == summary.strategyId }.comparisonValuePln
            assertEquals(value, summary.comparisonValuePln.p10)
            assertEquals(value, summary.comparisonValuePln.p50)
            assertEquals(value, summary.comparisonValuePln.p90)
        }
    }

    @Test
    fun `HTTP rejects invalid Monte Carlo controls retirement scope and excessive simulation work`() = testApplication {
        application { simulatorModule() }
        val valid = request()
        val longBase = valid.baseRequest.copy(
            endDate = "2076-12-31",
            assumptions = (2027..2076).map { YearAssumptions(it, 0.05, 0.025, 0.0085) },
            strategies = (0 until 32).map { Strategy(if (it == 0) "baseline" else "strategy-$it", 0.0) },
        )
        val manyLots = valid.baseRequest.copy(initial = InitialPortfolio(taxableLots = (1..2501).map {
            TaxLot("lot-$it", "2026-01-01", BigDecimal.ONE, BigDecimal.ZERO)
        }))
        val cases = listOf(
            valid.copy(seed = -1), valid.copy(seed = MonteCarloAnalysis.MAX_SEED + 1),
            valid.copy(pathCount = 0), valid.copy(pathCount = MonteCarloAnalysis.MAX_PATHS + 1),
            valid.copy(annualLogReturnVolatility = -0.1), valid.copy(annualLogReturnVolatility = 0.501),
            valid.copy(minimumRealAnnualIncomePln = BigDecimal("-1")),
            valid.copy(minimumRealAnnualIncomePln = BigDecimal("1000000000000001")),
            valid.copy(minimumRealAnnualIncomePln = BigDecimal("0.0000000000001")),
            valid.copy(baseRequest = valid.baseRequest.copy(annualWithdrawalPlan = null)),
            valid.copy(baseRequest = valid.baseRequest.copy(annualWithdrawalPlan = AnnualWithdrawalPlan("2031-01-01"))),
            valid.copy(baseRequest = valid.baseRequest.copy(endDate = "2030-06-30")),
            valid.copy(baseRequest = longBase, pathCount = 20),
            valid.copy(baseRequest = manyLots, pathCount = 100),
        )
        for (request in cases) {
            val response = client.post("/v1/monte-carlo-analyses") {
                contentType(ContentType.Application.Json)
                setBody(simulatorJson.encodeToString(request))
            }
            assertEquals(HttpStatusCode.BadRequest, response.status, response.bodyAsText())
            val error = simulatorJson.decodeFromString<ErrorResponse>(response.bodyAsText())
            assertEquals("invalid_request", error.code)
            assertTrue(error.message.isNotBlank())
        }
    }

    @Test
    fun `HTTP requires explicit seed and bounded strict JSON input`() = testApplication {
        application { simulatorModule() }
        val valid = simulatorJson.parseToJsonElement(simulatorJson.encodeToString(request())).jsonObject
        val bodies = listOf(
            "{", "{}", JsonObject(valid - "seed").toString(),
            valid.toString().replaceFirst("{", "{\"unexpected\":true,"),
            valid.toString().replace("\"seed\":1234", "\"seed\":1.5"),
            valid.toString().replace("\"minimumRealAnnualIncomePln\":\"1000000000000000\"", "\"minimumRealAnnualIncomePln\":\"1e1000000000\""),
        )
        for (body in bodies) {
            val response = client.post("/v1/monte-carlo-analyses") {
                contentType(ContentType.Application.Json)
                setBody(body)
            }
            assertEquals(HttpStatusCode.BadRequest, response.status, response.bodyAsText())
        }
        val wrongMedia = client.post("/v1/monte-carlo-analyses") {
            contentType(ContentType.Text.Plain)
            setBody("{}")
        }
        assertEquals(HttpStatusCode.UnsupportedMediaType, wrongMedia.status)
        val oversized = client.post("/v1/monte-carlo-analyses") {
            contentType(ContentType.Application.Json)
            setBody(" ".repeat(MAX_REQUEST_BYTES + 1))
        }
        assertEquals(HttpStatusCode.PayloadTooLarge, oversized.status)
    }

    @Test
    fun `CLI rejects invalid requests unknown paths and invalid usage without partial JSON`() {
        val valid = simulatorJson.encodeToString(request())
        for ((args, body) in listOf(
            listOf("monte-carlo", "-") to simulatorJson.encodeToString(request().copy(seed = -1)),
            listOf("monte-carlo", "-") to "{",
            listOf("monte-carlo") to valid,
            listOf("monte-carlo-path", "-", "p9999") to valid,
            listOf("monte-carlo-path", "-", "p0000") to valid,
            listOf("monte-carlo-path", "-") to valid,
        )) {
            val result = command(args, body)
            assertEquals(2, result.exitCode)
            assertEquals("", result.output)
            assertTrue(result.error.startsWith("Error:"))
        }
    }

    @Test
    fun `OpenAPI exposes required seed bounded controls and complete Monte Carlo response schemas`() {
        val document = simulatorJson.parseToJsonElement(openApiDocument()).jsonObject
        val operation = document["paths"]!!.jsonObject["/v1/monte-carlo-analyses"]!!.jsonObject["post"]!!.jsonObject
        assertEquals("analyzeMonteCarlo", operation["operationId"]!!.jsonPrimitive.content)
        val description = operation["description"]!!.jsonPrimitive.content
        assertTrue(description.contains(MonteCarloAnalysis.MAX_STRATEGY_DAYS.toString()))
        assertTrue(description.contains(MonteCarloAnalysis.MAX_OPENING_LOT_REPLAYS.toString()))
        assertTrue(operation["responses"]!!.jsonObject["400"]!!.jsonObject["description"]!!.jsonPrimitive.content.contains("no partial result"))
        val schemas = document["components"]!!.jsonObject["schemas"]!!.jsonObject
        for (name in listOf("MonteCarloRequest", "MonteCarloResult", "MonteCarloPath", "MonteCarloAnnualReturn", "MonteCarloStrategyResult", "MonteCarloIncomeMetrics", "MonteCarloMoneyDistribution", "MonteCarloAnnualIncome", "MonteCarloStrategySummary")) {
            assertNotNull(schemas[name], name)
        }
        val request = schemas["MonteCarloRequest"]!!.jsonObject
        assertTrue(request["required"]!!.jsonArray.map { it.jsonPrimitive.content }.containsAll(listOf("seed", "annualLogReturnVolatility", "baseRequest")))
        val properties = request["properties"]!!.jsonObject
        assertEquals(MonteCarloAnalysis.MAX_SEED.toString(), properties["seed"]!!.jsonObject["maximum"]!!.jsonPrimitive.content)
        assertEquals(MonteCarloAnalysis.MAX_PATHS.toString(), properties["pathCount"]!!.jsonObject["maximum"]!!.jsonPrimitive.content)
        assertEquals("100", properties["pathCount"]!!.jsonObject["default"]!!.jsonPrimitive.content)
        assertEquals("0.5", properties["annualLogReturnVolatility"]!!.jsonObject["maximum"]!!.jsonPrimitive.content)
    }

    private data class CommandResult(val exitCode: Int, val output: String, val error: String)

    private fun command(args: List<String>, body: String = ""): CommandResult {
        val output = ByteArrayOutputStream()
        val error = ByteArrayOutputStream()
        val code = runCommand(args, ByteArrayInputStream(body.toByteArray()), PrintStream(output), PrintStream(error))
        return CommandResult(code, output.toString(), error.toString())
    }

    private fun analyze(request: MonteCarloRequest): MonteCarloResult {
        val result = command(listOf("monte-carlo", "-"), simulatorJson.encodeToString(request))
        assertEquals(0, result.exitCode, result.error)
        return simulatorJson.decodeFromString(result.output)
    }

    private fun assertReplay(path: MonteCarloPath, comparison: ComparisonResult) {
        assertEquals(comparison.preferredStrategyId, path.preferredStrategyId)
        assertEquals(comparison.recommendation, path.recommendation)
        for (summary in path.strategies) {
            val full = comparison.results.single { it.strategyId == summary.strategyId }
            assertEquals(full.realNetLiquidationValuePln, summary.realNetLiquidationValuePln)
            assertEquals(full.realWithdrawalsPaidPln, summary.realWithdrawalsPaidPln)
            assertEquals(full.realTotalBenefitPln, summary.realTotalBenefitPln)
            assertEquals(full.comparisonValuePln, summary.comparisonValuePln)
            assertEquals(full.objectiveAdvantageVsBaselinePln, summary.objectiveAdvantageVsBaselinePln)
            assertEquals(full.withdrawalShortfallPln, summary.withdrawalShortfallPln)
            assertEquals(full.unpaidTaxPln, summary.unpaidTaxPln)
            assertEquals(full.outstandingTaxPln, summary.outstandingTaxPln)
            assertEquals(full.capitalGainsTaxPaidPln, summary.capitalGainsTaxPaidPln)
            assertEquals(full.okiTaxPaidPln, summary.okiTaxPaidPln)
            assertEquals(full.tradingFeesPln, summary.tradingFeesPln)
            assertEquals(full.annualWithdrawals, summary.annualWithdrawals)
        }
    }

    private fun request() = MonteCarloRequest(
        baseRequest = ComparisonRequest(
            startDate = "2027-01-01", endDate = "2030-12-31",
            initial = InitialPortfolio(taxableLots = listOf(TaxLot("lot", "2026-01-01", BigDecimal("10000"), BigDecimal("5000")))),
            assumptions = (2027..2030).map { YearAssumptions(it, 0.05, 0.025, 0.0085) },
            strategies = listOf(Strategy("baseline", 0.0), Strategy("move", 1.0, initialTransfer = TransferPlan(TransferDirection.TAXABLE_TO_OKI, 1.0))),
            baselineStrategyId = "baseline", annualWithdrawalPlan = AnnualWithdrawalPlan("2028-01-01"),
        ),
        seed = 1234, annualLogReturnVolatility = 0.15, pathCount = 4,
        minimumRealAnnualIncomePln = BigDecimal("1000000000000000"),
    )
}
