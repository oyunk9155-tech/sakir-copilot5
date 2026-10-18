package com.sakir.copilot

import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.AlarmClock
import android.provider.MediaStore
import android.view.KeyEvent
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Claude'un çağırabildiği araçlar: telefon üzerinde gerçek eylemler. */
class Tools(private val ctx: Context, private val mem: Memory, private val motion: Motion) {

    private class P(val n: String, val type: String, val d: String, val req: Boolean = false)

    private fun def(name: String, desc: String, vararg ps: P): JSONObject {
        val props = JSONObject()
        val req = JSONArray()
        for (p in ps) {
            props.put(p.n, JSONObject().put("type", p.type).put("description", p.d))
            if (p.req) req.put(p.n)
        }
        return JSONObject()
            .put("name", name)
            .put("description", desc)
            .put(
                "input_schema",
                JSONObject().put("type", "object").put("properties", props).put("required", req)
            )
    }

    private fun baseDefs(): JSONArray {
        val a = JSONArray()
        a.put(def("open_app", "Telefondaki bir uygulamayı adıyla açar (YouTube, Spotify, WhatsApp, Haritalar, Kamera vb.).",
            P("name", "string", "Uygulamanın adı", true)))
        a.put(def("youtube_search", "YouTube'da arama yapar. play=true ise sonuçlardaki ilk videoyu da açar.",
            P("query", "string", "Aranacak metin", true),
            P("play", "boolean", "İlk videoyu otomatik aç")))
        a.put(def("media_control", "Çalan müzik veya videoyu yönetir (Spotify, YouTube vb.). action: play, pause, next, previous, stop, toggle.",
            P("action", "string", "play | pause | next | previous | stop | toggle", true)))
        a.put(def("spotify_play", "Spotify'da müzik çalar. query boşsa en son çalınan müziği devam ettirir. Doluysa şarkı, sanatçı, albüm veya çalma listesi arayıp çalar.",
            P("query", "string", "Şarkı, sanatçı veya çalma listesi adı; boş bırakılırsa son çalınan devam eder"),
            P("kind", "string", "any | playlist | artist | album | track")))
        a.put(def("navigate", "Google Haritalar'da motosiklet modunda rota başlatır. 'en yakın benzinlik' gibi arama ifadeleri de verilebilir (örn. 'akaryakıt istasyonu').",
            P("destination", "string", "Hedef adresi, yer adı veya arama ifadesi", true)))
        a.put(def("navigate_saved", "Kayıtlı bir yere (ör. ev, iş) rota başlatır.",
            P("name", "string", "Kayıtlı yerin adı", true)))
        a.put(def("save_place", "Şu anki GPS konumunu verilen isimle kaydeder (ör. ev, iş).",
            P("name", "string", "Yerin adı", true)))
        a.put(def("remember", "Kullanıcı hakkında kalıcı bir bilgiyi hafızaya kaydeder.",
            P("key", "string", "Kısa anahtar (ör. motor_modeli)", true),
            P("value", "string", "Bilgi", true)))
        a.put(def("forget", "Hafızadaki bir bilgiyi siler.",
            P("key", "string", "Silinecek anahtar", true)))
        a.put(def("call_contact", "Rehberdeki bir kişiyi arar.",
            P("name", "string", "Kişinin adı veya telefon numarası", true)))
        a.put(def("whatsapp_send", "Rehberdeki bir kişiye WhatsApp mesajı gönderir.",
            P("contact", "string", "Kişinin adı", true),
            P("message", "string", "Mesaj metni", true)))
        a.put(def("set_alarm", "Alarm kurar.",
            P("hour", "integer", "Saat (0-23)", true),
            P("minute", "integer", "Dakika (0-59)", true),
            P("label", "string", "Alarm etiketi")))
        a.put(def("set_timer", "Geri sayım zamanlayıcısı kurar.",
            P("seconds", "integer", "Süre (saniye)", true),
            P("label", "string", "Etiket")))
        a.put(def("stopwatch", "Kronometre. action: start, stop, read, reset.",
            P("action", "string", "start | stop | read | reset", true)))
        a.put(def("set_volume", "Medya ses seviyesini değiştirir. percent (0-100) veya direction (up, down, mute) verilir.",
            P("percent", "integer", "Hedef ses yüzdesi"),
            P("direction", "string", "up | down | mute")))
        a.put(def("get_status", "Saat, tarih, pil, ses seviyesi ve hız gibi sistem durumunu verir."))
        a.put(def("get_speed", "GPS ile anlık hızı verir."))
        a.put(def("get_trip", "Yolculuk bilgisi: mesafe, süre, ortalama ve en yüksek hız."))
        a.put(def("lean_angle", "Motosiklet yatış açısı. action: calibrate (motor dikken kalibre et), current, last (son viraj), max (yolculuktaki en yüksek).",
            P("action", "string", "calibrate | current | last | max", true)))
        a.put(def("weather", "Hava durumu. place boşsa bulunulan yerin hava durumu.",
            P("place", "string", "Şehir veya ilçe adı")))
        a.put(def("route_weather", "Bulunulan yerden hedefe giden güzergâhın başlangıç, orta ve varış noktalarındaki hava durumu.",
            P("destination", "string", "Hedef şehir veya ilçe", true)))
        a.put(def("screen", "Erişilebilirlik ile ekranı kontrol eder. action: read (ekranı oku), click (metne dokun), back, home, recents.",
            P("action", "string", "read | click | back | home | recents", true),
            P("text", "string", "click için ekrandaki metin")))
        return a
    }

