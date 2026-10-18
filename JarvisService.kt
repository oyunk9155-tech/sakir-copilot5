package com.sakir.copilot

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.telecom.TelecomManager
import android.telephony.TelephonyManager
import java.util.concurrent.Executors

/**
 * Arka planda sürekli dinleyen ön plan servisi.
 * "Şakir" uyandırma sözünü duymadan hiçbir komut çalıştırmaz.
 */
class JarvisService : Service() {

    private val main = Handler(Looper.getMainLooper())
    private lateinit var mem: Memory
    private lateinit var motion: Motion
    private lateinit var voice: Voice
    private lateinit var brain: Brain

    private var rec: SpeechRecognizer? = null
    private var listening = false
    private var busy = false
    private var awakeUntil = 0L
    private var awaitingCall = false
    private var lastRing = "?"
    private val exec = Executors.newSingleThreadExecutor()
    private var wl: PowerManager.WakeLock? = null
    private var phoneReceiver: BroadcastReceiver? = null
    private val restart = Runnable { startListening() }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onCreate() {
        super.onCreate()
        startAsForeground()
        mem = Memory(this)
        motion = Motion(this)
        voice = Voice(this, mem)
        brain = Brain(mem, Tools(this, mem, motion), motion)
        motion.start()
        registerPhone()
        wl = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "sakir:listen").apply { acquire() }
        JarvisState.running = true
        JarvisState.mode = Mode.IDLE
        main.post { startListening() }
    }

    private fun startAsForeground() {
        val ch = "sakir"
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(ch, "Şakir", NotificationManager.IMPORTANCE_LOW))
        val pi = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this, ch)
            .setContentTitle("Şakir dinliyor")
            .setContentText("\"Şakir\" diyerek çağırabilirsiniz")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                startForeground(
                    1, n,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
                )
            } catch (e: SecurityException) {
                // Konum izni yoksa sadece mikrofon türüyle başlat
                startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            }
        } else {
            startForeground(1, n)
        }
    }

    // ---------- dinleme döngüsü ----------

    private fun startListening() {
        if (!JarvisState.running || busy || listening) return
        if (rec == null) {
            rec = SpeechRecognizer.createSpeechRecognizer(this).also { it.setRecognitionListener(listener) }
        }
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "tr-TR")
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        JarvisState.mode = if (System.currentTimeMillis() < awakeUntil || awaitingCall) Mode.LISTENING else Mode.IDLE
        try {
            listening = true
            rec?.startListening(i)
        } catch (e: Exception) {
            listening = false
            restartLater(1000)
        }
    }

    private fun restartLater(ms: Long) {
        main.removeCallbacks(restart)
        main.postDelayed(restart, ms)
    }

    private fun stopRecognizer() {
        listening = false
        main.removeCallbacks(restart)
        try {
            rec?.cancel()
        } catch (e: Exception) {
        }
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {
            JarvisState.level = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
        }

        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}

        override fun onError(error: Int) {
            listening = false
            JarvisState.level = 0f
            if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                JarvisState.heard = "Mikrofon izni gerekli."
                return
            }
            if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY || error == SpeechRecognizer.ERROR_CLIENT) {
                try {
                    rec?.destroy()
                } catch (e: Exception) {
                }
                rec = null
                restartLater(1200)
            } else {
                restartLater(250)
            }
        }

        override fun onResults(results: Bundle?) {
            listening = false
            JarvisState.level = 0f
            val list = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION) ?: arrayListOf()
            handleHeard(list)
        }

        override fun onPartialResults(partialResults: Bundle?) {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    // ---------- komut işleme ----------

    private fun handleHeard(alts: List<String>) {
        val t = alts.firstOrNull()?.trim().orEmpty()
        if (t.isEmpty()) {
            restartLater(100)
            return
        }
        if (awaitingCall) {
            handleCallDecision(norm(t))
            return
        }
        val now = System.currentTimeMillis()
        var woke = false
        var cmd = ""
        for (cand in alts) {
            val n = norm(cand)
            val a = n.indexOf("sakir")
            val b = n.indexOf("shakir")
            val idx = listOf(a, b).filter { it >= 0 }.minOrNull() ?: -1
            if (idx >= 0) {
                woke = true
                val len = if (b >= 0 && b == idx) 6 else 5
                cmd = cand.substring(minOf(cand.length, idx + len)).trim(' ', ',', '.', '!', '?', ':')
                break
            }
        }
        when {
            woke && cmd.length >= 3 -> {
                JarvisState.heard = t
                runCommand(cmd)
            }
            woke -> {
                JarvisState.heard = t
                awakeUntil = now + 8000
                speak("Efendim?")
            }
            now < awakeUntil -> {
                JarvisState.heard = t
                runCommand(t)
            }
            else -> restartLater(50)   // uyandırma sözü yok: hiçbir şey yapma
        }
    }

    private fun runCommand(cmd: String) {
        busy = true
        stopRecognizer()
        JarvisState.mode = Mode.THINKING
        JarvisState.reply = ""
        exec.execute {
            val reply = try {
                brain.ask(cmd)
            } catch (e: Exception) {
                "Efendim, bir aksilik oldu."
            }
            main.post { speak(reply) }
        }
    }

    private fun speak(text: String) {
        val clean = cleanForSpeech(text)
        JarvisState.reply = clean
        JarvisState.mode = Mode.SPEAKING
        busy = true
        stopRecognizer()
        voice.speak(clean) {
            busy = false
            // Şakir bir soru sorduysa kısa bir süre "Şakir" demeden cevap verilebilir.
            awakeUntil = if (clean.trimEnd().endsWith("?")) System.currentTimeMillis() + 9000 else 0L
            JarvisState.mode = Mode.IDLE
            startListening()
        }
    }

    // ---------- gelen arama ----------

    private fun registerPhone() {
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                if (i.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return
                val st = i.getStringExtra(TelephonyManager.EXTRA_STATE)
                if (st == TelephonyManager.EXTRA_STATE_RINGING) {
                    val num = i.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER) ?: ""
                    val haveLog = checkSelfPermission(Manifest.permission.READ_CALL_LOG) == PackageManager.PERMISSION_GRANTED
                    if (num.isEmpty() && haveLog) return      // numaralı ikinci yayını bekle
                    if (num == lastRing) return
                    lastRing = num
                    val who = when {
                        num.isEmpty() -> "Bir numara"
                        else -> contactNameForNumber(this@JarvisService, num) ?: "Kayıtlı olmayan bir numara"
                    }
                    announceCall(who)
                } else {
                    lastRing = "?"
                    awaitingCall = false
                }
            }
        }
        phoneReceiver = r
        val f = IntentFilter(TelephonyManager.ACTION_PHONE_STATE_CHANGED)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(r, f, Context.RECEIVER_EXPORTED) else registerReceiver(r, f)
    }

    private fun announceCall(who: String) {
        main.post {
            busy = true
            awaitingCall = true
            stopRecognizer()
            JarvisState.mode = Mode.SPEAKING
            val text = "Efendim, $who arıyor. Açayım mı?"
            JarvisState.reply = text
            voice.speak(text) {
                busy = false
                startListening()
            }
            main.postDelayed({ awaitingCall = false }, 25000)
        }
    }

    private fun handleCallDecision(n: String) {
        val tokens = n.split(" ")
        val no = n.contains("hayir") || n.contains("reddet") || n.contains("acma") || n.contains("kapat")
        val yes = tokens.any { it == "evet" || it == "ac" || it == "acin" || it == "tamam" || it == "olur" }
        val tm = getSystemService(Context.TELECOM_SERVICE) as TelecomManager
        val allowed = checkSelfPermission(Manifest.permission.ANSWER_PHONE_CALLS) == PackageManager.PERMISSION_GRANTED
        when {
            no -> {
                awaitingCall = false
                try {
                    if (allowed && Build.VERSION.SDK_INT >= 28) tm.endCall()
                } catch (e: Exception) {
                }
                restartLater(300)
            }
            yes -> {
                awaitingCall = false
                try {
                    if (allowed) tm.acceptRingingCall()
                } catch (e: Exception) {
                }
                restartLater(300)
            }
            else -> restartLater(100)
        }
    }

    override fun onDestroy() {
        JarvisState.running = false
        JarvisState.mode = Mode.IDLE
        main.removeCallbacksAndMessages(null)
        try {
            rec?.destroy()
        } catch (e: Exception) {
        }
        voice.shutdown()
        motion.stop()
        phoneReceiver?.let {
            try {
                unregisterReceiver(it)
            } catch (e: Exception) {
            }
        }
        wl?.let { if (it.isHeld) it.release() }
        exec.shutdownNow()
        super.onDestroy()
    }
}
