package com.pdfkhan.app

import java.text.Normalizer

/**
 * بخشی از متن یک صفحه که یک‌جا به موتور گفتار داده می‌شود.
 * [text] متن همان صفحه است (برای هایلایت)، [speak] شکل آماده‌شده برای تلفظ.
 */
data class Chunk(val page: Int, val start: Int, val end: Int, val text: String, val speak: String)

/** تبدیل عدد به حروف فارسی. */
object PersianNumbers {

    private val ones = listOf("صفر", "یک", "دو", "سه", "چهار", "پنج", "شش", "هفت", "هشت", "نه")
    private val teens = listOf(
        "ده", "یازده", "دوازده", "سیزده", "چهارده", "پانزده", "شانزده", "هفده", "هجده", "نوزده"
    )
    private val tens = listOf("", "", "بیست", "سی", "چهل", "پنجاه", "شصت", "هفتاد", "هشتاد", "نود")
    private val hundreds = listOf(
        "", "صد", "دویست", "سیصد", "چهارصد", "پانصد", "ششصد", "هفتصد", "هشتصد", "نهصد"
    )
    private val scales = listOf("", "هزار", "میلیون", "میلیارد", "تریلیون")
    private val fractionUnits = mapOf(1 to "دهم", 2 to "صدم", 3 to "هزارم")

    private fun below1000(n: Int): String {
        val parts = ArrayList<String>()
        val h = n / 100
        val rest = n % 100
        if (h > 0) parts.add(hundreds[h])
        if (rest in 1..9) {
            parts.add(ones[rest])
        } else if (rest in 10..19) {
            parts.add(teens[rest - 10])
        } else if (rest >= 20) {
            val t = tens[rest / 10]
            val o = rest % 10
            parts.add(if (o > 0) "$t و ${ones[o]}" else t)
        }
        return parts.joinToString(" و ")
    }

    /** [digits] فقط ارقام ASCII. */
    fun words(digits: String): String {
        // شماره‌ها و کدها (صفر ابتدایی یا خیلی بلند): رقم‌به‌رقم
        val isCode = (digits.length > 2 && digits.startsWith("0")) || digits.length > 15
        if (isCode) return digits.map { ones[it - '0'] }.joinToString(" ")

        val n = digits.toLong()
        if (n == 0L) return ones[0]

        val groups = ArrayList<Int>()
        var rest = n
        while (rest > 0) {
            groups.add((rest % 1000).toInt())
            rest /= 1000
        }

        val parts = ArrayList<String>()
        for (i in groups.indices.reversed()) {
            val g = groups[i]
            if (g == 0) continue
            if (i == 1 && g == 1) {
                parts.add(scales[1]) // «هزار»، نه «یک هزار»
            } else {
                val w = below1000(g)
                parts.add(if (i > 0) "$w ${scales[i]}" else w)
            }
        }
        return parts.joinToString(" و ")
    }

    /** عدد اعشاری: «3.5» ← «سه ممیز پنج دهم». */
    fun decimal(intPart: String, fraction: String): String {
        val head = words(intPart)
        val unit = fractionUnits[fraction.length]
        val tail = if (unit != null && !fraction.startsWith("0")) {
            "${words(fraction)} $unit"
        } else {
            fraction.map { ones[it - '0'] }.joinToString(" ")
        }
        return "$head ممیز $tail"
    }
}

object TextPrep {

    // نشانه‌های جهت‌دهی، soft hyphen و BOM — برای گفتار بی‌معنی‌اند
    private val invisibles = Regex("[\\u200E\\u200F\\u202A-\\u202E\\u2066-\\u2069\\u00AD\\uFEFF]")

    // فاصله‌های تکراری (نیم‌فاصله U+200C عمداً حفظ می‌شود)
    private val spaces = Regex("[ \\t\\u00A0\\u2000-\\u200A]+")

    // خط جدیدِ وسطِ جمله (قبلش نقطه/علامت پایان نیست و بعدش خط خالی نیست) → فاصله
    private val softBreak = Regex("(?<=[^.!?؟۔:\\n])\\n(?=[^\\n])")

    private val manyBlankLines = Regex("\\n{3,}")