    /** Claude için: temel araçlar + sunucu tarafı web arama. */
    fun definitions(): JSONArray {
        val a = baseDefs()
        a.put(JSONObject().put("type", "web_search_20250305").put("name", "web_search").put("max_uses", 3))
        return a
    }

    /** OpenAI uyumlu servisler (Gemini, OpenAI vb.) için araç tanımları. */
    fun openAiTools(): JSONArray {
        val out = JSONArray()
        val base = baseDefs()
        for (i in 0 until base.length()) {
            val d = base.getJSONObject(i)
            val schema = d.getJSONObject("input_schema")
            val props = schema.getJSONObject("properties")
            val fn = JSONObject()
                .put("name", d.getString("name"))
                .put("description", d.getString("description"))
            // Parametresiz araçlarda "parameters" hiç gönderilmez (bazı servisler boş şemayı reddeder).
            if (props.length() > 0) {
                val params = JSONObject().put("type", "object").put("properties", props)
                val req = schema.optJSONArray("required")
                if (req != null && req.length() > 0) params.put("required", req)
                fn.put("parameters", params)
            }
            out.put(JSONObject().put("type", "function").put("function", fn))
        }
        return out
    }

    fun run(name: String, i: JSONObject): String = when (name) {
        "open_app" -> openApp(i.optString("name"))
        "youtube_search" -> youtube(i.optString("query"), i.optBoolean("play", false))
        "media_control" -> media(i.optString("action"))
        "spotify_play" -> spotify(i.optString("query"), i.optString("kind"))
        "navigate" -> navigate(i.optString("destination"))
        "navigate_saved" -> navigateSaved(i.optString("name"))
        "save_place" -> savePlace(i.optString("name"))
        "remember" -> {
            mem.remember(i.optString("key"), i.optString("value"))
            "Kaydedildi."
        }
        "forget" -> if (mem.forget(i.optString("key"))) "Unutuldu." else "Böyle bir kayıt yok."
        "call_contact" -> call(i.optString("name"))
        "whatsapp_send" -> whatsapp(i.optString("contact"), i.optString("message"))
        "set_alarm" -> alarm(i.optInt("hour"), i.optInt("minute"), i.optString("label"))
        "set_timer" -> timer(i.optInt("seconds"), i.optString("label"))
        "stopwatch" -> stopwatch(i.optString("action"))
        "set_volume" -> volume(i)
        "get_status" -> status()
        "get_speed" -> speed()
        "get_trip" -> trip()
        "lean_angle" -> lean(i.optString("action", "last"))
        "weather" -> weather(i.optString("place"))
        "route_weather" -> routeWeather(i.optString("destination"))
        "screen" -> screen(i.optString("action"), i.optString("text"))
        else -> "Bilinmeyen araç: $name"
    }

    // ---------- yardımcılar ----------

    private fun start(i: Intent) {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(i)
    }

    private fun audio() = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    // ---------- uygulamalar ----------

    private fun openApp(name: String): String {
        val pm = ctx.packageManager
        val q = norm(name)
        val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(main, 0)
        val hit = apps.firstOrNull { norm(it.loadLabel(pm).toString()) == q }
            ?: apps.firstOrNull { norm(it.loadLabel(pm).toString()).contains(q) }
            ?: return "'$name' adlı uygulama bulunamadı."
        val launch = pm.getLaunchIntentForPackage(hit.activityInfo.packageName) ?: return "Uygulama açılamadı."
        start(launch)
        return "${hit.loadLabel(pm)} açıldı."
    }

