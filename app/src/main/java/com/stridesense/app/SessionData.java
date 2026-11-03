package com.stridesense.app;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

final class SessionData {
    private SessionData() { }

    static final class TrackPoint {
        long elapsedMs;
        double xMeters;
        double yMeters;
        int steps;
        String source;

        TrackPoint(long elapsedMs, double xMeters, double yMeters, int steps, String source) {
            this.elapsedMs = elapsedMs;
            this.xMeters = xMeters;
            this.yMeters = yMeters;
            this.steps = steps;
            this.source = source;
        }
    }

    static final class MarkerPoint {
        String name;
        double xMeters;
        double yMeters;
        long firstSeenMs;
        int sightings;

        MarkerPoint(String name, double xMeters, double yMeters, long firstSeenMs) {
            this.name = name;
            this.xMeters = xMeters;
            this.yMeters = yMeters;
            this.firstSeenMs = firstSeenMs;
            this.sightings = 1;
        }
    }

    static final class MotionSession {
        String id = UUID.randomUUID().toString();
        long startedAtMs = System.currentTimeMillis();
        long endedAtMs;
        String mode;
        int stepCount;
        final List<TrackPoint> points = new ArrayList<>();
        final List<MarkerPoint> markers = new ArrayList<>();

        MotionSession(String mode) { this.mode = mode; }

        double distanceMeters() {
            double total = 0;
            for (int i = 1; i < points.size(); i++) {
                TrackPoint a = points.get(i - 1), b = points.get(i);
                total += Math.hypot(b.xMeters - a.xMeters, b.yMeters - a.yMeters);
            }
            return total;
        }

        long durationMs() {
            long end = endedAtMs > 0 ? endedAtMs : System.currentTimeMillis();
            return Math.max(0, end - startedAtMs);
        }
    }

    static final class Store {
        private static final String FOLDER = "sessions";

        private static File directory(Context context) {
            File dir = new File(context.getFilesDir(), FOLDER);
            if (!dir.exists() && !dir.mkdirs()) throw new IllegalStateException("Could not create session folder");
            return dir;
        }

        static File save(Context context, MotionSession session) throws IOException {
            if (session.endedAtMs == 0) session.endedAtMs = System.currentTimeMillis();
            String safeId = session.id.replaceAll("[^A-Za-z0-9_-]", "");
            File file = new File(directory(context), "session_" + session.startedAtMs + "_" + safeId + ".json");
            try (java.io.FileOutputStream stream = new java.io.FileOutputStream(file)) {
                stream.write(toJson(session).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
            return file;
        }

        static List<File> list(Context context) {
            File[] files = directory(context).listFiles((dir, name) -> name.toLowerCase(Locale.US).endsWith(".json"));
            List<File> result = new ArrayList<>();
            if (files != null) {
                java.util.Collections.addAll(result, files);
                result.sort((a, b) -> Long.compare(b.lastModified(), a.lastModified()));
            }
            return result;
        }

        static MotionSession load(File file) throws IOException, JSONException {
            java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
            try (java.io.InputStream input = new java.io.FileInputStream(file)) {
                byte[] buffer = new byte[4096];
                int count;
                while ((count = input.read(buffer)) != -1) bytes.write(buffer, 0, count);
            }
            JSONObject root = new JSONObject(new String(bytes.toByteArray(), java.nio.charset.StandardCharsets.UTF_8));
            MotionSession session = new MotionSession(root.optString("mode", "ARCORE_VIO"));
            session.id = root.optString("id", UUID.randomUUID().toString());
            session.startedAtMs = root.optLong("startedAtMs", file.lastModified());
            session.endedAtMs = root.optLong("endedAtMs", 0);
            session.stepCount = root.optInt("stepCount", 0);
            JSONArray points = root.optJSONArray("points");
            if (points != null) for (int i = 0; i < points.length(); i++) {
                JSONObject p = points.optJSONObject(i);
                if (p == null) continue;
                session.points.add(new TrackPoint(p.optLong("elapsedMs"), p.optDouble("xMeters"),
                        p.optDouble("yMeters"), p.optInt("steps"), p.optString("source", "unknown")));
            }
            JSONArray markers = root.optJSONArray("markers");
            if (markers != null) for (int i = 0; i < markers.length(); i++) {
                JSONObject m = markers.optJSONObject(i);
                if (m == null) continue;
                MarkerPoint point = new MarkerPoint(m.optString("name"), m.optDouble("xMeters"),
                        m.optDouble("yMeters"), m.optLong("firstSeenMs"));
                point.sightings = m.optInt("sightings", 1);
                session.markers.add(point);
            }
            return session;
        }

        static String displayName(File file) {
            try {
                MotionSession session = load(file);
                String date = new SimpleDateFormat("MMM d, yyyy  h:mm a", Locale.getDefault())
                        .format(new Date(session.startedAtMs));
                return date + "  ·  " + session.points.size() + " route points  ·  " + session.markers.size() + " markers";
            } catch (Exception ignored) { return file.getName(); }
        }

        static String toJson(MotionSession session) {
            JSONObject root = new JSONObject();
            JSONArray points = new JSONArray();
            JSONArray markers = new JSONArray();
            try {
                root.put("format", "StrideSense Java indoor route");
                root.put("formatVersion", 2);
                root.put("id", session.id);
                root.put("startedAtMs", session.startedAtMs);
                root.put("endedAtMs", session.endedAtMs);
                root.put("durationMs", session.durationMs());
                root.put("trackingMode", session.mode);
                root.put("stepCount", session.stepCount);
                root.put("estimatedDistanceMeters", session.distanceMeters());
                for (TrackPoint p : session.points) {
                    points.put(new JSONObject().put("elapsedMs", p.elapsedMs).put("xMeters", p.xMeters)
                            .put("yMeters", p.yMeters).put("steps", p.steps).put("source", p.source));
                }
                for (MarkerPoint m : session.markers) {
                    markers.put(new JSONObject().put("name", m.name).put("xMeters", m.xMeters)
                            .put("yMeters", m.yMeters).put("firstSeenMs", m.firstSeenMs).put("sightings", m.sightings));
                }
                root.put("points", points);
                root.put("markers", markers);
            } catch (JSONException e) { throw new IllegalStateException("Could not serialize session", e); }
            return root.toString();
        }

        static String toCsv(MotionSession session) {
            StringBuilder csv = new StringBuilder();
            csv.append("format,StrideSense Java indoor route\n");
            csv.append("tracking_mode,").append(session.mode).append('\n');
            csv.append("started_at,").append(new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US)
                    .format(new Date(session.startedAtMs))).append('\n');
            csv.append("steps,").append(session.stepCount).append('\n');
            csv.append(String.format(Locale.US, "estimated_distance_m,%.3f%n", session.distanceMeters()));
            csv.append("elapsed_ms,x_m,y_m,steps,source\n");
            for (TrackPoint p : session.points) csv.append(String.format(Locale.US, "%d,%.5f,%.5f,%d,%s%n",
                    p.elapsedMs, p.xMeters, p.yMeters, p.steps, p.source));
            csv.append("\nmarker,x_m,y_m,first_seen_ms,sightings\n");
            for (MarkerPoint m : session.markers) csv.append(String.format(Locale.US, "%s,%.5f,%.5f,%d,%d%n",
                    m.name, m.xMeters, m.yMeters, m.firstSeenMs, m.sightings));
            return csv.toString();
        }
    }
}
