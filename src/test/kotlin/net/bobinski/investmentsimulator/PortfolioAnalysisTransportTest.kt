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
import java.nio.file.Path
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.bobinski.investmentsimulator.engine.OpeningTaxState
import net.bobinski.investmentsimulator.engine.YearAssumptions
import net.bobinski.investmentsimulator.portfolio.OpeningValuationPolicy
import net.bobinski.investmentsimulator.portfolio.PortfolioAnalysisPlan
import net.bobinski.investmentsimulator.portfolio.PortfolioAnalysisRequest
import net.bobinski.investmentsimulator.portfolio.PortfolioAnalysisResult
import net.bobinski.investmentsimulator.portfolio.PortfolioAnalysisStatus
import net.bobinski.investmentsimulator.portfolio.PortfolioDataGapCode
import net.bobinski.investmentsimulator.portfolio.PortfolioSnapshotRequest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PortfolioAnalysisTransportTest {
    @Test
    fun `analysis endpoint compares captured portfolio and reports the tax on migration`() = testApplication {
        application { simulatorModule() }
        val response = client.post("/v1/portfolio/analyses") {
            contentType(ContentType.Application.Json)
            setBody(simulatorJson.encodeToString(completeRequest()))
        }

        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        val result = simulatorJson.decodeFromString<PortfolioAnalysisResult>(response.bodyAsText())
        assertEquals(PortfolioAnalysisStatus.COMPLETE, result.status)
        assertTrue(result.dataGaps.isEmpty())
        assertNotNull(result.source)
        assertEquals(0, result.resolvedRequest!!.initial.taxableLots.single().costBasisPln.compareTo(BigDecimal("60")))
        val comparison = result.comparison!!
        val migrations = comparison.results.mapNotNull { it.initialTransfer }
        assertTrue(migrations.any { it.realizedGainPln > BigDecimal.ZERO })
        assertTrue(migrations.any { it.estimatedAdditionalCapitalGainsTaxPln > BigDecimal.ZERO })
        assertEquals(result.strategyDescriptions.map { it.id }.toSet(), comparison.results.map { it.strategyId }.toSet())
    }

    @Test
    fun `HTTP data gaps and unsupported scenarios remain structured 200 responses`() = testApplication {
        application { simulatorModule() }
        val complete = completeRequest()
        val cases = listOf(
            complete.copy(portfolio = complete.portfolio.copy(openingTaxState = null)) to PortfolioAnalysisStatus.NEEDS_INPUT,
            complete.copy(plan = complete.plan.copy(lossCarryforwardPln = BigDecimal("100"))) to PortfolioAnalysisStatus.UNSUPPORTED,
        )
        for ((request, status) in cases) {
            val response = client.post("/v1/portfolio/analyses") {
                contentType(ContentType.Application.Json)
                setBody(simulatorJson.encodeToString(request))
            }
            assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
            val report = simulatorJson.decodeFromString<PortfolioAnalysisResult>(response.bodyAsText())
            assertEquals(status, report.status)
            assertTrue(report.dataGaps.isNotEmpty())
            assertEquals(null, report.comparison)
        }
    }

    @Test
    fun `analysis rejects malformed JSON unknown fields wrong media and oversized bodies`() = testApplication {
        application { simulatorModule() }
        for (body in listOf("{", "{}", simulatorJson.encodeToString(completeRequest()).replaceFirst("{", "{\"unexpected\":true,"))) {
            val response = client.post("/v1/portfolio/analyses") {
                contentType(ContentType.Application.Json)
                setBody(body)
            }
            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertTrue(simulatorJson.decodeFromString<ErrorResponse>(response.bodyAsText()).message.isNotBlank())
        }
        val wrongMedia = client.post("/v1/portfolio/analyses") {
            contentType(ContentType.Text.Plain)
            setBody("{}")
        }
        assertEquals(HttpStatusCode.UnsupportedMediaType, wrongMedia.status)
        val oversized = client.post("/v1/portfolio/analyses") {
            contentType(ContentType.Application.Json)
            setBody(" ".repeat(MAX_REQUEST_BYTES + 1))
        }
        assertEquals(HttpStatusCode.PayloadTooLarge, oversized.status)
    }

    @Test
    fun `CLI emits JSON for completed and incomplete analyses and exit codes distinguish the results`() {
        val complete = completeRequest()
        val cases = listOf(
            Triple(complete, PortfolioAnalysisStatus.COMPLETE, 0),
            Triple(complete.copy(portfolio = complete.portfolio.copy(openingTaxState = null)), PortfolioAnalysisStatus.NEEDS_INPUT, 3),
            Triple(complete.copy(plan = complete.plan.copy(lossCarryforwardPln = BigDecimal("100"))), PortfolioAnalysisStatus.UNSUPPORTED, 3),
        )
        for ((request, status, expectedCode) in cases) {
            val output = ByteArrayOutputStream()
            val error = ByteArrayOutputStream()
            val exitCode = runCommand(
                listOf("analyze-portfolio", "-"),
                ByteArrayInputStream(simulatorJson.encodeToString(request).toByteArray()), PrintStream(output), PrintStream(error),
            )
            assertEquals(expectedCode, exitCode, error.toString())
            assertEquals("", error.toString())
            val report = simulatorJson.decodeFromString<PortfolioAnalysisResult>(output.toString())
            assertEquals(status, report.status)
            if (status == PortfolioAnalysisStatus.NEEDS_INPUT) {
                assertTrue(report.dataGaps.any { it.code == PortfolioDataGapCode.MISSING_TAX_STATE })
            }
        }
    }

    @Test
    fun `CLI malformed analysis input retains exit two and no JSON output`() {
        val output = ByteArrayOutputStream()
        val error = ByteArrayOutputStream()
        assertEquals(2, runCommand(
            listOf("analyze-portfolio", "-"), ByteArrayInputStream("{".toByteArray()), PrintStream(output), PrintStream(error),
        ))
        assertEquals("", output.toString())
        assertTrue(error.toString().startsWith("Error:"))
    }

    @Test
    fun `OpenAPI documents structured gaps as successful responses and uses analysis serializers`() {
        val document = simulatorJson.parseToJsonElement(openApiDocument()).jsonObject
        val operation = document["paths"]!!.jsonObject["/v1/portfolio/analyses"]!!.jsonObject["post"]!!.jsonObject
        assertEquals("analyzePortfolio", operation["operationId"]!!.jsonPrimitive.content)
        assertTrue(operation["responses"]!!.jsonObject["200"]!!.jsonObject["description"]!!.jsonPrimitive.content.contains("NEEDS_INPUT"))
        val schemas = document["components"]!!.jsonObject["schemas"]!!.jsonObject
        assertNotNull(schemas["PortfolioAnalysisRequest"])
        assertNotNull(schemas["PortfolioAnalysisResult"])
    }

    private fun completeRequest(): PortfolioAnalysisRequest {
        val portfolio = simulatorJson.decodeFromString<PortfolioSnapshotRequest>(
            Files.readString(Path.of("portfolio-adapter/src/test/resources/portfolio-bundle.json")),
        ).copy(openingTaxState = OpeningTaxState())
        return PortfolioAnalysisRequest(
            portfolio = portfolio,
            plan = PortfolioAnalysisPlan(
                startDate = "2027-01-01",
                endDate = "2027-12-31",
                assumptions = listOf(YearAssumptions(2027, 0.05, 0.025, 0.0085)),
                openingValuationPolicy = OpeningValuationPolicy.USE_CAPTURED_VALUES_UNCHANGED,
                taxStateAsOfDate = "2027-01-01",
            ),
        )
    }
}