    private fun youtube(query: String, play: Boolean): String {
        if (query.isBlank()) return "Arama metni boş."
        val canClick = AccessService.instance != null
        if (play && canClick) AccessService.pendingYoutubeClick = System.currentTimeMillis()
        val i = Intent(Intent.ACTION_SEARCH).setPackage("com.google.android.youtube").putExtra("query", query)
        try {
            start(i)
        } catch (e: Exception) {
            start(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=" + Uri.encode(query))))
        }
        return if (!play) "YouTube'da '$query' araması açıldı."
        else if (canClick) "YouTube'da '$query' aranıyor, ilk video açılacak."
        else "YouTube'da '$query' araması açıldı ama ilk videoyu otomatik açmak için erişilebilirlik servisi kapalı."
    }

    private fun media(action: String): String {
        val code = when (action) {
            "play" -> KeyEvent.KEYCODE_MEDIA_PLAY
            "pause" -> KeyEvent.KEYCODE_MEDIA_PAUSE
            "next" -> KeyEvent.KEYCODE_MEDIA_NEXT
            "previous" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
            "stop" -> KeyEvent.KEYCODE_MEDIA_STOP
            else -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
        }
        val am = audio()
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
        return "Medya komutu gönderildi: $action."
    }

    private fun spotify(query: String, kind: String): String {
        val pkg = "com.spotify.music"
        val launch = ctx.packageManager.getLaunchIntentForPackage(pkg) ?: return "Spotify yüklü değil."
        if (query.isBlank()) {
            start(launch)
            Handler(Looper.getMainLooper()).postDelayed({ media("play") }, 2000)
            return "Spotify açıldı, son çalınan müzik başlatılıyor."
        }
        val i = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).setPackage(pkg)
        i.putExtra(SearchManager.QUERY, query)
        i.putExtra(
            MediaStore.EXTRA_MEDIA_FOCUS,
            when (kind) {
                "playlist" -> MediaStore.Audio.Playlists.ENTRY_CONTENT_TYPE
                "artist" -> MediaStore.Audio.Artists.ENTRY_CONTENT_TYPE
                "album" -> MediaStore.Audio.Albums.ENTRY_CONTENT_TYPE
                "track" -> "vnd.android.cursor.item/audio"
                else -> "vnd.android.cursor.item/*"
            }
        )
        return try {
            start(i)
            "Spotify'da '$query' çalınıyor."
        } catch (e: Exception) {
            start(Intent(Intent.ACTION_VIEW, Uri.parse("spotify:search:" + Uri.encode(query))))
            "Spotify'da '$query' araması açıldı; otomatik oynatma bu cihazda desteklenmedi."
        }
    }

    // ---------- navigasyon ve yerler ----------

    private fun navigate(dest: String): String {
        if (dest.isBlank()) return "Hedef belirtilmedi."
        val uri = Uri.parse("google.navigation:q=" + Uri.encode(dest) + "&mode=l")
        return try {
            start(Intent(Intent.ACTION_VIEW, uri).setPackage("com.google.android.apps.maps"))
            "Google Haritalar'da '$dest' için motosiklet rotası başlatıldı."
        } catch (e: Exception) {
            "Google Haritalar açılamadı."
        }
    }

    private fun navigateSaved(name: String): String {
        val p = mem.place(name) ?: return "'$name' adında kayıtlı bir yer yok."
        val uri = Uri.parse("google.navigation:q=${p.getDouble("lat")},${p.getDouble("lon")}&mode=l")
        return try {
            start(Intent(Intent.ACTION_VIEW, uri).setPackage("com.google.android.apps.maps"))
            "'$name' için rota başlatıldı."
        } catch (e: Exception) {
            "Google Haritalar açılamadı."
        }
    }

    private fun savePlace(name: String): String {
        val l = motion.best() ?: return "Konum alınamıyor. GPS'in açık ve konum izninin verilmiş olduğundan emin olun."
        mem.savePlace(name, l.latitude, l.longitude)
        return "'$name' olarak mevcut konum kaydedildi."
    }

    // ---------- iletişim ----------

