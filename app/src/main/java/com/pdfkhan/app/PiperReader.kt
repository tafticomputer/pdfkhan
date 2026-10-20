package com.pdfkhan.app

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import java.io.File
import java.util.concurrent.Executors

/**
 * خواندن با صدای فارسی داخلی (مدل‌های Piper از طریق Sherpa-ONNX) — بدون نیاز به صدای نصب‌شده روی گوشی.
 *
 * تولید صدا و پخش روی یک نخ پس‌زمینه انجام می‌شود. تولید جمله‌به‌جمله است و
 * به‌محض آماده شدن هر جمله، همان لحظه پخش می‌شود.
 */
class PiperReader(context: Context, private val cb: PlaybackCallback) {

    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())

    // یک نخ: کار قبلی تمام می‌شود، بعد کار بعدی شروع می‌شود
    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "piper-tts") }

    @Volatile private var generation = 0
    @Volatile private var rate = 1f

    // فقط داخل executor دست‌کاری می‌شوند
    private var engine: OfflineTts? = null
    private var engineDir: String? = null

    fun setRate(r: Float) {
        rate = r
    }

    fun start(chunks: List<Chunk>, from: Int, dirName: String, rate: Float): Boolean {
        if (from !in chunks.indices) return false
        this.rate = rate
        val gen = ++generation
        executor.execute { run(gen, chunks, from, dirName) }
        return true
    }

    fun stop() {
        generation++
    }

    fun shutdown() {
        generation++
        executor.execute {
            engine?.release()
            engine = null
        }
        executor.shutdown()
    }

    // ───────────────────────── کار پس‌زمینه ─────────────────────────

    private fun run(gen: Int, chunks: List<Chunk>, from: Int, dirName: String) {
        if (gen != generation) return

        var track: AudioTrack? = null
        try {
            val tts = ensureEngine(dirName)
            if (gen != generation) return

            val sampleRate = tts.sampleRate()
            val t = buildTrack(sampleRate)
            track = t
            t.play()

            var framesWritten = 0L

            for (i in from until chunks.size) {
                if (gen != generation) break
                main.post { if (gen == generation) cb.onChunkStarted(i) }

                tts.generateWithCallback(chunks[i].speak, 0, rate) { samples ->
                    if (gen != generation) {
                        0
                    } else {
                        val ok = writeAll(t, samples, gen)
                        framesWritten += samples.size
                        if (ok && gen == generation) 1 else 0
                    }
                }
            }

            if (gen == generation) {
                waitUntilPlayed(t, framesWritten, gen)
            }
            if (gen == generation) {
                main.post { if (gen == generation) cb.onFinished() }
            }
        } catch (e: Throwable) {
            if (gen == generation) {
                main.post { if (gen == generation) cb.onError(app.getString(R.string.err_builtin)) }
            }
        } finally {
            try {
                track?.pause()
                track?.flush()
                track?.release()
            } catch (e: Exception) {
                // مهم نیست
            }
        }
    }

    private fun buildTrack(sampleRate: Int): AudioTrack {
        val minBytes = AudioTrack.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT
        )
        val bufferBytes = maxOf(minBytes, sampleRate * 4 * BUFFER_SECONDS)
        return AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(bufferBytes)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
    }

    /** نمونه‌ها را بدون بلوکه شدن همیشگی می‌نویسد تا توقف فوری کار کند. */
    private fun writeAll(track: AudioTrack, samples: FloatArray, gen: Int): Boolean {
        var offset = 0
        while (offset < samples.size) {
            if (gen != generation) return false
            val n = track.write(samples, offset, samples.size - offset, AudioTrack.WRITE_NON_BLOCKING)
            if (n < 0) return false
            if (n == 0) {
                try {
                    Thread.sleep(10)
                } catch (e: InterruptedException) {
                    return false
                }
            } else {
                offset += n
            }
        }
        return true
    }

    /** منتظر می‌ماند تا آخرین صدای نوشته‌شده هم پخش شود. */
    private fun waitUntilPlayed(track: AudioTrack, totalFrames: Long, gen: Int) {
        val deadline = System.currentTimeMillis() + (BUFFER_SECONDS + 3) * 1000L
        while (gen == generation && System.currentTimeMillis() < deadline) {
            val played = track.playbackHeadPosition.toLong() and 0xFFFFFFFFL
            if (played >= totalFrames) return
            try {
                Thread.sleep(50)
            } catch (e: InterruptedException) {
                return
            }
        }
    }

    // ───────────────────────── بارگذاری مدل ─────────────────────────

    private fun ensureEngine(dirName: String): OfflineTts {
        engine?.let { if (engineDir == dirName) return it }

        engine?.release()
        engine = null
        engineDir = null

        val base = File(File(app.filesDir, "tts"), dirName)
        if (!File(base, READY_MARK).exists()) throw IllegalStateException("voice not installed")
        val onnx = base.listFiles { f -> f.name.endsWith(".onnx") }?.firstOrNull()
            ?: throw IllegalStateException("model not found")

        val vits = OfflineTtsVitsModelConfig(
            model = onnx.absolutePath,
            tokens = File(base, "tokens.txt").absolutePath,
            dataDir = File(base, "espeak-ng-data").absolutePath,
            // کمی نویز کمتر از پیش‌فرض: تلفظ یکنواخت‌تر و پایدارتر
            noiseScale = 0.5f,
            noiseScaleW = 0.6f
        )
        val config = OfflineTtsConfig(
            model = OfflineTtsModelConfig(
                vits = vits,
                numThreads = THREADS,
                debug = false,
                provider = "cpu"
            ),
            maxNumSentences = 1
        )

        val tts = OfflineTts(assetManager = null, config = config)
        engine = tts
        engineDir = dirName
        return tts
    }

    private companion object {
        const val READY_MARK = ".ready"
        const val BUFFER_SECONDS = 2
        const val THREADS = 2
    }
}
