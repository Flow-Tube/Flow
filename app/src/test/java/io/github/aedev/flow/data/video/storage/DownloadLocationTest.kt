package io.github.aedev.flow.data.video.storage

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class DownloadLocationTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val staging by lazy { temp.newFolder("staging") }
    private val defaultFolder by lazy { temp.newFolder("Download", "Flow") }
    private val privateFolder by lazy { temp.newFolder("private") }
    private val blocked = mutableSetOf<File>()
    private val grantedTrees = mutableSetOf<String>()

    private fun resolve(chosen: DownloadLocation) =
        resolveDownloadDestination(
            chosen = chosen,
            defaults = listOf(defaultFolder, privateFolder),
            staging = staging,
            isUsableDirectory = { it !in blocked && (it.mkdirs() || it.isDirectory) },
            hasTreeAccess = { it in grantedTrees },
        )

    @Test
    fun `a writable chosen folder is written to directly`() {
        val chosen = File(temp.root, "Custom")
        val destination = resolve(DownloadLocation(path = chosen.path))
        assertEquals(chosen, destination.directory)
        assertNull(destination.exportTreeUri)
        assertFalse(destination.fellBack)
    }

    @Test
    fun `an unwritable chosen folder falls back to the default and says so`() {
        val chosen = File(temp.root, "ReadOnly").also { blocked += it }
        val destination = resolve(DownloadLocation(path = chosen.path))
        assertEquals(defaultFolder, destination.directory)
        assertTrue(destination.fellBack)
    }

    @Test
    fun `an unwritable chosen folder picked through the system picker is exported to`() {
        val chosen = File(temp.root, "Picked").also { blocked += it }
        val tree = "content://com.android.externalstorage.documents/tree/primary%3APicked"
        grantedTrees += tree
        val destination = resolve(DownloadLocation(path = chosen.path, treeUri = tree))
        assertEquals(staging, destination.directory)
        assertEquals(tree, destination.exportTreeUri)
        assertFalse(destination.fellBack)
    }

    @Test
    fun `a picked folder whose access was revoked falls back and says so`() {
        val tree = "content://com.android.externalstorage.documents/tree/primary%3AGone"
        val destination = resolve(DownloadLocation(treeUri = tree))
        assertEquals(defaultFolder, destination.directory)
        assertNull(destination.exportTreeUri)
        assertTrue(destination.fellBack)
    }

    @Test
    fun `no choice uses the first usable default without a fallback notice`() {
        blocked += defaultFolder
        val destination = resolve(DownloadLocation.DEFAULT)
        assertEquals(privateFolder, destination.directory)
        assertFalse(destination.fellBack)
    }

    @Test
    fun `music follows the video location until it has its own`() {
        val video = DownloadLocation(path = "/storage/emulated/0/Videos")
        val music = DownloadLocation(path = "/storage/emulated/0/Songs")
        assertEquals(video, DownloadLocation.forDownload(isMusic = true, video = video, music = DownloadLocation.DEFAULT))
        assertEquals(music, DownloadLocation.forDownload(isMusic = true, video = video, music = music))
        assertEquals(video, DownloadLocation.forDownload(isMusic = false, video = video, music = music))
    }

    @Test
    fun `downloads started together each keep their own folder`() {
        val videoFolder = File(temp.root, "Videos")
        val musicFolder = File(temp.root, "Songs")
        val video = DownloadLocation(path = videoFolder.path)
        val music = DownloadLocation(path = musicFolder.path)
        val results =
            runBlocking(Dispatchers.Default) {
                List(200) { index ->
                    val isMusic = index % 2 == 0
                    async { isMusic to resolve(DownloadLocation.forDownload(isMusic, video, music)).directory }
                }.awaitAll()
            }
        results.forEach { (isMusic, directory) -> assertEquals(if (isMusic) musicFolder else videoFolder, directory) }
    }

    @Test
    fun `document ids map to their storage paths`() {
        val root = "/storage/emulated/0"
        assertEquals("/storage/emulated/0/Movies/Flow", documentIdToPath("primary:Movies/Flow", root))
        assertEquals("/storage/emulated/0/Flow/a b.mp4", documentIdToPath("primary:Flow/a b.mp4", root))
        assertEquals("/storage/emulated/0", documentIdToPath("primary:", "$root/"))
        assertEquals("/storage/1A2B-3C4D/Music", documentIdToPath("1A2B-3C4D:Music", root))
        assertEquals("/storage/emulated/0/Download/x.mp4", documentIdToPath("raw:/storage/emulated/0/Download/x.mp4", root))
        assertNull(documentIdToPath("msf:1234", root))
        assertNull(documentIdToPath("1234", root))
    }

    @Test
    fun `a picked folder without its grant is lost`() {
        val tree = "content://com.android.externalstorage.documents/tree/primary%3ADownload%2FFlow"
        val lost = lostDownloadTree(listOf(DownloadLocation(treeUri = tree)), grantedTrees = emptySet(), hasAllFilesAccess = false)
        assertEquals(tree, lost)
    }

    @Test
    fun `a picked folder still granted is not lost`() {
        val tree = "content://provider/tree/a"
        assertNull(lostDownloadTree(listOf(DownloadLocation(treeUri = tree)), grantedTrees = setOf(tree), hasAllFilesAccess = false))
    }

    @Test
    fun `default and typed locations are never lost`() {
        val locations = listOf(DownloadLocation.DEFAULT, DownloadLocation(path = "/storage/emulated/0/Movies/Flow"))
        assertNull(lostDownloadTree(locations, grantedTrees = emptySet(), hasAllFilesAccess = false))
    }

    @Test
    fun `all files access reaches a picked folder through its path`() {
        val withPath = DownloadLocation(path = "/storage/emulated/0/Download/Flow", treeUri = "content://provider/tree/a")
        assertNull(lostDownloadTree(listOf(withPath), grantedTrees = emptySet(), hasAllFilesAccess = true))
        val withoutPath = DownloadLocation(treeUri = "content://provider/tree/b")
        assertEquals(
            "content://provider/tree/b",
            lostDownloadTree(listOf(withoutPath), grantedTrees = emptySet(), hasAllFilesAccess = true),
        )
    }

    @Test
    fun `the video folder is offered before the music folder`() {
        val video = DownloadLocation(treeUri = "content://provider/tree/video")
        val music = DownloadLocation(treeUri = "content://provider/tree/music")
        assertEquals(video.treeUri, lostDownloadTree(listOf(video, music), grantedTrees = emptySet(), hasAllFilesAccess = false))
        assertEquals(
            music.treeUri,
            lostDownloadTree(listOf(video, music), grantedTrees = setOf(video.treeUri!!), hasAllFilesAccess = false),
        )
    }
}
