package com.pdfkhan.app

import android.speech.tts.Voice

/** رویدادهای مشترک هر دو موتور خواندن؛ همه روی نخ اصلی صدا زده می‌شوند. */
interface PlaybackCallback {
    fun onChunkStarted(index: Int)
    fun onFinished()
    fun onError(message: String)
}

/** یک صدای قابل انتخاب در فهرست. */
sealed class VoiceOption(val key: String, val label: String) {

    /** صدای فارسی داخلی برنامه (آفلاین، Piper). [installed] false یعنی هنوز دانلود نشده. */
    class Builtin(val entry: VoiceCatalog.Entry, val installed: Boolean, label: String) :
        VoiceOption("b:${entry.dir}", label)

    /** صدای آنلاین هوش مصنوعی (نیازمند کلید سرویس و اینترنت). */
    class Online(val voice: OnlineVoice, label: String) :
        VoiceOption("o:${voice.service.name}:${voice.id}", label)

    /** صدای فارسی نصب‌شده روی گوشی (موتور TTS اندروید). */
    class System(val voice: Voice, label: String) : VoiceOption("s:${voice.name}", label)
}
