package com.stridesense.app

import android.content.Context
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.util.AttributeSet
import android.view.Display
import android.view.Surface
import android.view.WindowManager
import com.google.ar.core.Coordinates2d
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import com.google.ar.core.exceptions.CameraNotAvailableException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

class ArCameraPreview @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    private val onPose: (x: Float, z: Float, tracking: Boolean) -> Unit
) : GLSurfaceView(context, attrs), GLSurfaceView.Renderer {
    @Volatile private var arSession: Session? = null
    private var cameraTextureId = 0
    private var program = 0
    private var positionHandle = 0
    private var texCoordHandle = 0
    private var samplerHandle = 0
    private val vertices = floatBuffer(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f))
    private val ndcBuffer = floatBuffer(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f))
    private val transformedTexCoords: FloatBuffer = floatBuffer(FloatArray(8))
    private var viewWidth = 0
    private var viewHeight = 0
    private var lastStatusCallback = 0L
    private var attachedSession: Session? = null
    private val display: Display? = (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay

    init {
        setEGLContextClientVersion(2)
        preserveEGLContextOnPause = true
        setRenderer(this)
        renderMode = RENDERMODE_CONTINUOUSLY
    }

    fun setTrackingSession(session: Session?) {
        arSession = session
        if (cameraTextureId != 0) {
            queueEvent { attachCameraTexture(session) }
        }
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0.02f, 0.04f, 0.06f, 1f)
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        cameraTextureId = textures[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTextureId)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        program = createProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        positionHandle = GLES20.glGetAttribLocation(program, "a_Position")
        texCoordHandle = GLES20.glGetAttribLocation(program, "a_TexCoord")
        samplerHandle = GLES20.glGetUniformLocation(program, "u_Texture")
        attachedSession = null
        attachCameraTexture(arSession)
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        viewWidth = width
        viewHeight = height
        GLES20.glViewport(0, 0, width, height)
        updateDisplayGeometry()
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        val session = arSession ?: return
        try {
            if (attachedSession !== session) attachCameraTexture(session)
            if (viewWidth > 0 && viewHeight > 0) session.setDisplayGeometry(display?.rotation ?: Surface.ROTATION_0, viewWidth, viewHeight)
            val frame = session.update()
            transformedTexCoords.clear()
            frame.transformCoordinates2d(
                Coordinates2d.OPENGL_NORMALIZED_DEVICE_COORDINATES,
                ndcBuffer,
                Coordinates2d.TEXTURE_NORMALIZED,
                transformedTexCoords
            )
            drawCameraBackground()
            val camera = frame.camera
            val now = android.os.SystemClock.elapsedRealtime()
            if (now - lastStatusCallback >= 100L) {
                lastStatusCallback = now
                if (camera.trackingState == TrackingState.TRACKING) {
                    val translation = camera.pose.translation
                    onPose(translation[0], translation[2], true)
                } else {
                    onPose(0f, 0f, false)
                }
            }
        } catch (error: CameraNotAvailableException) {
            val now = android.os.SystemClock.elapsedRealtime()
            if (now - lastStatusCallback >= 500L) {
                lastStatusCallback = now
                onPose(0f, 0f, false)
            }
        } catch (_: Exception) {
            val now = android.os.SystemClock.elapsedRealtime()
            if (now - lastStatusCallback >= 500L) {
                lastStatusCallback = now
                onPose(0f, 0f, false)
            }
        }
    }

    fun updateDisplayGeometry() {
        queueEvent {
            try {
                val session = arSession
                if (session != null && viewWidth > 0 && viewHeight > 0) {
                    session.setDisplayGeometry(display?.rotation ?: Surface.ROTATION_0, viewWidth, viewHeight)
                }
            } catch (_: Exception) { }
        }
    }

    private fun attachCameraTexture(session: Session?) {
        if (session == null || cameraTextureId == 0 || attachedSession === session) return
        try {
            session.setCameraTextureName(cameraTextureId)
            attachedSession = session
        } catch (_: Exception) { }
    }

    private fun drawCameraBackground() {
        if (program == 0) return
        transformedTexCoords.position(0)
        vertices.position(0)
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTextureId)
        GLES20.glUniform1i(samplerHandle, 0)
        GLES20.glEnableVertexAttribArray(positionHandle)
        GLES20.glVertexAttribPointer(positionHandle, 2, GLES20.GL_FLOAT, false, 0, vertices)
        GLES20.glEnableVertexAttribArray(texCoordHandle)
        GLES20.glVertexAttribPointer(texCoordHandle, 2, GLES20.GL_FLOAT, false, 0, transformedTexCoords)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(positionHandle)
        GLES20.glDisableVertexAttribArray(texCoordHandle)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
    }

    private fun createProgram(vertexSource: String, fragmentSource: String): Int {
        val vertex = compileShader(GLES20.GL_VERTEX_SHADER, vertexSource)
        val fragment = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource)
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, vertex)
        GLES20.glAttachShader(p, fragment)
        GLES20.glLinkProgram(p)
        val status = IntArray(1)
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, status, 0)
        if (status[0] == 0) {
            val reason = GLES20.glGetProgramInfoLog(p)
            GLES20.glDeleteProgram(p)
            throw IllegalStateException("Camera preview shader link failed: $reason")
        }
        return p
    }

    private fun compileShader(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val status = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            val reason = GLES20.glGetShaderInfoLog(shader)
            GLES20.glDeleteShader(shader)
            throw IllegalStateException("Camera preview shader compile failed: $reason")
        }
        return shader
    }

    companion object {
        private const val VERTEX_SHADER = """
            attribute vec4 a_Position;
            attribute vec2 a_TexCoord;
            varying vec2 v_TexCoord;
            void main() {
                gl_Position = a_Position;
                v_TexCoord = a_TexCoord;
            }
        """
        private const val FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            uniform samplerExternalOES u_Texture;
            varying vec2 v_TexCoord;
            void main() {
                gl_FragColor = texture2D(u_Texture, v_TexCoord);
            }
        """

        private fun floatBuffer(values: FloatArray): FloatBuffer = ByteBuffer
            .allocateDirect(values.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply { put(values); position(0) }
    }
}
