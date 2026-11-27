package io.github.aedev.flow.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Represents a single downloaded file (video track, audio track, or muxed file).
 * Linked to a parent DownloadEntity via videoId foreign key.
 */
@Entity(
    tableName = "download_items",
    foreignKeys = [
        ForeignKey(
            entity = DownloadEntity::class,
            parentColumns = ["videoId"],
            childColumns = ["videoId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["videoId"]),
        Index(value = ["filePath"], unique = true),
    ],
)
data class DownloadItemEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,
    val videoId: String,
    val fileType: DownloadFileType,
    val fileName: String,
    val filePath: String,
    val url: String = "",
    val format: String = "",
    val quality: String = "",
    val mimeType: String = "",
    val downloadedBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val status: DownloadItemStatus = DownloadItemStatus.PENDING,
    /** The playback gain YouTube's loudness data gave the downloaded audio, for normalizing it offline. */
    val loudnessGainDb: Float? = null,
)

/**
 * Type of downloaded file
 */
enum class DownloadFileType {
    /** A video file (may contain both video+audio if muxed, or video-only for DASH) */
    VIDEO,

    /** An audio-only file */
    AUDIO,
}

/**
 * Status of a download item
 */
enum class DownloadItemStatus {
    PENDING,
    DOWNLOADING,
    PAUSED,
    COMPLETED,
    FAILED,
    CANCELLED,
}
