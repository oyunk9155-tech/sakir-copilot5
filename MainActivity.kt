package com.sakir.copilot

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.Spinner
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {

    private val h = Handler(Looper.getMainLooper())
    private lateinit var mem: Memory
    private lateinit var hud: TextView
    private lateinit var status: TextView
    private lateinit var heard: TextView
    private lateinit var reply: TextView
    private lateinit var toggle: Button
    private val cyan = Color.parseColor("#4FE3FF")

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun neededPermissions(): List<String> {
        val l = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.CALL_PHONE,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.ANSWER_PHONE_CALLS,
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.READ_CALL_LOG
        )
        if (Build.VERSION.SDK_INT >= 33) l.add(Manifest.permission.POST_NOTIFICATIONS)
        return l
    }

    private val tick = object : Runnable {
        override fun run() {
            val time = SimpleDateFormat("HH:mm", Locale("tr")).format(Date())
            val bi = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val lvl = bi?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = bi?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
            val bat = if (lvl >= 0) lvl * 100 / scale else 0
            hud.text = "$time  ·  %$bat pil  ·  ${JarvisState.speed.toInt()} km/sa  ·  yatış ${JarvisState.lean.toInt()}°"
            status.text = if (!JarvisState.running) "ÇEVRİMDIŞI" else when (JarvisState.mode) {
                Mode.IDLE -> "DİNLİYOR  ·  \"Şakir\" de"
                Mode.LISTENING -> "SENİ DİNLİYORUM"
                Mode.THINKING -> "DÜŞÜNÜYORUM"
                Mode.SPEAKING -> "KONUŞUYOR"
            }
            heard.text = if (JarvisState.heard.isEmpty()) "" else "Sen: " + JarvisState.heard
            reply.text = JarvisState.reply
            toggle.text = if (JarvisState.running) "DURDUR" else "BAŞLAT"
            h.postDelayed(this, 400)
        }
    }

    private fun label(size: Float, color: Int): TextView = TextView(this).apply {
        textSize = size
        setTextColor(color)
        typeface = Typeface.MONOSPACE
        gravity = Gravity.CENTER
    }

    private fun button(text: String, onClick: () -> Unit): Button = Button(this).apply {
        this.text = text
        setTextColor(cyan)
        typeface = Typeface.MONOSPACE
        background = GradientDrawable().apply {
            setColor(Color.TRANSPARENT)
            setStroke(dp(1), cyan)
            cornerRadius = dp(8).toFloat()
        }
        setOnClickListener { onClick() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        mem = Memory(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#04080F"))
            setPadding(dp(20), dp(40), dp(20), dp(20))
        }
        hud = label(13f, cyan)
        root.addView(hud, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        root.addView(ReactorView(this), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        status = label(16f, cyan)
        status.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        root.addView(status, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        heard = label(14f, Color.parseColor("#8FA3AD"))
        heard.setPadding(0, dp(10), 0, 0)
        root.addView(heard, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        reply = label(16f, Color.parseColor("#E8FBFF"))
        reply.setPadding(0, dp(8), 0, dp(12))
        root.addView(reply, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        toggle = button("BAŞLAT") { toggleService() }
        val perms = button("İZİNLER") { requestPermissions(neededPermissions().toTypedArray(), 1) }
        val special = button("ÖZEL İZİN") { specialPermissions() }
        val set = button("AYARLAR") { settings() }
        for (b in listOf(toggle, perms, special, set)) {
            val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            lp.setMargins(dp(3), 0, dp(3), 0)
            row.addView(b, lp)
        }
        root.addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        h.post(tick)
    }

    override fun onPause() {
        super.onPause()
        h.removeCallbacks(tick)
    }

    private fun missing(): List<String> =
        neededPermissions().filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }

    private fun toggleService() {
        val svc = Intent(this, JarvisService::class.java)
        if (JarvisState.running) {
            stopService(svc)
            return
        }
        if (mem.setting("ai_key").isBlank()) {
            Toast.makeText(this, "Önce Ayarlar'dan yapay zeka API anahtarını gir.", Toast.LENGTH_LONG).show()
            settings()
            return
        }
        val m = missing()
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(m.toTypedArray(), 1)
            return
        }
        if (m.isNotEmpty()) {
            Toast.makeText(this, "Bazı izinler eksik; ilgili komutlar çalışmayabilir. 'İZİNLER' ile verebilirsin.", Toast.LENGTH_LONG).show()
        }
        startForegroundService(svc)
    }

    private fun specialPermissions() {
        AlertDialog.Builder(this)
            .setTitle("Özel izinler")
            .setItems(
                arrayOf(
                    "1) Diğer uygulamaların üzerinde göster",
                    "2) Erişilebilirlik: Şakir erişim",
                    "3) Pil optimizasyonunu kapat"
                )
            ) { _, which ->
                when (which) {
                    0 -> startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                    1 -> startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    else -> startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
                }
            }
            .show()
    }

    private fun settings() {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
        }
        fun field(hint: String, key: String): EditText {
            val e = EditText(this)
            e.hint = hint
            e.setText(mem.setting(key))
            e.inputType = InputType.TYPE_CLASS_TEXT
            e.isSingleLine = true
            box.addView(e)
            return e
        }
        val providers = listOf(
            "gemini" to "Google Gemini (ücretsiz katman)",
            "openai" to "OpenAI / ChatGPT API",
            "claude" to "Claude"
        )
        val sp = Spinner(this)
        sp.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, providers.map { it.second })
        sp.setSelection(providers.indexOfFirst { it.first == mem.setting("provider").ifBlank { "gemini" } }.coerceAtLeast(0))
        box.addView(sp)
        val k1 = field("API anahtarı", "ai_key")
        val k4 = field("Model adı (boş bırakırsan varsayılan)", "ai_model")
        val k2 = field("ElevenLabs API anahtarı (isteğe bağlı)", "eleven_key")
        val k3 = field("ElevenLabs ses kimliği (isteğe bağlı)", "eleven_voice")
        AlertDialog.Builder(this)
            .setTitle("Ayarlar")
            .setView(android.widget.ScrollView(this).apply { addView(box) })
            .setPositiveButton("Kaydet") { _, _ ->
                mem.putSetting("provider", providers[sp.selectedItemPosition].first)
                mem.putSetting("ai_key", k1.text.toString())
                mem.putSetting("ai_model", k4.text.toString())
                mem.putSetting("eleven_key", k2.text.toString())
                mem.putSetting("eleven_voice", k3.text.toString())
            }
            .setNegativeButton("İptal", null)
            .show()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val rec = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        Toast.makeText(
            this,
            if (rec) "İzinler güncellendi. Şimdi BAŞLAT'a basabilirsin." else "Mikrofon izni olmadan Şakir çalışamaz.",
            Toast.LENGTH_LONG
        ).show()
    }
}
