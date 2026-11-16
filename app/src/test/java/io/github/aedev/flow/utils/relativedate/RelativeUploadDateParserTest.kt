/*
 * Copyright (C) 2025-2026 Flow | A-EDev
 *
 * This file is part of Flow (https://github.com/Flow-Tube/Flow).
 */

package io.github.aedev.flow.utils.relativedate

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

class RelativeUploadDateParserTest {
    private val now = 1_700_000_000_000L
    private val minuteMs = 60_000L
    private val hourMs = 3_600_000L
    private val dayMs = 86_400_000L
    private val weekMs = 7 * dayMs
    private val monthMs = 30 * dayMs
    private val yearMs = 365 * dayMs

    private fun parse(
        text: String?,
        hl: String? = "en",
    ) = RelativeUploadDateParser.parse(text, hl, now)

    @Test
    fun `parses common relative dates`() {
        assertThat(parse("3 days ago")).isEqualTo(now - 3 * dayMs)
        assertThat(parse("2 weeks ago")).isEqualTo(now - 2 * weekMs)
        assertThat(parse("1 month ago")).isEqualTo(now - monthMs)
        assertThat(parse("4 years ago")).isEqualTo(now - 4 * yearMs)
        assertThat(parse("45 mins ago")).isEqualTo(now - 45 * minuteMs)
        assertThat(parse("2 hrs ago")).isEqualTo(now - 2 * hourMs)
    }

    @Test
    fun `an article reads as one`() {
        assertThat(parse("an hour ago")).isEqualTo(now - hourMs)
        assertThat(parse("a year ago")).isEqualTo(now - yearMs)
    }

    @Test
    fun `compact english shorthand reads only as the whole text`() {
        assertThat(parse("3d")).isEqualTo(now - 3 * dayMs)
        assertThat(parse("5h ago")).isEqualTo(now - 5 * hourMs)
        assertThat(parse("2mo ago")).isEqualTo(now - 2 * monthMs)
        assertThat(parse("30s")).isEqualTo(now - 30_000L)
    }

    @Test
    fun `strips streamed and premiered prefixes`() {
        assertThat(parse("Streamed 2 days ago")).isEqualTo(now - 2 * dayMs)
        assertThat(parse("Premiered 5 hours ago")).isEqualTo(now - 5 * hourMs)
    }

    @Test
    fun `unknown text returns null - never now`() {
        assertThat(parse(null)).isNull()
        assertThat(parse("")).isNull()
        assertThat(parse("some random text")).isNull()
    }

    @Test
    fun `today and yesterday resolve`() {
        assertThat(parse("today")).isEqualTo(now)
        assertThat(parse("yesterday")).isEqualTo(now - dayMs)
    }

    @Test
    fun `a localized age is not read as seconds when the host language is unknown`() {
        assertThat(parse("hace 3 semanas", hl = "en")).isNull()
        assertThat(parse("il y a 2 mois", hl = null)).isNull()
        assertThat(parse("há 5 anos", hl = "xx")).isNull()
    }

    @Test
    fun `the top content languages parse in their own words`() {
        val cases =
            listOf(
                Triple("es", "hace 3 semanas", 3 * weekMs),
                Triple("fr", "il y a 2 mois", 2 * monthMs),
                Triple("pt", "há 5 anos", 5 * yearMs),
                Triple("de", "vor 4 Stunden", 4 * hourMs),
                Triple("ru", "3 недели назад", 3 * weekMs),
                Triple("ar", "منذ 3 أسابيع", 3 * weekMs),
                Triple("ja", "3 日前", 3 * dayMs),
                Triple("it", "2 settimane fa", 2 * weekMs),
                Triple("tr", "5 gün önce", 5 * dayMs),
                Triple("hi", "2 घंटे पहले", 2 * hourMs),
                Triple("id", "3 minggu yang lalu", 3 * weekMs),
            )
        for ((hl, text, age) in cases) {
            assertWithMessage("$hl: $text").that(parse(text, hl)).isEqualTo(now - age)
        }
    }

