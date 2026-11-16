package io.github.aedev.flow.ui.components.shared

import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import java.io.File

/**
 * Android 15 plays TextHandleMove only when the phone maker sets `config_enableHapticTextHandle`, which stock Android
 * leaves off, so on those phones every tap that used it was silent (#1866). It belongs to dragging a text cursor and
 * nowhere else. A file that really moves one adds itself to [TEXT_HANDLE_FILES].
 */
class HapticTypesTest {
    @Test
    fun textHandleMoveOnlyMovesTextHandles() {
        val offenders =
            FLAVORS
                .flatMap { flavor ->
                    val root = File("src/$flavor/java")
                    root
                        .walkTopDown()
                        .filter { it.isFile && it.extension == "kt" && "TextHandleMove" in it.readText() }
                        .map { it.relativeTo(root).invariantSeparatorsPath }
                        .toList()
                }.filterNot { it in TEXT_HANDLE_FILES }

        assertWithMessage("TextHandleMove is silent on stock Android; use VirtualKey, ContextClick, a toggle or a tick")
            .that(offenders)
            .isEmpty()
    }

    @Test
    fun theScanSeesTheSources() {
        assertWithMessage("source root").that(File("src/main/java").isDirectory).isTrue()
    }

    private companion object {
        val FLAVORS = listOf("main", "github", "foss", "nightly", "debug")
        val TEXT_HANDLE_FILES = emptySet<String>()
    }
}
