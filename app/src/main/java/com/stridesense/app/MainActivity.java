package com.stridesense.app;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import com.google.ar.core.ArCoreApk;
import com.google.ar.core.AugmentedImageDatabase;
import com.google.ar.core.Config;
import com.google.ar.core.Session;
import java.io.File;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import static com.stridesense.app.SessionData.MarkerPoint;
import static com.stridesense.app.SessionData.MotionSession;
import static com.stridesense.app.SessionData.TrackPoint;

/** StrideSense Android app. ARCore local pose is primary; PDR is a visibly-labelled fallback. */
public final class MainActivity extends Activity {
    private static final int CAMERA_REQUEST = 40;
    private static final int EXPORT_REQUEST = 41;
    private static final String MODE_AR = "ARCORE_VIO";
    private static final String MODE_PDR = "SENSOR_PDR";

    private final int ink = 0xff0a1016, surface = 0xff121b24, raised = 0xff192632;
    private final int mint = 0xff54e2bd, blue = 0xff83a9ff, amber = 0xffffc15a;
    private final int white = 0xfff0f5f8, muted = 0xff98a8b5;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private FrameLayout previewBox;
    private ArCameraPreview arPreview;
    private FallbackCameraPreview fallbackPreview;
    private TrajectoryView mapView;
    private TextView cameraStatus, markerBadge, statusLine, modeValue;
    private TextView timeValue, stepsValue, distanceValue, markersValue;
    private TextView startButton, stopButton, saveButton, loadButton;
    private Session arSession;
    private PdrTracker pdr;
    private MotionSession currentSession;
    private boolean recording, resumed, arTracking, arAvailabilityChecking, installRequested = true, cameraReady;
    private boolean fallbackActive;
    private double originX, originZ, originYaw, correctionX, correctionY;
    private boolean hasOrigin, hasSmoothPose;
    private double smoothX, smoothY, lastRawX, lastRawY;
    private long lastPoseTime, lastPointTime, recordingStartElapsed;
    private final Map<String, Long> lastMarkerUpdate = new HashMap<>();
    private String exportText = "", exportMime = "application/json", exportExtension = "json";

    private final Runnable ticker = new Runnable() {
        @Override public void run() { refreshStats(); handler.postDelayed(this, 500); }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(ink);
        getWindow().setNavigationBarColor(ink);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        pdr = new PdrTracker(this, this::onPdrStep);
        buildUi();
        handler.post(ticker);
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) prepareArCore();
        else requestPermissions(new String[]{Manifest.permission.CAMERA}, CAMERA_REQUEST);
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        scroll.setBackgroundColor(ink);
        scroll.setPadding(dp(17), dp(12), dp(17), dp(28));
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(content);

        content.addView(text("S T R I D E S E N S E     /     JAVA INDOOR MAP", 10, mint, true), margin(0, 0, 0, 7));
        content.addView(text("Map your movement.", 27, white, true), margin(0, 0, 0, 4));
        content.addView(text("Live camera tracking, visual landmarks and a local 2D route.", 13, muted, false), margin(0, 0, 0, 15));

        LinearLayout topPills = row();
        topPills.addView(pill("ARCORE VIO", mint));
        topPills.addView(space(8));
        topPills.addView(pill("PRINTED LANDMARKS", amber));
        content.addView(topPills, margin(0, 0, 0, 14));

