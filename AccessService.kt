package com.sakir.copilot

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Erişilebilirlik servisi: WhatsApp'ta "gönder"e basar, YouTube'da ilk videoyu açar,
 * ekranı okur ve ekrandaki öğelere dokunur. Kullanıcı Ayarlar'dan açmalıdır.
 */
class AccessService : AccessibilityService() {

    companion object {
        @Volatile var instance: AccessService? = null
        @Volatile var pendingWhatsappSend = 0L
        @Volatile var pendingYoutubeClick = 0L
    }

    override fun onServiceConnected() {
        instance = this
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onInterrupt() {}

    override fun onAccessibilityEvent(e: AccessibilityEvent?) {
        val pkg = e?.packageName?.toString() ?: return
        val now = System.currentTimeMillis()
        if (pkg == "com.whatsapp" && now - pendingWhatsappSend < 15000) {
            val root = rootInActiveWindow ?: return
            if (clickWhatsappSend(root)) pendingWhatsappSend = 0
        } else if (pkg == "com.google.android.youtube" && now - pendingYoutubeClick < 15000) {
            val root = rootInActiveWindow ?: return
            if (clickFirstVideo(root)) pendingYoutubeClick = 0
        }
    }

    private fun clickWhatsappSend(root: AccessibilityNodeInfo): Boolean {
        var nodes = root.findAccessibilityNodeInfosByViewId("com.whatsapp:id/send")
        if (nodes.isNullOrEmpty()) nodes = root.findAccessibilityNodeInfosByText("Gönder")
        if (nodes.isNullOrEmpty()) nodes = root.findAccessibilityNodeInfosByText("Send")
        val n = nodes?.firstOrNull() ?: return false
        return clickUp(n)
    }

    private fun clickFirstVideo(root: AccessibilityNodeInfo): Boolean {
        val n = findFirst(root) { node ->
            val d = node.contentDescription?.toString()?.lowercase() ?: return@findFirst false
            d.length > 20 && (d.contains("dakika") || d.contains("saniye") || d.contains("minute") || d.contains("second"))
        } ?: return false
        return clickUp(n)
    }

    private fun findFirst(n: AccessibilityNodeInfo, pred: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        if (pred(n)) return n
        for (i in 0 until n.childCount) {
            val c = n.getChild(i) ?: continue
            val r = findFirst(c, pred)
            if (r != null) return r
        }
        return null
    }

    /** Düğüm tıklanabilir değilse tıklanabilir ilk ebeveynine basar. */
    private fun clickUp(start: AccessibilityNodeInfo): Boolean {
        var n: AccessibilityNodeInfo? = start
        while (n != null) {
            if (n.isClickable) return n.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            n = n.parent
        }
        return false
    }

    fun readScreen(): String {
        val root = rootInActiveWindow ?: return "Ekran okunamadı."
        val out = ArrayList<String>()
        fun walk(n: AccessibilityNodeInfo) {
            if (out.size >= 60) return
            val t = n.text?.toString() ?: n.contentDescription?.toString()
            if (!t.isNullOrBlank()) out.add(t.trim())
            for (i in 0 until n.childCount) n.getChild(i)?.let { walk(it) }
        }
        walk(root)
        return if (out.isEmpty()) "Ekranda okunabilir metin yok." else out.joinToString("; ")
    }

    fun clickText(text: String): String {
        val root = rootInActiveWindow ?: return "Ekran okunamadı."
        val n = root.findAccessibilityNodeInfosByText(text)?.firstOrNull() ?: return "Ekranda '$text' bulunamadı."
        return if (clickUp(n)) "'$text' öğesine dokunuldu." else "'$text' öğesine dokunulamadı."
    }

    fun global(action: String): String {
        val code = when (action) {
            "back" -> GLOBAL_ACTION_BACK
            "home" -> GLOBAL_ACTION_HOME
            "recents" -> GLOBAL_ACTION_RECENTS
            else -> return "Bilinmeyen eylem."
        }
        performGlobalAction(code)
        return "Tamam."
    }
}
