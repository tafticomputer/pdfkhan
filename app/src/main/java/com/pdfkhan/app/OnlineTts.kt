package com.pdfkhan.app

import android.content.SharedPreferences
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

enum class OnlineService(val title: String) {
    AZURE("Azure"),
    GEMINI("Gemini"),
    OPENAI("OpenAI")
}

/** یک صدای آنلاین هوش مصنوعی. */
class OnlineVoice(val service: OnlineService, val id: String, val displayName: String)

object OnlineCatalog {
    val voices = listOf(
        OnlineVoice(OnlineService.AZURE, "fa-IR-DilaraNeural", "Dilara"),
        OnlineVoice(OnlineService.AZURE, "fa-IR-FaridNeural", "Farid"),
        OnlineVoice(OnlineService.GEMINI, "Kore", "Kore"),
        OnlineVoice(OnlineService.GEMINI, "Charon", "Charon"),
        OnlineVoice(OnlineService.OPENAI, "nova", "Nova"),
        OnlineVoice(OnlineService.OPENAI, "onyx", "Onyx")
    )
}

/** کلیدها و تنظیمات سرویس‌های آنلاین؛ فقط روی همین گوشی ذخیره می‌شود. */
class OnlineCredentials(
    val azureKey: String,
    val azureRegion: String,
    val geminiKey: String,
    val geminiModel: String,
    val openaiKey: String,
    val openaiModel: String
) {
    fun hasKey(service: OnlineService): Boolean = when (service) {
        OnlineService.AZURE -> azureKey.isNotBlank() && azureRegion.isNotBlank()
        OnlineService.GEMINI -> geminiKey.isNotBlank() && geminiModel.isNotBlank()
        OnlineService.OPENAI -> openaiKey.isNotBlank() && openaiModel.isNotBlank()
    }

    companion object {
        const val K_AZURE_KEY = "azure_key"
        const val K_AZURE_REGION = "azure_region"
        const val K_GEMINI_KEY = "gemini_key"
        const val K_GEMINI_MODEL = "gemini_model"
        const val K_OPENAI_KEY = "openai_key"
        const val K_OPENAI_MODEL = "openai_model"
        const val DEFAULT_GEMINI_MODEL = "gemini-2.5-flash-preview-tts"
        const val DEFAULT_OPENAI_MODEL = "gpt-4o-mini-tts"

        fun load(p: SharedPreferences) = OnlineCredentials(
            azureKey = p.getString(K_AZURE_KEY, "")?.trim() ?: "",
            azureRegion = p.getString(K_AZURE_REGION, "")?.trim() ?: "",
            geminiKey = p.getString(K_GEMINI_KEY, "")?.trim() ?: "",
            geminiModel = (p.getString(K_GEMINI_MODEL, "")?.trim() ?: "").ifEmpty { DEFAULT_GEMINI_MODEL },
            openaiKey = p.getString(K_OPENAI_KEY, "")?.trim() ?: "",
            openaiModel = (p.getString(K_OPENAI_MODEL, "")?.trim() ?: "").ifEmpty { DEFAULT_OPENAI_MODEL }
        )
    }
}

/** درخواست‌های تبدیل متن به گفتار به سرویس‌های آنلاین؛ خروجی PCM ‏16 بیتی ‏۲۴ کیلوهرتز مونو. */
class OnlineTts(private val voice: OnlineVoice, private val creds: OnlineCredentials) {

    /** کلید نامعتبر/دسترسی ممنوع (تکرار درخواست فایده ندارد). */
    class AuthException(message: String) : IOException(message)

    val sampleRate = 24000

    fun synthesize(text: String, rate: Float): ByteArray {
        var attempt = 0
        while (true) {
            try {
                return when (voice.service) {
                    OnlineService.AZURE -> azure(text, rate)
                    OnlineService.GEMINI -> gemini(text)
                    OnlineService.OPENAI -> openai(text, rate)
                }
            } catch (e: AuthException) {
                throw e
            } catch (e: IOException) {
                attempt++
                if (attempt >= MAX_ATTEMPTS) throw e
                Thread.sleep(attempt * 2000L)
            }
        }
    }

    // ───────────────────────── Azure ─────────────────────────

