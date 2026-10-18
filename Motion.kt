package com.sakir.copilot

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import kotlin.math.acos
import kotlin.math.max

/**
 * GPS (hız, yol bilgisi) ve gyro tabanlı yatış açısı.
 * Yatış açısı için telefonun motora sabit takılı olması ve motor dikken "kalibre et" denmesi gerekir.
 */
class Motion(private val ctx: Context) : LocationListener, SensorEventListener {
    private val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    @Volatile var last: Location? = null
    @Volatile var speedKmh = 0f
    @Volatile var tripMeters = 0.0
    @Volatile var maxSpeed = 0f
    val tripStart = System.currentTimeMillis()

    // Yatış
    private val rot = FloatArray(9)
    private val latestUp = FloatArray(3)
    private val up0 = FloatArray(3)
    private var haveUp = false
    @Volatile var calibrated = false
    @Volatile var lean = 0f
    @Volatile var lastCornerPeak = -1f
    @Volatile var maxLean = 0f
    private var inCorner = false
    private var curPeak = 0f

    fun start() {
        for (p in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
            try {
                lm.requestLocationUpdates(p, 1000L, 0f, this, Looper.getMainLooper())
            } catch (e: SecurityException) {
            } catch (e: IllegalArgumentException) {
            }
        }
        sm.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)?.let {
            sm.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
    }

    fun stop() {
        try {
            lm.removeUpdates(this)
        } catch (e: SecurityException) {
        }
        sm.unregisterListener(this)
    }

    /** En iyi bilinen konum. */
    fun best(): Location? {
        val l = last
        if (l != null && System.currentTimeMillis() - l.time < 10 * 60 * 1000) return l
        return try {
            lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
        } catch (e: SecurityException) {
            null
        }
    }

    /** GPS hızı güncel mi (son 8 sn)? */
    fun hasFreshSpeed(): Boolean {
        val l = last ?: return false
        return l.provider == LocationManager.GPS_PROVIDER && System.currentTimeMillis() - l.time < 8000
    }

    fun calibrate(): Boolean {
        if (!haveUp) return false
        for (i in 0..2) up0[i] = latestUp[i]
        calibrated = true
        lean = 0f
        lastCornerPeak = -1f
        maxLean = 0f
        inCorner = false
        curPeak = 0f
        return true
    }

    override fun onLocationChanged(l: Location) {
        val prev = last
        if (l.provider == LocationManager.GPS_PROVIDER) {
            if (prev != null && l.speed > 1f) tripMeters += prev.distanceTo(l).toDouble()
            speedKmh = l.speed * 3.6f
            maxSpeed = max(maxSpeed, speedKmh)
            JarvisState.speed = speedKmh
            // Düz yolda hızlanınca motor dik kabul edilir: ilk seferde otomatik kalibrasyon.
            if (!calibrated && speedKmh > 40f) calibrate()
        }
        last = l
    }

    override fun onSensorChanged(e: SensorEvent) {
        if (e.sensor.type != Sensor.TYPE_GAME_ROTATION_VECTOR) return
        SensorManager.getRotationMatrixFromVector(rot, e.values)
        // Dünyanın "yukarı" yönünün telefon eksenlerindeki karşılığı
        latestUp[0] = rot[6]
        latestUp[1] = rot[7]
        latestUp[2] = rot[8]
        haveUp = true
        if (!calibrated) return
        var dot = latestUp[0] * up0[0] + latestUp[1] * up0[1] + latestUp[2] * up0[2]
        if (dot > 1f) dot = 1f
        if (dot < -1f) dot = -1f
        lean = Math.toDegrees(acos(dot.toDouble())).toFloat()
        JarvisState.lean = lean
        if (lean > 10f) {
            inCorner = true
            if (lean > curPeak) curPeak = lean
        } else if (lean < 6f && inCorner) {
            inCorner = false
            lastCornerPeak = curPeak
            if (curPeak > maxLean) maxLean = curPeak
            curPeak = 0f
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {}

    @Deprecated("Eski API'ler için gerekli")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {
    }
}
