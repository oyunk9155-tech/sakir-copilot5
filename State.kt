package com.sakir.copilot

enum class Mode { IDLE, LISTENING, THINKING, SPEAKING }

/** Servis ile arayüz arasındaki ortak durum. */
object JarvisState {
    @Volatile var mode: Mode = Mode.IDLE
    @Volatile var level: Float = 0f      // mikrofon seviyesi 0..1 (reaktör animasyonu için)
    @Volatile var heard: String = ""
    @Volatile var reply: String = ""
    @Volatile var running: Boolean = false
    @Volatile var speed: Float = 0f      // km/sa
    @Volatile var lean: Float = 0f       // derece
}
