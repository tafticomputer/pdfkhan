package com.pdfkhan.app

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Future

/**
 * خواندن با صدای آنلاین هوش مصنوعی. در حین پخش هر بخش، بخش بعدی از قبل دانلود می‌شود
 * تا بین بخش‌ها وقفه نیفتد.
 */
class OnlineReader(context: Context, private val cb: PlaybackCallback) {

    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())

    private val player = Executors.newSingleThreadExecutor { r -> Thread(r, "online-play") }
    private val fetcher = Executors.newSingleThreadExecutor { r -> Thread(r, "online-fetch") }

    @Volatile private var generation = 0
    @Volatile private var rate = 1f

    fun setRate(r: Float) {
        rate = r
    }

    fun start(chunks: List<Chunk>, from: Int, voice: OnlineVoice, creds: OnlineCredentials, rate: Float): Boolean {
        if (from !in chunks.indices) return false
        this.rate = rate
        val gen = ++generation
        player.execute { run(gen, chunks, from, voice, creds) }
        return true
    }

    fun stop() {
        generation++
    }

    fun shutdown() {
        generation++
        player.shutdownNow()
        fetcher.shutdownNow()
    }

    private fun run(gen: Int, chunks: List<Chunk>, from: Int, voice: OnlineVoice, creds: OnlineCredentials) {
        if (gen != generation) return

        var track: AudioTrack? = null
        var next: Future<ByteArray>? = null
        try {
            val tts = OnlineTts(voice, creds)
            val t = buildTrack(tts.sampleRate)
            track = t
            t.play()

            next = fetcher.submit(Callable { tts.synthesize(chunks[from].speak, rate) })
            var framesWritten = 0L

            for (i in from until chunks.size) {
                if (gen != generation) break

                val pcm = try {
                    next!!.get()
                } catch (e: ExecutionException) {
                    throw e.cause ?: e
                }
                if (gen != generation) break

                if (i + 1 < chunks.size) {
                    val idx = i + 1
                    next = fetcher.submit(Callable { tts.synthesize(chunks[idx].speak, rate) })
                }

                main.post { if (gen == generation) cb.onChunkStarted(i) }
                framesWritten += writeAll(t, pcm, gen)
            }

            if (gen == generation) waitUntilPlayed(t, framesWritten, gen)
            if (gen == generation) {
                main.post { if (gen == generation) cb.onFinished() }
            }
        } catch (e: Throwable) {
            if (gen == generation) {
                val message = when (e) {
                    is OnlineTts.AuthException -> app.getString(R.string.err_online_key)
                    is IOException -> app.getString(R.string.err_online_net, e.message ?: "")
                    is InterruptedException -> null
                    else -> app.getString(R.string.err_online_net, e.message ?: "")
                }
                if (message != null) {
                    main.post { if (gen == generation) cb.onError(message) }
                }
            }
        } finally {
            next?.cancel(true)
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
            sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        return AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(maxOf(minBytes, sampleRate * 2 * 2))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
    }

    /** PCM را می‌نویسد و تعداد فریم نوشته‌شده را برمی‌گرداند. */
    private fun writeAll(track: AudioTrack, pcm: ByteArray, gen: Int): Long {
        val samples = ShortArray(pcm.size / 2)
        ByteBuffer.wrap(pcm, 0, samples.size * 2).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(samples)

        var offset = 0
        while (offset < samples.size) {
            if (gen != generation) return offset.toLong()
            val n = track.write(samples, offset, samples.size - offset, AudioTrack.WRITE_NON_BLOCKING)
            if (n < 0) return offset.toLong()
            if (n == 0) {
                try {
                    Thread.sleep(10)
                } catch (e: InterruptedException) {
                    return offset.toLong()
                }
            } else {
                offset += n
            }
        }
        return samples.size.toLong()
    }

    private fun waitUntilPlayed(track: AudioTrack, totalFrames: Long, gen: Int) {
        val deadline = System.currentTimeMillis() + 6_000L
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
}
