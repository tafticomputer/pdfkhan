package com.pdfkhan.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.File
import java.io.FileOutputStream

/** نتیجه‌ی استخراج: متن هر صفحه + تعداد صفحه‌هایی که با OCR خوانده شد. */
class ExtractResult(val pages: List<String>, val ocrPages: Int, val ocrUnavailable: Boolean)

/**
 * متن هر صفحه را از لایه‌ی متنی PDF می‌گیرد؛ برای صفحه‌هایی که متن ندارند
 * (اسکن‌شده) یا وقتی [forceOcr] روشن است، صفحه را تصویر می‌کند و OCR فارسی می‌زند.
 */
object PdfExtractor {

    /** صفحه‌ای با کمتر از این تعداد حرف، «بدون متن» حساب می‌شود. */
    private const val MIN_LETTERS = 15

    /** طول ضلع بلند تصویر برای OCR (پیکسل). */
    private const val OCR_LONG_SIDE = 2800

    fun extract(
        context: Context,
        uri: Uri,
        forceOcr: Boolean,
        onProgress: (done: Int, total: Int, ocr: Boolean) -> Unit
    ): ExtractResult {
        // ۱) لایه‌ی متنی
        val layer: MutableList<String>? = try {
            readTextLayer(context, uri, onProgress)
        } catch (e: Throwable) {
            null // شاید فایل فقط با رندرکننده‌ی اندروید باز شود
        }

        // ۲) کدام صفحه‌ها OCR می‌خواهند؟
        val renderer = openRenderer(context, uri)
        try {
            val total = renderer?.pdf?.pageCount ?: layer?.size ?: throw IllegalStateException("unreadable")
            val texts: MutableList<String> = layer ?: MutableList(total) { "" }

            val need = (0 until total).filter { i ->
                forceOcr || texts[i].count { it.isLetter() } < MIN_LETTERS
            }
            if (need.isEmpty() || renderer == null) {
                return ExtractResult(texts, 0, need.isNotEmpty())
            }

            // ۳) OCR
            val ocr = OcrEngine(context)
            if (!ocr.init()) {
                return ExtractResult(texts, 0, true)
            }
            try {
                var done = 0
                for (i in need) {
                    onProgress(done, need.size, true)
                    val bitmap = renderPage(renderer.pdf, i)
                    if (bitmap != null) {
                        try {
                            val text = ocr.recognize(bitmap)
                            if (text.isNotBlank()) texts[i] = text
                        } finally {
                            bitmap.recycle()
                        }
                    }
                    done++
                }
                onProgress(need.size, need.size, true)
            } finally {
                ocr.close()
            }
            return ExtractResult(texts, need.size, false)
        } finally {
            renderer?.close()
        }
    }

    // ───────────────────────── لایه‌ی متنی ─────────────────────────

    private fun readTextLayer(
        context: Context,
        uri: Uri,
        onProgress: (Int, Int, Boolean) -> Unit
    ): MutableList<String> {
        PDFBoxResourceLoader.init(context.applicationContext)

        val stream = context.contentResolver.openInputStream(uri)
            ?: throw IllegalStateException("Cannot open file")

        return stream.use { input ->
            PDDocument.load(input).use { doc ->
                val total = doc.numberOfPages
                val stripper = PDFTextStripper()
                val result = ArrayList<String>(total)

                for (page in 1..total) {
                    stripper.startPage = page
                    stripper.endPage = page
                    result.add(stripper.getText(doc))
                    if (page % 5 == 0 || page == total) onProgress(page, total, false)
                }
                result
            }
        }
    }

    // ───────────────────────── رندر صفحه ─────────────────────────

    private class Rendering(
        val pdf: PdfRenderer,
        private val fd: ParcelFileDescriptor,
        private val tempFile: File?
    ) {
        fun close() {
            try { pdf.close() } catch (e: Exception) { }
            try { fd.close() } catch (e: Exception) { }
            tempFile?.delete()
        }
    }

    private fun openRenderer(context: Context, uri: Uri): Rendering? {
        // اول مستقیم؛ اگر توصیف‌گر قابل جستجو نبود، فایل را به کش کپی می‌کنیم
        try {
            val fd = context.contentResolver.openFileDescriptor(uri, "r")
            if (fd != null) {
                try {
                    return Rendering(PdfRenderer(fd), fd, null)
                } catch (e: Exception) {
                    fd.close()
                }
            }
        } catch (e: Exception) {
            // ادامه با کپی
        }

        return try {
            val temp = File.createTempFile("ocr", ".pdf", context.cacheDir)
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(temp).use { output -> input.copyTo(output) }
            } ?: return null
            val fd = ParcelFileDescriptor.open(temp, ParcelFileDescriptor.MODE_READ_ONLY)
            Rendering(PdfRenderer(fd), fd, temp)
        } catch (e: Exception) {
            null
        }
    }

    private fun renderPage(pdf: PdfRenderer, index: Int): Bitmap? {
        return try {
            pdf.openPage(index).use { page ->
                val scale = OCR_LONG_SIDE.toFloat() / maxOf(page.width, page.height)
                val width = (page.width * scale).toInt().coerceAtLeast(1)
                val height = (page.height * scale).toInt().coerceAtLeast(1)
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                Canvas(bitmap).drawColor(Color.WHITE)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                bitmap
            }
        } catch (e: Throwable) {
            null
        }
    }

    private inline fun <T> PdfRenderer.Page.use(block: (PdfRenderer.Page) -> T): T {
        try {
            return block(this)
        } finally {
            close()
        }
    }
}
