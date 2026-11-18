package io.github.aedev.flow.utils

/** One avatar served at different sizes shares this key, so a list keeps a single copy of it. */
internal fun String?.avatarImageIdentityKey(): String =
    orEmpty()
        .substringBefore("?")
        .replace(Regex("=s\\d+.*$"), "")
