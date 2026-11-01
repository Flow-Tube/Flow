package io.github.aedev.flow.sync2.engine

/** Why a linked-devices action stopped, beyond store and protocol errors. */
sealed class LinkedException(
    message: String,
) : Exception(message) {
    class NotLinked : LinkedException("This device is not in a sync group")

    class Removed : LinkedException("This device was removed from its sync group")

    class Declined : LinkedException("The verification code was not confirmed")

    class TimedOut : LinkedException("The other device did not answer in time")

    class JoinDeclined : LinkedException("A linked device declined the request to join")

    class Unexpected : LinkedException("The other device sent something unexpected")

    class Stopped : LinkedException("The other device stopped pairing")

    class WrongWords : LinkedException("These aren't the recovery words of a group")

    class RecoveryUsed : LinkedException("This recovery key was already used")

    class AlreadyLinked : LinkedException("This device is already in a sync group")

    class ServerHasGroup : LinkedException("This server already has a group")

    class NeedsMember : LinkedException("A new device's code is scanned from a device already in the group")

    class LanUnreachable : LinkedException("The other device can't be reached on this network")

    class NoProfileSynced : LinkedException("No profile on this device syncs, so a new device has none to join")
}
