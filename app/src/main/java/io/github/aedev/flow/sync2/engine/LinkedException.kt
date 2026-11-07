package io.github.aedev.flow.sync2.engine

/** Why a linked-devices action stopped, beyond store and protocol errors. */
sealed class LinkedException(
    message: String,
) : Exception(message) {
    class NotLinked : LinkedException("This device is not in a sync group")

    class Removed : LinkedException("This device was removed from its sync group")

    class RecoveryDue : LinkedException("The recovery key has to be replaced before anything is written")

    class RestoreUnfinished : LinkedException("A restore has to sign out the devices the user didn't choose before anything is written")

    class RemovalUnconfirmed : LinkedException("The roster that leaves this device out replaces entries it had applied")

    class Contested : LinkedException("Two sides of the roster removed each other")

    class RosterRolledBack : LinkedException("The store no longer shows a roster entry this device applied")

    class RosterRewritten : LinkedException("The store changed a roster entry this device applied")

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
}
