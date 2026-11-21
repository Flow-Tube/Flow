package io.github.aedev.flow.utils

import java.util.Locale

/**
 * One value built for the current locale and rebuilt only when the locale changes. Access is
 * serialised because the values it holds, ICU formatters among them, are not thread-safe.
 */
internal class LocaleSlot<T : Any>(
    private val create: (Locale) -> T,
) {
    private var locale: Locale? = null
    private var value: T? = null

    @Synchronized
    fun <R> use(
        locale: Locale,
        block: (T) -> R,
    ): R {
        val current =
            value?.takeIf { this.locale == locale } ?: create(locale).also {
                this.locale = locale
                value = it
            }
        return block(current)
    }
}
