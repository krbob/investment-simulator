@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package net.bobinski.investmentsimulator

import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import net.bobinski.investmentsimulator.engine.ComparisonRequest
import net.bobinski.investmentsimulator.engine.ComparisonResult
import net.bobinski.investmentsimulator.engine.SensitivityAnalysis
import net.bobinski.investmentsimulator.engine.SensitivityRequest
import net.bobinski.investmentsimulator.engine.SensitivityResult
import net.bobinski.investmentsimulator.portfolio.PortfolioAnalysisRequest
import net.bobinski.investmentsimulator.portfolio.PortfolioAnalysisResult
import net.bobinski.investmentsimulator.portfolio.PortfolioSnapshotRequest
import net.bobinski.investmentsimulator.portfolio.PortfolioSnapshotResult

/** The transport contract follows the same serialization descriptors as CLI and HTTP payloads. */
fun openApiDocument(): String {
    val schemas = SchemaRegistry()
    val health = schemas.schema(HealthResponse.serializer().descriptor)
    val error = schemas.schema(ErrorResponse.serializer().descriptor)
    val comparisonRequest = schemas.schema(ComparisonRequest.serializer().descriptor)
    val comparisonResult = schemas.schema(ComparisonResult.serializer().descriptor)
    val sensitivityRequest = schemas.schema(SensitivityRequest.serializer().descriptor)
    val sensitivityResult = schemas.schema(SensitivityResult.serializer().descriptor)
    val snapshotRequest = schemas.schema(PortfolioSnapshotRequest.serializer().descriptor)
    val snapshotResult = schemas.schema(PortfolioSnapshotResult.serializer().descriptor)
    val analysisRequest = schemas.schema(PortfolioAnalysisRequest.serializer().descriptor)
    val analysisResult = schemas.schema(PortfolioAnalysisResult.serializer().descriptor)
    val document = obj(
        "openapi" to str("3.1.0"),
        "info" to obj(
            "title" to str("Investment Simulator API"),
            "version" to str("0.1.0"),
            "description" to str(
                "Deterministic comparisons of a taxable brokerage account and OKI for one accumulating global equity exposure. " +
                    "All money and source quantities use decimal JSON strings. Rates are decimal fractions (0.0085 = 0.85%). " +
                    "Future returns, inflation and OKI rates are supplied explicitly. No OKI asset allowance is applied. " +
                    "Bodies are limited to $MAX_REQUEST_BYTES bytes. The default listener is 127.0.0.1:8080.",
            ),
        ),
        "servers" to JsonArray(listOf(obj("url" to str("http://127.0.0.1:8080")))),
        "paths" to obj(
            "/health" to obj("get" to obj(
                "operationId" to str("health"),
                "summary" to str("Check that the process is running"),
                "responses" to obj("200" to response("Process is running", health)),
            )),
            "/openapi.json" to obj("get" to obj(
                "operationId" to str("openApi"),
                "summary" to str("Read this OpenAPI contract"),
                "responses" to obj("200" to response("OpenAPI 3.1 document", obj("type" to str("object")))),
            )),
            "/v1/strategy-comparisons" to obj("post" to operation(
                "compareStrategies",
                "Compare strategies against one baseline on the same deterministic market path",
                comparisonRequest,
                comparisonResult,
                error,
            )),
            "/v1/sensitivity-analyses" to obj("post" to operation(
                "analyzeSensitivity",
                "Compare a deterministic grid of additive rate shifts and shorter horizons",
                sensitivityRequest,
                sensitivityResult,
                error,
                successDescription = "Complete grid; scenario frequencies are not probabilities and transitions bracket adjacent samples only",
                badRequestDescription = "Invalid JSON, invalid scenario, or exceeded grid limit; no partial result is returned",
                description = "Each axis supports at most ${SensitivityAnalysis.MAX_AXIS_VALUES} values. " +
                    "The grid supports at most ${SensitivityAnalysis.MAX_SCENARIOS} scenarios and " +
                    "${SensitivityAnalysis.MAX_STRATEGY_DAYS} total simulated strategy-days. " +
                    "Opening tax-lot replays across scenarios and strategies are limited to ${SensitivityAnalysis.MAX_OPENING_LOT_REPLAYS}. " +
                    "Rate shifts are additive decimal fractions (0.01 = one percentage point); established OKI rates remain unchanged. " +
                    "Each horizon must end on 31 December and cannot extend beyond the base request. " +
                    "The entire grid is validated before simulation. Results group strategy summaries by horizon.",
            )),
            "/v1/portfolio/snapshots" to obj("post" to operation(
                "importPortfolioSnapshot",
                "Map supplied portfolio API exports into initial balances and FIFO tax lots",
                snapshotRequest,
                snapshotResult,
                error,
            )),
            "/v1/portfolio/analyses" to obj("post" to operation(
                "analyzePortfolio",
                "Compare standard strategies for supplied Portfolio data and an investment plan",
                analysisRequest,
                analysisResult,
                error,
                successDescription = "Analysis report: COMPLETE, NEEDS_INPUT, or UNSUPPORTED; data gaps remain a successful structured response",
                badRequestDescription = "Invalid JSON or request shape",
            )),
        ),
        "components" to obj("schemas" to JsonObject(schemas.definitions)),
    )
    return simulatorJson.encodeToString(JsonObject.serializer(), document)
}

