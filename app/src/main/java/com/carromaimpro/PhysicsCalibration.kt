package com.carromaimpro

import kotlin.math.pow

/**
 * Learns coarse rolling drag and cushion restitution from consecutive screen
 * observations. Values are intentionally conservative; this is online
 * adaptation, not a substitute for a measured game-physics dataset.
 */
object PhysicsCalibration {
    @Volatile var frictionPerFrame: Float = 0.992f
        private set
    @Volatile var cushionRestitution: Float = 0.86f
        private set

    private var dragSamples = 0
    private var bounceSamples = 0

    @Synchronized
    fun observe(previousSpeed: Float, currentSpeed: Float, elapsedSeconds: Float, wallBounce: Boolean) {
        if (previousSpeed < 18f || currentSpeed < 5f || elapsedSeconds !in 0.008f..0.8f) return
        if (wallBounce) {
            val ratio = (currentSpeed / previousSpeed).coerceIn(0.45f, 1.05f)
            cushionRestitution = if (bounceSamples == 0) ratio
                else cushionRestitution * 0.8f + ratio * 0.2f
            cushionRestitution = cushionRestitution.coerceIn(0.55f, 0.98f)
            bounceSamples++
            return
        }
        // Ignore acceleration/collisions; estimate only smooth rolling slowdown.
        if (currentSpeed >= previousSpeed * 0.985f) return
        val perFrame = (currentSpeed / previousSpeed).toDouble()
            .pow(1.0 / (elapsedSeconds * 60.0)).toFloat()
            .coerceIn(0.965f, 0.99995f)
        frictionPerFrame = if (dragSamples == 0) perFrame
            else frictionPerFrame * 0.9f + perFrame * 0.1f
        dragSamples++
    }

    @Synchronized
    fun status(): String = "معايرة الحركة: ${dragSamples} عينات، ارتداد: ${bounceSamples} عينات"
}