        LinearLayout cameraCard = card();
        LinearLayout cameraHeader = row();
        cameraHeader.setPadding(dp(13), dp(11), dp(13), dp(10));
        cameraHeader.addView(text("LIVE CAMERA", 11, white, true), new LinearLayout.LayoutParams(0, dp(18), 1));
        cameraHeader.addView(text("ON DEVICE", 9, mint, true));
        cameraCard.addView(cameraHeader);
        previewBox = new FrameLayout(this);
        previewBox.setBackground(rounded(0xff05090c, 15));
        previewBox.setClipToOutline(true);
        arPreview = new ArCameraPreview(this, update -> runOnUiThread(() -> handleArFrame(update)));
        fallbackPreview = new FallbackCameraPreview(this);
        fallbackPreview.setVisibility(View.GONE);
        FrameLayout.LayoutParams cameraParams = new FrameLayout.LayoutParams(-1, dp(218));
        previewBox.addView(arPreview, cameraParams);
        previewBox.addView(fallbackPreview, new FrameLayout.LayoutParams(-1, dp(218)));
        TextView reticle = text("+", 28, 0xccffffff, false);
        reticle.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams reticleParams = new FrameLayout.LayoutParams(dp(40), dp(40), Gravity.CENTER);
        previewBox.addView(reticle, reticleParams);
        markerBadge = text("0 MARKERS", 9, amber, true);
        markerBadge.setBackground(rounded(0xdd101820, 9));
        markerBadge.setPadding(dp(9), dp(6), dp(9), dp(6));
        FrameLayout.LayoutParams markerParams = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
        markerParams.setMargins(0, dp(10), dp(10), 0);
        previewBox.addView(markerBadge, markerParams);
        cameraStatus = text("Checking ARCore and camera…", 10, white, true);
        cameraStatus.setBackground(rounded(0xdd0a1016, 9));
        cameraStatus.setPadding(dp(9), dp(7), dp(9), dp(7));
        cameraStatus.setMaxLines(2);
        FrameLayout.LayoutParams statusParams = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.START);
        statusParams.setMargins(dp(10), 0, dp(10), dp(10));
        previewBox.addView(cameraStatus, statusParams);
        LinearLayout.LayoutParams previewParams = new LinearLayout.LayoutParams(-1, dp(218));
        previewParams.setMargins(dp(9), 0, dp(9), dp(9));
        cameraCard.addView(previewBox, previewParams);
        content.addView(cameraCard, margin(0, 0, 0, 12));

        LinearLayout statusCard = card();
        statusCard.setPadding(dp(13), dp(11), dp(13), dp(11));
        LinearLayout statusRow = row();
        View dot = new View(this); dot.setBackground(rounded(mint, 20));
        statusRow.addView(dot, new LinearLayout.LayoutParams(dp(8), dp(8)));
        statusLine = text("Waiting for camera permission", 12, white, true);
        statusRow.addView(statusLine, new LinearLayout.LayoutParams(0, -2, 1) {{ leftMargin = dp(8); }});
        statusCard.addView(statusRow);
        modeValue = text("ARCore visual-inertial pose is preferred when this phone supports it.", 10, muted, false);
        statusCard.addView(modeValue, margin(dp(16), 5, 0, 0));
        content.addView(statusCard, margin(0, 0, 0, 12));

        LinearLayout stats = new LinearLayout(this);
        stats.setOrientation(LinearLayout.HORIZONTAL);
        stats.setGravity(Gravity.CENTER_VERTICAL);
        stats.setBackground(rounded(surface, 17));
        stats.setPadding(dp(3), dp(10), dp(3), dp(10));
        Stat timer = stat("TIME", "00:00");
        Stat steps = stat("STEPS", "0");
        Stat distance = stat("DISTANCE", "0.00 m");
        Stat markerCount = stat("LANDMARKS", "0");
        timeValue = timer.value; stepsValue = steps.value; distanceValue = distance.value; markersValue = markerCount.value;
        stats.addView(timer.view, new LinearLayout.LayoutParams(0, -2, 1)); stats.addView(separator());
        stats.addView(steps.view, new LinearLayout.LayoutParams(0, -2, 1)); stats.addView(separator());
        stats.addView(distance.view, new LinearLayout.LayoutParams(0, -2, 1.2f)); stats.addView(separator());
        stats.addView(markerCount.view, new LinearLayout.LayoutParams(0, -2, 1));
        content.addView(stats, margin(0, 0, 0, 17));

        LinearLayout mapHeading = row();
        mapHeading.addView(text("Live floor map", 18, white, true), new LinearLayout.LayoutParams(0, -2, 1));
        mapHeading.addView(pill("TOP VIEW · M", blue));
        content.addView(mapHeading, margin(0, 0, 0, 9));
        LinearLayout mapCard = card();
        mapCard.setPadding(dp(7), dp(7), dp(7), dp(7));
        mapView = new TrajectoryView(this);
        mapCard.addView(mapView, new LinearLayout.LayoutParams(-1, dp(255)));
        content.addView(mapCard, margin(0, 0, 0, 7));
        content.addView(text("Mint line: route   •   Blue: start   •   Gold: printed visual landmark", 10, muted, false), margin(dp(3), 0, 0, 15));

        content.addView(text("SESSION CONTROLS", 10, muted, true), margin(0, 0, 0, 8));
        LinearLayout controls = card();
        controls.setPadding(dp(8), dp(8), dp(8), dp(8));
        LinearLayout rowOne = row();
        startButton = action("▶  START", true, this::startRecording);
        stopButton = action("■  STOP", false, this::stopRecording);
        TextView reset = action("↺  RESET", false, this::resetSession);
        rowOne.addView(startButton, weightedButton()); rowOne.addView(stopButton, weightedButton()); rowOne.addView(reset, weightedButton());
        controls.addView(rowOne);
        LinearLayout rowTwo = row(); rowTwo.setPadding(0, dp(7), 0, 0);
        saveButton = action("SAVE", false, this::saveCurrentSession);
        loadButton = action("LOAD", false, this::showSavedSessions);
        TextView csv = action("CSV", false, () -> exportSession("csv"));
        rowTwo.addView(saveButton, weightedButton()); rowTwo.addView(loadButton, weightedButton()); rowTwo.addView(csv, weightedButton());
        controls.addView(rowTwo);
        LinearLayout rowThree = row(); rowThree.setPadding(0, dp(7), 0, 0);
        rowThree.addView(action("JSON", false, () -> exportSession("json")), weightedButton());
        rowThree.addView(action("CALIBRATE PDR", false, this::showCalibration), weightedButton());
        rowThree.addView(action("MARKER GUIDE", false, this::showMarkerGuide), weightedButton());
        controls.addView(rowThree);
        content.addView(controls, margin(0, 0, 0, 13));

        LinearLayout note = card(); note.setPadding(dp(13), dp(12), dp(13), dp(12));
        note.addView(text("TRACKING & ACCURACY", 10, mint, true), margin(0, 0, 0, 6));
        note.addView(text("ARCore uses the camera and motion sensors for a relative local path. Re-seeing a printed marker can reduce positional drift. Sensor-only fallback estimates distance from your calibrated step length and drifts more. This is not a surveying tool or general object-recognition app.", 11, muted, false));
        content.addView(note, margin(0, 0, 0, 12));
        content.addView(text("Camera frames are processed locally and are not stored. Sessions are saved on this phone.", 10, muted, false), margin(dp(3), 0, 0, 0));

        setContentView(scroll);
        refreshStats();
    }

    private void prepareArCore() {
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED || isFinishing()) return;
        if (arSession != null) {
            try { arSession.resume(); useArCamera(); }
            catch (Exception e) {
                try { arSession.close(); } catch (Exception ignored) { }
                arSession = null;
                showFallback("ARCore could not resume. Sensor-only mode is available.");
            }
            return;
        }
        if (arAvailabilityChecking) return;
        arAvailabilityChecking = true;
        cameraStatus.setText("Checking ARCore support…");
        ArCoreApk.getInstance().checkAvailabilityAsync(this, availability -> {
            arAvailabilityChecking = false;
            if (isFinishing() || isDestroyed()) return;
            if (!availability.isSupported()) { showFallback("ARCore is not supported on this phone."); return; }
            try {
                ArCoreApk.InstallStatus result = ArCoreApk.getInstance().requestInstall(this, installRequested);
                if (result == ArCoreApk.InstallStatus.INSTALL_REQUESTED) {
                    installRequested = false;
                    showFallback("Install Google Play Services for AR to enable visual tracking.");
                } else createArSession();
            } catch (Exception e) { showFallback("ARCore setup failed. Sensor-only mode is available."); }
        });
    }

    private void createArSession() {
        Session created = null;
        try {
            created = new Session(this);
            Config config = new Config(created);
            config.setUpdateMode(Config.UpdateMode.LATEST_CAMERA_IMAGE);
            config.setFocusMode(Config.FocusMode.AUTO);
            int imagesAdded = 0;
            AugmentedImageDatabase database = new AugmentedImageDatabase(created);
            for (int i = 1; i <= 4; i++) {
                String file = String.format(Locale.US, "markers/stride_marker_%02d.png", i);
                try (InputStream stream = getAssets().open(file)) {
                    Bitmap bitmap = BitmapFactory.decodeStream(stream);
                    if (bitmap != null) {
                        database.addImage(String.format(Locale.US, "SS-%02d", i), bitmap, 0.085f);
                        bitmap.recycle();
                        imagesAdded++;
                    }
                }
            }
            if (imagesAdded > 0) config.setAugmentedImageDatabase(database);
            created.configure(config);
            arSession = created;
            cameraReady = true;
            arPreview.setTrackingSession(created);
            useArCamera();
            cameraStatus.setText("ARCore starting · move slowly across textured areas");
            modeValue.setText("Visual-inertial camera pose" + (imagesAdded > 0 ? " · 4 printed markers enabled" : " · marker images unavailable"));
            if (resumed) created.resume();
        } catch (Exception e) {
            if (created != null) try { created.close(); } catch (Exception ignored) { }
            arSession = null;
            showFallback("ARCore setup failed. Sensor-only mode is available.");
        }
    }

    private void useArCamera() {
        fallbackActive = false;
        fallbackPreview.stopPreview();
        fallbackPreview.setVisibility(View.GONE);
        arPreview.setVisibility(View.VISIBLE);
    }

    private void showFallback(String message) {
        fallbackActive = true;
        cameraReady = true;
        if (arSession != null) { try { arSession.pause(); } catch (Exception ignored) { } }
        arPreview.setVisibility(View.GONE);
        fallbackPreview.setVisibility(View.VISIBLE);
        if (resumed) fallbackPreview.startPreview();
        cameraStatus.setText("Camera preview · sensor fallback");
        statusLine.setText("Sensor dead-reckoning available");
        modeValue.setText(message + " Estimated path may drift; calibrate stride length.");
        refreshStats();
    }

    private void handleArFrame(ArCameraPreview.PoseUpdate update) {
        if (isFinishing() || isDestroyed()) return;
        arTracking = update.tracking;
        if (!update.tracking) {
            if (arSession != null) cameraStatus.setText("ARCore searching · point at a well-lit textured area");
            if (recording && currentSession != null && MODE_AR.equals(currentSession.mode)) statusLine.setText("Recording · waiting for visual tracking");
            return;
        }
        if (update.markers.isEmpty()) cameraStatus.setText("ARCore visual tracking · local pose");
        else {
            StringBuilder seen = new StringBuilder("LANDMARK IN VIEW · ");
            for (int i = 0; i < update.markers.size(); i++) {
                if (i > 0) seen.append(", ");
                seen.append(update.markers.get(i).name);
            }
            cameraStatus.setText(seen.toString());
        }
        if (!hasOrigin) {
            originX = update.x; originZ = update.z; originYaw = update.yaw; hasOrigin = true;
        }
        double[] camera = toMap(update.x, update.z);
        boolean markerChanged = processMarkers(update.markers);
        if (recording && currentSession != null && MODE_AR.equals(currentSession.mode)) {
            double x = camera[0] + correctionX, y = camera[1] + correctionY;
            appendArPoint(x, y);
            statusLine.setText("Recording · ARCore visual-inertial tracking");
            modeValue.setText("Relative path · ARCore camera pose" + (currentSession.markers.isEmpty() ? " · scan a printed marker to pin a landmark" : " · " + currentSession.markers.size() + " landmark(s) saved"));
        }
        if (markerChanged) updateMap();
    }

    private boolean processMarkers(List<ArCameraPreview.MarkerObservation> observations) {
        if (!recording || currentSession == null || !MODE_AR.equals(currentSession.mode) || !hasOrigin) return false;
        boolean changed = false;
        long now = SystemClock.elapsedRealtime();
        long elapsed = Math.max(0, now - recordingStartElapsed);
        for (ArCameraPreview.MarkerObservation observation : observations) {
            double[] observed = toMap(observation.x, observation.z);
            MarkerPoint anchor = findMarker(observation.name);
            Long last = lastMarkerUpdate.get(observation.name);
            if (anchor == null) {
                anchor = new MarkerPoint(observation.name, observed[0] + correctionX, observed[1] + correctionY, elapsed);
                currentSession.markers.add(anchor);
                lastMarkerUpdate.put(observation.name, now);
                changed = true;
            } else {
                double targetX = anchor.xMeters - observed[0];
                double targetY = anchor.yMeters - observed[1];
                // Smoothly align later sightings of the same printed image to the stored map pin.
                double nextX = correctionX + (targetX - correctionX) * 0.025;
                double nextY = correctionY + (targetY - correctionY) * 0.025;
                double dx = nextX - correctionX, dy = nextY - correctionY;
                if (Math.hypot(dx, dy) > 0.00005) {
                    correctionX = nextX; correctionY = nextY;
                    for (TrackPoint p : currentSession.points) { p.xMeters += dx; p.yMeters += dy; }
                    if (hasSmoothPose) { smoothX += dx; smoothY += dy; }
                    changed = true;
                }
                if (last == null || now - last > 1_000) { anchor.sightings++; lastMarkerUpdate.put(observation.name, now); }
            }
        }
        return changed;
    }

    private MarkerPoint findMarker(String name) {
        for (MarkerPoint marker : currentSession.markers) if (marker.name.equals(name)) return marker;
        return null;
    }

    private double[] toMap(double worldX, double worldZ) {
        double dx = worldX - originX, dz = worldZ - originZ;
        double c = Math.cos(originYaw), s = Math.sin(originYaw);
        return new double[]{dx * c - dz * s, -dx * s - dz * c};
    }

    private void appendArPoint(double x, double y) {
        long now = SystemClock.elapsedRealtime();
        if (!hasSmoothPose) { smoothX = x; smoothY = y; lastRawX = x; lastRawY = y; lastPoseTime = now; hasSmoothPose = true; }
        else {
            double dt = Math.max(0.05, (now - lastPoseTime) / 1000.0);
            double jump = Math.hypot(x - lastRawX, y - lastRawY);
            if (jump / dt > 3.5) { lastRawX = x; lastRawY = y; lastPoseTime = now; return; }
            smoothX += 0.62 * (x - smoothX); smoothY += 0.62 * (y - smoothY);
            lastRawX = x; lastRawY = y; lastPoseTime = now;
        }
        long elapsed = now - recordingStartElapsed;
        TrackPoint last = currentSession.points.isEmpty() ? null : currentSession.points.get(currentSession.points.size() - 1);
        if (last == null || Math.hypot(smoothX - last.xMeters, smoothY - last.yMeters) >= 0.025 || now - lastPointTime >= 900) {
            currentSession.stepCount = pdr.getSteps();
            currentSession.points.add(new TrackPoint(elapsed, smoothX, smoothY, currentSession.stepCount, "ARCore"));
            lastPointTime = now;
            updateMap();
        }
    }

    private void onPdrStep(double x, double y, int count) {
        if (!recording || currentSession == null) return;
        currentSession.stepCount = count;
        if (MODE_PDR.equals(currentSession.mode)) {
            long elapsed = SystemClock.elapsedRealtime() - recordingStartElapsed;
            currentSession.points.add(new TrackPoint(elapsed, x, y, count, "PDR"));
            updateMap();
        }
        refreshStats();
    }

    private void startRecording() {
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, CAMERA_REQUEST);
            toast("Allow camera access to start a session"); return;
        }
        if (!cameraReady) { toast("Camera setup is still in progress"); return; }
        if (recording) return;
        String mode = arSession != null ? MODE_AR : MODE_PDR;
        currentSession = new MotionSession(mode);
        recordingStartElapsed = SystemClock.elapsedRealtime();
        recording = true;
        hasOrigin = false; hasSmoothPose = false;
        correctionX = 0; correctionY = 0;
        lastPointTime = 0; lastMarkerUpdate.clear();
        pdr.reset(); pdr.start();
        statusLine.setText(mode.equals(MODE_AR) ? "Recording · waiting for ARCore pose" : "Recording · sensor-only estimated path");
        modeValue.setText(mode.equals(MODE_AR) ? "Move slowly; keep room detail in view and scan the printed markers." : "PDR fallback · stride " + String.format(Locale.US, "%.2f m", pdr.getStrideMeters()) + " · expected to drift more than ARCore.");
        refreshStats(); updateMap();
    }

    private void stopRecording() {
        if (!recording) return;
        recording = false; pdr.stop();
        currentSession.endedAtMs = System.currentTimeMillis();
        statusLine.setText("Session stopped · save or export your route");
        refreshStats();
    }

    private void resetSession() {
        if (recording) stopRecording();
        currentSession = null;
        hasOrigin = false; hasSmoothPose = false; correctionX = 0; correctionY = 0;
        pdr.reset(); statusLine.setText("Ready for a new indoor route");
        updateMap(); refreshStats();
    }

    private void saveCurrentSession() {
        if (recording) stopRecording();
        if (currentSession == null || currentSession.points.isEmpty()) { toast("Record some movement before saving"); return; }
        try { SessionData.Store.save(this, currentSession); toast("Session saved on this phone"); }
        catch (Exception e) { toast("Save failed: " + e.getLocalizedMessage()); }
    }

    private void showSavedSessions() {
        List<File> files = SessionData.Store.list(this);
        if (files.isEmpty()) { toast("No saved sessions yet"); return; }
        String[] labels = new String[files.size()];
        for (int i = 0; i < files.size(); i++) labels[i] = SessionData.Store.displayName(files.get(i));
        new AlertDialog.Builder(this).setTitle("Load a saved session").setItems(labels, (dialog, which) -> {
            if (recording) stopRecording();
            try {
                currentSession = SessionData.Store.load(files.get(which));
                statusLine.setText("Loaded local session");
                modeValue.setText(currentSession.mode + " · saved route and marker positions");
                updateMap(); refreshStats();
            } catch (Exception e) { toast("Could not load this session file"); }
        }).setNegativeButton("Cancel", null).show();
    }

    private void exportSession(String type) {
        if (recording) stopRecording();
        if (currentSession == null || currentSession.points.isEmpty()) { toast("Record or load a route before exporting"); return; }
        exportExtension = type;
        exportMime = type.equals("csv") ? "text/csv" : "application/json";
        exportText = type.equals("csv") ? SessionData.Store.toCsv(currentSession) : SessionData.Store.toJson(currentSession);
        String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date(currentSession.startedAtMs));
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType(exportMime);
        intent.putExtra(Intent.EXTRA_TITLE, "stridesense_" + stamp + "." + type);
        startActivityForResult(intent, EXPORT_REQUEST);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == EXPORT_REQUEST && resultCode == Activity.RESULT_OK && data != null && data.getData() != null) {
            Uri uri = data.getData();
            try (java.io.OutputStream out = getContentResolver().openOutputStream(uri)) {
                if (out == null) throw new java.io.IOException("No output stream");
                out.write(exportText.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                toast(exportExtension.toUpperCase(Locale.US) + " exported");
            } catch (Exception e) { toast("Export failed: " + e.getLocalizedMessage()); }
        }
    }

    private void showCalibration() {
        LinearLayout form = new LinearLayout(this); form.setOrientation(LinearLayout.VERTICAL); form.setPadding(dp(20), dp(8), dp(20), 0);
        TextView help = text("Walk a measured distance and enter how many steps you took. This only changes the sensor fallback estimate.", 13, muted, false);
        form.addView(help, margin(0, 0, 0, 10));
        EditText distance = new EditText(this); distance.setSingleLine(true); distance.setInputType(android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL); distance.setHint("Distance in metres (example: 10)"); distance.setText("10"); form.addView(distance);
        EditText count = new EditText(this); count.setSingleLine(true); count.setInputType(android.text.InputType.TYPE_CLASS_NUMBER); count.setHint("Number of steps (example: 14)"); form.addView(count);
        new AlertDialog.Builder(this).setTitle("Calibrate PDR step length").setView(form)
                .setPositiveButton("Save", (dialog, which) -> {
                    try {
                        double meters = Double.parseDouble(distance.getText().toString().trim());
                        int steps = Integer.parseInt(count.getText().toString().trim());
                        if (meters < 1 || meters > 100 || steps < 2) throw new NumberFormatException();
                        float stride = (float) (meters / steps);
                        if (stride < 0.35 || stride > 1.30) throw new NumberFormatException();
                        pdr.setStrideMeters(stride);
                        modeValue.setText("PDR stride calibrated to " + String.format(Locale.US, "%.2f m", stride) + " · ARCore route remains camera-based");
                        toast("PDR stride updated");
                    } catch (Exception e) { toast("Enter a measured distance and a valid step count"); }
                }).setNegativeButton("Cancel", null).show();
    }

    private void showMarkerGuide() {
        new AlertDialog.Builder(this).setTitle("Use visual landmarks")
                .setMessage("1. Print PRINT-MARKERS-A4.pdf at Actual size / 100%. Each square should be 8.5 cm wide.\n\n2. Cut out the four cards and place them flat on walls or furniture in a well-lit room.\n\n3. Start a route and point the rear camera at each card until its gold pin appears on the map.\n\n4. Seeing the same card later gives ARCore a repeated visual landmark and can reduce local position drift. Keep markers still and do not cover them.")
                .setPositiveButton("Got it", null).show();
    }

    private void refreshStats() {
        MotionSession session = currentSession;
        long elapsed = session == null ? 0 : (recording ? SystemClock.elapsedRealtime() - recordingStartElapsed : session.durationMs());
        timeValue.setText(String.format(Locale.US, "%02d:%02d", elapsed / 60_000, (elapsed / 1000) % 60));
        stepsValue.setText(String.valueOf(session == null ? 0 : session.stepCount));
        distanceValue.setText(String.format(Locale.US, "%.2f m", session == null ? 0 : session.distanceMeters()));
        int markerCount = session == null ? 0 : session.markers.size();
        markersValue.setText(String.valueOf(markerCount));
        markerBadge.setText(markerCount + " LANDMARK" + (markerCount == 1 ? "" : "S"));
        saveButton.setEnabled(session != null && !session.points.isEmpty());
        loadButton.setEnabled(true);
        startButton.setEnabled(!recording && cameraReady && checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED);
        stopButton.setEnabled(recording);
        updateButtonAppearance(startButton); updateButtonAppearance(stopButton); updateButtonAppearance(saveButton);
    }

    private void updateMap() {
        if (mapView != null) mapView.setData(currentSession == null ? new ArrayList<>() : currentSession.points,
                currentSession == null ? new ArrayList<>() : currentSession.markers);
        refreshStats();
    }

    private void showPermissionHelp() {
        statusLine.setText("Allow camera access in Android app settings to start tracking");
        cameraStatus.setText("Camera permission is required");
    }

    @Override protected void onResume() {
        super.onResume(); resumed = true;
        if (arPreview != null) arPreview.onResume();
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) prepareArCore();
    }

    @Override protected void onPause() {
        resumed = false;
        if (recording) stopRecording();
        if (arSession != null) try { arSession.pause(); } catch (Exception ignored) { }
        if (arPreview != null) arPreview.onPause();
        if (fallbackPreview != null) fallbackPreview.stopPreview();
        super.onPause();
    }

    @Override protected void onDestroy() {
        handler.removeCallbacks(ticker);
        if (pdr != null) pdr.stop();
        if (arSession != null) { try { arSession.close(); } catch (Exception ignored) { } arSession = null; }
        super.onDestroy();
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grants) {
        super.onRequestPermissionsResult(requestCode, permissions, grants);
        if (requestCode == CAMERA_REQUEST) {
            if (grants.length > 0 && grants[0] == PackageManager.PERMISSION_GRANTED) prepareArCore();
            else showPermissionHelp();
        }
    }

    private LinearLayout card() { LinearLayout v = new LinearLayout(this); v.setOrientation(LinearLayout.VERTICAL); v.setBackground(rounded(surface, 17)); return v; }
    private LinearLayout row() { LinearLayout v = new LinearLayout(this); v.setOrientation(LinearLayout.HORIZONTAL); v.setGravity(Gravity.CENTER_VERTICAL); return v; }
    private TextView text(String value, float size, int color, boolean bold) {
        TextView v = new TextView(this); v.setText(value); v.setTextSize(size); v.setTextColor(color); v.setIncludeFontPadding(false);
        if (bold) v.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL), Typeface.BOLD);
        return v;
    }
    private TextView pill(String value, int color) {
        TextView v = text(value, 8, color, true); v.setBackground(rounded(Color.argb(35, Color.red(color), Color.green(color), Color.blue(color)), 40));
        v.setPadding(dp(9), dp(6), dp(9), dp(6)); return v;
    }
    private View space(int width) { View v = new View(this); v.setLayoutParams(new LinearLayout.LayoutParams(dp(width), 1)); return v; }
    private LinearLayout.LayoutParams margin(int left, int top, int right, int bottom) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2); p.setMargins(left, top, right, bottom); return p;
    }
    private LinearLayout.LayoutParams weightedButton() { LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(42), 1); p.setMargins(dp(3), 0, dp(3), 0); return p; }
    private TextView action(String label, boolean primary, Runnable click) {
        TextView v = text(label, 10, primary ? ink : white, true); v.setGravity(Gravity.CENTER); v.setMaxLines(1);
        v.setPadding(dp(3), 0, dp(3), 0); v.setBackground(rounded(primary ? mint : raised, 11)); v.setClickable(true); v.setFocusable(true);
        v.setOnClickListener(view -> click.run()); return v;
    }
    private void updateButtonAppearance(TextView button) { if (button != null) button.setAlpha(button.isEnabled() ? 1f : 0.42f); }
    private View separator() { View v = new View(this); v.setBackgroundColor(0xff334650); v.setLayoutParams(new LinearLayout.LayoutParams(dp(1), dp(30))); return v; }
    private Stat stat(String label, String value) {
        LinearLayout col = new LinearLayout(this); col.setOrientation(LinearLayout.VERTICAL); col.setGravity(Gravity.CENTER); col.setPadding(dp(2), dp(3), dp(2), dp(3));
        col.addView(text(label, 8, muted, true), new LinearLayout.LayoutParams(-2, -2));
        TextView v = text(value, 13, white, true); v.setPadding(0, dp(7), 0, 0); col.addView(v); return new Stat(col, v);
    }
    private static final class Stat { final View view; final TextView value; Stat(View view, TextView value) { this.view = view; this.value = value; } }
    private GradientDrawable rounded(int color, int radius) { GradientDrawable d = new GradientDrawable(); d.setColor(color); d.setCornerRadius(dp(radius)); return d; }
    private int dp(float value) { return (int) (value * getResources().getDisplayMetrics().density + 0.5f); }
    private void toast(String message) { Toast.makeText(this, message, Toast.LENGTH_SHORT).show(); }
}
