package com.sakir.copilot

import android.content.Context
import android.net.Uri
import android.provider.ContactsContract
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.Normalizer
import java.util.Locale

/** Türkçe karakterleri sadeleştirip küçük harfe çevirir (eşleştirme için). */
fun norm(s: String): String {
    val t = s.lowercase(Locale("tr", "TR"))
        .replace('ı', 'i').replace('ş', 's').replace('ğ', 'g')
        .replace('ü', 'u').replace('ö', 'o').replace('ç', 'c')
    return Normalizer.normalize(t, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
}

/** Sesli okunacak metinden markdown işaretlerini temizler. */
fun cleanForSpeech(s: String): String =
    s.replace(Regex("[*#_`>]"), "").replace(Regex("\\s+"), " ").trim()

/** Rehberde isme göre arar. Dönen çift: (isim, numara). */
fun findContactNumber(ctx: Context, name: String): Pair<String, String>? {
    val q = norm(name)
    if (q.isEmpty()) return null
    var best: Pair<String, String>? = null
    var bestScore = 0
    try {
        val cur = ctx.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER
            ),
            null, null, null
        ) ?: return null
        cur.use {
            while (it.moveToNext()) {
                val n = it.getString(0) ?: continue
                val num = it.getString(1) ?: continue
                val nn = norm(n)
                val score = when {
                    nn == q -> 4
                    nn.split(" ").any { w -> w == q } -> 3
                    nn.startsWith(q) -> 2
                    nn.contains(q) -> 1
                    else -> 0
                }
                if (score > bestScore) {
                    bestScore = score
                    best = Pair(n, num)
                }
            }
        }
    } catch (e: SecurityException) {
        return null
    }
    return best
}

/** Gelen numaradan rehberdeki ismi bulur. */
fun contactNameForNumber(ctx: Context, number: String): String? {
    return try {
        val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
        ctx.contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)
            ?.use { if (it.moveToFirst()) it.getString(0) else null }
    } catch (e: Exception) {
        null
    }
}

/** WhatsApp için uluslararası numara biçimi (Türkiye varsayımıyla). */
fun waNumber(raw: String): String {
    var d = raw.filter { it.isDigit() }
    if (d.startsWith("00")) d = d.substring(2)
    else if (d.startsWith("0")) d = "90" + d.substring(1)
    else if (d.length == 10 && d.startsWith("5")) d = "90$d"
    return d
}

fun httpGetJson(url: String): JSONObject {
    val c = URL(url).openConnection() as HttpURLConnection
    c.connectTimeout = 10000
    c.readTimeout = 15000
    val stream = if (c.responseCode < 400) c.inputStream else c.errorStream
    return JSONObject(stream.bufferedReader().use { it.readText() })
}
