package com.stridesense.app

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A deliberately simple pedestrian dead-reckoning fallback. Step length is a
 * fixed estimate and heading comes from the phone's rotation-vector sensor.
 */
class PdrTracker(
    context: Context,
    private val onStep: (xMeters: Float, yMeters: Float, count: Int) -> Unit
) : SensorEventListener {
    companion object {
        const val DEFAULT_STRIDE_METERS = 0.72f
    }

    private val manager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val stepSensor = manager.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)
    private val rotationSensor = manager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val accelerometer = manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private var usingStepSensor = false
    private var running = false
    private var stepCount = 0
    private var headingRadians = 0f
    private var xMeters = 0f
    private var yMeters = 0f
    private var gravityEstimate = SensorManager.GRAVITY_EARTH
    private var accelerometerArmed = true
    private var lastFallbackStepAt = 0L

    fun start() {
        if (running) return
        running = true
        usingStepSensor = stepSensor?.let { manager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) } == true
        rotationSensor?.let { manager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
        if (!usingStepSensor) accelerometer?.let { manager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
    }

    fun stop() {
        running = false
        manager.unregisterListener(this)
    }

    fun reset() {
        stepCount = 0
        xMeters = 0f
        yMeters = 0f
        gravityEstimate = SensorManager.GRAVITY_EARTH
        accelerometerArmed = true
        lastFallbackStepAt = 0L
    }

    val steps: Int get() = stepCount
    val hasStepSensor: Boolean get() = usingStepSensor

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_ROTATION_VECTOR -> {
                val matrix = FloatArray(9)
                val orientation = FloatArray(3)
                SensorManager.getRotationMatrixFromVector(matrix, event.values)
                SensorManager.getOrientation(matrix, orientation)
                headingRadians = orientation[0]
            }
            Sensor.TYPE_STEP_DETECTOR -> addStep()
            Sensor.TYPE_ACCELEROMETER -> if (!usingStepSensor) detectFallbackStep(event)
        }
    }

    private fun detectFallbackStep(event: SensorEvent) {
        val magnitude = sqrt(event.values[0] * event.values[0] + event.values[1] * event.values[1] + event.values[2] * event.values[2])
        gravityEstimate = gravityEstimate * 0.92f + magnitude * 0.08f
        val dynamic = abs(magnitude - gravityEstimate)
        val now = event.timestamp / 1_000_000L
        if (dynamic < 0.38f) accelerometerArmed = true
        if (dynamic > 1.25f && accelerometerArmed && now - lastFallbackStepAt > 360L) {
            accelerometerArmed = false
            lastFallbackStepAt = now
            addStep()
        }
    }

    private fun addStep() {
        if (!running) return
        stepCount += 1
        xMeters += sin(headingRadians) * DEFAULT_STRIDE_METERS
        yMeters += cos(headingRadians) * DEFAULT_STRIDE_METERS
        onStep(xMeters, yMeters, stepCount)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
