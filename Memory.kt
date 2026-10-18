package com.sakir.copilot

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Kalıcı hafıza: ayarlar, öğrenilen bilgiler, kayıtlı yerler ve son konuşmalar. */
class Memory(ctx: Context) {
    private val sp = ctx.getSharedPreferences("sakir", Context.MODE_PRIVATE)

    fun setting(k: String): String = sp.getString("set_$k", "") ?: ""
    fun putSetting(k: String, v: String) {
        sp.edit().putString("set_$k", v.trim()).apply()
    }

    fun facts(): JSONObject = try {
        JSONObject(sp.getString("facts", "{}") ?: "{}")
    } catch (e: Exception) {
        JSONObject()
    }

    fun remember(k: String, v: String) {
        val f = facts()
        f.put(k, v)
        sp.edit().putString("facts", f.toString()).apply()
    }

    fun forget(k: String): Boolean {
        val f = facts()
        val had = f.has(k)
        f.remove(k)
        sp.edit().putString("facts", f.toString()).apply()
        return had
    }

    fun places(): JSONObject = try {
        JSONObject(sp.getString("places", "{}") ?: "{}")
    } catch (e: Exception) {
        JSONObject()
    }

    fun savePlace(name: String, lat: Double, lon: Double) {
        val p = places()
        p.put(norm(name), JSONObject().put("name", name).put("lat", lat).put("lon", lon))
        sp.edit().putString("places", p.toString()).apply()
    }

    fun place(name: String): JSONObject? = places().optJSONObject(norm(name))

    fun history(): JSONArray = try {
        JSONArray(sp.getString("history", "[]") ?: "[]")
    } catch (e: Exception) {
        JSONArray()
    }

    /** Son 12 konuşma turunu (kullanıcı + asistan metni) saklar. */
    fun addTurn(user: String, assistant: String) {
        val h = history()
        h.put(JSONObject().put("role", "user").put("content", user))
        h.put(JSONObject().put("role", "assistant").put("content", assistant))
        val out = JSONArray()
        val start = maxOf(0, h.length() - 24)
        for (i in start until h.length()) out.put(h.get(i))
        sp.edit().putString("history", out.toString()).apply()
    }
}