    @Test
    fun `every language Flow ships a translation for parses`() {
        val cases =
            listOf(
                Triple("ar", "منذ 11 شهرًا", 11 * monthMs),
                Triple("az", "2 həftə əvvəl", 2 * weekMs),
                Triple("yue", "3 週前", 3 * weekMs),
                Triple("bs", "prije 2 dana", 2 * dayMs),
                Triple("cs", "před 3 dny", 3 * dayMs),
                Triple("de", "vor 1 Jahr", yearMs),
                Triple("es", "hace 10 minutos", 10 * minuteMs),
                Triple("et", "5 päeva tagasi", 5 * dayMs),
                Triple("fa", "۳ هفته پیش", 3 * weekMs),
                Triple("fr", "il y a 4 jours", 4 * dayMs),
                Triple("hi", "1 महीना पहले", monthMs),
                Triple("in", "2 bulan yang lalu", 2 * monthMs),
                Triple("it", "3 anni fa", 3 * yearMs),
                Triple("ja", "2 か月前", 2 * monthMs),
                Triple("ko", "2일 전", 2 * dayMs),
                Triple("lo", "3 ມື້ກ່ອນນີ້", 3 * dayMs),
                Triple("pa", "2 ਹਫ਼ਤੇ ਪਹਿਲਾਂ", 2 * weekMs),
                Triple("pl", "2 tygodnie temu", 2 * weekMs),
                Triple("pt", "há 1 mês", monthMs),
                Triple("pt-BR", "há 6 horas", 6 * hourMs),
                Triple("ru", "1 год назад", yearMs),
                Triple("si", "දින 3කට පෙර", 3 * dayMs),
                Triple("sr", "пре 2 дана", 2 * dayMs),
                Triple("tr", "1 yıl önce", yearMs),
                Triple("uk", "3 тижні тому", 3 * weekMs),
                Triple("vi", "2 tuần trước", 2 * weekMs),
                Triple("zh-CN", "3天前", 3 * dayMs),
                Triple("zh-TW", "2 個月前", 2 * monthMs),
                Triple("en", "7 hours ago", 7 * hourMs),
            )
        for ((hl, text, age) in cases) {
            assertWithMessage("$hl: $text").that(parse(text, hl)).isEqualTo(now - age)
        }
    }

    @Test
    fun `duals without a numeral read as two`() {
        assertThat(parse("منذ يومين", hl = "ar")).isEqualTo(now - 2 * dayMs)
        assertThat(parse("منذ ساعة", hl = "ar")).isEqualTo(now - hourMs)
        assertThat(parse("לפני שבועיים", hl = "he")).isEqualTo(now - 2 * weekMs)
    }

    @Test
    fun `region variants fall back to their language`() {
        assertThat(parse("hace 2 días", hl = "es-419")).isEqualTo(now - 2 * dayMs)
        assertThat(parse("il y a 1 an", hl = "fr_CA")).isEqualTo(now - yearMs)
        assertThat(parse("3 週前", hl = "zh-Hant-TW")).isEqualTo(now - 3 * weekMs)
        assertThat(parse("3 週前", hl = "zh-HK")).isEqualTo(now - 3 * weekMs)
    }

    @Test
    fun `a localized streamed prefix does not hide the age`() {
        assertThat(parse("Emitido hace 2 días", hl = "es")).isEqualTo(now - 2 * dayMs)
        assertThat(parse("Diffusé il y a 3 semaines", hl = "fr")).isEqualTo(now - 3 * weekMs)
    }

    @Test
    fun `view counts and scheduled starts are not ages`() {
        assertThat(parse("1,2 M de visualizaciones", hl = "es")).isNull()
        assertThat(parse("Se estrena el 12/10/26, 18:00", hl = "es")).isNull()
        assertThat(parse("Première le 12 oct. à 18 h", hl = "fr")).isNull()
        assertThat(parse("1.2M views", hl = "en")).isNull()
    }

