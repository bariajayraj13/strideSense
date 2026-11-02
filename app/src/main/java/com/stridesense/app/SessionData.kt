package com.stridesense.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

enum class TrackingMode(val displayName: String) {
    ARCORE_VIO("ARCore visual-inertial"),
    SENSOR_PDR("Sensor dead-reckoning")
}

data class TrackPoint(
    val elapsedMs: Long,
    val xMeters: Float,
    val yMeters: Float,
    val steps: Int,
    val source: String
)

data class MotionSession(
    val id: String = UUID.randomUUID().toString(),
    val startedAtMs: Long = System.currentTimeMillis(),
    var endedAtMs: Long = 0L,
    val mode: TrackingMode,
    var stepCount: Int = 0,
    val points: MutableList<TrackPoint> = mutableListOf()
) {
    val distanceMeters: Float
        get() {
            var total = 0f
            for (i in 1 until points.size) {
                val dx = points[i].xMeters - points[i - 1].xMeters
                val dy = points[i].yMeters - points[i - 1].yMeters
                total += kotlin.math.sqrt(dx * dx + dy * dy)
            }
            return total
        }

    val durationMs: Long
        get() = ((if (endedAtMs > 0) endedAtMs else System.currentTimeMillis()) - startedAtMs).coerceAtLeast(0)
}

object SessionStore {
    private const val FOLDER = "sessions"
    private const val FORMAT_VERSION = 1

    private fun directory(context: Context): File = File(context.filesDir, FOLDER).apply { mkdirs() }

    fun save(context: Context, session: MotionSession): File {
        if (session.endedAtMs == 0L) session.endedAtMs = System.currentTimeMillis()
        val safeId = session.id.replace(Regex("[^A-Za-z0-9_-]"), "")
        val file = File(directory(context), "session_${session.startedAtMs}_$safeId.json")
        file.writeText(toJson(session), Charsets.UTF_8)
        return file
    }

    fun load(context: Context, file: File): MotionSession {
        val root = JSONObject(file.readText(Charsets.UTF_8))
        val mode = runCatching { TrackingMode.valueOf(root.optString("mode")) }
            .getOrDefault(TrackingMode.SENSOR_PDR)
        val points = mutableListOf<TrackPoint>()
        val array = root.optJSONArray("points") ?: JSONArray()
        for (i in 0 until array.length()) {
            val p = array.optJSONObject(i) ?: continue
            points += TrackPoint(
                elapsedMs = p.optLong("elapsedMs"),
                xMeters = p.optDouble("xMeters").toFloat(),
                yMeters = p.optDouble("yMeters").toFloat(),
                steps = p.optInt("steps"),
                source = p.optString("source", "unknown")
            )
        }
        return MotionSession(
            id = root.optString("id", UUID.randomUUID().toString()),
            startedAtMs = root.optLong("startedAtMs", file.lastModified()),
            endedAtMs = root.optLong("endedAtMs"),
            mode = mode,
            stepCount = root.optInt("stepCount"),
            points = points
        )
    }

    fun list(context: Context): List<File> = directory(context)
        .listFiles { file -> file.isFile && file.extension.equals("json", ignoreCase = true) }
        .orEmpty()
        .sortedByDescending(File::lastModified)

    fun toJson(session: MotionSession): String {
        val root = JSONObject()
            .put("format", "StrideSense session")
            .put("formatVersion", FORMAT_VERSION)
            .put("id", session.id)
            .put("startedAtMs", session.startedAtMs)
            .put("endedAtMs", session.endedAtMs)
            .put("durationMs", session.durationMs)
            .put("mode", session.mode.name)
            .put("modeLabel", session.mode.displayName)
            .put("stepCount", session.stepCount)
            .put("distanceMeters", session.distanceMeters.toDouble())
        val points = JSONArray()
        session.points.forEach { point ->
            points.put(
                JSONObject()
                    .put("elapsedMs", point.elapsedMs)
                    .put("xMeters", point.xMeters.toDouble())
                    .put("yMeters", point.yMeters.toDouble())
                    .put("steps", point.steps)
                    .put("source", point.source)
            )
        }
        root.put("points", points)
        return root.toString(2)
    }

    fun toCsv(session: MotionSession): String = buildString {
        appendLine("format,StrideSense session")
        appendLine("started_at,${isoDate(session.startedAtMs)}")
        appendLine("tracking_mode,${session.mode.displayName}")
        appendLine("step_count,${session.stepCount}")
        appendLine("estimated_distance_m,${"%.3f".format(Locale.US, session.distanceMeters)}")
        appendLine("elapsed_ms,x_m,y_m,steps,source")
        session.points.forEach { p ->
            appendLine("${p.elapsedMs},${p.xMeters},${p.yMeters},${p.steps},${p.source}")
        }
    }

    fun displayName(context: Context, file: File): String = runCatching {
        val session = load(context, file)
        val date = SimpleDateFormat("MMM d, yyyy  •  h:mm a", Locale.getDefault())
            .format(Date(session.startedAtMs))
        "$date   ·   ${session.points.size} points"
    }.getOrDefault(file.name)

    private fun isoDate(epochMs: Long): String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US)
        .format(Date(epochMs))
}
