package io.github.aedev.flow.player.sponsorblock

import android.util.Log
import io.github.aedev.flow.data.local.SponsorBlockAction
import io.github.aedev.flow.data.model.SponsorBlockCategories
import io.github.aedev.flow.data.model.SponsorBlockSegment
import io.github.aedev.flow.data.repository.SponsorBlockRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** A skip that happened. [firstInVideo] is false when this segment was already skipped in this video. */
class SponsorBlockSkip(
    val segment: SponsorBlockSegment,
    val firstInVideo: Boolean,
)

/**
 * Handles SponsorBlock segment loading and skip logic.
 *
 * Per-category actions are controlled by [categoryActions] map; [SponsorBlockActionPolicy] says what
 * each one does.
 */
class SponsorBlockHandler(
    private val scope: CoroutineScope,
) {
    companion object {
        private const val TAG = "SponsorBlockHandler"
        private const val VISIBLE_SEGMENTS_STOP_MS = 5_000L
    }

    private val sponsorBlockRepository = SponsorBlockRepository()

    private val _sponsorSegments = MutableStateFlow<List<SponsorBlockSegment>>(emptyList())
    val sponsorSegments: StateFlow<List<SponsorBlockSegment>> = _sponsorSegments.asStateFlow()

    /** Emitted when a segment should be skipped (seeked past). */
    private val _skipEvent = MutableSharedFlow<SponsorBlockSkip>(extraBufferCapacity = 1)
    val skipEvent: SharedFlow<SponsorBlockSkip> = _skipEvent.asSharedFlow()

    /** Emitted when entering a MUTE segment (true) or leaving one (false). */
    private val _muteEvent = MutableSharedFlow<Boolean>(extraBufferCapacity = 1)
    val muteEvent: SharedFlow<Boolean> = _muteEvent.asSharedFlow()

    /** Emitted when a SHOW_TOAST segment is encountered. */
    private val _toastEvent = MutableSharedFlow<SponsorBlockSegment>(extraBufferCapacity = 1)
    val toastEvent: SharedFlow<SponsorBlockSegment> = _toastEvent.asSharedFlow()

    private var loadJob: Job? = null

    /** Segments already acted on (skipped, jumped over or announced) until playback rewinds before them. */
    private val handledUuids = mutableSetOf<String>()
    private var currentMutedSegmentUuid: String? = null
    private var currentVideoId: String? = null

    /** Segments stored with a download, held even while SponsorBlock is off so turning it on can show
     * them without a network refresh that would fail offline. */
    private var offlineSegments: List<SponsorBlockSegment> = emptyList()

    /** The one video the user switched SponsorBlock off for. Kept by id, so preparing that video again
     * keeps it off, and any other video starts with the user's setting. */
    private var disabledVideoId: String? = null
    private val _disabledForCurrentVideo = MutableStateFlow(false)
    val disabledForCurrentVideo: StateFlow<Boolean> = _disabledForCurrentVideo.asStateFlow()

    var isEnabled: Boolean = false
        private set

    private val _categoryActions = MutableStateFlow<Map<String, SponsorBlockAction>>(emptyMap())

    /** Category id to action; a category missing here takes [SponsorBlockCategories.defaultAction]. */
    var categoryActions: Map<String, SponsorBlockAction>
        get() = _categoryActions.value
        set(value) {
            _categoryActions.value = value
        }

    /** The segments the seek bar and the skip button show: every category but the disabled ones. */
    val visibleSegments: StateFlow<List<SponsorBlockSegment>> =
        combine(_sponsorSegments, _categoryActions, SponsorBlockActionPolicy::visibleSegments)
            .stateIn(scope, SharingStarted.WhileSubscribed(VISIBLE_SEGMENTS_STOP_MS), emptyList())

    private val _skippedOnce = MutableStateFlow<Set<String>>(emptySet())

    /** Kept by id like [disabledVideoId], so preparing the same video again does not skip a second time. */
    private var skippedOnceVideoId: String? = null

    /** Segments of the current video a "skip once" category has already skipped. */
    val skippedOnce: StateFlow<Set<String>> = _skippedOnce.asStateFlow()

    private val skippedInVideo = mutableSetOf<String>()

    /**
     * Set whether SponsorBlock is enabled.
     */
    fun setEnabled(enabled: Boolean) {
        if (isEnabled != enabled) {
            isEnabled = enabled
            if (enabled) {
                if (offlineSegments.isNotEmpty()) {
                    _sponsorSegments.value = offlineSegments
                } else {
                    currentVideoId?.let { loadSegments(it) }
                }
            } else {
                loadJob?.cancel()
                _sponsorSegments.value = emptyList()
                handledUuids.clear()
                releaseMute()
            }
        }
    }

    /**
     * Load SponsorBlock segments directly from a pre-fetched list (e.g. saved offline).
     * Bypasses the network API call. Safe to call even when [isEnabled] is false —
     * the segments are held and shown once SponsorBlock is enabled.
     */
    fun loadSegmentsFromList(
        videoId: String,
        segments: List<SponsorBlockSegment>,
    ) {
        startVideo(videoId)
        loadJob?.cancel()
        handledUuids.clear()
        offlineSegments = segments
        _sponsorSegments.value = if (isEnabled) segments else emptyList()
        Log.d(TAG, "Loaded ${segments.size} offline SponsorBlock segments for video $videoId")
    }

    /**
     * Load SponsorBlock segments for a video.
     */
    fun loadSegments(videoId: String) {
        startVideo(videoId)

        if (!isEnabled) return

        // Cancel previous load and clear state
        loadJob?.cancel()
        _sponsorSegments.value = emptyList()
        handledUuids.clear()

        loadJob =
            scope.launch {
                try {
                    val segments = sponsorBlockRepository.getSegments(videoId)
                    _sponsorSegments.value = segments
                    Log.d(TAG, "Loaded ${segments.size} segments for video $videoId")
                    segments.forEach {
                        Log.d(TAG, "Segment: ${it.category} [${it.startTime} - ${it.endTime}]")
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to load segments for video $videoId", e)
                }
            }
    }

    /**
     * Refetch [videoId]'s segments in place, e.g. after the user submitted one. The current list stays
     * on the seek bar until the new one arrives, and an empty answer (the fetch failed, or the server
     * has not published the submission yet) keeps it.
     */
    fun reloadSegments(videoId: String) {
        if (!isEnabled || videoId != currentVideoId) return
        loadJob?.cancel()
        loadJob =
            scope.launch {
                try {
                    val segments = sponsorBlockRepository.getSegments(videoId)
                    if (segments.isNotEmpty() && videoId == currentVideoId) {
                        offlineSegments = emptyList()
                        _sponsorSegments.value = segments
                    }
                    Log.d(TAG, "Reloaded ${segments.size} segments for video $videoId")
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to reload segments for video $videoId", e)
                }
            }
    }

    /**
     * Reset SponsorBlock state for a new video.
     */
    fun reset() {
        loadJob?.cancel()
        _sponsorSegments.value = emptyList()
        handledUuids.clear()
        releaseMute()
        currentVideoId = null
        offlineSegments = emptyList()
    }

    /**
     * Switch SponsorBlock off or back on for the current video only. Turning it back on while inside a
     * segment leaves that segment alone, so playback does not jump the moment the user taps.
     */
    fun setDisabledForCurrentVideo(
        disabled: Boolean,
        currentPositionMs: Long,
    ) {
        val videoId = currentVideoId ?: return
        disabledVideoId = videoId.takeIf { disabled }
        _disabledForCurrentVideo.value = disabled
        if (disabled) {
            releaseMute()
        } else {
            handledUuids.clear()
            SponsorBlockSegmentResolver
                .segmentsAt(_sponsorSegments.value, currentPositionMs / 1000f)
                .mapTo(handledUuids) { it.uuid }
        }
    }

    private fun startVideo(videoId: String) {
        releaseMute()
        if (videoId != skippedOnceVideoId) {
            skippedOnceVideoId = videoId
            _skippedOnce.value = emptySet()
            skippedInVideo.clear()
        }
        currentVideoId = videoId
        if (videoId != disabledVideoId) disabledVideoId = null
        _disabledForCurrentVideo.value = videoId == disabledVideoId
    }

    private fun releaseMute() {
        if (currentMutedSegmentUuid != null) {
            currentMutedSegmentUuid = null
            _muteEvent.tryEmit(false)
        }
    }

    /**
     * Check if we need to act on a segment at the given position.
     * Returns the seek position in milliseconds if a SKIP is needed, null otherwise.
     * MUTE and SHOW_TOAST actions are handled via their respective flows.
     */
    fun checkForSkip(currentPositionMs: Long): Long? {
        if (!isEnabled || _disabledForCurrentVideo.value) return null
        val segments = _sponsorSegments.value
        if (segments.isEmpty()) return null

        val posSec = currentPositionMs / 1000f

        if (handledUuids.isNotEmpty()) {
            segments
                .filter { it.uuid in handledUuids && SponsorBlockSegmentResolver.isRewoundBefore(posSec, it) }
                .forEach {
                    Log.d(TAG, "Seek back detected, re-arming segment: ${it.category}")
                    handledUuids -= it.uuid
                }
        }

        if (currentMutedSegmentUuid != null) {
            val mutedSeg = segments.find { it.uuid == currentMutedSegmentUuid }
            if (mutedSeg == null || posSec >= mutedSeg.endTime || posSec < mutedSeg.startTime) {
                Log.d(TAG, "Exiting mute segment")
                currentMutedSegmentUuid = null
                _muteEvent.tryEmit(false)
            }
        }

        val hit =
            SponsorBlockSegmentResolver.resolve(segments, posSec, categoryActions, _skippedOnce.value, handledUuids)
                ?: return null
        Log.d(TAG, "Segment hit: ${hit.segment.category} action=${hit.action}")
        if (hit.skipsAutomatically) return skip(hit)
        when (hit.action) {
            SponsorBlockAction.MUTE -> {
                if (currentMutedSegmentUuid != hit.segment.uuid) {
                    currentMutedSegmentUuid = hit.segment.uuid
                    _muteEvent.tryEmit(true)
                }
            }

            SponsorBlockAction.SHOW_TOAST -> {
                handledUuids += hit.segment.uuid
                _toastEvent.tryEmit(hit.segment)
            }

            else -> {
                Unit
            }
        }

        return null
    }

    /**
     * Where a seek to [targetMs] lands instead when the target is inside a segment that skips
     * automatically, or null to seek as asked. The tick's re-arm can't cover this: a segment at the very
     * start of a video has no position before it, and the tick would show a second of the segment first.
     */
    fun skipTargetForSeek(targetMs: Long): Long? = autoSkipAt(targetMs)?.let(::skip)

    /** Where [skipTargetForSeek] would land, without skipping anything. */
    fun seekLandingFor(targetMs: Long): Long? = autoSkipAt(targetMs)?.targetMs

    private fun autoSkipAt(targetMs: Long): SponsorBlockResolution? {
        if (!isEnabled || _disabledForCurrentVideo.value) return null
        return SponsorBlockSegmentResolver
            .resolve(_sponsorSegments.value, targetMs / 1000f, categoryActions, _skippedOnce.value)
            ?.takeIf { it.skipsAutomatically }
    }

    private fun skip(resolution: SponsorBlockResolution): Long {
        resolution.covered.mapTo(handledUuids) { it.uuid }
        val skippedOnce =
            resolution.chain.filter {
                SponsorBlockActionPolicy.actionFor(it, categoryActions) == SponsorBlockAction.SKIP_ONCE
            }
        if (skippedOnce.isNotEmpty()) _skippedOnce.value += skippedOnce.map { it.uuid }
        val firstInVideo = skippedInVideo.add(resolution.segment.uuid)
        resolution.chain.forEach { skippedInVideo.add(it.uuid) }
        _skipEvent.tryEmit(SponsorBlockSkip(resolution.segment, firstInVideo))
        return resolution.targetMs
    }

    /**
     * Get the current segments list.
     */
    fun getSegments(): List<SponsorBlockSegment> = _sponsorSegments.value

    /**
     * Check if segments have been loaded.
     */
    fun hasSegments(): Boolean = _sponsorSegments.value.isNotEmpty()
}
