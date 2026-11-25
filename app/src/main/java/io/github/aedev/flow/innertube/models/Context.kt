package io.github.aedev.flow.innertube.models

import kotlinx.serialization.Serializable

@Serializable
data class Context(
    val client: Client,
    val thirdParty: ThirdParty? = null,
    private val request: Request = Request(),
    private val user: User = User(),
) {
    @Serializable
    data class Client(
        val clientName: String,
        val clientVersion: String,
        val osName: String? = null,
        val osVersion: String? = null,
        val deviceMake: String? = null,
        val deviceModel: String? = null,
        val androidSdkVersion: String? = null,
        val originalUrl: String? = null,
        val platform: String? = null,
        val utcOffsetMinutes: Int? = null,
        val timeZone: String? = null,
        val gl: String,
        val hl: String,
        val visitorData: String?,
        val userAgent: String? = null,
    )

    @Serializable
    data class ThirdParty(
        val embedUrl: String,
    )

    @Serializable
    data class Request(
        val internalExperimentFlags: Array<String> = emptyArray(),
        val useSsl: Boolean = true,
    )

    @Serializable
    data class User(
        val lockedSafetyMode: Boolean = false,
        val onBehalfOfUser: String? = null,
    )
}
