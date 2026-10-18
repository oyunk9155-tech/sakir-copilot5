package com.sakir.copilot

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Şakir'in beyni. Üç sağlayıcıdan biri seçilebilir:
 *  - gemini: Google Gemini (OpenAI uyumlu uç nokta, ücretsiz katman var)
 *  - openai: OpenAI / ChatGPT API
 *  - claude: Anthropic Claude
 */
class Brain(private val mem: Memory, private val tools: Tools, private val motion: Motion) {

    companion object {
        const val CLAUDE_URL = "https://api.anthropic.com/v1/messages"
        const val CLAUDE_MODEL = "claude-sonnet-5-5"
        const val GEMINI_URL = "https://generativelanguage.googleapis.com/v1beta/openai/chat/completions"
        const val GEMINI_MODEL = "gemini-3.8-flash"
        const val OPENAI_URL = "https://api.openai.com/v1/chat/completions"
        const val OPENAI_MODEL = "gpt-4o-mini"
    }

    private class Resp(val code: Int, val json: JSONObject?)

    private fun systemPrompt(webSearch: Boolean): String {
        val now = SimpleDateFormat("d MMMM yyyy EEEE HH:mm", Locale("tr")).format(Date())
        val loc = motion.best()
        val where = if (loc != null) {
            "enlem ${"%.4f".format(Locale.US, loc.latitude)}, boylam ${"%.4f".format(Locale.US, loc.longitude)}" +
                (if (motion.hasFreshSpeed()) ", hız ${motion.speedKmh.toInt()} km/sa" else "")
        } else "bilinmiyor"
        val places = mem.places()
        val placeNames = ArrayList<String>()
        for (k in places.keys()) placeNames.add(places.getJSONObject(k).optString("name"))
        val web = if (webSearch) {
            "Güncel bilgi (haber, fiyat, skor vb.) için web_search aracını kullan."
        } else {
            "İnternette arama yapma aracın yok; güncel bir bilgiyi bilmiyorsan uydurma, bilmediğini söyle (hava durumu için weather aracı var)."
        }
        return """
Sen "Şakir"sin: kullanıcının telefonundaki sesli yapay zeka yardımcısı, Iron Man'deki Jarvis tarzında. Kullanıcıya "efendim" diye hitap et; kibar, zeki, hafif esprili ve güven veren bir tonun var.
Kullanıcı çoğunlukla motosiklet sürüyor ve kaskıyla seninle konuşuyor. Cevapların sesli okunacak: kısa ve akıcı konuş (çoğu zaman 1-3 cümle). Markdown, liste, emoji ve bağlantı kullanma. Rakamları ve birimleri konuşma diliyle söyle.
Kullanıcı yorulduğunu, acıktığını, benzininin azaldığını söylerse yalnızca tavsiye verme, çözüm üret: gerekirse araçla en yakın akaryakıt istasyonu veya dinlenme yeri için rota başlat ve ne yaptığını tek cümleyle söyle.
Eylem gerektiren her istekte araçları kullan. Birden fazla komut varsa hepsini sırayla uygula, sonunda yaptıklarını doğal dille özetle. Bir araç başarısız olursa bunu dürüstçe söyle, olmayan bir şeyi yapmış gibi davranma.
Yatış açısını söylerken makul ölçüde takdir et (örneğin "Süperdiniz efendim"), ama sürüş güvenliğini de ima et.
$web
Hafızanı sohbet akışını bozmadan doğal biçimde kullan. Kullanıcı bir şeyi hatırlamanı isterse remember aracıyla kaydet; "evin konumunu tut" gibi bir konum isterse save_place aracını kullan.
Şu an: $now
Konum: $where
Bilinen bilgiler: ${mem.facts()}
Kayıtlı yerler: ${if (placeNames.isEmpty()) "yok" else placeNames.joinToString(", ")}
""".trim()
    }

    /** Bloklayıcıdır: arka plan iş parçacığında çağrılmalı. */
    fun ask(userText: String): String {
        val key = mem.setting("ai_key")
        if (key.isBlank()) return "Efendim, henüz bir API anahtarı girmediniz. Ayarlar bölümünden ekleyebilirsiniz."
        val provider = mem.setting("provider").ifBlank { "gemini" }
        return if (provider == "claude") askClaude(userText, key) else askOpenAiStyle(userText, key, provider)
    }

    // ---------- Claude ----------

