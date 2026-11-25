package io.github.aedev.flow.innertube.models

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

@Serializable
data class ResponseContext(
    val visitorData: String?,
    val serviceTrackingParams: List<ServiceTrackingParam>?,
) {
    @Serializable
    data class ServiceTrackingParam(
        val params: List<Param>,
        val service: String,
    ) {
        @Serializable
        data class Param(
            val key: String,
            val value: String,
        )
    }
}

/** How long the server lets this response be reused, from `responseContext.maxAgeSeconds`; null when it allows none. */
fun JsonElement.responseMaxAgeSeconds(): Int? =
    ((this as? JsonObject)?.get("responseContext") as? JsonObject)
        ?.get("maxAgeSeconds")
        ?.let { runCatching { it.jsonPrimitive.intOrNull }.getOrNull() }
        ?.takeIf { it > 0 }
