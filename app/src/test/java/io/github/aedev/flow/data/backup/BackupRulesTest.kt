package io.github.aedev.flow.data.backup

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * What Android may copy off the device (#907). Every store the app declares is classified here, so a
 * new one has to be placed deliberately; anything not included in the rules stays on the device.
 */
class BackupRulesTest {
    private val settings =
        setOf(
            "file:datastore/player_preferences.preferences_pb",
            "file:datastore/device_preferences.preferences_pb",
            "file:datastore/flow_preferences.preferences_pb",
            "file:datastore/app_font.preferences_pb",
            "file:datastore/app_ui_mode.preferences_pb",
            "file:datastore/audio_settings.preferences_pb",
            "file:datastore/local_media.preferences_pb",
            "sharedpref:flow_language_prefs.xml",
            "file:datastore/profiles_registry.json",
            "file:profile_settings",
            "file:profile_avatars",
        )

    private val library =
        setOf(
            "database:flow_database",
            "database:flow_database-wal",
            "database:flow_database-shm",
            "file:datastore/liked_videos.preferences_pb",
            "file:datastore/subscriptions.preferences_pb",
            "file:datastore/search_history.preferences_pb",
            "file:datastore/view_history.preferences_pb",
            "file:datastore/playlists.preferences_pb",
            "file:datastore/favourite_artists.preferences_pb",
            "file:datastore/saved_playlist_sync.preferences_pb",
            "file:datastore/watch_later_metadata_migration.preferences_pb",
            "file:datastore/flow_recap.preferences_pb",
            "file:datastore/flow_recap_images.preferences_pb",
            "file:datastore/flow_video_stats_v1.json",
            "file:datastore/flow_video_stats_hot_v1.json",
            "file:datastore/flow_music_stats_v1.json",
            "file:datastore/flow_music_stats_hot_v1.json",
            "file:profile_library",
        )

    /** Stores that belong to this install: the engine, sync, secrets, queues, widgets and caches. */
    private val installOnly =
        setOf(
            "flow_neuro_brain_v7.json",
            "flow_neuro_brain_v8.json",
            "flow_neuro_brain_v9.json",
            "flow_neuro_brain_v10.json",
            "flow_neuro_content_v1.json",
            "flow_neuro_categories_v1.json",
            "flow_neuro_cowatch_v1.json",
            "flow_neuro_neighbours_v1.json",
            "flow_blocked_channel_names_v1.json",
            "flow_music_brain_v1.json",
            "sync_identity",
            "linked_device",
            "sync_brain_crdt",
            "sync_music_brain_crdt",
            "scrobbling",
            "youtube_identity",
            "flow_prefs",
            "flow_crash_prefs",
            "flow_storage_prefs",
            "music_queue",
            "video_queue",
            "now_playing_widget",
            "widget_content",
            "widget_theme",
            "downloads",
            "animated_artwork",
            "playback_errors",
            "flow_file_tombstones",
            "music_home_cache_prefs",
        )

    private fun includes(
        file: String,
        section: String?,
    ): Set<String> {
        val root =
            DocumentBuilderFactory
                .newInstance()
                .newDocumentBuilder()
                .parse(File("src/main/res/xml/$file"))
                .documentElement
        val scope = section?.let { root.getElementsByTagName(it).item(0) as Element } ?: root
        val nodes = scope.getElementsByTagName("include")
        assertThat(scope.getElementsByTagName("exclude").length).isEqualTo(0)
        return (0 until nodes.length)
            .map {
                (nodes.item(it) as Element).let { e ->
                    "${e.getAttribute("domain")}:${e.getAttribute("path")}"
                }
            }.toSet()
    }

    @Test
    fun `cloud backup and Android 11 and older carry settings only`() {
        assertThat(includes("data_extraction_rules.xml", "cloud-backup")).containsExactlyElementsIn(settings)
        assertThat(includes("backup_rules.xml", null)).containsExactlyElementsIn(settings)
    }

    @Test
    fun `a new phone also gets the library but never the engine or sync state`() {
        assertThat(includes("data_extraction_rules.xml", "device-transfer")).containsExactlyElementsIn(settings + library)
    }

    @Test
    fun `every store the app declares is classified`() {
        val declared =
            Regex(
                """(?:PreferencesDataStore\(\s*name\s*=\s*|getSharedPreferences\(\s*|const val (?:[A-Z]+_)*PREFS(?:_NAME|_FILE)? = )"([^"]+)"""",
            )
        val typed = Regex(""""(flow_[a-z_]+_v\d+\.json)"""")
        val names =
            File("src/main/java")
                .walkTopDown()
                .filter { it.extension == "kt" }
                .flatMap { file ->
                    val text = file.readText()
                    declared.findAll(text).map { it.groupValues[1] } + typed.findAll(text).map { it.groupValues[1] }
                }.toSet()
        val classified =
            (settings + library)
                .map {
                    it
                        .substringAfter(
                            ':',
                        ).substringAfterLast('/')
                        .removeSuffix(".preferences_pb")
                        .removeSuffix(".xml")
                }.toSet() +
                installOnly

        assertThat(names).isNotEmpty()
        assertThat(names - classified).isEmpty()
    }

    @Test
    fun `a restore removes the engine and sync files an older backup carried`() {
        val dir =
            kotlin.io.path
                .createTempDirectory("restored")
                .toFile()
        val datastore = File(dir, "datastore").apply { mkdirs() }
        listOf(
            "flow_neuro_brain_v10.json",
            "flow_neuro_brain_v9.json",
            "flow_neuro_content_v1.json",
            "sync_identity.preferences_pb",
            "sync_ledger_likes.json",
        ).forEach { File(datastore, it).writeText("{}") }
        File(dir, "user_neuro_brain.json").writeText("{}")
        File(datastore, "player_preferences.preferences_pb").writeText("kept")

        val deleted = RestoredDataCleanup.deleteInstallOnlyFiles(dir)

        assertThat(deleted).containsExactly(
            "user_neuro_brain.json",
            "flow_neuro_brain_v9.json",
            "flow_neuro_brain_v10.json",
            "flow_neuro_content_v1.json",
            "sync_identity.preferences_pb",
            "sync_ledger_likes.json",
        )
        assertThat(File(datastore, "player_preferences.preferences_pb").exists()).isTrue()
        dir.deleteRecursively()
    }
}
