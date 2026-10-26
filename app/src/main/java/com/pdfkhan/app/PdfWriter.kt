package com.pdfkhan.app

import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.text.Layout
import android.text.StaticLayout
import android.text.TextDirectionHeuristics
import android.text.TextPaint
import java.io.IOException

/**
 * متن استخراج‌شده را به یک فایل PDF تازه تبدیل می‌کند.
 * متن واقعی (قابل انتخاب و جستجو) نوشته می‌شود، نه تصویر؛ فارسی راست‌به‌چپ و متصل رندر می‌شود.
 * هر صفحه‌ی PDF اصلی از صفحه‌ی جدید شروع می‌شود و اگر متنش بلند باشد، به چند صفحه می‌رود.
 */
object PdfWriter {

    private const val PAGE_WIDTH = 595   // A4 به پوینت
    private const val PAGE_HEIGHT = 842
    private const val MARGIN = 48
    private const val TEXT_SIZE = 14f

    /** @return تعداد صفحه‌های PDF ساخته‌شده */
    fun write(context: Context, uri: Uri, pages: List<String>): Int {
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = TEXT_SIZE
        }
        val contentWidth = PAGE_WIDTH - 2 * MARGIN
        val contentHeight = PAGE_HEIGHT - 2 * MARGIN

        val doc = PdfDocument()
        var pageNumber = 0
        try {
            for (text in pages) {
                if (text.isBlank()) continue

                val layout = StaticLayout.Builder
                    .obtain(text, 0, text.length, paint, contentWidth)
                    .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                    .setTextDirection(TextDirectionHeuristics.FIRSTSTRONG_RTL)
                    .setLineSpacing(0f, 1.35f)
                    .setIncludePad(false)
                    .build()

                var line = 0
                while (line < layout.lineCount) {
                    val top = layout.getLineTop(line)
                    var end = line
                    while (end < layout.lineCount && layout.getLineBottom(end) - top <= contentHeight) end++
                    if (end == line) end = line + 1 // خطی که از صفحه بلندتر است

                    pageNumber++
                    val page = doc.startPage(
                        PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, pageNumber).create()
                    )
                    val canvas = page.canvas
                    canvas.save()
                    canvas.translate(MARGIN.toFloat(), (MARGIN - top).toFloat())
                    canvas.clipRect(0f, top.toFloat(), contentWidth.toFloat(), layout.getLineBottom(end - 1).toFloat())
                    layout.draw(canvas)
                    canvas.restore()
                    doc.finishPage(page)

                    line = end
                }
            }

            if (pageNumber == 0) throw IOException("no text")

            val out = context.contentResolver.openOutputStream(uri)
                ?: throw IOException("cannot open output")
            out.use { doc.writeTo(it) }
            return pageNumber
        } finally {
            doc.close()
        }
    }
}
