package com.pdfkhan.app

import android.content.Context
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import org.apache.commons.compress.utils.CountingInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

/** فهرست صداهای فارسی داخلی که می‌توان دانلود کرد. */
object VoiceCatalog {

    class Entry(val dir: String, val displayName: String, val sizeMb: Int) {
        val url: String
            get() = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/$dir.tar.bz2"
    }

    val entries = listOf(
        Entry("vits-piper-fa_IR-amir-medium", "امیر", 67),
        Entry("vits-piper-fa_IR-gyro-medium", "Gyro", 67),
        Entry("vits-piper-fa_IR-reza_ibrahim-medium", "Reza Ibrahim", 67),
        Entry("vits-piper-fa_IR-ganji-medium", "Ganji", 67),
        Entry("vits-piper-fa_IR-ganji_adabi-medium", "Ganji Adabi", 67)
    )
}

/**
 * دانلود صدا با قابلیت ادامه (HTTP Range): اگر اینترنت قطع شود، خودکار چند بار دوباره تلاش می‌کند
 * و حتی پس از بستن برنامه، دانلود از همان بایت قبلی ادامه پیدا می‌کند.
 * همه‌ی تابع‌ها بلوکه‌کننده‌اند و باید روی نخ پس‌زمینه صدا زده شوند.
 */
class VoiceDownloader(context: Context) {

    enum class Phase { DOWNLOAD, EXTRACT }

    private val root = File(context.applicationContext.filesDir, "tts")

    fun isInstalled(dir: String): Boolean = File(root, "$dir/$READY_MARK").exists()

    fun baseDir(dir: String): File = File(root, dir)

    /**
     * @return true اگر نصب شد، false اگر کاربر لغو کرد (فایل نیمه‌کاره برای ادامه می‌ماند).
     * @throws IOException اگر پس از چند تلاش هم نشد.
     */
    fun install(
        entry: VoiceCatalog.Entry,
        cancelled: AtomicBoolean,
        onProgress: (phase: Phase, done: Long, total: Long) -> Unit
    ): Boolean {
        root.mkdirs()
        val part = File(root, "${entry.dir}.tar.bz2.part")

        // ۱) دانلود با تلاش مجدد
        var attempt = 0
        while (true) {
            if (cancelled.get()) return false
            try {
                if (!downloadOnce(entry.url, part, cancelled, onProgress)) return false
                break
            } catch (e: IOException) {
                if (cancelled.get()) return false
                attempt++
                if (attempt >= MAX_ATTEMPTS) throw e
                try {
                    Thread.sleep(minOf(attempt, 5) * 2000L)
                } catch (ie: InterruptedException) {
                    return false
                }
            }
        }

        // ۲) استخراج
        try {
            return extract(part, entry, cancelled, onProgress)
        } catch (e: IOException) {
            part.delete() // آرشیو خراب است؛ دفعه‌ی بعد از نو دانلود شود
            throw e
        }
    }

    // ───────────────────────── دانلود ─────────────────────────

    /** یک تلاش دانلود؛ در صورت وجود فایل نیمه‌کاره، از ادامه‌ی آن می‌گیرد. */
    private fun downloadOnce(
        url: String,
        part: File,
        cancelled: AtomicBoolean,
        onProgress: (Phase, Long, Long) -> Unit
    ): Boolean {
        val existing = if (part.exists()) part.length() else 0L

        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 20_000
            conn.readTimeout = 30_000
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", "PdfKhan")
            if (existing > 0) conn.setRequestProperty("Range", "bytes=$existing-")

            val code = conn.responseCode
            val append: Boolean
            val total: Long

            when (code) {
                HttpURLConnection.HTTP_PARTIAL -> {
                    append = true
                    // Content-Range: bytes 100-999/1000
                    total = conn.getHeaderField("Content-Range")
                        ?.substringAfter('/', "")?.toLongOrNull() ?: -1L
                }
                HttpURLConnection.HTTP_OK -> {
                    append = false // سرور Range را نپذیرفت؛ از اول
                    total = conn.contentLengthLong
                }
                416 -> {
                    // «بازه نامعتبر»: یعنی فایل قبلاً کامل دانلود شده است
                    return existing > 0
                }
                else -> throw IOException("HTTP $code")
            }

            val startAt = if (append) existing else 0L
            var done = startAt
            val buffer = ByteArray(64 * 1024)
            var lastReport = 0L

            conn.inputStream.use { input ->
                FileOutputStream(part, append).use { output ->
                    while (true) {
                        if (cancelled.get()) return false
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        done += n
                        val now = System.currentTimeMillis()
                        if (now - lastReport > 250) {
                            lastReport = now
                            onProgress(Phase.DOWNLOAD, done, total)
                        }
                    }
                }
            }

            if (total > 0 && part.length() != total) {
                throw IOException("incomplete: ${part.length()} of $total")
            }
            onProgress(Phase.DOWNLOAD, done, total)
            return true
        } finally {
            conn.disconnect()
        }
    }

    // ───────────────────────── استخراج ─────────────────────────

    private fun extract(
        archive: File,
        entry: VoiceCatalog.Entry,
        cancelled: AtomicBoolean,
        onProgress: (Phase, Long, Long) -> Unit
    ): Boolean {
        val tmp = File(root, "${entry.dir}.tmp")
        tmp.deleteRecursively()
        tmp.mkdirs()
        val tmpPath = tmp.canonicalPath + File.separator

        val total = archive.length()
        val counting = CountingInputStream(BufferedInputStream(FileInputStream(archive), 64 * 1024))
        var lastReport = 0L

        BZip2CompressorInputStream(counting).use { bz ->
            TarArchiveInputStream(bz).use { tar ->
                while (true) {
                    if (cancelled.get()) {
                        tmp.deleteRecursively()
                        return false
                    }
                    val e = tar.nextTarEntry ?: break

                    // نام اولین پوشه (نام مدل) را حذف می‌کنیم
                    val rel = e.name.removePrefix("./").substringAfter('/', "")
                    val wanted = rel.endsWith(".onnx") || rel == "tokens.txt" || rel.startsWith("espeak-ng-data/")
                    if (rel.isEmpty() || !wanted) continue

                    val out = File(tmp, rel)
                    if (!out.canonicalPath.startsWith(tmpPath)) continue // امنیت: خروج از مسیر

                    if (e.isDirectory) {
                        out.mkdirs()
                    } else if (e.isFile) {
                        out.parentFile?.mkdirs()
                        FileOutputStream(out).use { o -> tar.copyTo(o) }
                    }

                    val now = System.currentTimeMillis()
                    if (now - lastReport > 250) {
                        lastReport = now
                        onProgress(Phase.EXTRACT, counting.bytesRead, total)
                    }
                }
            }
        }

        val hasModel = tmp.listFiles { f -> f.name.endsWith(".onnx") }?.isNotEmpty() == true
        if (!hasModel || !File(tmp, "tokens.txt").exists() || !File(tmp, "espeak-ng-data").isDirectory) {
            tmp.deleteRecursively()
            throw IOException("bad archive")
        }

        File(tmp, READY_MARK).writeText("ok")
        val target = File(root, entry.dir)
        target.deleteRecursively()
        if (!tmp.renameTo(target)) throw IOException("cannot install")

        archive.delete()
        onProgress(Phase.EXTRACT, total, total)
        return true
    }

    private companion object {
        const val READY_MARK = ".ready"
        const val MAX_ATTEMPTS = 10
    }
}
