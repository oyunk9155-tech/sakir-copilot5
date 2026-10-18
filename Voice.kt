package com.sakir.copilot

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/**
 * Ses çıkışı. ElevenLabs anahtarı ve ses kimliği girilmişse gerçekçi, tok bir ses kullanır;
 * girilmemişse (veya hata olursa) telefonun Türkçe konuşma motoruna düşer.
 */
class Voice(private val ctx: Context, private val mem: Memory) : TextToSpeech.OnInitListener {
    private val main = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = TextToSpeech(ctx, this)
    private var ready = false
    private var pending: (() -> Unit)? = null
    private var player: MediaPlayer? = null

    override fun onInit(status: Int) {
        val t = tts ?: return
        if (status == TextToSpeech.SUCCESS) {
            t.language = Locale("tr", "TR")
            t.setPitch(0.72f)          // daha kalın ses
            t.setSpeechRate(0.95f)
            t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) {
                    finish()
                }

                @Deprecated("Eski API'ler için gerekli")
                override fun onError(utteranceId: String?) {
                    finish()
                }
            })
            ready = true
        }
    }

    private fun finish() {
        main.post {
            val cb = pending
            pending = null
            cb?.invoke()
        }
    }

    fun speak(text: String, done: () -> Unit) {
        pending = done
        val key = mem.setting("eleven_key")
        val voiceId = mem.setting("eleven_voice")
        if (key.isNotBlank() && voiceId.isNotBlank()) {
            Thread {
                val ok = speakEleven(text, key, voiceId)
                if (!ok) main.post { speakLocal(text) }
            }.start()
        } else {
            speakLocal(text)
        }
    }

    private fun speakLocal(text: String) {
        val t = tts
        if (!ready || t == null) {
            finish()
            return
        }
        t.speak(text, TextToSpeech.QUEUE_FLUSH, null, "u" + System.nanoTime())
    }

    private fun speakEleven(text: String, key: String, voiceId: String): Boolean {
        return try {
            val c = URL("https://api.elevenlabs.io/v1/text-to-speech/$voiceId").openConnection() as HttpURLConnection
            c.requestMethod = "POST"
            c.connectTimeout = 10000
            c.readTimeout = 30000
            c.doOutput = true
            c.setRequestProperty("xi-api-key", key)
            c.setRequestProperty("Content-Type", "application/json")
            c.setRequestProperty("Accept", "audio/mpeg")
            val body = JSONObject()
                .put("text", text)
                .put("model_id", "eleven_multilingual_v2")
                .put("voice_settings", JSONObject().put("stability", 0.5).put("similarity_boost", 0.8))
            c.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            if (c.responseCode != 200) return false
            val f = File(ctx.cacheDir, "tts.mp3")
            c.inputStream.use { i -> f.outputStream().use { o -> i.copyTo(o) } }
            main.post {
                try {
                    val mp = MediaPlayer()
                    player = mp
                    mp.setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    mp.setDataSource(f.path)
                    mp.setOnCompletionListener {
                        it.release()
                        finish()
                    }
                    mp.setOnErrorListener { m, _, _ ->
                        m.release()
                        finish()
                        true
                    }
                    mp.prepare()
                    mp.start()
                } catch (e: Exception) {
                    finish()
                }
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    fun stop() {
        tts?.stop()
        try {
            player?.release()
        } catch (e: Exception) {
        }
        player = null
    }

    fun shutdown() {
        stop()
        tts?.shutdown()
        tts = null
    }
}