    private fun askClaude(userText: String, key: String): String {
        val model = mem.setting("ai_model").ifBlank { CLAUDE_MODEL }
        val msgs = JSONArray()
        val hist = mem.history()
        for (i in 0 until hist.length()) msgs.put(hist.getJSONObject(i))
        msgs.put(JSONObject().put("role", "user").put("content", userText))

        val toolDefs = tools.definitions()
        var finalText = ""
        for (round in 0 until 8) {
            val body = JSONObject()
                .put("model", model)
                .put("max_tokens", 800)
                .put("system", systemPrompt(true))
                .put("tools", toolDefs)
                .put("messages", msgs)
            val headers = mapOf("x-api-key" to key, "anthropic-version" to "2023-06-01")
            val r = post(CLAUDE_URL, headers, body) ?: return "Efendim, internet bağlantısı kurulamadı."
            val resp = r.json
            if (r.code >= 400 || resp == null) return "Efendim, bir sorun var: " + errorText(r)
            val content = resp.optJSONArray("content") ?: return "Efendim, boş bir yanıt aldım."
            msgs.put(JSONObject().put("role", "assistant").put("content", content))

            val text = StringBuilder()
            val results = JSONArray()
            for (i in 0 until content.length()) {
                val b = content.getJSONObject(i)
                when (b.optString("type")) {
                    "text" -> text.append(b.optString("text"))
                    "tool_use" -> {
                        val out = try {
                            tools.run(b.getString("name"), b.optJSONObject("input") ?: JSONObject())
                        } catch (e: Exception) {
                            "Hata: ${e.message}"
                        }
                        results.put(
                            JSONObject()
                                .put("type", "tool_result")
                                .put("tool_use_id", b.getString("id"))
                                .put("content", out)
                        )
                    }
                }
            }
            val stop = resp.optString("stop_reason")
            if (stop == "tool_use" && results.length() > 0) {
                msgs.put(JSONObject().put("role", "user").put("content", results))
                continue
            }
            if (stop == "pause_turn") continue
            finalText = text.toString().trim()
            break
        }
        if (finalText.isBlank()) finalText = "Komutları uyguladım efendim."
        mem.addTurn(userText, finalText)
        return finalText
    }

    // ---------- Gemini ve OpenAI (OpenAI uyumlu sohbet biçimi) ----------

    private fun askOpenAiStyle(userText: String, key: String, provider: String): String {
        val gemini = provider == "gemini"
        val url = if (gemini) GEMINI_URL else OPENAI_URL
        val model = mem.setting("ai_model").ifBlank { if (gemini) GEMINI_MODEL else OPENAI_MODEL }

        val msgs = JSONArray()
        msgs.put(JSONObject().put("role", "system").put("content", systemPrompt(false)))
        val hist = mem.history()
        for (i in 0 until hist.length()) msgs.put(hist.getJSONObject(i))
        msgs.put(JSONObject().put("role", "user").put("content", userText))

        val toolDefs = tools.openAiTools()
        val headers = mapOf("Authorization" to "Bearer $key")
        var finalText = ""
        for (round in 0 until 8) {
            val body = JSONObject()
                .put("model", model)
                .put("messages", msgs)
                .put("tools", toolDefs)
                .put("tool_choice", "auto")
            val r = post(url, headers, body) ?: return "Efendim, internet bağlantısı kurulamadı."
            val j = r.json
            if (r.code >= 400 || j == null) return "Efendim, bir sorun var: " + errorText(r)
            val message = j.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")
                ?: return "Efendim, boş bir yanıt aldım."

            val calls = message.optJSONArray("tool_calls")
            if (calls != null && calls.length() > 0) {
                // Yanıtı olduğu gibi geri ekle (Gemini 3'ün düşünce imzaları bu şekilde korunur).
                msgs.put(message)
                for (i in 0 until calls.length()) {
                    val c = calls.getJSONObject(i)
                    val fn = c.optJSONObject("function") ?: continue
                    val name = fn.optString("name")
                    val raw = fn.optString("arguments", "{}").ifBlank { "{}" }
                    val args = try {
                        JSONObject(raw)
                    } catch (e: Exception) {
                        JSONObject()
                    }
                    val out = try {
                        tools.run(name, args)
                    } catch (e: Exception) {
                        "Hata: ${e.message}"
                    }
                    val tm = JSONObject()
                        .put("role", "tool")
                        .put("tool_call_id", c.optString("id"))
                        .put("content", out)
                    if (gemini) tm.put("name", name)
                    msgs.put(tm)
                }
                continue
            }
            finalText = if (message.isNull("content")) "" else message.optString("content").trim()
            break
        }
        if (finalText.isBlank()) finalText = "Komutları uyguladım efendim."
        mem.addTurn(userText, finalText)
        return finalText
    }

    // ---------- ortak ağ yardımcıları ----------

    private fun post(url: String, headers: Map<String, String>, body: JSONObject): Resp? {
        return try {
            val c = URL(url).openConnection() as HttpURLConnection
            c.requestMethod = "POST"
            c.connectTimeout = 15000
            c.readTimeout = 60000
            c.doOutput = true
            c.setRequestProperty("content-type", "application/json")
            for ((k, v) in headers) c.setRequestProperty(k, v)
            c.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = c.responseCode
            val stream = if (code < 400) c.inputStream else c.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            Resp(code, parse(text))
        } catch (e: Exception) {
            null
        }
    }

    /** Bazı servisler hata gövdesini dizi içinde döndürür; ikisini de kabul eder. */
    private fun parse(t: String): JSONObject? = try {
        val s = t.trim()
        if (s.startsWith("[")) JSONArray(s).optJSONObject(0) else JSONObject(s)
    } catch (e: Exception) {
        null
    }

    private fun errorText(r: Resp): String {
        if (r.code == 429) return "istek sınırı doldu, biraz sonra tekrar deneyin (ücretsiz katmanın günlük veya dakikalık limiti olabilir)."
        if (r.code == 401 || r.code == 403) return "API anahtarı geçersiz veya yetkisiz. Ayarlardan kontrol edin."
        val m = r.json?.optJSONObject("error")?.optString("message")
        return if (!m.isNullOrBlank()) m else "sunucu hatası (kod ${r.code}). Model adı yanlış olabilir, Ayarlar'dan kontrol edin."
    }
}
