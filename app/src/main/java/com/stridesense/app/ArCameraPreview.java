package com.stridesense.app;

import android.content.Context;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.Display;
import android.view.Surface;
import android.view.WindowManager;
import com.google.ar.core.AugmentedImage;
import com.google.ar.core.Camera;
import com.google.ar.core.Coordinates2d;
import com.google.ar.core.Frame;
import com.google.ar.core.Pose;
import com.google.ar.core.Session;
import com.google.ar.core.TrackingState;
import com.google.ar.core.exceptions.CameraNotAvailableException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/** ARCore-owned camera texture and Java pose/registered-image callbacks. */
final class ArCameraPreview extends GLSurfaceView implements GLSurfaceView.Renderer {
    interface Listener { void onFrame(PoseUpdate update); }
    static final class MarkerObservation {
        final String name;
        final double x, y, z, yaw;
        MarkerObservation(String name, Pose pose) {
            this.name = name;
            float[] t = pose.getTranslation(), q = pose.getRotationQuaternion();
            x = t[0]; y = t[1]; z = t[2]; yaw = yaw(q);
        }
    }
    static final class PoseUpdate {
        final boolean tracking;
        final double x, y, z, yaw;
        final List<MarkerObservation> markers;
        PoseUpdate(boolean tracking, double x, double y, double z, double yaw, List<MarkerObservation> markers) {
            this.tracking = tracking; this.x = x; this.y = y; this.z = z; this.yaw = yaw; this.markers = markers;
        }
    }

    private volatile Session session;
    private Session attachedSession;
    private final Listener listener;
    private final FloatBuffer vertices = floatBuffer(new float[]{-1,-1, 1,-1, -1,1, 1,1});
    private final FloatBuffer ndc = floatBuffer(new float[]{-1,-1, 1,-1, -1,1, 1,1});
    private final FloatBuffer textureCoords = floatBuffer(new float[8]);
    private final Display display;
    private int textureId, program, positionHandle, texCoordHandle, samplerHandle;
    private int viewWidth, viewHeight;
    private long lastCallbackMs;

    ArCameraPreview(Context context, Listener listener) { this(context, null, listener); }
    ArCameraPreview(Context context, AttributeSet attrs, Listener listener) {
        super(context, attrs);
        this.listener = listener;
        display = ((WindowManager) context.getSystemService(Context.WINDOW_SERVICE)).getDefaultDisplay();
        setEGLContextClientVersion(2);
        setPreserveEGLContextOnPause(true);
        setRenderer(this);
        setRenderMode(RENDERMODE_CONTINUOUSLY);
    }

    void setTrackingSession(Session session) {
        this.session = session;
        if (textureId != 0) queueEvent(() -> attachCameraTexture(session));
    }

    @Override public void onSurfaceCreated(GL10 gl, EGLConfig config) {
        GLES20.glClearColor(0.02f, 0.04f, 0.06f, 1f);
        int[] ids = new int[1];
        GLES20.glGenTextures(1, ids, 0);
        textureId = ids[0];
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        program = createProgram(VERTEX_SHADER, FRAGMENT_SHADER);
        positionHandle = GLES20.glGetAttribLocation(program, "a_Position");
        texCoordHandle = GLES20.glGetAttribLocation(program, "a_TexCoord");
        samplerHandle = GLES20.glGetUniformLocation(program, "u_Texture");
        attachedSession = null;
        attachCameraTexture(session);
    }

    @Override public void onSurfaceChanged(GL10 gl, int width, int height) {
        viewWidth = width; viewHeight = height;
        GLES20.glViewport(0, 0, width, height);
        updateGeometry();
    }

