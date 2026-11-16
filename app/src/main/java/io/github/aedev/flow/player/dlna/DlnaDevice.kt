package io.github.aedev.flow.player.dlna

/**
 * Represents a DLNA/UPnP AV renderer (e.g., a smart TV or media player) discovered
 * via SSDP on the local network. No Google Play Services required.
 */
data class DlnaDevice(
    val friendlyName: String,
    val location: String,
    val usn: String,
    /**
     * Absolute URL for the AVTransport SOAP service, filled in after the device
     * description XML is fetched. Empty until [SsdpDiscovery] resolves it.
     */
    val avTransportUrl: String = "",
    /** This phone's address on the network the renderer answered on; null when that wasn't known. */
    val localHost: String? = null,
)
