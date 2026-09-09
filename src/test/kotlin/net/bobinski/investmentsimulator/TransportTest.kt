package net.bobinski.investmentsimulator

import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import io.swagger.v3.parser.OpenAPIV3Parser
import io.swagger.v3.parser.core.models.ParseOptions
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.bobinski.investmentsimulator.engine.ComparisonResult
import net.bobinski.investmentsimulator.portfolio.PortfolioSnapshotResult
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TransportTest {
    @Test
    fun `comparison route returns the tax triggered by brokerage to OKI transfer`() = testApplication {
        application { simulatorModule() }

        val response = client.post("/v1/strategy-comparisons") {
            contentType(ContentType.Application.Json)
            setBody(COMPARISON_JSON)
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val result = simulatorJson.decodeFromString<ComparisonResult>(response.bodyAsText())
        assertEquals("baseline", result.baselineStrategyId)
        val transfer = result.results.single { it.strategyId == "move" }.initialTransfer!!
        assertEquals(0, BigDecimal("2000").compareTo(transfer.realizedGainPln))
        assertEquals(0, BigDecimal("380").compareTo(transfer.estimatedAdditionalCapitalGainsTaxPln))
        assertTrue(response.bodyAsText().contains("\"realizedGainPln\": \"2000"))
    }

    @Test
    fun `malformed JSON and invalid engine inputs produce structured errors`() = testApplication {
        application { simulatorModule() }

        for (body in listOf("{", COMPARISON_JSON.replace("\"fraction\": 1.0", "\"fraction\": 1.5"), COMPARISON_JSON.replace("2027-12-31", "not-a-date"))) {
            val response = client.post("/v1/strategy-comparisons") {
                contentType(ContentType.Application.Json)
                setBody(body)
            }
            assertEquals(HttpStatusCode.BadRequest, response.status, response.bodyAsText())
            assertTrue(simulatorJson.decodeFromString<ErrorResponse>(response.bodyAsText()).message.isNotBlank())
        }
    }

    @Test
    fun `unknown top level input fields are rejected`() = testApplication {
        application { simulatorModule() }
        val response = client.post("/v1/strategy-comparisons") {
            contentType(ContentType.Application.Json)
            setBody(COMPARISON_JSON.replaceFirst("{", "{\"contributonPln\": \"1000\","))
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `comparison rejects excessive decimal exponents precision and length while decoding`() = testApplication {
        application { simulatorModule() }
        for (decimal in UNSUPPORTED_DECIMALS) {
            val response = client.post("/v1/strategy-comparisons") {
                contentType(ContentType.Application.Json)
                setBody(COMPARISON_JSON.replace("\"marketValuePln\": \"10000\"", "\"marketValuePln\": \"$decimal\""))
            }
            assertEquals(HttpStatusCode.BadRequest, response.status, response.bodyAsText())
            val error = simulatorJson.decodeFromString<ErrorResponse>(response.bodyAsText())
            assertEquals("invalid_json", error.code)
            assertTrue(error.message.startsWith("Decimal "))
        }
    }

    @Test
    fun `snapshot rejects excessive decimals before ledger arithmetic or provenance serialization`() = testApplication {
        application { simulatorModule() }
        val bundle = Files.readString(Path.of("portfolio-adapter/src/test/resources/portfolio-bundle.json"))
        for (decimal in UNSUPPORTED_DECIMALS) {
            val response = client.post("/v1/portfolio/snapshots") {
                contentType(ContentType.Application.Json)
                setBody(bundle.replaceFirst("\"grossAmount\": \"1000\"", "\"grossAmount\": \"$decimal\""))
            }
            assertEquals(HttpStatusCode.BadRequest, response.status, response.bodyAsText())
            val error = simulatorJson.decodeFromString<ErrorResponse>(response.bodyAsText())
            assertEquals("invalid_json", error.code)
            assertTrue(error.message.startsWith("Decimal "))
        }
    }

    @Test
    fun `media type and body limit are enforced`() = testApplication {
        application { simulatorModule() }
        val wrongType = client.post("/v1/strategy-comparisons") {
            contentType(ContentType.Text.Plain)
            setBody(COMPARISON_JSON)
        }
        assertEquals(HttpStatusCode.UnsupportedMediaType, wrongType.status)

        val oversized = client.post("/v1/strategy-comparisons") {
            contentType(ContentType.Application.Json)
            setBody(" ".repeat(MAX_REQUEST_BYTES + 1))
        }
        assertEquals(HttpStatusCode.PayloadTooLarge, oversized.status)
    }

    @Test
    fun `snapshot route rejects incomplete bundles`() = testApplication {
        application { simulatorModule() }
        val response = client.post("/v1/portfolio/snapshots") {
            contentType(ContentType.Application.Json)
            setBody("{}")
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertTrue(simulatorJson.decodeFromString<ErrorResponse>(response.bodyAsText()).message.isNotBlank())
    }

    @Test
    fun `snapshot route and CLI accept upstream extras and reconstruct FIFO tax basis`() = testApplication {
        application { simulatorModule() }
        val bundle = Files.readString(Path.of("portfolio-adapter/src/test/resources/portfolio-bundle.json"))
        val response = client.post("/v1/portfolio/snapshots") {
            contentType(ContentType.Application.Json)
            setBody(bundle)
        }
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        val snapshot = simulatorJson.decodeFromString<PortfolioSnapshotResult>(response.bodyAsText())
        val remainingLot = snapshot.initial.taxableLots.single()
        assertEquals(0, BigDecimal("60").compareTo(remainingLot.costBasisPln))
        assertEquals(0, BigDecimal("120").compareTo(remainingLot.marketValuePln))
        assertEquals(0, BigDecimal("1150").compareTo(snapshot.initial.cashPln))

        val output = ByteArrayOutputStream()
        val error = ByteArrayOutputStream()
        val exitCode = runCommand(listOf("import-portfolio", "-"), ByteArrayInputStream(bundle.toByteArray()), PrintStream(output), PrintStream(error))
        assertEquals(0, exitCode, error.toString())
        assertEquals(snapshot, simulatorJson.decodeFromString<PortfolioSnapshotResult>(output.toString()))
    }

    @Test
    fun `health and published OpenAPI are available`() = testApplication {
        application { simulatorModule() }
        assertEquals(HealthResponse(), simulatorJson.decodeFromString<HealthResponse>(client.get("/health").bodyAsText()))
        assertEquals(openApiDocument(), client.get("/openapi.json").bodyAsText())
    }

    @Test
    fun `OpenAPI snapshot matches serializers and is a valid contract`() {
        val document = openApiDocument()
        assertEquals(Files.readString(Path.of("openapi/investment-simulator-v1.json")).trim(), document.trim())
        val parsed = OpenAPIV3Parser().readContents(document, null, ParseOptions().apply { isResolve = true })
        assertTrue(parsed.messages.isEmpty(), parsed.messages.toString())
        assertNotNull(parsed.openAPI)
        assertNotNull(parsed.openAPI.paths["/v1/strategy-comparisons"]?.post)
        assertNotNull(parsed.openAPI.paths["/v1/portfolio/snapshots"]?.post)

        val schemas = simulatorJson.parseToJsonElement(document).jsonObject["components"]!!.jsonObject["schemas"]!!.jsonObject
        val taxLotMoney = schemas["TaxLot"]!!.jsonObject["properties"]!!.jsonObject["costBasisPln"]!!.jsonObject
        assertEquals("string", taxLotMoney["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun `CLI reads stdin and produces only JSON on stdout`() {
        val output = ByteArrayOutputStream()
        val error = ByteArrayOutputStream()
        val exitCode = runCommand(listOf("compare", "-"), ByteArrayInputStream(COMPARISON_JSON.toByteArray()), PrintStream(output), PrintStream(error))
        assertEquals(0, exitCode, error.toString())
        val result = simulatorJson.decodeFromString<ComparisonResult>(output.toString())
        assertEquals(2, result.results.size)
        assertEquals("", error.toString())
    }

    @Test
    fun `CLI validation failure uses nonzero exit code and stderr`() {
        val output = ByteArrayOutputStream()
        val error = ByteArrayOutputStream()
        val exitCode = runCommand(listOf("compare", "-"), ByteArrayInputStream("{}".toByteArray()), PrintStream(output), PrintStream(error))
        assertEquals(2, exitCode)
        assertEquals("", output.toString())
        assertTrue(error.toString().startsWith("Error:"))
        assertFalse(error.toString().contains("at net.bobinski"))
    }
}

private val UNSUPPORTED_DECIMALS = listOf("1e1000000000", "1e-1000000000", "9".repeat(65), "9".repeat(129))

private val COMPARISON_JSON = """
    {
      "startDate": "2027-01-01",
      "endDate": "2027-12-31",
      "initial": {
        "taxableLots": [{"id": "purchase-1", "acquiredOn": "2026-01-01", "marketValuePln": "10000", "costBasisPln": "8000"}]
      },
      "assumptions": [{"year": 2027, "equityReturnRate": 0.0, "inflationRate": 0.0, "okiTaxRate": 0.0085}],
      "strategies": [
        {"id": "baseline", "contributionToOkiFraction": 0.0},
        {"id": "move", "contributionToOkiFraction": 1.0, "initialTransfer": {"direction": "TAXABLE_TO_OKI", "fraction": 1.0}}
      ],
      "baselineStrategyId": "baseline"
    }
""".trimIndent()
