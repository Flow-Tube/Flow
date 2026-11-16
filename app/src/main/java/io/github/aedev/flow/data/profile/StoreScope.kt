package io.github.aedev.flow.data.profile

/**
 * Who owns a store's data, which decides where [ProfileFiles] puts it. Default keeps today's path for every scope;
 * other profiles get a folder per backup class.
 */
enum class StoreScope {
    /** One copy per install, shared by every profile. */
    DEVICE,

    /** Settings: backed up to the cloud and to a new phone, like today's settings stores. */
    PROFILE_SETTINGS,

    /** History, likes, playlists: copied to a new phone only, like today's library stores. */
    PROFILE_LIBRARY,

    /** Engine state, queues and accounts: never backed up. */
    PROFILE_PRIVATE,

    /** Files only Default ever had, such as the brains from before profiles. */
    DEFAULT_ONLY,
}
