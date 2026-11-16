package io.github.aedev.flow.sync2

import android.app.Application
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.aedev.flow.sync.protocol.SyncCollection
import io.github.aedev.flow.sync2.data.DEFAULT_PROFILE
import io.github.aedev.flow.sync2.data.LinkedDatabase
import io.github.aedev.flow.sync2.data.SentRecord
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

/** Upgrades run on real files, so each one starts from the schema an older build exported. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class LinkedDatabaseMigrationTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun the_group_link_becomes_default_s_with_what_was_sent() =
        runTest {
            val name = "linked_migration"
            context.deleteDatabase(name)
            createAt(name, version = 5) {
                execSQL(
                    "INSERT INTO group_record (groupId, storeUrl, link, ownSeq, ownPrev, cursor, id) VALUES ('g', 'memory', '$LINK', 0, '', 0, 1)",
                )
                execSQL("INSERT INTO sent (collection, recordKey, hash, sentAt) VALUES ('${SyncCollection.WATCH_HISTORY}', 'a', 'h', 7)")
            }
            val db = Room.databaseBuilder(context, LinkedDatabase::class.java, name).build()
            assertEquals(
                listOf(SentRecord(LINK, SyncCollection.WATCH_HISTORY, "a", "h", 7)),
                db.sent().forCollection(LINK, SyncCollection.WATCH_HISTORY),
            )
            db.openHelper.readableDatabase.query("SELECT profile, link, caughtUp FROM profile_links").use {
                assertTrue(it.moveToFirst())
                assertEquals(listOf(DEFAULT_PROFILE, LINK, "1"), (0..2).map(it::getString))
                assertFalse(it.moveToNext())
            }
            db.close()
        }

    private fun createAt(
        name: String,
        version: Int,
        rows: SQLiteDatabase.() -> Unit,
    ) {
        val schema =
            Json
                .parseToJsonElement(File("schemas/${LinkedDatabase::class.qualifiedName}/$version.json").readText())
                .jsonObject
                .getValue("database")
                .jsonObject
        context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null).use { db ->
            for (entity in schema.getValue("entities").jsonArray.map { it.jsonObject }) {
                val table = entity.text("tableName")
                db.execSQL(entity.text("createSql").replace("\${TABLE_NAME}", table))
                entity["indices"]?.jsonArray?.forEach { db.execSQL(it.jsonObject.text("createSql").replace("\${TABLE_NAME}", table)) }
            }
            schema.getValue("setupQueries").jsonArray.forEach { db.execSQL(it.jsonPrimitive.content) }
            db.rows()
            db.version = version
        }
    }

    private fun JsonObject.text(key: String) = getValue(key).jsonPrimitive.content
}
