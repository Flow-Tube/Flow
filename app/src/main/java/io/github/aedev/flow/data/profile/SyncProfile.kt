package io.github.aedev.flow.data.profile

import android.content.Context
import io.github.aedev.flow.R

/**
 * One-time sync (FLOW-SYNC/1) belongs to Default: it carries no profile link, so another profile syncing under the
 * same device id would merge into the other device's Default. Linked devices sync each profile under its own link.
 */
object SyncProfile {
    fun syncsHere(context: Context): Boolean = ActiveProfile.get(context).isDefault

    /** The name to show for the profile that syncs. */
    fun name(
        state: ProfileRegistryState,
        context: Context,
    ): String =
        state.profiles.firstOrNull { it.id == state.defaultId }?.displayName(context) ?: context.getString(R.string.profile_default_name)
}
