package com.stridesense.app;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;

/** Low-cost fallback only. Its distance is estimated from step length and its heading can drift. */
final class PdrTracker implements SensorEventListener {
    interface StepCallback { void onStep(double x, double y, int count); }
    private final SensorManager sensors;
    private final Sensor stepDetector;
    private final Sensor rotationVector;
    private final Sensor accelerometer;
    private final Context context;
    private final StepCallback callback;
    private boolean running;
    private boolean usingStepDetector;
    private int steps;
    private double x, y, heading, headingOrigin;
    private float gravity = SensorManager.GRAVITY_EARTH;
    private boolean armed = true;
    private boolean hasHeading;
    private long lastFallbackStep;
    private float strideMeters = 0.72f;

    PdrTracker(Context context, StepCallback callback) {
        this.context = context.getApplicationContext();
        this.callback = callback;
        sensors = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
        stepDetector = sensors.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR);
        rotationVector = sensors.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
        accelerometer = sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        strideMeters = this.context.getSharedPreferences("settings", Context.MODE_PRIVATE).getFloat("stride_m", 0.72f);
    }

    void start() {
        if (running) return;
        running = true;
        hasHeading = false;
        usingStepDetector = stepDetector != null && sensors.registerListener(this, stepDetector, SensorManager.SENSOR_DELAY_NORMAL);
        if (rotationVector != null) sensors.registerListener(this, rotationVector, SensorManager.SENSOR_DELAY_GAME);
        if (!usingStepDetector && accelerometer != null) sensors.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_GAME);
        headingOrigin = heading;
    }

    void stop() { running = false; sensors.unregisterListener(this); }
    void reset() { steps = 0; x = 0; y = 0; gravity = SensorManager.GRAVITY_EARTH; armed = true; lastFallbackStep = 0; hasHeading = false; }
    int getSteps() { return steps; }
    float getStrideMeters() { return strideMeters; }
    boolean hasStepSensor() { return stepDetector != null; }
    boolean hasHeadingSensor() { return rotationVector != null; }

    void setStrideMeters(float meters) {
        strideMeters = Math.max(0.35f, Math.min(1.30f, meters));
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().putFloat("stride_m", strideMeters).apply();
    }

    @Override public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() == Sensor.TYPE_ROTATION_VECTOR) {
            float[] matrix = new float[9], orientation = new float[3];
            SensorManager.getRotationMatrixFromVector(matrix, event.values);
            SensorManager.getOrientation(matrix, orientation);
            heading = orientation[0];
            if (!hasHeading) { headingOrigin = heading; hasHeading = true; }
        } else if (event.sensor.getType() == Sensor.TYPE_STEP_DETECTOR) {
            addStep();
        } else if (event.sensor.getType() == Sensor.TYPE_ACCELEROMETER && !usingStepDetector) {
            detectFallbackStep(event);
        }
    }

    private void detectFallbackStep(SensorEvent event) {
        double magnitude = Math.sqrt(event.values[0] * event.values[0] + event.values[1] * event.values[1] + event.values[2] * event.values[2]);
        gravity = gravity * 0.92f + (float) magnitude * 0.08f;
        double dynamic = Math.abs(magnitude - gravity);
        long now = event.timestamp / 1_000_000L;
        if (dynamic < 0.38) armed = true;
        if (dynamic > 1.25 && armed && now - lastFallbackStep > 360) {
            armed = false;
            lastFallbackStep = now;
            addStep();
        }
    }

    private void addStep() {
        if (!running) return;
        steps++;
        double relativeHeading = normalize(heading - headingOrigin);
        x += Math.sin(relativeHeading) * strideMeters;
        y += Math.cos(relativeHeading) * strideMeters;
        callback.onStep(x, y, steps);
    }

    private static double normalize(double angle) {
        while (angle > Math.PI) angle -= Math.PI * 2;
        while (angle < -Math.PI) angle += Math.PI * 2;
        return angle;
    }

    @Override public void onAccuracyChanged(Sensor sensor, int accuracy) { }
}
