package com.stridesense.app

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Config
import com.google.ar.core.Session
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {
    companion object {
        private const val CAMERA_REQUEST = 40
        private const val EXPORT_REQUEST = 41
    }

    private val ink = Color.rgb(10, 16, 22)
    private val surface = Color.rgb(18, 27, 36)
    private val surfaceHigh = Color.rgb(25, 38, 50)
    private val mint = Color.rgb(84, 226, 189)
    private val blue = Color.rgb(131, 169, 255)
    private val white = Color.rgb(240, 245, 248)
    private val muted = Color.rgb(152, 168, 181)
    private val handler = Handler(Looper.getMainLooper())

    private lateinit var previewBox: FrameLayout
    private lateinit var arPreview: ArCameraPreview
    private lateinit var fallbackPreview: FallbackCameraPreview
    private lateinit var trajectoryView: TrajectoryView
    private lateinit var cameraStatus: TextView
    private lateinit var statusLine: TextView
    private lateinit var timerValue: TextView
    private lateinit var stepsValue: TextView
    private lateinit var distanceValue: TextView
    private lateinit var modeValue: TextView
    private lateinit var startButton: TextView
    private lateinit var stopButton: TextView
    private lateinit var saveButton: TextView
    private lateinit var loadButton: TextView

    private lateinit var pdrTracker: PdrTracker
    private var arSession: Session? = null
    private var currentSession: MotionSession? = null
    private var recording = false
    private var arOriginX: Float? = null
    private var arOriginZ: Float? = null
    private var arTracking = false
    private var availabilityCheckInFlight = false
    private var installRequested = true
    private var fallbackActive = false
    private var activityResumed = false
    private var exportText = ""
    private var exportMimeType = "application/json"
    private var exportExtension = "json"

    private val ticker = object : Runnable {
        override fun run() {
            refreshStats()
            handler.postDelayed(this, 500L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = ink
        window.navigationBarColor = ink
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        pdrTracker = PdrTracker(this) { x, y, steps -> onPdrStep(x, y, steps) }
        buildUi()
        handler.post(ticker)
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            prepareArCore()
        } else {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), CAMERA_REQUEST)
        }
    }

    private fun buildUi() {
        val scroll = ScrollView(this).apply {
            setBackgroundColor(ink)
            isFillViewport = true
            clipToPadding = false
            setPadding(dp(18), dp(12), dp(18), dp(28))
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(ink)
        }
        scroll.addView(content)

        val eyebrow = text("S T R I D E S E N S E     /     INDOOR MOTION", 11f, mint, true)
        content.addView(eyebrow, marginParams(bottom = 8))
        content.addView(text("Map your movement.", 27f, white, true), marginParams(bottom = 4))
        content.addView(
            text("A live camera view with a local 2D path from ARCore pose tracking or phone motion sensors.", 14f, muted),
            marginParams(bottom = 16)
        )

        val chips = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        chips.addView(pill("ARCORE VIO", mint))
        chips.addView(space(8))
        chips.addView(pill("SENSOR PDR FALLBACK", blue))
        content.addView(chips, marginParams(bottom = 16))

        val previewCard = card()
        val previewHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(10))
        }
        previewHeader.addView(text("LIVE CAMERA", 11f, white, true), LinearLayout.LayoutParams(0, dp(20), 1f))
        previewHeader.addView(text("LOCAL ONLY", 10f, mint, true))
        previewCard.addView(previewHeader)

        previewBox = FrameLayout(this).apply {
            background = rounded(Color.rgb(5, 9, 12), 18)
            clipToOutline = true
        }
        arPreview = ArCameraPreview(this) { x, z, tracking -> onArPose(x, z, tracking) }
        fallbackPreview = FallbackCameraPreview(this).apply { visibility = View.GONE }
        previewBox.addView(arPreview, FrameLayout.LayoutParams(-1, dp(228)))
        previewBox.addView(fallbackPreview, FrameLayout.LayoutParams(-1, dp(228)))
        val crosshair = TextView(this).apply {
            text = "+"
            textSize = 32f
            setTextColor(0xAAFFFFFF.toInt())
            gravity = Gravity.CENTER
            alpha = 0.7f
        }
        previewBox.addView(crosshair, FrameLayout.LayoutParams(dp(42), dp(42), Gravity.CENTER))
        val cameraStatusBg = rounded(0xCC0A1016.toInt(), 10)
        cameraStatus = text("Checking camera and ARCore…", 11f, white, true).apply {
            background = cameraStatusBg
            setPadding(dp(10), dp(7), dp(10), dp(7))
            maxLines = 2
        }
        val cameraStatusParams = FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.START).apply {
            setMargins(dp(12), 0, dp(12), dp(12))
        }
        previewBox.addView(cameraStatus, cameraStatusParams)
        previewCard.addView(previewBox, LinearLayout.LayoutParams(-1, dp(228)).apply {
            setMargins(dp(10), 0, dp(10), dp(10))
        })
        content.addView(previewCard, marginParams(bottom = 14))

        val statusCard = card().apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(12), dp(14), dp(12)) }
        val statusHeader = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        statusHeader.addView(dot(mint))
        statusLine = text("Ready when camera access is granted", 13f, white, true)
        statusHeader.addView(statusLine, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(8) })
        statusCard.addView(statusHeader)
        modeValue = text("Waiting for device support check", 11f, muted).apply { setPadding(dp(16), dp(5), 0, 0) }
        statusCard.addView(modeValue)
        content.addView(statusCard, marginParams(bottom = 14))

        val statsCard = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(surface, 18)
            setPadding(dp(4), dp(12), dp(4), dp(12))
        }
        val timerColumn = statColumn("TIME", "00:00")
        val stepsColumn = statColumn("STEPS", "0")
        val distanceColumn = statColumn("DISTANCE", "0.00 m")
        timerValue = timerColumn.second
        stepsValue = stepsColumn.second
        distanceValue = distanceColumn.second
        statsCard.addView(timerColumn.first, LinearLayout.LayoutParams(0, -2, 1f))
        statsCard.addView(separator())
        statsCard.addView(stepsColumn.first, LinearLayout.LayoutParams(0, -2, 1f))
        statsCard.addView(separator())
        statsCard.addView(distanceColumn.first, LinearLayout.LayoutParams(0, -2, 1f))
        content.addView(statsCard, marginParams(bottom = 18))

        val graphHeading = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        graphHeading.addView(text("Live trajectory", 18f, white, true), LinearLayout.LayoutParams(0, -2, 1f))
        graphHeading.addView(pill("RELATIVE XY", blue))
        content.addView(graphHeading, marginParams(bottom = 10))
        val graphCard = card().apply { orientation = LinearLayout.VERTICAL; setPadding(dp(8), dp(8), dp(8), dp(8)) }
        trajectoryView = TrajectoryView(this)
        graphCard.addView(trajectoryView, LinearLayout.LayoutParams(-1, dp(210)))
        content.addView(graphCard, marginParams(bottom = 8))
        content.addView(
            text("North is up · Start point is blue · Current point is white", 11f, muted),
            marginParams(bottom = 18)
        )

        content.addView(text("SESSION CONTROLS", 11f, muted, true), marginParams(bottom = 9))
        val controls = card().apply { orientation = LinearLayout.VERTICAL; setPadding(dp(10), dp(10), dp(10), dp(10)) }
        val rowOne = buttonRow()
        startButton = actionButton("▶  Start", primary = true).also { it.setOnClickListener { startRecording() } }
        stopButton = actionButton("■  Stop", primary = false).also { it.setOnClickListener { stopRecording() } }
        val resetButton = actionButton("↺  Reset", primary = false).also { it.setOnClickListener { resetSession() } }
        rowOne.addView(startButton, weightedButtonParams())
        rowOne.addView(stopButton, weightedButtonParams())
        rowOne.addView(resetButton, weightedButtonParams())
        controls.addView(rowOne)

        val rowTwo = buttonRow().apply { setPadding(0, dp(8), 0, 0) }
        saveButton = actionButton("Save", primary = false).also { it.setOnClickListener { saveCurrentSession() } }
        loadButton = actionButton("Load", primary = false).also { it.setOnClickListener { showSavedSessions() } }
        val csvButton = actionButton("Export CSV", primary = false).also { it.setOnClickListener { exportSession("csv") } }
        rowTwo.addView(saveButton, weightedButtonParams())
        rowTwo.addView(loadButton, weightedButtonParams())
        rowTwo.addView(csvButton, weightedButtonParams())
        controls.addView(rowTwo)

        val rowThree = buttonRow().apply { setPadding(0, dp(8), 0, 0) }
        val jsonButton = actionButton("Export JSON", primary = false).also { it.setOnClickListener { exportSession("json") } }
        rowThree.addView(jsonButton, weightedButtonParams())
        rowThree.addView(View(this), LinearLayout.LayoutParams(0, dp(44), 1f))
        rowThree.addView(View(this), LinearLayout.LayoutParams(0, dp(44), 1f))
        controls.addView(rowThree)
        content.addView(controls, marginParams(bottom = 16))

        val noteCard = card().apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(13), dp(14), dp(13)) }
        noteCard.addView(text("WHAT THE TRACKER USES", 11f, mint, true), marginParams(bottom = 7))
        noteCard.addView(
            text("ARCore visual-inertial pose when available; otherwise the step detector and phone heading estimate a path. This is a local relative trace and can drift. It is not ORB-SLAM3 or a surveying tool.", 12f, muted),
            LinearLayout.LayoutParams(-1, -2)
        )
        content.addView(noteCard, marginParams(bottom = 18))

        content.addView(text("Sessions stay on this device. Camera frames are not saved.", 11f, muted), marginParams(bottom = 4))
        setContentView(scroll)
        refreshStats()
    }

    private fun prepareArCore() {
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return
        if (arSession != null) {
            val existing = arSession ?: return
            val resumeResult = runCatching { existing.resume() }
            if (resumeResult.isSuccess) {
                useArPreview()
                return
            }
            runCatching { existing.close() }
            arSession = null
            showPdrFallback("ARCore could not resume the camera. Sensor tracking is ready.")
            return
        }
        if (availabilityCheckInFlight) return
        availabilityCheckInFlight = true
        cameraStatus.text = "Checking ARCore support…"
        ArCoreApk.getInstance().checkAvailabilityAsync(this) { availability ->
            availabilityCheckInFlight = false
            if (isFinishing || isDestroyed) return@checkAvailabilityAsync
            if (!availability.isSupported) {
                showPdrFallback("ARCore is not supported here. Sensor tracking is ready.")
                return@checkAvailabilityAsync
            }
            try {
                when (ArCoreApk.getInstance().requestInstall(this, installRequested)) {
                    ArCoreApk.InstallStatus.INSTALL_REQUESTED -> {
                        installRequested = false
                        cameraStatus.text = "Install or update Google Play Services for AR to enable visual tracking"
                        statusLine.text = "ARCore setup is needed for visual tracking"
                    }
                    ArCoreApk.InstallStatus.INSTALLED -> createArSession()
                }
            } catch (error: Exception) {
                showPdrFallback("ARCore could not start. Sensor tracking is ready.")
            }
        }
    }

    private fun createArSession() {
        var session: Session? = null
        try {
            val createdSession = Session(this)
            session = createdSession
            val config = Config(createdSession).apply {
                updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
                focusMode = Config.FocusMode.AUTO
            }
            createdSession.configure(config)
            arSession = createdSession
            arPreview.setTrackingSession(createdSession)
            useArPreview()
            cameraStatus.text = "ARCore camera ready · move slowly for stable tracking"
            modeValue.text = "Tracking source: ARCore local visual-inertial pose"
            if (activityResumed) createdSession.resume()
        } catch (error: Exception) {
            runCatching { session?.close() }
            arSession = null
            showPdrFallback("ARCore setup failed. Sensor tracking is ready.")
        }
    }

    private fun useArPreview() {
        fallbackActive = false
        fallbackPreview.stopPreview()
        fallbackPreview.visibility = View.GONE
        arPreview.visibility = View.VISIBLE
    }

    private fun showPdrFallback(message: String) {
        fallbackActive = true
        arPreview.visibility = View.GONE
        fallbackPreview.visibility = View.VISIBLE
        if (activityResumed) fallbackPreview.startPreview()
        cameraStatus.text = "Camera preview · sensor dead-reckoning mode"
        statusLine.text = "Sensor-based tracking ready"
        modeValue.text = message
    }

    private fun startRecording() {
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), CAMERA_REQUEST)
            toast("Camera permission is needed for a movement session")
            return
        }
        if (recording) return
        val mode = if (arSession != null) TrackingMode.ARCORE_VIO else TrackingMode.SENSOR_PDR
        currentSession = MotionSession(mode = mode)
        recording = true
        arOriginX = null
        arOriginZ = null
        arTracking = false
        pdrTracker.reset()
        pdrTracker.start()
        trajectoryView.setRoute(emptyList())
        stopButton.isEnabled = true
        startButton.isEnabled = false
        statusLine.text = "Recording · keep the phone moving steadily"
        modeValue.text = if (mode == TrackingMode.ARCORE_VIO) {
            "ARCore VIO · local pose can drift; step sensor counts shown separately"
        } else if (pdrTracker.hasStepSensor) {
            "Sensor PDR · step detector + estimated stride + device heading"
        } else {
            "Sensor PDR · accelerometer step estimate + device heading"
        }
        refreshStats()
    }

    private fun stopRecording() {
        if (!recording) return
        recording = false
        pdrTracker.stop()
        currentSession?.endedAtMs = System.currentTimeMillis()
        startButton.isEnabled = true
        stopButton.isEnabled = false
        statusLine.text = "Session paused · save it or continue later"
        refreshStats()
    }

    private fun resetSession() {
        if (recording) stopRecording()
        currentSession = null
        arOriginX = null
        arOriginZ = null
        pdrTracker.reset()
        trajectoryView.setRoute(emptyList())
        statusLine.text = "Ready to start a new session"
        refreshStats()
    }

    private fun saveCurrentSession() {
        if (recording) stopRecording()
        val session = currentSession
        if (session == null || session.points.isEmpty()) {
            toast("Record some movement before saving a session")
            return
        }
        runCatching { SessionStore.save(this, session) }
            .onSuccess { toast("Session saved on this device") }
            .onFailure { toast("Could not save session: ${it.localizedMessage ?: "storage error"}") }
    }

    private fun showSavedSessions() {
        val files = SessionStore.list(this)
        if (files.isEmpty()) {
            toast("No saved sessions yet")
            return
        }
        val labels = files.map { SessionStore.displayName(this, it) }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Load a saved session")
            .setItems(labels) { _, index ->
                if (recording) stopRecording()
                runCatching { SessionStore.load(this, files[index]) }
                    .onSuccess { session ->
                        currentSession = session
                        trajectoryView.setRoute(session.points)
                        statusLine.text = "Loaded saved session"
                        modeValue.text = "${session.mode.displayName} · saved locally"
                        refreshStats()
                    }
                    .onFailure { toast("Could not load that session") }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun exportSession(kind: String) {
        val session = currentSession
        if (session == null || session.points.isEmpty()) {
            toast("Record or load a session before exporting")
            return
        }
        if (recording) stopRecording()
        exportExtension = kind
        exportMimeType = if (kind == "csv") "text/csv" else "application/json"
        exportText = if (kind == "csv") SessionStore.toCsv(session) else SessionStore.toJson(session)
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(session.startedAtMs))
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = exportMimeType
            putExtra(Intent.EXTRA_TITLE, "stridesense_session_$stamp.$kind")
        }
        startActivityForResult(intent, EXPORT_REQUEST)
    }

    @Deprecated("Uses the platform document picker for broad Android version support")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == EXPORT_REQUEST && resultCode == Activity.RESULT_OK) {
            val uri: Uri = data?.data ?: return
            runCatching {
                contentResolver.openOutputStream(uri)?.bufferedWriter(Charsets.UTF_8)?.use { it.write(exportText) }
                    ?: error("Unable to open destination")
            }.onSuccess { toast("${exportExtension.uppercase(Locale.US)} exported") }
                .onFailure { toast("Export failed: ${it.localizedMessage ?: "write error"}") }
        }
    }

    private fun onArPose(x: Float, z: Float, tracking: Boolean) {
        runOnUiThread {
            arTracking = tracking
            if (tracking) {
                cameraStatus.text = "ARCore tracking · visual-inertial local pose"
                if (recording && currentSession?.mode == TrackingMode.ARCORE_VIO) {
                    val baseX = arOriginX ?: x.also { arOriginX = it }
                    val baseZ = arOriginZ ?: z.also { arOriginZ = it }
                    appendPoint(x - baseX, -(z - baseZ), "ARCore")
                }
            } else if (arSession != null) {
                cameraStatus.text = "ARCore ready · point at a well-lit, textured area"
            }
            if (recording && currentSession?.mode == TrackingMode.ARCORE_VIO) {
                statusLine.text = if (tracking) "Recording · ARCore visual tracking" else "Recording · waiting for visual tracking"
            }
        }
    }

    private fun onPdrStep(x: Float, y: Float, count: Int) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            runOnUiThread { onPdrStep(x, y, count) }
            return
        }
        val session = currentSession ?: return
        if (!recording) return
        session.stepCount = count
        if (session.mode == TrackingMode.SENSOR_PDR) appendPoint(x, y, "PDR") else refreshStats()
    }

    private fun appendPoint(x: Float, y: Float, source: String) {
        val session = currentSession ?: return
        val elapsed = (System.currentTimeMillis() - session.startedAtMs).coerceAtLeast(0)
        val previous = session.points.lastOrNull()
        if (previous != null) {
            val dx = x - previous.xMeters
            val dy = y - previous.yMeters
            if (dx * dx + dy * dy < 0.0004f && elapsed - previous.elapsedMs < 350L) return
        }
        session.points += TrackPoint(elapsed, x, y, session.stepCount, source)
        trajectoryView.setRoute(session.points)
        refreshStats()
    }

    private fun refreshStats() {
        val session = currentSession
        val elapsed = if (recording && session != null) System.currentTimeMillis() - session.startedAtMs else (session?.durationMs ?: 0L)
        timerValue.text = String.format(Locale.US, "%02d:%02d", elapsed / 60_000L, (elapsed / 1_000L) % 60L)
        stepsValue.text = (session?.stepCount ?: 0).toString()
        distanceValue.text = String.format(Locale.US, "%.2f m", session?.distanceMeters ?: 0f)
        saveButton.isEnabled = session != null && session.points.isNotEmpty()
        loadButton.isEnabled = true
        if (!recording) {
            startButton.isEnabled = checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
            stopButton.isEnabled = false
        }
    }

    override fun onResume() {
        super.onResume()
        activityResumed = true
        if (::arPreview.isInitialized) arPreview.onResume()
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            prepareArCore()
            if (fallbackActive) fallbackPreview.startPreview()
        }
    }

    override fun onPause() {
        activityResumed = false
        if (recording) stopRecording()
        runCatching { arSession?.pause() }
        if (::arPreview.isInitialized) arPreview.onPause()
        if (::fallbackPreview.isInitialized) fallbackPreview.stopPreview()
        super.onPause()
    }

    override fun onDestroy() {
        handler.removeCallbacks(ticker)
        pdrTracker.stop()
        runCatching { arSession?.close() }
        arSession = null
        super.onDestroy()
    }

    @Deprecated("Deprecated in Android API")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == CAMERA_REQUEST) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
                startButton.isEnabled = true
                prepareArCore()
            } else {
                cameraStatus.text = "Camera permission is required for the preview"
                statusLine.text = "Allow camera access to begin"
                modeValue.text = "Camera frames are processed locally and are not saved by this app."
                startButton.isEnabled = false
            }
        }
    }

    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(surface, 20)
        clipToOutline = true
    }

    private fun text(value: String, size: Float, color: Int, bold: Boolean = false): TextView = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(color)
        if (bold) setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL), Typeface.BOLD)
        includeFontPadding = false
    }

    private fun pill(label: String, color: Int): TextView = text(label, 9f, color, true).apply {
        background = rounded(Color.argb(34, Color.red(color), Color.green(color), Color.blue(color)), 50)
        setPadding(dp(10), dp(7), dp(10), dp(7))
    }

    private fun dot(color: Int): View = View(this).apply {
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color) }
        layoutParams = LinearLayout.LayoutParams(dp(8), dp(8))
    }

    private fun space(width: Int): View = View(this).apply { layoutParams = LinearLayout.LayoutParams(dp(width), 1) }

    private fun statColumn(label: String, value: String): Pair<LinearLayout, TextView> {
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(3), dp(3), dp(3), dp(3))
        }
        column.addView(text(label, 9f, muted, true), LinearLayout.LayoutParams(-2, -2).apply { bottomMargin = dp(7) })
        val valueView = text(value, 17f, white, true)
        column.addView(valueView)
        return column to valueView
    }

    private fun separator(): View = View(this).apply {
        setBackgroundColor(Color.rgb(47, 62, 73))
        layoutParams = LinearLayout.LayoutParams(dp(1), dp(34))
    }

    private fun buttonRow(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    private fun weightedButtonParams(): LinearLayout.LayoutParams = LinearLayout.LayoutParams(0, dp(44), 1f).apply {
        setMargins(dp(3), 0, dp(3), 0)
    }

    private fun actionButton(label: String, primary: Boolean): TextView = text(label, 12f, if (primary) ink else white, true).apply {
        gravity = Gravity.CENTER
        maxLines = 1
        background = rounded(if (primary) mint else surfaceHigh, 13)
        isClickable = true
        isFocusable = true
        setPadding(dp(5), 0, dp(5), 0)
        setOnTouchListener { view, event ->
            if (view.isEnabled && event.action == android.view.MotionEvent.ACTION_DOWN) view.alpha = 0.72f
            if (event.action == android.view.MotionEvent.ACTION_UP || event.action == android.view.MotionEvent.ACTION_CANCEL) view.alpha = 1f
            false
        }
    }

    private fun marginParams(bottom: Int = 0): LinearLayout.LayoutParams = LinearLayout.LayoutParams(-1, -2).apply {
        if (bottom > 0) bottomMargin = dp(bottom)
    }

    private fun rounded(color: Int, radiusDp: Int): GradientDrawable = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radiusDp).toFloat()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
}
