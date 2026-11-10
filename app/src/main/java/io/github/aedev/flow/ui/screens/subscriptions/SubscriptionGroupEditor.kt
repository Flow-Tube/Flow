package io.github.aedev.flow.ui.screens.subscriptions

import io.github.aedev.flow.data.local.PlayerPreferences
import io.github.aedev.flow.data.local.dao.SubscriptionGroupDao
import io.github.aedev.flow.utils.PerformanceDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Creates, edits, deletes and orders subscription groups, keeping the selected chip on a renamed or deleted group. */
internal class SubscriptionGroupEditor(
    private val scope: CoroutineScope,
    private val dao: SubscriptionGroupDao,
    private val preferences: PlayerPreferences,
    private val selectedGroup: () -> String?,
    private val onSelectedRenamed: (String) -> Unit,
    private val onSelectedDeleted: () -> Unit,
) {
    private val orderLock = Mutex()

    fun create(
        name: String,
        channelIds: List<String>,
    ) {
        scope.launch(PerformanceDispatcher.diskIO) { dao.appendGroup(name, channelIds) }
    }

    fun update(
        oldName: String,
        newName: String,
        channelIds: List<String>,
    ) {
        scope.launch(PerformanceDispatcher.diskIO) {
            val edited = dao.editGroup(oldName, newName, channelIds)
            if (edited && selectedGroup() == oldName) {
                onSelectedRenamed(newName)
                preferences.setSelectedSubscriptionGroup(newName)
            }
        }
    }

    fun delete(name: String) {
        scope.launch(PerformanceDispatcher.diskIO) {
            dao.deleteGroup(name)
            if (selectedGroup() == name) onSelectedDeleted()
        }
    }

    /** Saves [names] as the group order and Ungrouped's place in it; each save is a whole order, so saves are kept in sequence. */
    fun setOrder(names: List<String>) {
        val (groupNames, ungroupedPosition) = splitUngroupedOrder(names)
        scope.launch(PerformanceDispatcher.diskIO) {
            orderLock.withLock {
                dao.setOrder(groupNames)
                ungroupedPosition?.let { preferences.setSubscriptionUngroupedPosition(it) }
            }
        }
    }
}