    private val terminators = charArrayOf('.', '!', '?', '؟', '۔', '؛', ';', ':', '\n')

    // ───────────────────────── متن معکوس ─────────────────────────

    private val mirror = mapOf(
        '(' to ')', ')' to '(', '[' to ']', ']' to '[', '{' to '}', '}' to '{',
        '«' to '»', '»' to '«', '<' to '>', '>' to '<'
    )

    // دنباله‌های چپ‌به‌راست (لاتین و عدد) که بعد از وارونه‌کردن خط باید دوباره درست شوند
    private val ltrRun = Regex("[A-Za-z0-9\\u0660-\\u0669\\u06F0-\\u06F9]+")

    private fun reverseLine(s: String): String {
        val sb = StringBuilder(s.length)
        for (i in s.indices.reversed()) {
            val c = s[i]
            sb.append(mirror[c] ?: c)
        }
        return ltrRun.replace(sb) { it.value.reversed() }
    }

    private val commonWords = listOf(
        "در", "به", "از", "که", "را", "این", "با", "است", "برای", "آن", "یک", "هر",
        "شود", "شده", "های", "می", "نیز"
    )
    private val forwardSet = commonWords.toSet()
    private val backwardSet = commonWords.map { it.reversed() }.filter { it !in forwardSet }.toSet()
    private val wordSplit = Regex("[\\s\\p{Punct}،؛؟«»]+")

    /**
     * بعضی PDFها متن فارسی را با ترتیب بصری (حروف هر خط برعکس) استخراج می‌کنند.
     * اگر کلمه‌های پرکاربرد فارسی در حالت وارونه بیشتر دیده شوند، true برمی‌گرداند.
     */
    fun looksReversed(pages: List<String>): Boolean {
        var forward = 0
        var backward = 0
        var seen = 0
        for (p in pages) {
            if (seen > 40_000) break
            val t = Normalizer.normalize(invisibles.replace(p, ""), Normalizer.Form.NFKC)
                .replace('ي', 'ی').replace('ك', 'ک')
            for (w in t.split(wordSplit)) {
                if (w.isEmpty()) continue
                if (w in forwardSet) forward++ else if (w in backwardSet) backward++
            }
            seen += t.length
        }
        return backward >= 8 && backward > forward * 2
    }

    // ───────────────────────── پاک‌سازی صفحه ─────────────────────────

    /**
     * متن خام PDF را برای نمایش و خواندن آماده می‌کند.
     * @param reverseLines اگر PDF متن را برعکس (به ترتیب بصری) داده باشد، هر خط وارونه می‌شود.
     */
    fun clean(raw: String, reverseLines: Boolean): String {
        var t = raw.replace("\r\n", "\n").replace('\r', '\n')

        if (reverseLines) {
            t = t.split('\n').joinToString("\n") { reverseLine(it) }
        }

        // تبدیل شکل‌های نمایشی عربی/فارسی (ﻣ ﻌ ﻻ ...) به حروف پایه
        t = Normalizer.normalize(t, Normalizer.Form.NFKC)

        t = invisibles.replace(t, "")
        t = t.replace('ي', 'ی') // ي عربی → ی فارسی
        t = t.replace('ك', 'ک') // ك عربی → ک فارسی
        t = spaces.replace(t, " ")
        t = t.split('\n').joinToString("\n") { it.trim() }
        t = softBreak.replace(t, " ")
        t = manyBlankLines.replace(t, "\n\n")
        return t.trim()
    }

    // ───────────────────────── آماده‌سازی برای تلفظ ─────────────────────────

    private val tashkeel = Regex("[\\u064B-\\u0652\\u0670\\u0640]")
    private val urls = Regex("(https?://|www\\.)\\S+")
    private val quotes = Regex("[«»\"“”‘’`]")
    private val brackets = Regex("[()\\[\\]{}]")
    private val bullets = Regex("[*#•▪■●◦▫►]")
    private val spacedDash = Regex("\\s[-–—]+\\s|[—–]")
    private val ellipsis = Regex("…|\\.{3,}")
    private val thousands = Regex("(?<=[0-9])[,٬](?=[0-9]{3}(?![0-9]))")
    private val numberRun = Regex("([0-9]+)(?:[.٫]([0-9]+))?")
    private val commaRuns = Regex("(\\s*,){2,}")
    private val spaceBeforePunct = Regex("\\s+([,.!?؟؛:])")

