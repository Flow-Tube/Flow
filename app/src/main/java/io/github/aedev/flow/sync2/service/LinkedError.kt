package io.github.aedev.flow.sync2.service

import io.github.aedev.flow.sync2.crypto.ProtocolError
import io.github.aedev.flow.sync2.crypto.ProtocolException
import io.github.aedev.flow.sync2.engine.LinkedException
import io.github.aedev.flow.sync2.store.NetworkFailure
import io.github.aedev.flow.sync2.store.StoreException

/** The [StoreException.Refused.code] of every refusal from a WebDAV folder. */
private const val WEBDAV = "webdav"

/** Why a linked-devices action failed, as the UI tells the user. */
enum class LinkedError(
    /** Worth trying again later on its own, without the user changing anything. */
    val retryable: Boolean = false,
) {
    INSECURE,
    UNREACHABLE(retryable = true),
    CONNECT_TIMEOUT(retryable = true),
    CONNECTION_REFUSED(retryable = true),
    NO_SUCH_HOST(retryable = true),
    NO_ANSWER(retryable = true),
    NOT_HTTP,
    REFUSED,
    FOLDER_REDIRECTED,
    FOLDER_MISSING,
    FOLDER_FORBIDDEN,
    GROUP_MISSING,
    ADMIN_REFUSED,
    ADMIN_BLOCKED,
    LOGIN_REFUSED,
    BUSY(retryable = true),
    INSTANCE_CHANGED,
    PROTOCOL,
    LOCAL(retryable = true),
    NOT_LINKED,
    REMOVED,
    REMOVAL_UNCONFIRMED,
    CONTESTED(retryable = true),
    ROLLED_BACK(retryable = true),
    RECOVERY_DUE(retryable = false),
    ROSTER_CHANGED(retryable = true),
    RESTORE_UNFINISHED(retryable = true),
    GROUP_OUTDATED,
    DECLINED,
    TIMED_OUT,
    JOIN_DECLINED,
    WRONG_WORDS,
    RECOVERY_USED,
    ALREADY_LINKED,
    LAN_UNREACHABLE,
    SERVER_HAS_GROUP,
    NEEDS_MEMBER,
    UNEXPECTED,
    STOPPED,
}

fun Throwable.linkedError(): LinkedError =
    when (this) {
        is StoreException.Insecure -> {
            LinkedError.INSECURE
        }

        is StoreException.Network -> {
            when (failure) {
                NetworkFailure.CONNECT_TIMEOUT -> LinkedError.CONNECT_TIMEOUT
                NetworkFailure.CONNECTION_REFUSED -> LinkedError.CONNECTION_REFUSED
                NetworkFailure.NO_SUCH_HOST -> LinkedError.NO_SUCH_HOST
                NetworkFailure.NO_ANSWER -> LinkedError.NO_ANSWER
                NetworkFailure.NOT_HTTP -> LinkedError.NOT_HTTP
                NetworkFailure.OTHER -> LinkedError.UNREACHABLE
            }
        }

        is StoreException.Refused -> {
            when {
                rateLimited -> LinkedError.BUSY
                code == "unclaimed" -> LinkedError.GROUP_MISSING
                code == WEBDAV && status in 300..399 -> LinkedError.FOLDER_REDIRECTED
                code == WEBDAV && (status == 404 || status == 409) -> LinkedError.FOLDER_MISSING
                code == WEBDAV && (status == 403 || status == 405) -> LinkedError.FOLDER_FORBIDDEN
                else -> LinkedError.REFUSED
            }
        }

        is StoreException.AdminRefused -> {
            LinkedError.ADMIN_REFUSED
        }

        is StoreException.AdminBlocked -> {
            LinkedError.ADMIN_BLOCKED
        }

        is StoreException.LoginRefused -> {
            LinkedError.LOGIN_REFUSED
        }

        is StoreException.InstanceChanged -> {
            LinkedError.INSTANCE_CHANGED
        }

        is StoreException.SignInRefused -> {
            LinkedError.REMOVED
        }

        is ProtocolException -> {
            if (error == ProtocolError.ROSTER_GROUP) LinkedError.GROUP_OUTDATED else LinkedError.PROTOCOL
        }

        is StoreException.Malformed, is StoreException.TooLarge -> {
            LinkedError.PROTOCOL
        }

        is LinkedException.NotLinked -> {
            LinkedError.NOT_LINKED
        }

        is LinkedException.Removed -> {
            LinkedError.REMOVED
        }

        is LinkedException.RemovalUnconfirmed -> {
            LinkedError.REMOVAL_UNCONFIRMED
        }

        is LinkedException.Contested -> {
            LinkedError.CONTESTED
        }

        is LinkedException.RecoveryDue -> {
            LinkedError.RECOVERY_DUE
        }

        is LinkedException.RestoreUnfinished -> {
            LinkedError.RESTORE_UNFINISHED
        }

        is LinkedException.RosterRolledBack -> {
            LinkedError.ROLLED_BACK
        }

        is LinkedException.RosterRewritten -> {
            LinkedError.ROSTER_CHANGED
        }

        is LinkedException.Declined -> {
            LinkedError.DECLINED
        }

        is LinkedException.TimedOut -> {
            LinkedError.TIMED_OUT
        }

        is LinkedException.JoinDeclined -> {
            LinkedError.JOIN_DECLINED
        }

        is LinkedException.WrongWords -> {
            LinkedError.WRONG_WORDS
        }

        is LinkedException.RecoveryUsed -> {
            LinkedError.RECOVERY_USED
        }

        is LinkedException.AlreadyLinked -> {
            LinkedError.ALREADY_LINKED
        }

        is LinkedException.LanUnreachable -> {
            LinkedError.LAN_UNREACHABLE
        }

        is LinkedException.ServerHasGroup -> {
            LinkedError.SERVER_HAS_GROUP
        }

        is LinkedException.NeedsMember -> {
            LinkedError.NEEDS_MEMBER
        }

        is LinkedException.Unexpected -> {
            LinkedError.UNEXPECTED
        }

        is LinkedException.Stopped -> {
            LinkedError.STOPPED
        }

        // Damaged device keys, a failed merge, the database.
        else -> {
            LinkedError.LOCAL
        }
    }
