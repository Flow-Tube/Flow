package io.github.aedev.flow.ui.components.musicplayer.queue

/** What a queue row's menu offers on a remote, which can't swipe or drag the row. */
internal enum class QueueRowMenuAction {
    PLAY_NEXT,
    ADD_TO_QUEUE,
    MOVE_UP,
    MOVE_DOWN,
    MOVE_TO_END,
    REMOVE,
}

/** A radio suggestion can only join the queue; a queued row moves within it or leaves it. */
internal fun queueRowMenuActions(
    isRadioItem: Boolean,
    index: Int,
    lastIndex: Int,
): List<QueueRowMenuAction> =
    if (isRadioItem) {
        listOf(QueueRowMenuAction.PLAY_NEXT, QueueRowMenuAction.ADD_TO_QUEUE)
    } else {
        buildList {
            add(QueueRowMenuAction.PLAY_NEXT)
            if (index > 0) add(QueueRowMenuAction.MOVE_UP)
            if (index < lastIndex) {
                add(QueueRowMenuAction.MOVE_DOWN)
                add(QueueRowMenuAction.MOVE_TO_END)
            }
            add(QueueRowMenuAction.REMOVE)
        }
    }
