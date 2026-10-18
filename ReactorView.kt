package com.sakir.copilot

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.view.View
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** Konuşmaya tepki veren, hareketli arc reaktörü arayüzü. */
class ReactorView(ctx: Context) : View(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private var t = 0f
    private var rot = 0f
    private var smooth = 0f

    private fun a(color: Int, alpha: Int): Int = (color and 0x00FFFFFF) or (alpha.coerceIn(0, 255) shl 24)

    override fun onDraw(c: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = min(width, height) / 2f * 0.92f
        val mode = JarvisState.mode

        val target = when (mode) {
            Mode.SPEAKING -> 0.55f + 0.45f * abs(sin(t * 7f))
            Mode.LISTENING -> 0.3f + JarvisState.level * 0.7f
            Mode.THINKING -> 0.65f
            Mode.IDLE -> if (JarvisState.running) 0.14f + 0.08f * sin(t * 1.6f) else 0.04f
        }
        smooth += (target - smooth) * 0.15f
        val color = if (mode == Mode.THINKING) 0xFFFFB347.toInt() else 0xFF4FE3FF.toInt()
        val speed = if (mode == Mode.THINKING) 3.2f else 1f + smooth

        t += 0.016f
        rot += 0.016f * 40f * speed

        // Parıltı
        val coreR = r * (0.2f + 0.08f * smooth)
        p.style = Paint.Style.FILL
        p.shader = RadialGradient(
            cx, cy, coreR * 3f,
            intArrayOf(a(color, 110 + (110 * smooth).toInt()), a(color, 0)),
            null, Shader.TileMode.CLAMP
        )
        c.drawCircle(cx, cy, coreR * 3f, p)
        p.shader = null

        // Çekirdek
        p.color = a(0xFFFFFFFF.toInt(), 235)
        c.drawCircle(cx, cy, coreR * 0.55f, p)
        p.style = Paint.Style.STROKE
        p.strokeWidth = 4f
        p.color = a(color, 255)
        c.drawCircle(cx, cy, coreR, p)

        // Dönen halkalar
        for (i in 0..3) {
            val rr = r * (0.42f + 0.17f * i)
            p.strokeWidth = 3f + i * 1.5f
            p.color = a(color, 170 - i * 25 + (60 * smooth).toInt())
            val dir = if (i % 2 == 0) 1f else -1f
            c.save()
            c.rotate(rot * (0.6f + 0.5f * i) * dir, cx, cy)
            rect.set(cx - rr, cy - rr, cx + rr, cy + rr)
            val segs = 3 + i
            val sweep = 360f / segs
            for (s in 0 until segs) c.drawArc(rect, s * sweep, sweep * 0.62f, false, p)
            c.restore()
        }

        // Dış cetvel çizgileri
        c.save()
        c.rotate(rot * 0.15f, cx, cy)
        p.strokeWidth = 2f
        for (i in 0 until 72) {
            val ang = Math.toRadians(i * 5.0)
            val long = i % 6 == 0
            val r1 = r * 0.98f
            val r2 = r * (if (long) 0.90f else 0.94f)
            p.color = a(color, if (long) 220 else 110)
            c.drawLine(
                cx + (cos(ang) * r1).toFloat(), cy + (sin(ang) * r1).toFloat(),
                cx + (cos(ang) * r2).toFloat(), cy + (sin(ang) * r2).toFloat(), p
            )
        }
        c.restore()

        postInvalidateOnAnimation()
    }
}
