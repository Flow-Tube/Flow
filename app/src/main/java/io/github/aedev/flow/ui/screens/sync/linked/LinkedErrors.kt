package io.github.aedev.flow.ui.screens.sync.linked

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import io.github.aedev.flow.R
import io.github.aedev.flow.sync2.service.LinkedError

@Composable
internal fun LinkedError.message(): String =
    stringResource(
        when (this) {
            LinkedError.INSECURE -> R.string.linked_error_insecure
            LinkedError.UNREACHABLE -> R.string.linked_error_unreachable
            LinkedError.REFUSED -> R.string.linked_error_refused
            LinkedError.GROUP_MISSING -> R.string.linked_error_group_missing
            LinkedError.ADMIN_REFUSED -> R.string.linked_error_admin_refused
            LinkedError.ADMIN_BLOCKED -> R.string.linked_error_admin_blocked
            LinkedError.LOGIN_REFUSED -> R.string.linked_error_login_refused
            LinkedError.BUSY -> R.string.linked_error_busy
            LinkedError.INSTANCE_CHANGED -> R.string.linked_error_instance_changed
            LinkedError.PROTOCOL -> R.string.linked_error_protocol
            LinkedError.LOCAL -> R.string.linked_error_local
            LinkedError.NOT_LINKED -> R.string.linked_error_not_linked
            LinkedError.REMOVED -> R.string.linked_error_removed
            LinkedError.REMOVAL_UNCONFIRMED -> R.string.linked_error_removal_unconfirmed
            LinkedError.CONTESTED -> R.string.linked_error_contested
            LinkedError.RECOVERY_DUE -> R.string.linked_error_recovery_due
            LinkedError.ROLLED_BACK -> R.string.linked_error_rolled_back
            LinkedError.ROSTER_CHANGED -> R.string.linked_error_roster_changed
            LinkedError.RESTORE_UNFINISHED -> R.string.linked_error_restore_unfinished
            LinkedError.GROUP_OUTDATED -> R.string.linked_error_group_outdated
            LinkedError.DECLINED -> R.string.linked_error_declined
            LinkedError.TIMED_OUT -> R.string.linked_error_timed_out
            LinkedError.JOIN_DECLINED -> R.string.linked_error_join_declined
            LinkedError.WRONG_WORDS -> R.string.linked_error_wrong_words
            LinkedError.RECOVERY_USED -> R.string.linked_error_recovery_used
            LinkedError.ALREADY_LINKED -> R.string.linked_error_already_linked
            LinkedError.LAN_UNREACHABLE -> R.string.linked_error_lan_unreachable
            LinkedError.SERVER_HAS_GROUP -> R.string.linked_error_server_has_group
            LinkedError.NEEDS_MEMBER -> R.string.linked_error_needs_member
            LinkedError.UNEXPECTED -> R.string.linked_error_unexpected
            LinkedError.STOPPED -> R.string.linked_error_stopped
        },
    )