    @Test
    fun `an age keeps the unit it was given in`() {
        assertThat(RelativeUploadDateParser.read("1 year ago", "en", now)?.unit).isEqualTo(RelativeDateUnit.YEAR)
        assertThat(RelativeUploadDateParser.read("hace 3 semanas", "es", now)?.unit).isEqualTo(RelativeDateUnit.WEEK)
        assertThat(RelativeUploadDateParser.read("5h", "en", now)?.unit).isEqualTo(RelativeDateUnit.HOUR)
        assertThat(RelativeUploadDateParser.read("Streamed 2 days ago", "en", now)?.unit).isEqualTo(RelativeDateUnit.DAY)
    }

    @Test
    fun `only an age under a day places the calendar date`() {
        assertThat(RelativeUploadDateParser.read("just now", "en", now)?.placesCalendarDay).isTrue()
        assertThat(RelativeUploadDateParser.read("45 minutes ago", "en", now)?.placesCalendarDay).isTrue()
        assertThat(RelativeUploadDateParser.read("hace 5 horas", "es", now)?.placesCalendarDay).isTrue()
        assertThat(RelativeUploadDateParser.read("yesterday", "en", now)?.placesCalendarDay).isFalse()
        assertThat(RelativeUploadDateParser.read("3 days ago", "en", now)?.placesCalendarDay).isFalse()
        assertThat(RelativeUploadDateParser.read("1 year ago", "en", now)?.placesCalendarDay).isFalse()
    }

    @Test
    fun `reads the abbreviated ages of the related list`() {
        val cases =
            listOf(
                Triple("vor 7 Std.", "de", 7 * hourMs),
                Triple("hace 7 h", "es", 7 * hourMs),
                Triple("hace 6 d", "es", 6 * dayMs),
                Triple("Emitido hace 7 d", "es", 7 * dayMs),
                Triple("hace 2 sem.", "es", 2 * weekMs),
                Triple("hace 3 m", "es", 3 * monthMs),
                Triple("Emitido hace 1 m", "es", monthMs),
                Triple("hace 15 a", "es", 15 * yearMs),
                Triple("il y a 7 h", "fr", 7 * hourMs),
                Triple("Diffusé il y a 7 j", "fr", 7 * dayMs),
                Triple("il y a 3 sem.", "fr", 3 * weekMs),
                Triple("il y a 2 m.", "fr", 2 * monthMs),
                Triple("il y a 10 a", "fr", 10 * yearMs),
                Triple("13 h fa", "it", 13 * hourMs),
                Triple("1 g fa", "it", dayMs),
                Triple("4 gg fa", "it", 4 * dayMs),
                Triple("2 sett. fa", "it", 2 * weekMs),
                Triple("há 2 sem.", "pt", 2 * weekMs),
                Triple("21 ч назад", "ru", 21 * hourMs),
                Triple("Трансляция закончилась 10 ч назад", "ru", 10 * hourMs),
                Triple("5 дн. назад", "ru", 5 * dayMs),
                Triple("4 нед. назад", "ru", 4 * weekMs),
                Triple("2 мес. назад", "ru", 2 * monthMs),
                Triple("2 г. назад", "ru", 2 * yearMs),
                Triple("15 л. назад", "ru", 15 * yearMs),
            )
        cases.forEach { (text, hl, age) ->
            assertWithMessage("$hl: $text").that(parse(text, hl)).isEqualTo(now - age)
        }
    }

    @Test
    fun `an abbreviated unit counts only right after the one number in the text`() {
        assertThat(parse("1,3 M", hl = "es")).isNull()
        assertThat(parse("il y a", hl = "fr")).isNull()
        assertThat(parse("Première le 12 oct. à 18 h", hl = "fr")).isNull()
        assertThat(parse("hace 5 min", hl = "es")).isNotEqualTo(now - 5 * monthMs)
    }

    @Test
    fun `read and parse agree on the timestamp`() {
        listOf("today", "yesterday", "just now", "3 days ago", "2 hrs ago", "1 month ago").forEach { text ->
            assertWithMessage(text).that(RelativeUploadDateParser.read(text, "en", now)?.timestamp).isEqualTo(parse(text))
        }
    }
}
