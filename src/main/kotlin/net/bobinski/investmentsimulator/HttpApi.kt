package net.bobinski.investmentsimulator

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.contentType
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.readAvailable
import java.io.ByteArrayOutputStream
import java.time.DateTimeException
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import net.bobinski.investmentsimulator.engine.ComparisonRequest
import net.bobinski.investmentsimulator.engine.MonteCarloAnalysis
import net.bobinski.investmentsimulator.engine.MonteCarloRequest
import net.bobinski.investmentsimulator.engine.SensitivityAnalysis
import net.bobinski.investmentsimulator.engine.SensitivityRequest
import net.bobinski.investmentsimulator.engine.SimulationEngine
import net.bobinski.investmentsimulator.portfolio.PortfolioAnalysisRequest
import net.bobinski.investmentsimulator.portfolio.PortfolioAnalysisService
import net.bobinski.investmentsimulator.portfolio.PortfolioSnapshotMapper
import net.bobinski.investmentsimulator.portfolio.PortfolioSnapshotRequest

const val MAX_REQUEST_BYTES = 2 * 1024 * 1024

@Serializable
data class HealthResponse(val status: String = "ok")

@Serializable
data class ErrorResponse(val code: String, val message: String)

private class PayloadTooLargeException : RuntimeException()
private class JsonContentTypeRequiredException : RuntimeException()

fun Application.simulatorModule() {
    install(ContentNegotiation) {
        json(simulatorJson)
    }
    install(StatusPages) {
        exception<PayloadTooLargeException> { call, _ ->
            call.respond(HttpStatusCode.PayloadTooLarge, ErrorResponse("payload_too_large", "JSON body exceeds $MAX_REQUEST_BYTES bytes."))
        }
        exception<JsonContentTypeRequiredException> { call, _ ->
            call.respond(HttpStatusCode.UnsupportedMediaType, ErrorResponse("unsupported_media_type", "Content-Type must be application/json."))
        }
        exception<SerializationException> { call, cause ->
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid_json", cause.message ?: "Invalid JSON request."))
        }
        exception<IllegalArgumentException> { call, cause ->
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid_request", cause.message ?: "Invalid simulation request."))
        }
        exception<BadRequestException> { call, cause ->
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid_request", cause.message ?: "Invalid request."))
        }
        exception<DateTimeException> { call, cause ->
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid_request", cause.message ?: "Invalid date."))
        }
    }
    routing {
        get("/health") {
            call.respond(HealthResponse())
        }
        get("/openapi.json") {
            call.respondText(openApiDocument(), ContentType.Application.Json)
        }
        post("/v1/strategy-comparisons") {
            val request = simulatorJson.decodeFromString<ComparisonRequest>(call.readJsonBody())
            call.respond(SimulationEngine.compare(request))
        }
        post("/v1/sensitivity-analyses") {
            val request = simulatorJson.decodeFromString<SensitivityRequest>(call.readJsonBody())
            call.respond(SensitivityAnalysis.analyze(request))
        }
        post("/v1/monte-carlo-analyses") {
            val request = simulatorJson.decodeFromString<MonteCarloRequest>(call.readJsonBody())
            call.respond(MonteCarloAnalysis.analyze(request))
        }
        post("/v1/portfolio/snapshots") {
            val request = simulatorJson.decodeFromString<PortfolioSnapshotRequest>(call.readJsonBody())
            call.respond(PortfolioSnapshotMapper.map(request))
        }
        post("/v1/portfolio/analyses") {
            val request = simulatorJson.decodeFromString<PortfolioAnalysisRequest>(call.readJsonBody())
            call.respond(PortfolioAnalysisService.analyze(request))
        }
    }
}

internal suspend fun ApplicationCall.readJsonBody(): String {
    if (!request.contentType().match(ContentType.Application.Json)) throw JsonContentTypeRequiredException()
    val channel = receiveChannel()
    val bytes = ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        val read = channel.readAvailable(buffer, 0, buffer.size)
        if (read == -1) break
        if (bytes.size() + read > MAX_REQUEST_BYTES) throw PayloadTooLargeException()
        bytes.write(buffer, 0, read)
    }
    return bytes.toString(Charsets.UTF_8)
}
