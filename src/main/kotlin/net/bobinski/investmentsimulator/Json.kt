package net.bobinski.investmentsimulator

import kotlinx.serialization.json.Json

val simulatorJson = Json {
    encodeDefaults = true
    prettyPrint = true
    ignoreUnknownKeys = false
}
