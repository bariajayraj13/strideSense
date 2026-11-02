package com.stridesense.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.os.Handler
import android.os.HandlerThread
import android.util.Size
import android.view.Surface
import android.view.TextureView

/** A camera-only preview used when ARCore cannot run on this device. */
class FallbackCameraPreview(context: Context) : TextureView(context), TextureView.SurfaceTextureListener {
    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private var cameraThread: HandlerThread? = null
    private var cameraHandler: Handler? = null
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var previewSurface: Surface? = null
    private var active = false

    init {
        surfaceTextureListener = this
    }

    fun startPreview() {
        if (active) return
        active = true
        cameraThread = HandlerThread("StrideSenseCamera").also { it.start() }
        cameraHandler = Handler(cameraThread!!.looper)
        if (isAvailable) openCamera()
    }

    fun stopPreview() {
        active = false
        captureSession?.close()
        captureSession = null
        cameraDevice?.close()
        cameraDevice = null
        previewSurface?.release()
        previewSurface = null
        cameraThread?.quitSafely()
        cameraThread = null
        cameraHandler = null
    }

    private fun openCamera() {
        if (!active || cameraDevice != null || !isAvailable) return
        if (context.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return
        try {
            val cameraId = cameraManager.cameraIdList.firstOrNull { id ->
                cameraManager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
            } ?: cameraManager.cameraIdList.firstOrNull() ?: return
            cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    if (!active) {
                        camera.close()
                        return
                    }
                    cameraDevice = camera
                    createPreview(camera)
                }

                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    if (cameraDevice === camera) cameraDevice = null
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                    if (cameraDevice === camera) cameraDevice = null
                }
            }, cameraHandler)
        } catch (_: Exception) { }
    }

    private fun createPreview(camera: CameraDevice) {
        val texture: SurfaceTexture = surfaceTexture ?: return
        val sizes = cameraManager.getCameraCharacteristics(camera.id)
            .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?.getOutputSizes(SurfaceTexture::class.java)
            .orEmpty()
        val size = sizes.firstOrNull { it.width >= 960 && it.height >= 540 }
            ?: sizes.firstOrNull()
            ?: Size(1280, 720)
        texture.setDefaultBufferSize(size.width, size.height)
        val surface = Surface(texture)
        previewSurface = surface
        try {
            val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(surface)
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            }.build()
            camera.createCaptureSession(listOf(surface), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    if (!active) {
                        session.close()
                        return
                    }
                    captureSession = session
                    runCatching { session.setRepeatingRequest(request, null, cameraHandler) }
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    session.close()
                }
            }, cameraHandler)
        } catch (_: Exception) { }
    }

    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
        if (active) openCamera()
    }

    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) = Unit

    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
        stopPreview()
        return true
    }

    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
}
