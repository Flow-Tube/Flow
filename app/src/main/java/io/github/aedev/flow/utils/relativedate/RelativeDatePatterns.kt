/*
 * Copyright (C) 2025-2026 Flow | A-EDev
 *
 * This file is part of Flow (https://github.com/Flow-Tube/Flow).
 *
 * The word lists below are ported from NewPipe Extractor's timeago patterns
 * (https://github.com/TeamNewPipe/NewPipeExtractor, package
 * org.schabi.newpipe.extractor.timeago.patterns, v0.26.5), Copyright (C) the NewPipe
 * contributors, licensed under the GNU General Public License v3, the same licence as Flow.
 * Flow's changes: lowercased, zero-width characters and embedded numerals dropped, regional
 * variants identical to their base language folded into it, Arabic duals added as fixed
 * amounts, English limited to whole words, and the abbreviated units YouTube writes in the
 * related list added.
 */

package io.github.aedev.flow.utils.relativedate

internal enum class RelativeDateUnit(
    val millis: Long,
) {
    SECOND(1_000L),
    MINUTE(60_000L),
    HOUR(3_600_000L),
    DAY(86_400_000L),
    WEEK(7L * 86_400_000L),
    MONTH(30L * 86_400_000L),
    YEAR(365L * 86_400_000L),
}

/**
 * How one YouTube host language words an age. [spaced] languages separate words, so a unit only
 * counts as a whole word; the others ("3日前") are matched as a plain substring. [abbreviated] units
 * ("hace 3 m", "5 дн. назад") only count right after a whole number, since a lone letter is also a word.
 */
internal class RelativeDateVocabulary(
    val spaced: Boolean,
    val units: List<Pair<RelativeDateUnit, List<String>>>,
    val fixed: Map<String, Pair<RelativeDateUnit, Int>>,
    val abbreviated: List<Pair<RelativeDateUnit, List<String>>> = emptyList(),
)

