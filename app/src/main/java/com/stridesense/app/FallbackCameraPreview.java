package com.stridesense.app;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Size;
import android.view.Surface;
import android.view.TextureView;
import java.util.Collections;

/** Live camera-only fallback when ARCore is unavailable. It does not estimate visual motion. */
final class FallbackCameraPreview extends TextureView implements TextureView.SurfaceTextureListener {
    private final CameraManager manager;
    private HandlerThread thread;
    private Handler handler;
    private CameraDevice device;
    private CameraCaptureSession capture;
    private Surface previewSurface;
    private boolean active;

    FallbackCameraPreview(Context context) {
        super(context);
        manager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
        setSurfaceTextureListener(this);
    }

    void startPreview() {
        if (active) return;
        active = true;
        thread = new HandlerThread("StrideSenseCamera"); thread.start(); handler = new Handler(thread.getLooper());
        if (isAvailable()) openCamera();
    }

    void stopPreview() {
        active = false;
        if (capture != null) { capture.close(); capture = null; }
        if (device != null) { device.close(); device = null; }
        if (previewSurface != null) { previewSurface.release(); previewSurface = null; }
        if (thread != null) { thread.quitSafely(); thread = null; handler = null; }
    }

    private void openCamera() {
        if (!active || device != null || !isAvailable() || getContext().checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return;
        try {
            String cameraId = null;
            for (String id : manager.getCameraIdList()) {
                Integer facing = manager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING);
                if (facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) { cameraId = id; break; }
            }
            if (cameraId == null && manager.getCameraIdList().length > 0) cameraId = manager.getCameraIdList()[0];
            if (cameraId == null) return;
            manager.openCamera(cameraId, new CameraDevice.StateCallback() {
                @Override public void onOpened(CameraDevice camera) { if (!active) { camera.close(); return; } device = camera; createPreview(camera); }
                @Override public void onDisconnected(CameraDevice camera) { camera.close(); if (device == camera) device = null; }
                @Override public void onError(CameraDevice camera, int error) { camera.close(); if (device == camera) device = null; }
            }, handler);
        } catch (Exception ignored) { }
    }

    private void createPreview(CameraDevice camera) {
        SurfaceTexture texture = getSurfaceTexture();
        if (texture == null) return;
        try {
            Size chosen = new Size(1280, 720);
            android.hardware.camera2.params.StreamConfigurationMap map = manager.getCameraCharacteristics(camera.getId()).get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            if (map != null) {
                Size[] available = map.getOutputSizes(SurfaceTexture.class);
                if (available != null && available.length > 0) {
                    for (Size candidate : available) if (candidate.getWidth() >= 960 && candidate.getHeight() >= 540) { chosen = candidate; break; }
                    if (chosen.getWidth() == 1280 && chosen.getHeight() == 720 && available.length > 0) chosen = available[0];
                }
            }
            texture.setDefaultBufferSize(chosen.getWidth(), chosen.getHeight());
            previewSurface = new Surface(texture);
            CaptureRequest.Builder builder = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            builder.addTarget(previewSurface);
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
            builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);
            camera.createCaptureSession(Collections.singletonList(previewSurface), new CameraCaptureSession.StateCallback() {
                @Override public void onConfigured(CameraCaptureSession session) {
                    if (!active) { session.close(); return; }
                    capture = session;
                    try { session.setRepeatingRequest(builder.build(), null, handler); } catch (Exception ignored) { }
                }
                @Override public void onConfigureFailed(CameraCaptureSession session) { session.close(); }
            }, handler);
        } catch (Exception ignored) { }
    }

    @Override public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) { if (active) openCamera(); }
    @Override public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) { }
    @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) { stopPreview(); return true; }
    @Override public void onSurfaceTextureUpdated(SurfaceTexture surface) { }
}
