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
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.bobinski.investmentsimulator.engine.AnnualWithdrawalPlan
import net.bobinski.investmentsimulator.engine.ComparisonObjective
import net.bobinski.investmentsimulator.engine.ComparisonRequest
import net.bobinski.investmentsimulator.engine.ComparisonResult
import net.bobinski.investmentsimulator.engine.InitialPortfolio
import net.bobinski.investmentsimulator.engine.MonthlyPlan
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

class RetirementTransportTest {
    @Test
    fun `HTTP retirement grid replays through CLI and comparison endpoint with annual payment details`() = testApplication {
        application { simulatorModule() }
        val response = client.post("/v1/sensitivity-analyses") {
            contentType(ContentType.Application.Json)
            setBody(simulatorJson.encodeToString(request()))
        }
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        val report = simulatorJson.decodeFromString<SensitivityResult>(response.bodyAsText())
        assertEquals(ComparisonObjective.REAL_WITHDRAWALS_PLUS_TERMINAL_WEALTH, report.comparisonObjective)
        assertEquals(2, report.scenarioCount)
        val scenario = report.scenarios.first()
        val output = ByteArrayOutputStream()
        val error = ByteArrayOutputStream()
        val exitCode = runCommand(
            listOf("sensitivity-scenario", "-", scenario.id),
            ByteArrayInputStream(simulatorJson.encodeToString(report.request).toByteArray()), PrintStream(output), PrintStream(error),
        )
        assertEquals(0, exitCode, error.toString())
        val resolved = simulatorJson.decodeFromString<ComparisonRequest>(output.toString())
        assertEquals("2028-01-01", resolved.annualWithdrawalPlan!!.startDate)
        assertEquals("2029-12-31", resolved.endDate)
        val replay = client.post("/v1/strategy-comparisons") {
            contentType(ContentType.Application.Json)
            setBody(output.toString())
        }
        assertEquals(HttpStatusCode.OK, replay.status, replay.bodyAsText())
        val comparison = simulatorJson.decodeFromString<ComparisonResult>(replay.bodyAsText())
        assertEquals(report.comparisonObjective, comparison.comparisonObjective)
        assertEquals(scenario.preferredStrategyId, comparison.preferredStrategyId)
        for (summary in scenario.strategies) {
            val full = comparison.results.single { it.strategyId == summary.strategyId }
            assertEquals(full.annualWithdrawals, summary.annualWithdrawals)
            assertEquals(2, full.annualWithdrawals.size)
            assertEquals(full.objectiveAdvantageVsBaselinePln, summary.objectiveAdvantageVsBaselinePln)
            assertEquals(full.realTotalBenefitPln, summary.realTotalBenefitPln)
            assertEquals(full.realWithdrawalsPaidPln, summary.realWithdrawalsPaidPln)
        }
    }

    @Test
    fun `HTTP rejects incompatible horizon modes and ambiguous withdrawal policies`() = testApplication {
        application { simulatorModule() }
        val request = request()
        for (invalid in listOf(
            request.copy(axes = request.axes.copy(endDates = listOf("2029-12-31"))),
            request.copy(axes = request.axes.copy(withdrawalYears = 10)),
            request.copy(baseRequest = request.baseRequest.copy(monthlyPlan = MonthlyPlan(withdrawalPln = BigDecimal("100")))),
        )) {
            val response = client.post("/v1/sensitivity-analyses") {
                contentType(ContentType.Application.Json)
                setBody(simulatorJson.encodeToString(invalid))
            }
            assertEquals(HttpStatusCode.BadRequest, response.status, response.bodyAsText())
            assertEquals("invalid_request", simulatorJson.decodeFromString<ErrorResponse>(response.bodyAsText()).code)
        }
    }

    @Test
    fun `OpenAPI includes retirement plan objective and both sensitivity horizon modes`() {
        val document = simulatorJson.parseToJsonElement(openApiDocument()).jsonObject
        assertEquals("0.2.0", document["info"]!!.jsonObject["version"]!!.jsonPrimitive.content)
        val schemas = document["components"]!!.jsonObject["schemas"]!!.jsonObject
        assertNotNull(schemas["AnnualWithdrawalPlan"])
        assertNotNull(schemas["AnnualWithdrawalResult"])
        val comparison = schemas["ComparisonRequest"]!!.jsonObject["properties"]!!.jsonObject
        assertTrue(comparison["comparisonObjective"]!!.jsonObject["enum"].toString().contains("REAL_WITHDRAWALS_PLUS_TERMINAL_WEALTH"))
        val plan = schemas["PortfolioAnalysisPlan"]!!.jsonObject["properties"]!!.jsonObject
        assertNotNull(plan["annualWithdrawalPlan"])
        assertNotNull(plan["comparisonObjective"])
        val axes = schemas["SensitivityAxes"]!!.jsonObject["properties"]!!.jsonObject
        assertNotNull(axes["accumulationEndDates"])
        assertNotNull(axes["withdrawalYears"])
        val description = document["paths"]!!.jsonObject["/v1/sensitivity-analyses"]!!.jsonObject["post"]!!.jsonObject["description"]!!.jsonPrimitive.content
        assertTrue(description.contains("mutually exclusive"))
    }

    private fun request() = SensitivityRequest(
        baseRequest = ComparisonRequest(
            startDate = "2027-01-01", endDate = "2030-12-31",
            initial = InitialPortfolio(taxableLots = listOf(TaxLot("lot", "2026-01-01", BigDecimal("10000"), BigDecimal("8000")))),
            assumptions = (2027..2030).map { YearAssumptions(it, 0.05, 0.025, 0.0085) },
            strategies = listOf(Strategy("baseline", 0.0), Strategy("move", 1.0, initialTransfer = TransferPlan(TransferDirection.TAXABLE_TO_OKI, 1.0))),
            baselineStrategyId = "baseline", annualWithdrawalPlan = AnnualWithdrawalPlan("2029-01-01"),
        ),
        axes = SensitivityAxes(accumulationEndDates = listOf("2027-12-31", "2028-12-31"), withdrawalYears = 2),
    )
}