private fun operation(
    id: String,
    summary: String,
    requestSchema: JsonObject,
    responseSchema: JsonObject,
    errorSchema: JsonObject,
    successDescription: String = "Successful calculation",
    badRequestDescription: String = "Invalid JSON, input or unsupported portfolio data",
    description: String = summary,
) = obj(
    "operationId" to str(id),
    "summary" to str(summary),
    "description" to str(description),
    "requestBody" to obj(
        "required" to JsonPrimitive(true),
        "content" to jsonContent(requestSchema),
    ),
    "responses" to obj(
        "200" to response(successDescription, responseSchema),
        "400" to response(badRequestDescription, errorSchema),
        "413" to response("Request exceeds the body size limit", errorSchema),
        "415" to response("Content-Type must be application/json", errorSchema),
    ),
)

private fun response(description: String, schema: JsonObject) = obj(
    "description" to str(description),
    "content" to jsonContent(schema),
)

private fun jsonContent(schema: JsonObject) = obj("application/json" to obj("schema" to schema))

private class SchemaRegistry {
    val definitions = linkedMapOf<String, JsonElement>()

    fun schema(descriptor: SerialDescriptor): JsonObject {
        val base = nonNullableSchema(descriptor)
        return if (descriptor.isNullable) {
            obj("anyOf" to JsonArray(listOf(base, obj("type" to str("null")))))
        } else {
            base
        }
    }

    private fun nonNullableSchema(descriptor: SerialDescriptor): JsonObject = when (descriptor.kind) {
        PrimitiveKind.STRING, PrimitiveKind.CHAR -> {
            if (descriptor.serialName.removeSuffix("?") == "Decimal") {
                obj(
                    "type" to str("string"),
                    "format" to str("decimal"),
                    "description" to str("Exact decimal represented as a JSON string."),
                    "examples" to JsonArray(listOf(str("1000.00"))),
                )
            } else obj("type" to str("string"))
        }
        PrimitiveKind.BOOLEAN -> obj("type" to str("boolean"))
        PrimitiveKind.BYTE, PrimitiveKind.SHORT, PrimitiveKind.INT, PrimitiveKind.LONG -> obj("type" to str("integer"))
        PrimitiveKind.FLOAT, PrimitiveKind.DOUBLE -> obj("type" to str("number"))
        StructureKind.LIST -> obj("type" to str("array"), "items" to schema(descriptor.getElementDescriptor(0)))
        StructureKind.MAP -> obj("type" to str("object"), "additionalProperties" to schema(descriptor.getElementDescriptor(1)))
        SerialKind.ENUM -> obj(
            "type" to str("string"),
            "enum" to JsonArray((0 until descriptor.elementsCount).map { str(descriptor.getElementName(it)) }),
        )
        StructureKind.CLASS, StructureKind.OBJECT -> classSchema(descriptor)
        else -> JsonObject(emptyMap())
    }

    private fun classSchema(descriptor: SerialDescriptor): JsonObject {
        val name = descriptor.serialName.removeSuffix("?").substringAfterLast('.')
        if (name !in definitions) {
            definitions[name] = JsonObject(emptyMap())
            val properties = linkedMapOf<String, JsonElement>()
            val required = mutableListOf<JsonElement>()
            for (index in 0 until descriptor.elementsCount) {
                val property = descriptor.getElementName(index)
                val propertySchema = schema(descriptor.getElementDescriptor(index))
                properties[property] = if (property in DATE_FIELDS) {
                    JsonObject(propertySchema + ("format" to str("date")))
                } else if (property in TIMESTAMP_FIELDS) {
                    JsonObject(propertySchema + ("format" to str("date-time")))
                } else propertySchema
                if (!descriptor.isElementOptional(index)) required += str(property)
            }
            definitions[name] = obj(
                "type" to str("object"),
                "properties" to JsonObject(properties),
                "required" to JsonArray(required),
                "additionalProperties" to JsonPrimitive(descriptor.annotations.any { it.annotationClass.simpleName == "JsonIgnoreUnknownKeys" }),
            )
        }
        return obj("\$ref" to str("#/components/schemas/$name"))
    }
}

private val DATE_FIELDS = setOf(
    "startDate", "endDate", "acquiredOn", "okiOpenedOn", "dueDate", "withdrawalFrom", "contributionUntil",
    "date", "throughDate", "sourceAsOfDate", "tradeDate", "valuedAt", "taxStateAsOfDate",
)
private val TIMESTAMP_FIELDS = setOf("exportedAt", "createdAt")

private fun obj(vararg properties: Pair<String, JsonElement>) = JsonObject(linkedMapOf(*properties))
private fun str(value: String) = JsonPrimitive(value)
