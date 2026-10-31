package io.github.aedev.flow.data.local

import com.google.gson.Gson
import com.google.gson.Strictness
import com.google.gson.stream.JsonReader
import java.io.BufferedWriter
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.io.Reader

/**
 * The app data of a backup as JSON, streamed rather than built as one string: a full profile is tens of
 * megabytes of JSON, which as a string (and then its bytes) does not fit in the heap of every phone.
 */
internal class BackupJson(
    private val gson: Gson,
) {
    /** Writes [data] into [out] and leaves it open, so a zip can go on to its next entry. */
    fun write(
        data: BackupData,
        out: OutputStream,
    ) {
        val writer = BufferedWriter(OutputStreamWriter(out, Charsets.UTF_8))
        gson.toJson(data, writer)
        writer.flush()
    }

    /** Reads one backup from [source], leniently; null for an empty one. */
    fun read(source: Reader): BackupData? {
        val reader = JsonReader(source)
        reader.setStrictness(Strictness.LENIENT)
        return gson.fromJson(reader, BackupData::class.java)
    }
}
