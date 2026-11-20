package io.github.aedev.flow.player.factory

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.media3.common.util.NetworkTypeObserver
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.upstream.BandwidthMeter
import io.github.aedev.flow.data.local.PlayerPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Keeps the meter's last measured estimate and its network type, and saves it on [save] for
 * [PlayerFactory.createBandwidthMeter] to start from. Only a real sample is saved: the meter's
 * unmeasured estimate is the seed itself, and saving that would keep an old seed alive forever.
 */
@UnstableApi
internal class BandwidthSeedRecorder(
    context: Context,
    private val scope: CoroutineScope,
    private val meter: BandwidthMeter,
) : BandwidthMeter.EventListener {
    private val appContext = context.applicationContext
    private val prefs = PlayerPreferences(appContext)
    private var sampledBits: Long? = null
    private var sampledNetworkType = 0

    init {
        meter.addEventListener(Handler(Looper.getMainLooper()), this)
    }

    override fun onBandwidthSample(
        elapsedMs: Int,
        bytesTransferred: Long,
        bitrateEstimate: Long,
    ) {
        sampledBits = bitrateEstimate
        sampledNetworkType = NetworkTypeObserver.getInstance(appContext).networkType
    }

    fun save() {
        val bits = sampledBits ?: return
        val networkType = sampledNetworkType
        sampledBits = null
        scope.launch(Dispatchers.IO) {
            val seeds = BandwidthSeeds.decode(prefs.bandwidthSeeds.first())
            val updated = BandwidthSeeds.record(seeds, networkType, bits, System.currentTimeMillis())
            if (updated != seeds) prefs.setBandwidthSeeds(BandwidthSeeds.encode(updated))
        }
    }

    fun release() {
        save()
        meter.removeEventListener(this)
    }
}