    private fun azure(text: String, rate: Float): ByteArray {
        val percent = ((rate - 1f) * 100f).toInt().coerceIn(-50, 100)
        val rateAttr = if (percent >= 0) "+$percent%" else "$percent%"
        val ssml = "<speak version='1.0' xml:lang='fa-IR'>" +
            "<voice name='${voice.id}'><prosody rate='$rateAttr'>${escapeXml(text)}</prosody></voice></speak>"

        val url = "https://${creds.azureRegion}.tts.speech.microsoft.com/cognitiveservices/v1"
        return post(
            url,
            mapOf(
                "Ocp-Apim-Subscription-Key" to creds.azureKey,
                "Content-Type" to "application/ssml+xml",
                "X-Microsoft-OutputFormat" to "raw-24khz-16bit-mono-pcm",
                "User-Agent" to "PdfKhan"
            ),
            ssml.toByteArray(Charsets.UTF_8)
        )
    }

    private fun escapeXml(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            .replace("'", "&apos;").replace("\"", "&quot;")

    // ───────────────────────── Gemini ─────────────────────────

    private fun gemini(text: String): ByteArray {
        val body = JSONObject()
            .put(
                "contents",
                JSONArray().put(JSONObject().put("parts", JSONArray().put(JSONObject().put("text", text))))
            )
            .put(
                "generationConfig",
                JSONObject()
                    .put("responseModalities", JSONArray().put("AUDIO"))
                    .put(
                        "speechConfig",
                        JSONObject().put(
                            "voiceConfig",
                            JSONObject().put("prebuiltVoiceConfig", JSONObject().put("voiceName", voice.id))
                        )
                    )
            )

        val url = "https://generativelanguage.googleapis.com/v1beta/models/${creds.geminiModel}:generateContent"
        val response = post(
            url,
            mapOf(
                "x-goog-api-key" to creds.geminiKey,
                "Content-Type" to "application/json"
            ),
            body.toString().toByteArray(Charsets.UTF_8)
        )

        try {
            val data = JSONObject(String(response, Charsets.UTF_8))
                .getJSONArray("candidates").getJSONObject(0)
                .getJSONObject("content").getJSONArray("parts").getJSONObject(0)
                .getJSONObject("inlineData").getString("data")
            return Base64.decode(data, Base64.DEFAULT)
        } catch (e: Exception) {
            throw IOException("no audio in response")
        }
    }

    // ───────────────────────── OpenAI ─────────────────────────

    /** خروجی «pcm» در OpenAI همان PCM خام ۱۶ بیتی ۲۴ کیلوهرتز مونو است. */
    private fun openai(text: String, rate: Float): ByteArray {
        val body = JSONObject()
            .put("model", creds.openaiModel)
            .put("input", text)
            .put("voice", voice.id)
            .put("response_format", "pcm")
            .put("speed", rate.toDouble().coerceIn(0.25, 4.0))
        // فقط مدل‌های gpt-4o از «instructions» پشتیبانی می‌کنند
        if (creds.openaiModel.startsWith("gpt-4o")) {
            body.put("instructions", "Read the text aloud in clear, natural Persian (Farsi), at a steady pace.")
        }

        return post(
            "https://api.openai.com/v1/audio/speech",
            mapOf(
                "Authorization" to "Bearer ${creds.openaiKey}",
                "Content-Type" to "application/json"
            ),
            body.toString().toByteArray(Charsets.UTF_8)
        )
    }

    // ───────────────────────── HTTP ─────────────────────────

    private fun post(url: String, headers: Map<String, String>, body: ByteArray): ByteArray {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 20_000
            conn.readTimeout = 60_000
            conn.doOutput = true
            for ((k, v) in headers) conn.setRequestProperty(k, v)
            conn.outputStream.use { it.write(body) }

            val code = conn.responseCode
            if (code == HttpURLConnection.HTTP_OK) {
                return conn.inputStream.use { input ->
                    val out = ByteArrayOutputStream()
                    input.copyTo(out)
                    out.toByteArray()
                }
            }

            val detail = try {
                conn.errorStream?.use { String(it.readBytes(), Charsets.UTF_8) }?.take(160) ?: ""
            } catch (e: Exception) {
                ""
            }
            val message = "HTTP $code $detail".trim()
            if (code == 401 || code == 403) throw AuthException(message)
            throw IOException(message)
        } finally {
            conn.disconnect()
        }
    }

    private companion object {
        const val MAX_ATTEMPTS = 3
    }
}