    private fun call(name: String): String {
        if (ctx.checkSelfPermission(android.Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED)
            return "Arama izni verilmemiş."
        val digits = name.filter { it.isDigit() }
        val c = if (digits.length >= 7) Pair(name, name)
        else findContactNumber(ctx, name) ?: return "Rehberde '$name' bulunamadı."
        start(Intent(Intent.ACTION_CALL, Uri.parse("tel:" + Uri.encode(c.second))))
        return "${c.first} aranıyor."
    }

    private fun whatsapp(contact: String, message: String): String {
        if (message.isBlank()) return "Mesaj boş."
        val c = findContactNumber(ctx, contact) ?: return "Rehberde '$contact' bulunamadı."
        val num = waNumber(c.second)
        val canSend = AccessService.instance != null
        if (canSend) AccessService.pendingWhatsappSend = System.currentTimeMillis()
        val i = Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/$num?text=" + Uri.encode(message)))
            .setPackage("com.whatsapp")
        return try {
            start(i)
            if (canSend) "${c.first} kişisine mesaj gönderiliyor."
            else "${c.first} sohbeti mesajla açıldı ama gönder tuşuna basmak için erişilebilirlik servisi kapalı."
        } catch (e: Exception) {
            "WhatsApp açılamadı."
        }
    }

    // ---------- zaman ----------

    private fun alarm(h: Int, m: Int, label: String): String {
        val i = Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, h)
            .putExtra(AlarmClock.EXTRA_MINUTES, m)
            .putExtra(AlarmClock.EXTRA_MESSAGE, label)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        start(i)
        return String.format(Locale("tr"), "%02d:%02d için alarm kuruldu.", h, m)
    }

    private fun timer(sec: Int, label: String): String {
        if (sec <= 0) return "Süre geçersiz."
        val i = Intent(AlarmClock.ACTION_SET_TIMER)
            .putExtra(AlarmClock.EXTRA_LENGTH, sec)
            .putExtra(AlarmClock.EXTRA_MESSAGE, label)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        start(i)
        return "$sec saniyelik zamanlayıcı kuruldu."
    }

    private var swRunning = false
    private var swStart = 0L
    private var swAccum = 0L

    private fun swElapsed(): Long = swAccum + if (swRunning) SystemClock.elapsedRealtime() - swStart else 0L

    private fun fmt(ms: Long): String {
        val s = ms / 1000
        return "${s / 60} dakika ${s % 60} saniye"
    }

    private fun stopwatch(action: String): String = when (action) {
        "start" -> {
            if (!swRunning) {
                swRunning = true
                swStart = SystemClock.elapsedRealtime()
            }
            "Kronometre başladı."
        }
        "stop" -> {
            swAccum = swElapsed()
            swRunning = false
            "Kronometre durdu: ${fmt(swAccum)}."
        }
        "reset" -> {
            swRunning = false
            swAccum = 0L
            "Kronometre sıfırlandı."
        }
        else -> "Kronometre: ${fmt(swElapsed())}."
    }

    // ---------- sistem ----------

    private fun volume(i: JSONObject): String {
        val am = audio()
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val cur = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        val step = maxOf(1, max / 8)
        val target = if (i.has("percent")) {
            i.optInt("percent").coerceIn(0, 100) * max / 100
        } else when (i.optString("direction")) {
            "up" -> cur + step
            "down" -> cur - step
            "mute" -> 0
            else -> cur
        }
        val t = target.coerceIn(0, max)
        am.setStreamVolume(AudioManager.STREAM_MUSIC, t, AudioManager.FLAG_SHOW_UI)
        return "Ses seviyesi yüzde ${t * 100 / max} oldu."
    }

