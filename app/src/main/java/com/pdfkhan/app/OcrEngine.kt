package com.pdfkhan.app

import android.content.Context
import android.graphics.Bitmap
import com.googlecode.tesseract.android.TessBaseAPI
import java.io.File
import java.io.FileOutputStream

/**
 * حالت‌های OCR. [longSide] طول ضلع بلند تصویر (پیکسل) است؛ بزرگ‌تر یعنی دقیق‌تر و کندتر.
 * [accurate] یعنی مدل «best» به‌جای «fast».
 */
enum class OcrQuality(val accurate: Boolean, val longSide: Int) {
    FAST(false, 1800),
    BALANCED(false, 2200),
    ACCURATE(true, 2800)
}

/**
 * تشخیص متن فارسی از تصویر با Tesseract (کاملاً آفلاین).
 * هر نمونه فقط باید در یک نخ استفاده شود؛ برای پردازش موازی چند نمونه بسازید.
 *
 * [accurate] false = مدل «fast» (حدود چند برابر سریع‌تر، دقت کمی کمتر)،
 * true = مدل «best» (دقیق‌تر، کندتر).
 */
class OcrEngine(private val context: Context, private val accurate: Boolean) {

    private var api: TessBaseAPI? = null

    /** false یعنی داده‌ی زبان در دسترس نیست یا موتور راه نیفتاد. */
    fun init(): Boolean {
        val root = try {
            prepare(context, accurate)
        } catch (e: Exception) {
            return false
        }

        val tess = TessBaseAPI()
        if (!tess.init(root.absolutePath, LANG)) {
            tess.recycle()
            return false
        }
        tess.setPageSegMode(TessBaseAPI.PageSegMode.PSM_AUTO)
        // بررسی «متن وارونه‌رنگ» گاهی کار را دوبار انجام می‌دهد؛ برای اسکن عادی لازم نیست
        tess.setVariable("tessedit_do_invert", "0")
        api = tess
        return true
    }

    fun recognize(bitmap: Bitmap): String {
        val tess = api ?: return ""
        tess.setImage(bitmap)
        val text = tess.getUTF8Text() ?: ""
        tess.clear()
        return text
    }

    fun close() {
        api?.recycle()
        api = null
    }

    companion object {
        private const val LANG = "fas"

        /** داده‌ی زبان را (یک‌بار) از assets به حافظه‌ی برنامه کپی می‌کند و پوشه‌ی ریشه‌ی Tesseract را برمی‌گرداند. */
        @Synchronized
        fun prepare(context: Context, accurate: Boolean): File {
            val mode = if (accurate) "best" else "fast"
            val root = File(context.filesDir, "tesseract_$mode")
            val tessdata = File(root, "tessdata")
            tessdata.mkdirs()

            val lang = File(tessdata, "$LANG.traineddata")
            if (!lang.exists() || lang.length() == 0L) {
                val tmp = File(tessdata, "$LANG.traineddata.tmp")
                context.assets.open("tessdata/$mode/$LANG.traineddata").use { input ->
                    FileOutputStream(tmp).use { output -> input.copyTo(output) }
                }
                if (!tmp.renameTo(lang)) throw java.io.IOException("cannot install OCR data")
            }
            return root
        }
    }
}