    @Override public void onDrawFrame(GL10 gl) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT | GLES20.GL_DEPTH_BUFFER_BIT);
        Session active = session;
        if (active == null) return;
        try {
            if (attachedSession != active) attachCameraTexture(active);
            if (viewWidth > 0 && viewHeight > 0) active.setDisplayGeometry(display.getRotation(), viewWidth, viewHeight);
            Frame frame = active.update();
            textureCoords.clear();
            frame.transformCoordinates2d(Coordinates2d.OPENGL_NORMALIZED_DEVICE_COORDINATES, ndc,
                    Coordinates2d.TEXTURE_NORMALIZED, textureCoords);
            drawBackground();
            long now = SystemClock.elapsedRealtime();
            if (now - lastCallbackMs >= 100) {
                lastCallbackMs = now;
                Camera camera = frame.getCamera();
                if (camera.getTrackingState() == TrackingState.TRACKING) {
                    Pose pose = camera.getPose();
                    float[] t = pose.getTranslation();
                    List<MarkerObservation> markerList = new ArrayList<>();
                    Collection<AugmentedImage> images = active.getAllTrackables(AugmentedImage.class);
                    for (AugmentedImage image : images) {
                        if (image.getTrackingState() == TrackingState.TRACKING)
                            markerList.add(new MarkerObservation(image.getName(), image.getCenterPose()));
                    }
                    listener.onFrame(new PoseUpdate(true, t[0], t[1], t[2], yaw(pose.getRotationQuaternion()), markerList));
                } else {
                    listener.onFrame(new PoseUpdate(false, 0, 0, 0, 0, new ArrayList<>()));
                }
            }
        } catch (CameraNotAvailableException | IllegalStateException ignored) {
            reportNotTracking();
        } catch (Exception ignored) {
            reportNotTracking();
        }
    }

    private void reportNotTracking() {
        long now = SystemClock.elapsedRealtime();
        if (now - lastCallbackMs >= 500) {
            lastCallbackMs = now;
            listener.onFrame(new PoseUpdate(false, 0, 0, 0, 0, new ArrayList<>()));
        }
    }

    private void updateGeometry() {
        queueEvent(() -> {
            try { if (session != null && viewWidth > 0 && viewHeight > 0) session.setDisplayGeometry(display.getRotation(), viewWidth, viewHeight); }
            catch (Exception ignored) { }
        });
    }

    private void attachCameraTexture(Session active) {
        if (active == null || textureId == 0 || attachedSession == active) return;
        try { active.setCameraTextureName(textureId); attachedSession = active; }
        catch (Exception ignored) { }
    }

    private void drawBackground() {
        if (program == 0) return;
        textureCoords.position(0); vertices.position(0);
        GLES20.glDisable(GLES20.GL_DEPTH_TEST);
        GLES20.glUseProgram(program);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId);
        GLES20.glUniform1i(samplerHandle, 0);
        GLES20.glEnableVertexAttribArray(positionHandle);
        GLES20.glVertexAttribPointer(positionHandle, 2, GLES20.GL_FLOAT, false, 0, vertices);
        GLES20.glEnableVertexAttribArray(texCoordHandle);
        GLES20.glVertexAttribPointer(texCoordHandle, 2, GLES20.GL_FLOAT, false, 0, textureCoords);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        GLES20.glDisableVertexAttribArray(positionHandle);
        GLES20.glDisableVertexAttribArray(texCoordHandle);
        GLES20.glEnable(GLES20.GL_DEPTH_TEST);
    }

    private static int createProgram(String vertex, String fragment) {
        int vs = compile(GLES20.GL_VERTEX_SHADER, vertex), fs = compile(GLES20.GL_FRAGMENT_SHADER, fragment);
        int p = GLES20.glCreateProgram(); GLES20.glAttachShader(p, vs); GLES20.glAttachShader(p, fs); GLES20.glLinkProgram(p);
        int[] status = new int[1]; GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, status, 0);
        if (status[0] == 0) { String reason = GLES20.glGetProgramInfoLog(p); GLES20.glDeleteProgram(p); throw new IllegalStateException(reason); }
        return p;
    }

    private static int compile(int type, String source) {
        int shader = GLES20.glCreateShader(type); GLES20.glShaderSource(shader, source); GLES20.glCompileShader(shader);
        int[] status = new int[1]; GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0);
        if (status[0] == 0) { String reason = GLES20.glGetShaderInfoLog(shader); GLES20.glDeleteShader(shader); throw new IllegalStateException(reason); }
        return shader;
    }

    private static float yaw(float[] q) {
        double x = q[0], y = q[1], z = q[2], w = q[3];
        return (float) Math.atan2(2.0 * (w * y + x * z), 1.0 - 2.0 * (y * y + z * z));
    }
    private static FloatBuffer floatBuffer(float[] values) {
        FloatBuffer b = ByteBuffer.allocateDirect(values.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        b.put(values).position(0); return b;
    }

    private static final String VERTEX_SHADER = "attribute vec4 a_Position; attribute vec2 a_TexCoord; varying vec2 v_TexCoord; void main(){gl_Position=a_Position; v_TexCoord=a_TexCoord;}";
    private static final String FRAGMENT_SHADER = "#extension GL_OES_EGL_image_external : require\nprecision mediump float; uniform samplerExternalOES u_Texture; varying vec2 v_TexCoord; void main(){gl_FragColor=texture2D(u_Texture,v_TexCoord);}";
}