/** Keyed by lowercased hl tag ("es", "zh-tw"); [RelativeUploadDateParser] resolves aliases. */
internal object RelativeDatePatterns {
    val byTag: Map<String, RelativeDateVocabulary> =
        mapOf(
            "af" to spaced("sekonde|sekondes", "minute|minuut", "ure|uur", "dae|dag", "week|weke", "maand|maande", "jaar"),
            "am" to spaced("ሰኮንዶች|ሴኮንድ", "ደቂቃ|ደቂቃዎች", "ሰዓት|ሰዓቶች", "ቀን|ቀኖች", "ሳምንታት|ሳምንት", "ወራት|ወር", "ዓመታት|ዓመት"),
            "ar" to
                spaced(
                    "ثانية|ثوانٍ",
                    "دقائق|دقيقة",
                    "ساعات|ساعة",
                    "أيام|يوم",
                    "أسابيع|أسبوع",
                    "أشهر|شهر|شهرًا",
                    "سنة|سنوات",
                    fixed =
                        mapOf(
                            "ثانيتين" to (RelativeDateUnit.SECOND to 2),
                            "دقيقتين" to (RelativeDateUnit.MINUTE to 2),
                            "ساعتين" to (RelativeDateUnit.HOUR to 2),
                            "يومين" to (RelativeDateUnit.DAY to 2),
                            "أسبوعين" to (RelativeDateUnit.WEEK to 2),
                            "شهرين" to (RelativeDateUnit.MONTH to 2),
                            "سنتين" to (RelativeDateUnit.YEAR to 2),
                        ),
                ),
            "az" to spaced("saniyə", "dəqiqə", "saat", "gün", "həftə", "ay", "il"),
            "be" to
                spaced(
                    "секунд|секунду|секунды",
                    "хвілін|хвіліну|хвіліны",
                    "гадзін|гадзіну|гадзіны",
                    "дзень|дзён|дня|дні",
                    "тыдзень|тыдня|тыдні",
                    "месяц|месяца|месяцы|месяцаў",
                    "год|года|гады|гадоў",
                ),
            "bg" to spaced("секунда|секунди", "минута|минути", "час|часа", "ден|дни", "седмица|седмици", "месец|месеца", "година|години"),
            "bn" to spaced("সেকেন্ড", "মিনিট", "ঘণ্টা", "দিন", "সপ্তাহ", "মাস", "বছর"),
            "bs" to
                spaced(
                    "sekundi|sekunde|sekundu",
                    "minuta|minute|minutu",
                    "h|sat|sata|sati",
                    "dan|dana",
                    "sedm.",
                    "mj.|mjesec|mjeseca|mjeseci",
                    "godina|godine|godinu",
                ),
            "ca" to spaced("segon|segons", "minut|minuts", "hora|hores", "dia|dies", "setmana|setmanes", "mes|mesos", "any|anys"),
            "cs" to
                spaced(
                    "sekundami|sekundou",
                    "minutami|minutou",
                    "hodinami|hodinou",
                    "dny|včera",
                    "týdnem|týdny",
                    "měsícem|měsíci",
                    "rokem|roky|lety",
                ),
            "da" to spaced("sekund|sekunder", "minut|minutter", "time|timer", "dag|dage", "uge|uger", "måned|måneder", "år"),
            "de" to
                spaced(
                    "sekunde|sekunden",
                    "minute|minuten",
                    "stunde|stunden",
                    "tag|tagen",
                    "woche|wochen",
                    "monat|monaten",
                    "jahr|jahren",
                    abbreviated = mapOf(RelativeDateUnit.HOUR to "std."),
                ),
            "el" to
                spaced(
                    "δευτερόλεπτα|δευτερόλεπτο",
                    "λεπτά|λεπτό",
                    "ώρα|ώρες",
                    "ημέρα|ημέρες",
                    "εβδομάδα|εβδομάδες",
                    "μήνα|μήνες",
                    "χρόνια|χρόνο",
                ),
            "en" to
                spaced(
                    "second|seconds|sec|secs",
                    "minute|minutes|min|mins",
                    "hour|hours|hr|hrs",
                    "day|days",
                    "week|weeks|wk|wks",
                    "month|months",
                    "year|years|yr|yrs",
                ),
            "es" to
                spaced(
                    "segundo|segundos",
                    "minuto|minutos",
                    "hora|horas",
                    "día|días",
                    "semana|semanas",
                    "mes|meses",
                    "año|años",
                    abbreviated =
                        mapOf(
                            RelativeDateUnit.HOUR to "h",
                            RelativeDateUnit.DAY to "d",
                            RelativeDateUnit.WEEK to "sem.",
                            RelativeDateUnit.MONTH to "m",
                            RelativeDateUnit.YEAR to "a",
                        ),
                ),
            "et" to spaced("sekund|sekundit", "minut|minutit", "tund|tundi", "päev|päeva", "nädal|nädalat", "kuu|kuud", "aasta|aastat"),
            "eu" to spaced("segundo", "minutu", "ordu|ordubete", "egun", "aste|astebete", "hilabete", "urte|urtebete"),
            "fa" to spaced("ثانیه", "دقیقه", "ساعت", "روز", "هفته", "ماه", "سال"),
            "fi" to
                spaced(
                    "sekunti|sekuntia",
                    "minuutti|minuuttia",
                    "tunti|tuntia",
                    "päivä|päivää",
                    "viikko|viikkoa",
                    "kuukausi|kuukautta",
                    "vuosi|vuotta",
                ),
            "fil" to spaced("segundo", "minuto", "oras", "araw", "linggo", "buwan", "taon"),
            "fr" to
                spaced(
                    "seconde|secondes",
                    "minute|minutes",
                    "heure|heures",
                    "jour|jours",
                    "semaine|semaines",
                    "mois",
                    "an|ans",
                    abbreviated =
                        mapOf(
                            RelativeDateUnit.HOUR to "h",
                            RelativeDateUnit.DAY to "j",
                            RelativeDateUnit.WEEK to "sem.",
                            RelativeDateUnit.MONTH to "m.",
                            RelativeDateUnit.YEAR to "a",
                        ),
                ),
            "gl" to spaced("segundo|segundos", "minuto|minutos", "hora|horas", "día|días", "semana|semanas", "mes|meses", "ano|anos"),
            "gu" to spaced("સેકંડ", "મિનિટ", "કલાક", "દિવસ", "અઠવાડિયા", "મહિના", "વર્ષ"),
            "hi" to spaced("सेकंड", "मिनट", "घंटा|घंटे", "दिन", "सप्ताह|हफ़्ते", "महीना|महीने", "वर्ष"),
            "hr" to
                spaced(
                    "sekunde|sekundi|sekundu",
                    "minuta|minute|minutu",
                    "sat|sata|sati",
                    "dan|dana",
                    "tjedan|tjedna",
                    "mjesec|mjeseca|mjeseci",
                    "godina|godine|godinu",
                ),
            "hu" to spaced("másodperce", "perce", "órája", "napja", "hete", "hónapja", "éve"),
            "hy" to spaced("վայրկյան", "րոպե", "ժամ", "օր", "շաբաթ", "ամիս", "տարի"),
            "id" to spaced("detik", "menit", "jam", "hari", "minggu", "bulan", "tahun"),
            "is" to
                spaced(
                    "sekúndu|sekúndum|second|seconds",
                    "mínútu|mínútum|minute|minutes",
                    "klukkustund|klukkustundum|hour|hours",
                    "degi|dögum|day|days",
                    "viku|vikum|week|weeks",
                    "mánuði|mánuðum",
                    "ári|árum",
                ),
            "it" to
                spaced(
                    "secondi|secondo",
                    "minuti|minuto",
                    "ora|ore",
                    "giorni|giorno",
                    "settimana|settimane",
                    "mese|mesi",
                    "anni|anno",
                    abbreviated =
                        mapOf(
                            RelativeDateUnit.HOUR to "h",
                            RelativeDateUnit.DAY to "g|gg",
                            RelativeDateUnit.WEEK to "sett.",
                        ),
                ),
            "iw" to
                spaced(
                    "שניות|שנייה",
                    "דקה|דקות",
                    "שעה|שעות",
                    "יום|ימים",
                    "שבוע|שבועות",
                    "חודש|חודשים",
                    "שנה|שנים",
                    fixed =
                        mapOf(
                            "שעתיים" to (RelativeDateUnit.HOUR to 2),
                            "יומיים" to (RelativeDateUnit.DAY to 2),
                            "שבועיים" to (RelativeDateUnit.WEEK to 2),
                            "חודשיים" to (RelativeDateUnit.MONTH to 2),
                            "שנתיים" to (RelativeDateUnit.YEAR to 2),
                        ),
                ),
            "ja" to unspaced("秒前", "分前", "時間前", "日前", "週間前", "か月前", "年前"),
            "ka" to spaced("წამის", "წუთის", "საათის", "დღის", "კვირის", "თვის", "წლის"),
            "kk" to spaced("секунд", "минут", "сағат", "күн", "апта", "ай", "жыл"),
            "km" to unspaced("វិនាទីមុន", "នាទីមុន", "ម៉ោងមុន", "ថ្ងៃមុន", "សប្តាហ៍មុន", "ខែមុន", "ឆ្នាំមុន"),
            "kn" to
                spaced(
                    "ಸೆಕೆಂಡುಗಳ|ಸೆಕೆಂಡ್",
                    "ನಿಮಿಷಗಳ|ನಿಮಿಷದ",
                    "ಗಂಟೆಗಳ|ಗಂಟೆಯ",
                    "ದಿನಗಳ|ದಿನದ",
                    "ವಾರಗಳ|ವಾರದ",
                    "ತಿಂಗಳ|ತಿಂಗಳುಗಳ",
                    "ವರ್ಷಗಳ|ವರ್ಷದ",
                ),
            "ko" to unspaced("초", "분", "시간", "일", "주", "개월", "년"),
            "ky" to spaced("секунд", "мүнөт", "саат", "күн", "апта", "ай", "жыл"),
            "lo" to unspaced("ວິນາທີກ່ອນນີ້", "ນາທີກ່ອນນີ້", "ຊົ່ວໂມງກ່ອນນີ້", "ມື້ກ່ອນນີ້", "ອາທິດກ່ອນນີ້", "ເດືອນກ່ອນນີ້", "ປີກ່ອນນີ້"),
            "lt" to
                spaced(
                    "sekundes|sekundę|sekundžių",
                    "minutes|minutę|minučių",
                    "valandas|valandą|valandų",
                    "dienas|dieną",
                    "savaites|savaitę",
                    "mėnesius|mėnesių|mėnesį",
                    "metus|metų",
                ),
            "lv" to
                spaced(
                    "sekundes|sekundēm",
                    "minūtes|minūtēm",
                    "stundas|stundām",
                    "dienas|dienām",
                    "nedēļas|nedēļām",
                    "mēneša|mēnešiem",
                    "gada|gadiem",
                ),
            "mk" to spaced("секунда|секунди", "минута|минути", "час|часа", "ден|дена", "недела|недели", "месец|месеци", "година|години"),
            "ml" to unspaced("സെക്കന്റ്|സെക്കൻഡ്", "മിനിറ്റ്", "മണിക്കൂർ", "ദിവസം", "ആഴ്ച", "മാസം", "വർഷം"),
            "mn" to spaced("секундын", "минутын", "цагийн", "өдрийн", "долоо|хоногийн", "сарын", "жилийн"),
            "mr" to
                unspaced(
                    "सेकंदांपूर्वी|सेकंदापूर्वी",
                    "मिनिटांपूर्वी|मिनिटापूर्वी",
                    "तासांपूर्वी|तासापूर्वी",
                    "दिवसांपूर्वी|दिवसापूर्वी",
                    "आठवड्यांपूर्वी|आठवड्यापूर्वी",
                    "महिन्यांपूर्वी|महिन्यापूर्वी",
                    "वर्षांपूर्वी|वर्षापूर्वी",
                ),
            "ms" to spaced("saat", "minit", "jam", "hari", "minggu", "bulan", "tahun"),
            "my" to spaced("စက္ကန့်", "မိနစ်", "နာရီ", "ရက်", "ပတ်", "လ", "နှစ်"),
            "ne" to spaced("सेकेन्ड", "मिनेट", "घन्टा", "दिन", "हप्ता", "महिना", "वर्ष"),
            "nl" to spaced("seconde|seconden", "minuten|minuut", "uur", "dag|dagen", "week|weken", "maand|maanden", "jaar"),
            "no" to spaced("sekund|sekunder", "minutt|minutter", "time|timer", "dag|dager", "uke|uker", "md.", "år"),
            "pa" to spaced("ਸਕਿੰਟ", "ਮਿੰਟ", "ਘੰਟਾ|ਘੰਟੇ", "ਦਿਨ", "ਹਫ਼ਤਾ|ਹਫ਼ਤੇ", "ਮਹੀਨਾ|ਮਹੀਨੇ", "ਸਾਲ"),
            "pl" to
                spaced(
                    "sekund|sekundy|sekundę",
                    "minut|minuty|minutę",
                    "godzin|godziny|godzinę",
                    "dni|dzień",
                    "tydzień|tygodnie",
                    "miesiąc|miesiące|miesięcy",
                    "lat|lata|rok",
                ),
            "pt" to
                spaced(
                    "segundo|segundos",
                    "minuto|minutos",
                    "hora|horas",
                    "dia|dias",
                    "semana|semanas",
                    "meses|mês",
                    "ano|anos",
                    abbreviated = mapOf(RelativeDateUnit.WEEK to "sem."),
                ),
            "ro" to spaced("secunde|secundă", "minut|minute", "ore|oră", "zi|zile", "săptămâni|săptămână", "luni|lună", "an|ani"),
            "ru" to
                spaced(
                    "секунд|секунду|секунды|только что",
                    "минут|минуту|минуты",
                    "час|часа|часов",
                    "день|дней|дня",
                    "неделю|недели",
                    "месяц|месяца|месяцев",
                    "год|года|лет",
                    abbreviated =
                        mapOf(
                            RelativeDateUnit.HOUR to "ч",
                            RelativeDateUnit.DAY to "дн.",
                            RelativeDateUnit.WEEK to "нед.",
                            RelativeDateUnit.MONTH to "мес.",
                            RelativeDateUnit.YEAR to "г.|л.",
                        ),
                ),
            "si" to spaced("තත්පර", "මිනිත්තු", "පැය", "දින", "සති", "මාස", "වසර"),
            "sk" to
                spaced(
                    "sekundami|sekundou",
                    "minútami|minútou",
                    "hodinami|hodinou",
                    "dňami|dňom",
                    "týždňami|týždňom",
                    "mesiacmi|mesiacom",
                    "rokmi|rokom",
                ),
            "sl" to
                spaced(
                    "sekundama|sekundami|sekundo",
                    "minutama|minutami|minuto",
                    "urama|urami|uro",
                    "dnem|dnevi|dnevoma",
                    "tedni|tednom|tednoma",
                    "mesecem|mesecema|meseci",
                    "leti|letom|letoma",
                ),
            "sq" to spaced("sekonda|sekondë", "minuta|minutë", "orë", "ditë", "javë", "muaj", "vit|vjet"),
            "sr" to
                spaced(
                    "секунде|секунди",
                    "минута",
                    "сат|сата|сати",
                    "дан|дана",
                    "недеље|недељу",
                    "месец|месеца|месеци",
                    "година|године|годину",
                ),
            "sr-latn" to
                spaced(
                    "sekunde|sekundi",
                    "minuta",
                    "sat|sati|sata",
                    "dan|dana",
                    "nedelja|nedelje|nedelju",
                    "mesec|meseci|meseca",
                    "godine|godina|godinu",
                ),
            "sv" to spaced("sekund|sekunder", "minut|minuter", "timmar|timme", "dag|dagar", "vecka|veckor", "månad|månader", "år"),
            "sw" to spaced("sekunde", "dakika", "saa", "siku", "wiki", "mwezi|miezi", "miaka|mwaka"),
            "ta" to
                spaced(
                    "வினாடி|வினாடிகளுக்கு",
                    "நிமிடங்கள்|நிமிடம்",
                    "மணிநேரத்திற்கு",
                    "நாட்களுக்கு|நாளுக்கு",
                    "வாரங்களுக்கு|வாரம்",
                    "மாதங்கள்|மாதம்",
                    "ஆண்டு|ஆண்டுகளுக்கு",
                ),
            "te" to spaced("సెకను|సెకన్ల", "నిమిషం|నిమిషాల", "గంట|గంటల", "రోజు|రోజుల", "వారం|వారాల", "నెల|నెలల", "సంవత్సరం|సంవత్సరాల"),
            "th" to
                unspaced(
                    "วินาทีที่ผ่านมา",
                    "นาทีที่ผ่านมา",
                    "ชั่วโมงที่ผ่านมา",
                    "วันที่ผ่านมา",
                    "สัปดาห์ที่ผ่านมา",
                    "เดือนที่ผ่านมา",
                    "ปีที่ผ่านมา",
                ),
            "tr" to spaced("saniye", "dakika", "saat", "gün", "hafta", "ay", "yıl"),
            "uk" to
                spaced(
                    "секунд|секунди|секунду",
                    "хвилин|хвилини|хвилину",
                    "годин|години|годину",
                    "день|дні|днів",
                    "тиждень|тижні",
                    "місяць|місяці|місяців",
                    "роки|років|рік",
                ),
            "ur" to spaced("سیکنڈ|سیکنڈز", "منٹ|منٹس", "گھنٹہ|گھنٹے", "دن", "ہفتہ|ہفتے", "ماہ", "سال"),
            "uz" to spaced("soniya", "daqiqa", "soat", "kun", "hafta", "oy", "yil"),
            "vi" to spaced("giây", "phút", "giờ|tiếng", "ngày", "tuần", "tháng", "năm"),
            "zh-cn" to unspaced("秒前", "分钟前", "小时前", "天前", "周前", "个月前", "年前"),
            "zh-hk" to unspaced("秒前", "分鐘前", "小時前", "天前", "週前", "個月前", "年前"),
            "zh-tw" to unspaced("秒前", "分鐘前", "小時前", "天前", "週前", "個月前", "年前"),
            "zu" to
                spaced(
                    "amasekhondi|isekhondi",
                    "amaminithi|iminithi",
                    "amahora|ihora",
                    "izinsuku|usuku",
                    "amaviki|iviki",
                    "inyanga|izinyanga",
                    "iminyaka|unyaka",
                ),
        )

    private fun spaced(
        seconds: String,
        minutes: String,
        hours: String,
        days: String,
        weeks: String,
        months: String,
        years: String,
        fixed: Map<String, Pair<RelativeDateUnit, Int>> = emptyMap(),
        abbreviated: Map<RelativeDateUnit, String> = emptyMap(),
    ) = vocabulary(true, listOf(seconds, minutes, hours, days, weeks, months, years), fixed, abbreviated)

    private fun unspaced(
        seconds: String,
        minutes: String,
        hours: String,
        days: String,
        weeks: String,
        months: String,
        years: String,
    ) = vocabulary(false, listOf(seconds, minutes, hours, days, weeks, months, years), emptyMap())

    private fun vocabulary(
        spaced: Boolean,
        words: List<String>,
        fixed: Map<String, Pair<RelativeDateUnit, Int>>,
        abbreviated: Map<RelativeDateUnit, String> = emptyMap(),
    ) = RelativeDateVocabulary(
        spaced = spaced,
        units = RelativeDateUnit.entries.zip(words) { unit, list -> unit to list.split('|') },
        fixed = fixed,
        abbreviated = abbreviated.map { (unit, list) -> unit to list.split('|') },
    )
}
