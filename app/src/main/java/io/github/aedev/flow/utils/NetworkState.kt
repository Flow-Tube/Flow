package io.github.aedev.flow.utils

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/**
 * Whether these capabilities can actually reach the internet.
 *
 * Requires `NET_CAPABILITY_VALIDATED`, so an unauthenticated captive portal reads as offline.
 * Shared with [NetworkConnectivityObserver] so the polled and the observed answer cannot drift.
 */
internal fun NetworkCapabilities?.hasInternet(): Boolean =
    this != null &&
        hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
        hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)

/**
 * Point-in-time answers to "what network am I on?".
 *
 * The same two questions had four hand-rolled answers across the player, the Shorts pager and the
 * player effects, and they disagreed on what to return when connectivity could not be read.
 * [NetworkConnectivityObserver] answers the reactive version of the same questions.
 */
object NetworkState {
    /**
     * Whether the active transport is wifi.
     *
     * Returns `false` when connectivity cannot be read. The player copies of this check used to
     * assume wifi in that case, which only differs when there is no active network at all — i.e.
     * when nothing is going to stream anyway — so the data-conservative answer wins.
     */
    fun isOnWifi(context: Context): Boolean = capabilities(context)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true

    /** Whether the active network can actually reach the internet. */
    fun isOnline(context: Context): Boolean = capabilities(context).hasInternet()

    /** Whether Android's Data Saver is holding this app's background use back on the current network. */
    fun isDataSaverOn(context: Context): Boolean =
        try {
            val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            manager != null && manager.isActiveNetworkMetered &&
                manager.restrictBackgroundStatus == ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED
        } catch (_: Exception) {
            false
        }

    private fun capabilities(context: Context): NetworkCapabilities? =
        try {
            val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            manager?.getNetworkCapabilities(manager.activeNetwork)
        } catch (e: SecurityException) {
            null
        }
}
