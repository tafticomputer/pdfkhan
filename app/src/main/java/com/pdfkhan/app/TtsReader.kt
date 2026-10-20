package com.pdfkhan.app

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice

/**
 * خواندن پشت‌سرهم بخش‌ها با TextToSpeech.
 * فقط چند بخش جلوتر در صف موتور گذاشته می‌شود تا توقف/ادامه سریع باشد.
 * همه‌ی callbackها روی نخ اصلی صدا زده می‌شوند.
 */
class TtsReader(context: Context, private val cb: Callback) {

    interface Callback : PlaybackCallback {
        fun onInit(ok: Boolean)
    }

    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())

    private var tts: TextToSpeech? = null
    private var chunks: List<Chunk> = emptyList()
    private var generation = 0      // با هر شروع/توقف زیاد می‌شود تا callbackهای قدیمی نادیده گرفته شوند
    private var nextToQueue = 0
    private var currentIndex = 0

    private val progressListener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) {
            val (g, i) = parse(utteranceId) ?: return
            main.post {
                if (g != generation) return@post
                currentIndex = i
                cb.onChunkStarted(i)
                fillQueue()
            }
        }

        override fun onDone(utteranceId: String?) {
            val (g, i) = parse(utteranceId) ?: return
            main.post {
                if (g != generation) return@post
                if (i == chunks.lastIndex) cb.onFinished()
            }
        }

        @Deprecated("Deprecated in Java")
        override fun onError(utteranceId: String?) {
            reportError(utteranceId)
        }

        override fun onError(utteranceId: String?, errorCode: Int) {
            reportError(utteranceId)
        }
    }

    init {
        tts = TextToSpeech(appContext) { status ->
            main.post {
                val ok = status == TextToSpeech.SUCCESS
                if (ok) tts?.setOnUtteranceProgressListener(progressListener)
                cb.onInit(ok)
            }
        }
    }

    /** صداهای فارسیِ نصب‌شده؛ ابتدا آفلاین‌ها. */
    fun persianVoices(): List<Voice> {
        val all: Set<Voice> = try {
            tts?.voices ?: emptySet()
        } catch (e: Exception) {
            emptySet()
        }
        return all
            .filter {
                val lang = it.locale.language
                lang == "fa" || lang == "fas" || lang == "per"
            }
            .sortedWith(compareBy<Voice>({ it.isNetworkConnectionRequired }, { it.name }))
    }

    fun setRate(rate: Float) {
        tts?.setSpeechRate(rate)
    }

    fun start(list: List<Chunk>, from: Int, voice: Voice, rate: Float): Boolean {
        val t = tts ?: return false
        generation++
        t.stop()
        if (from !in list.indices) return false

        if (t.setVoice(voice) != TextToSpeech.SUCCESS) {
            cb.onError(appContext.getString(R.string.err_voice))
            return false
        }
        t.setSpeechRate(rate)

        chunks = list
        currentIndex = from
        nextToQueue = from
        fillQueue()
        return true
    }

    fun pause() = halt()

    fun stop() = halt()

    fun shutdown() {
        generation++
        tts?.stop()
        tts?.shutdown()
        tts = null
    }

    private fun halt() {
        generation++
        tts?.stop()
    }

    private fun fillQueue() {
        val t = tts ?: return
        while (nextToQueue < chunks.size && nextToQueue - currentIndex < LOOKAHEAD) {
            val id = "g${generation}_$nextToQueue"
            val result = t.speak(chunks[nextToQueue].speak, TextToSpeech.QUEUE_ADD, Bundle(), id)
            if (result != TextToSpeech.SUCCESS) {
                halt()
                cb.onError(appContext.getString(R.string.err_speak))
                return
            }
            nextToQueue++
        }
    }

    private fun reportError(utteranceId: String?) {
        val (g, _) = parse(utteranceId) ?: return
        main.post {
            if (g != generation) return@post
            halt()
            cb.onError(appContext.getString(R.string.err_speak))
        }
    }

    private fun parse(id: String?): Pair<Int, Int>? {
        if (id == null || !id.startsWith("g")) return null
        val parts = id.substring(1).split("_")
        if (parts.size != 2) return null
        val g = parts[0].toIntOrNull() ?: return null
        val i = parts[1].toIntOrNull() ?: return null
        return g to i
    }

    private companion object {
        const val LOOKAHEAD = 3
    }
}
