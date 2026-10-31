package io.github.aedev.flow.data.local

import com.google.gson.GsonBuilder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStreamReader
import java.io.StringReader
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** The master backup's app data streams into and out of its zip entry without closing or overrunning the zip. */
class BackupJsonTest {
    private val json = BackupJson(GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create())
    private val data =
        BackupData(
            version = 2,
            timestamp = 1_700_000_000_000,
            contentPreferences =
                ContentPreferencesBackup(
                    preferredTopics = setOf("synths"),
                    blockedTopics = emptySet(),
                    blockedChannels = setOf("UCx"),
                ),
        )
    private val after = byteArrayOf(1, 2, 3, 4)

    private fun zipped(): ByteArray {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip ->
            zip.putNextEntry(ZipEntry("app_data.json"))
            json.write(data, zip)
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("after.bin"))
            zip.write(after)
            zip.closeEntry()
        }
        return bytes.toByteArray()
    }

    @Test
    fun app_data_round_trips_through_a_zip_and_the_next_entry_survives() {
        var read: BackupData? = null
        var next: ByteArray? = null
        ZipInputStream(ByteArrayInputStream(zipped())).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                when (entry.name) {
                    "app_data.json" -> read = json.read(InputStreamReader(zip, Charsets.UTF_8))
                    "after.bin" -> next = zip.readBytes()
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        assertEquals(data, read)
        assertArrayEquals(after, next)
    }

    @Test
    fun an_empty_backup_reads_as_nothing() {
        assertNull(json.read(StringReader("")))
    }
}