    private fun asciiDigits(s: String): String {
        val sb = StringBuilder(s.length)
        for (c in s) {
            sb.append(
                when (c) {
                    in '۰'..'۹' -> '0' + (c - '۰')
                    in '٠'..'٩' -> '0' + (c - '٠')
                    else -> c
                }
            )
        }
        return sb.toString()
    }

    /** قواعد «کلمه = تلفظ جایگزین» که کاربر نوشته است. */
    fun compileRules(text: String): List<Pair<Regex, String>> {
        val rules = ArrayList<Pair<Regex, String>>()
        for (line in text.lines()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue
            val idx = trimmed.indexOf('=').takeIf { it > 0 } ?: trimmed.indexOf('→').takeIf { it > 0 } ?: continue
            val from = trimmed.substring(0, idx).trim()
            val to = trimmed.substring(idx + 1).trim()
            if (from.isEmpty()) continue
            val regex = Regex(
                "(?<![\\p{L}\\p{N}\\u200C])" + Regex.escape(from) + "(?![\\p{L}\\p{N}\\u200C])"
            )
            rules.add(regex to to)
        }
        return rules
    }

    /** متنِ آماده‌ی تلفظ: عددها به حروف، علامت‌های اضافه حذف، و قواعد اصلاح تلفظ اعمال می‌شوند. */
    fun forSpeech(piece: String, rules: List<Pair<Regex, String>>): String {
        var t = tashkeel.replace(piece, "")
        t = urls.replace(t, " ")

        for ((regex, replacement) in rules) {
            t = regex.replace(t, Regex.escapeReplacement(replacement))
        }

        t = asciiDigits(t)
        t = t.replace("%", " درصد ").replace("٪", " درصد ")
        t = thousands.replace(t, "")
        t = numberRun.replace(t) { m ->
            val frac = m.groupValues[2]
            " " + (if (frac.isEmpty()) PersianNumbers.words(m.groupValues[1])
            else PersianNumbers.decimal(m.groupValues[1], frac)) + " "
        }

        t = quotes.replace(t, "")
        t = bullets.replace(t, " ")
        t = ellipsis.replace(t, ".")
        t = spacedDash.replace(t, ", ")
        t = brackets.replace(t, ", ")
        t = t.replace('/', ' ').replace('\\', ' ').replace('|', ' ')

        t = commaRuns.replace(t, ",")
        t = spaces.replace(t, " ")
        t = spaceBeforePunct.replace(t, "$1")
        return t.trim()
    }

    // ───────────────────────── تقسیم به بخش‌ها ─────────────────────────

    /** متن یک صفحه را به بخش‌هایی حداکثر [maxLen] نویسه‌ای (ترجیحاً در انتهای جمله) می‌شکند. */
    fun split(
        page: Int,
        text: String,
        maxLen: Int,
        rules: List<Pair<Regex, String>>
    ): List<Chunk> {
        val out = ArrayList<Chunk>()
        val n = text.length
        var i = 0

        while (i < n) {
            while (i < n && text[i].isWhitespace()) i++
            if (i >= n) break

            var end = minOf(n, i + maxLen)
            if (end < n) {
                var cut = -1
                // آخرین پایان جمله در پنجره
                var k = end - 1
                while (k >= i) {
                    if (text[k] in terminators) {
                        cut = k + 1
                        break
                    }
                    k--
                }
                // در نبود پایان جمله: آخرین فاصله
                if (cut < 0) {
                    k = end - 1
                    while (k > i) {
                        if (text[k].isWhitespace()) {
                            cut = k
                            break
                        }
                        k--
                    }
                }
                if (cut > i) end = cut
            }

            val piece = text.substring(i, end).trimEnd()
            if (piece.isNotEmpty() && piece.any { it.isLetterOrDigit() }) {
                val speak = forSpeech(piece, rules)
                if (speak.any { it.isLetterOrDigit() }) {
                    out.add(Chunk(page, i, i + piece.length, piece, speak))
                }
            }
            i = end
        }
        return out
    }
}
