package io.github.aedev.flow.player.dlna

import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** A renderer's AVTransport actions over SOAP. Every call blocks, so callers run them off the main thread. */
internal class DlnaTransport(
    private val http: OkHttpClient,
) {
    fun setUri(
        device: DlnaDevice,
        uri: String,
        metadata: String,
    ) {
        send(
            device,
            "SetAVTransportURI",
            "<InstanceID>0</InstanceID>" +
                "<CurrentURI>${CastXml.escape(uri)}</CurrentURI>" +
                "<CurrentURIMetaData>${CastXml.escape(metadata)}</CurrentURIMetaData>",
        )
    }

    fun play(device: DlnaDevice) = send(device, "Play", "<InstanceID>0</InstanceID><Speed>1</Speed>")

    fun pause(device: DlnaDevice) = send(device, "Pause", "<InstanceID>0</InstanceID>")

    fun stop(device: DlnaDevice) = send(device, "Stop", "<InstanceID>0</InstanceID>")

    fun seek(
        device: DlnaDevice,
        positionSeconds: Long,
    ) {
        val hours = positionSeconds / 3600
        val minutes = (positionSeconds % 3600) / 60
        val seconds = positionSeconds % 60
        val target = String.format("%02d:%02d:%02d", hours, minutes, seconds)
        send(device, "Seek", "<InstanceID>0</InstanceID><Unit>REL_TIME</Unit><Target>$target</Target>")
    }

    /** The renderer's transport state (PLAYING, PAUSED_PLAYBACK, STOPPED, …), or null when it didn't answer. */
    fun state(device: DlnaDevice): String? =
        runCatching {
            val response = sendWithResponse(device, "GetTransportInfo", "<InstanceID>0</InstanceID>")
            Regex("<CurrentTransportState>([^<]+)</CurrentTransportState>").find(response)?.groupValues?.get(1)
        }.getOrNull()

    fun position(device: DlnaDevice): CastPosition =
        CastXml.positionInfo(sendWithResponse(device, "GetPositionInfo", "<InstanceID>0</InstanceID>"))

    private fun send(
        device: DlnaDevice,
        action: String,
        args: String,
    ) {
        if (device.avTransportUrl.isBlank()) {
            Log.w(TAG, "sendSoap: empty control URL, skipping")
            return
        }
        http.newCall(request(device, action, args)).execute().use { response ->
            if (!response.isSuccessful) {
                val responseBody = response.body?.string().orEmpty()
                throw IllegalStateException("SOAP ${AV_TRANSPORT}#$action failed (${response.code}): $responseBody")
            }
        }
    }

    private fun sendWithResponse(
        device: DlnaDevice,
        action: String,
        args: String,
    ): String = http.newCall(request(device, action, args)).execute().use { response -> response.body?.string() ?: "" }

    private fun request(
        device: DlnaDevice,
        action: String,
        args: String,
    ): Request =
        Request
            .Builder()
            .url(device.avTransportUrl)
            .addHeader("SOAPAction", "\"$AV_TRANSPORT#$action\"")
            .post(CastXml.soapEnvelope(action, AV_TRANSPORT, args).toRequestBody(SOAP_TYPE))
            .build()

    private companion object {
        const val TAG = "DlnaCastManager"
        const val AV_TRANSPORT = "urn:schemas-upnp-org:service:AVTransport:1"
        val SOAP_TYPE = "text/xml; charset=\"utf-8\"".toMediaType()
    }
}