    private fun status(): String {
        val now = Date()
        val tr = Locale("tr")
        val date = SimpleDateFormat("d MMMM yyyy EEEE", tr).format(now)
        val time = SimpleDateFormat("HH:mm", tr).format(now)
        val b = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val lvl = b?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = b?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val st = b?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = st == BatteryManager.BATTERY_STATUS_CHARGING || st == BatteryManager.BATTERY_STATUS_FULL
        val temp = (b?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10
        val am = audio()
        val vol = am.getStreamVolume(AudioManager.STREAM_MUSIC) * 100 / maxOf(1, am.getStreamMaxVolume(AudioManager.STREAM_MUSIC))
        val speed = if (motion.hasFreshSpeed()) "${motion.speedKmh.toInt()} km/sa" else "GPS hızı yok"
        return "Saat $time, tarih $date. Pil yüzde ${if (lvl >= 0) lvl * 100 / scale else 0}" +
            (if (charging) " (şarjda)" else "") + ", pil sıcaklığı $temp derece. Medya sesi yüzde $vol. Hız: $speed."
    }

    private fun speed(): String =
        if (motion.hasFreshSpeed()) "Hız saatte ${motion.speedKmh.toInt()} kilometre."
        else "GPS hızı şu an alınamıyor."

    private fun trip(): String {
        val km = motion.tripMeters / 1000.0
        val mins = (System.currentTimeMillis() - motion.tripStart) / 60000
        return String.format(
            Locale("tr"),
            "Yolculuk: %.1f kilometre, %d dakika, en yüksek hız %d km/sa.",
            km, mins, motion.maxSpeed.toInt()
        )
    }

    private fun lean(action: String): String = when (action) {
        "calibrate" ->
            if (motion.calibrate()) "Kalibrasyon tamam, mevcut duruş dik konum olarak ayarlandı."
            else "Sensör verisi henüz yok, birkaç saniye sonra tekrar deneyin."
        "current" ->
            if (!motion.calibrated) "Henüz kalibre edilmedi."
            else "Şu anki yatış ${motion.lean.toInt()} derece."
        "max" ->
            if (!motion.calibrated) "Henüz kalibre edilmedi."
            else "Bu yolculuktaki en yüksek yatış ${motion.maxLean.toInt()} derece."
        else ->
            if (!motion.calibrated) "Henüz kalibre edilmedi."
            else if (motion.lastCornerPeak < 0f) "Henüz ölçülmüş bir viraj yok."
            else "Son virajda en fazla ${motion.lastCornerPeak.toInt()} derece yattınız."
    }

    // ---------- hava durumu ----------

    private fun wmo(c: Int): String = when (c) {
        0 -> "açık"
        1, 2, 3 -> "parçalı bulutlu"
        45, 48 -> "sisli"
        in 51..57 -> "çiseleyen yağmurlu"
        in 61..67 -> "yağmurlu"
        in 71..77 -> "karlı"
        in 80..82 -> "sağanak yağışlı"
        85, 86 -> "kar sağanaklı"
        in 95..99 -> "gök gürültülü fırtınalı"
        else -> "değişken"
    }

    private fun wx(lat: Double, lon: Double): String {
        val j = httpGetJson(
            "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
                "&current=temperature_2m,apparent_temperature,precipitation,weather_code,wind_speed_10m" +
                "&daily=precipitation_probability_max&timezone=auto&forecast_days=1"
        )
        val c = j.getJSONObject("current")
        val pp = j.optJSONObject("daily")?.optJSONArray("precipitation_probability_max")?.optInt(0, 0) ?: 0
        return "${Math.round(c.optDouble("temperature_2m"))} derece (hissedilen ${Math.round(c.optDouble("apparent_temperature"))}), " +
            "${wmo(c.optInt("weather_code"))}, rüzgâr saatte ${Math.round(c.optDouble("wind_speed_10m"))} kilometre, " +
            "bugün en yüksek yağış ihtimali yüzde $pp"
    }

    private fun geocode(place: String): Triple<Double, Double, String>? {
        val j = httpGetJson(
            "https://geocoding-api.open-meteo.com/v1/search?name=" + Uri.encode(place) + "&count=1&language=tr"
        )
        val r = j.optJSONArray("results")?.optJSONObject(0) ?: return null
        return Triple(r.getDouble("latitude"), r.getDouble("longitude"), r.optString("name"))
    }

    private fun weather(place: String): String {
        if (place.isBlank()) {
            val l = motion.best() ?: return "Konum alınamıyor."
            return "Bulunduğunuz yerde: " + wx(l.latitude, l.longitude)
        }
        val g = geocode(place) ?: return "'$place' konumu bulunamadı."
        return "${g.third}: " + wx(g.first, g.second)
    }

    private fun routeWeather(dest: String): String {
        val s = motion.best() ?: return "Konum alınamıyor."
        val g = geocode(dest) ?: return "'$dest' konumu bulunamadı."
        val midLat = (s.latitude + g.first) / 2
        val midLon = (s.longitude + g.second) / 2
        return "Başlangıç: " + wx(s.latitude, s.longitude) + ". " +
            "Yolun ortası: " + wx(midLat, midLon) + ". " +
            "Varış (${g.third}): " + wx(g.first, g.second) + ". " +
            "Not: yol ve trafik durumu için Google Haritalar'ın canlı trafik verisi kullanılabilir."
    }

    // ---------- ekran kontrolü ----------

    private fun screen(action: String, text: String): String {
        val s = AccessService.instance
            ?: return "Erişilebilirlik servisi kapalı. Ayarlardan 'Şakir erişim' servisini açmanız gerekiyor."
        return when (action) {
            "read" -> s.readScreen()
            "click" -> s.clickText(text)
            else -> s.global(action)
        }
    }
}
