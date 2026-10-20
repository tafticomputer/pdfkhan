package com.pdfkhan.app

import android.content.Context
import android.graphics.Bitmap
import com.googlecode.tesseract.android.TessBaseAPI
import java.io.File
import java.io.FileOutputStream

/** تشخیص متن فارسی از تصویر با Tesseract (کاملاً آفلاین). */
class OcrEngine(private val context: Context) {

    private var api: TessBaseAPI? = null

    /** false یعنی داده‌ی زبان در دسترس نیست یا موتور راه نیفتاد. */
    fun init(): Boolean {
        val root = File(context.filesDir, "tesseract")
        val tessdata = File(root, "tessdata")
        tessdata.mkdirs()

        val lang = File(tessdata, "$LANG.traineddata")
        if (!lang.exists() || lang.length() == 0L) {
            context.assets.open("tessdata/$LANG.traineddata").use { input ->
                FileOutputStream(lang).use { output -> input.copyTo(output) }
            }
        }

        val tess = TessBaseAPI()
        if (!tess.init(root.absolutePath, LANG)) {
            tess.recycle()
            return false
        }
        tess.setPageSegMode(TessBaseAPI.PageSegMode.PSM_AUTO)
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

    private companion object {
        const val LANG = "fas"
    }
}
