package com.example.vrsbsplayer.render

import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.util.Log
import android.view.Surface
import com.example.vrsbsplayer.profile.ViewerProfile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * Renders the decoded video twice, once per eye, applying:
 *   1. the SBS / TB source split (StereoMode)
 *   2. Cardboard lens pre-distortion (EyeOptics + ShaderSource)
 *   3. the user's stereo separation, offset and zoom
 *
 * No 360 sphere, no duplicated frame: each eye samples only its own half of the source,
 * straight from the decoder's external texture, with no CPU copy anywhere.
 */
class StereoGLRenderer(
    private val onSurfaceReady: (Surface) -> Unit,
    private val onFrameAvailable: () -> Unit
) : android.opengl.GLSurfaceView.Renderer {

    @Volatile var profile: ViewerProfile = ViewerProfile(id = "tmp", name = "tmp")
    @Volatile var stereoMode: StereoMode = StereoMode.FULL_SBS

    /** Aspect ratio of the whole decoded frame, updated from the player. */
    @Volatile var frameAspect: Float = 16f / 9f

    private var program = 0
    private var videoTextureId = 0
    private var surfaceTexture: SurfaceTexture? = null
    private var videoSurface: Surface? = null
    private val stMatrix = FloatArray(16)
    private var hasFrame = false

    private var aPositionLoc = 0
    private var uVideoTextureLoc = 0
    private var uTexMatrixLoc = 0
    private var uHalfSizeMmLoc = 0
    private var uLensCenterOffsetMmLoc = 0
    private var uScreenToLensMmLoc = 0
    private var uKLoc = 0
    private var uFovTanLoc = 0
    private var uImageHalfTanLoc = 0
    private var uCropRectLoc = 0
    private var uExtraShiftLoc = 0
    private var uProjectionModeLoc = 0
    private var uCinemaHalfAngleRadLoc = 0

    private var screenWidthPx = 0
    private var screenHeightPx = 0
    private var screenWidthMm = 0f
    private var screenHeightMm = 0f

    private val quadVertices = floatArrayOf(
        -1f, -1f, 0f,
        1f, -1f, 0f,
        -1f, 1f, 0f,
        1f, 1f, 0f
    )
    private val quadBuffer: FloatBuffer = ByteBuffer.allocateDirect(quadVertices.size * 4)
        .order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(quadVertices); position(0) }

    @Volatile private var frameReady = false

    fun setScreenPhysicalSizeMm(widthMm: Float, heightMm: Float) {
        screenWidthMm = widthMm
        screenHeightMm = heightMm
    }

    /** Called from the player once the real video size is known. */
    fun setVideoSize(width: Int, height: Int, pixelWidthHeightRatio: Float) {
        if (width <= 0 || height <= 0) return
        val par = if (pixelWidthHeightRatio > 0f) pixelWidthHeightRatio else 1f
        frameAspect = (width * par) / height
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glDisable(GLES20.GL_CULL_FACE)
        hasFrame = false
        System.arraycopy(EyeOptics.IDENTITY, 0, stMatrix, 0, 16)

        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        videoTextureId = textures[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, videoTextureId)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        surfaceTexture = SurfaceTexture(videoTextureId).apply {
            setOnFrameAvailableListener {
                frameReady = true
                onFrameAvailable()
            }
        }
        videoSurface?.release()
        val surface = Surface(surfaceTexture)
        videoSurface = surface

        program = buildProgram(ShaderSource.VERTEX_SHADER, ShaderSource.FRAGMENT_SHADER)
        aPositionLoc = GLES20.glGetAttribLocation(program, "aPosition")
        uVideoTextureLoc = GLES20.glGetUniformLocation(program, "uVideoTexture")
        uTexMatrixLoc = GLES20.glGetUniformLocation(program, "uTexMatrix")
        uHalfSizeMmLoc = GLES20.glGetUniformLocation(program, "uHalfSizeMm")
        uLensCenterOffsetMmLoc = GLES20.glGetUniformLocation(program, "uLensCenterOffsetMm")
        uScreenToLensMmLoc = GLES20.glGetUniformLocation(program, "uScreenToLensMm")
        uKLoc = GLES20.glGetUniformLocation(program, "uK")
        uFovTanLoc = GLES20.glGetUniformLocation(program, "uFovTan")
        uImageHalfTanLoc = GLES20.glGetUniformLocation(program, "uImageHalfTan")
        uCropRectLoc = GLES20.glGetUniformLocation(program, "uCropRect")
        uExtraShiftLoc = GLES20.glGetUniformLocation(program, "uExtraShift")
        uProjectionModeLoc = GLES20.glGetUniformLocation(program, "uProjectionMode")
        uCinemaHalfAngleRadLoc = GLES20.glGetUniformLocation(program, "uCinemaHalfAngleRad")

        onSurfaceReady(surface)
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        screenWidthPx = if (width > 0) width else 1
        screenHeightPx = if (height > 0) height else 1
        GLES20.glViewport(0, 0, width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        if (frameReady) {
            frameReady = false
            surfaceTexture?.let { st ->
                st.updateTexImage()
                st.getTransformMatrix(stMatrix)
                hasFrame = true
            }
        }
        if (!hasFrame || screenWidthPx == 0 || screenHeightPx == 0) return

        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, videoTextureId)
        GLES20.glUniform1i(uVideoTextureLoc, 0)
        GLES20.glUniformMatrix4fv(uTexMatrixLoc, 1, false, stMatrix, 0)

        quadBuffer.position(0)
        GLES20.glVertexAttribPointer(aPositionLoc, 3, GLES20.GL_FLOAT, false, 12, quadBuffer)
        GLES20.glEnableVertexAttribArray(aPositionLoc)

        drawEye(isLeftEye = true, viewportX = 0)
        drawEye(isLeftEye = false, viewportX = screenWidthPx / 2)

        GLES20.glDisableVertexAttribArray(aPositionLoc)
    }

    private fun drawEye(isLeftEye: Boolean, viewportX: Int) {
        GLES20.glViewport(viewportX, 0, screenWidthPx / 2, screenHeightPx)

        val p = profile
        val mode = stereoMode
        val eye = EyeOptics.compute(
            profile = p,
            screenWidthMm = screenWidthMm,
            screenHeightMm = screenHeightMm,
            isLeftEye = isLeftEye,
            imageAspect = mode.eyeAspect(frameAspect)
        )

        GLES20.glUniform2f(uHalfSizeMmLoc, eye.halfWidthMm, eye.halfHeightMm)
        GLES20.glUniform2f(uLensCenterOffsetMmLoc, eye.lensOffsetXMm, eye.lensOffsetYMm)
        GLES20.glUniform1f(uScreenToLensMmLoc, p.screenToLensMm)
        GLES20.glUniform3f(uKLoc, p.distortionK1, p.distortionK2, p.distortionK3)
        GLES20.glUniform4f(
            uFovTanLoc, eye.fovTanLeft, eye.fovTanRight, eye.fovTanBottom, eye.fovTanTop
        )
        GLES20.glUniform2f(uImageHalfTanLoc, eye.imageHalfTanX, eye.imageHalfTanY)
        GLES20.glUniform1f(
            uProjectionModeLoc,
            if (p.projectionMode == ViewerProfile.ProjectionMode.CINEMA) 1f else 0f
        )
        GLES20.glUniform1f(uCinemaHalfAngleRadLoc, eye.cinemaHalfAngleRad)

        val crop = mode.eyeCropRect(isLeftEye)
        GLES20.glUniform4f(uCropRectLoc, crop[0], crop[1], crop[2], crop[3])

        // Stereo separation is a parallax shift of the image inside each eye. It is a
        // completely different quantity from the physical inter-lens distance above.
        val sepFrac = (p.stereoSeparationPercent / 100f) * 0.1f
        val eyeSign = if (isLeftEye) -1f else 1f
        GLES20.glUniform2f(
            uExtraShiftLoc,
            p.horizontalOffsetPercent / 100f + eyeSign * sepFrac,
            p.verticalOffsetPercent / 100f
        )

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }

    private fun buildProgram(vertexSrc: String, fragmentSrc: String): Int {
        val vs = compileShader(GLES20.GL_VERTEX_SHADER, vertexSrc)
        val fs = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentSrc)
        val prog = GLES20.glCreateProgram()
        GLES20.glAttachShader(prog, vs)
        GLES20.glAttachShader(prog, fs)
        GLES20.glLinkProgram(prog)
        val linked = IntArray(1)
        GLES20.glGetProgramiv(prog, GLES20.GL_LINK_STATUS, linked, 0)
        if (linked[0] == 0) {
            Log.e("StereoGLRenderer", "Program link failed: ${GLES20.glGetProgramInfoLog(prog)}")
        }
        GLES20.glDeleteShader(vs)
        GLES20.glDeleteShader(fs)
        return prog
    }

    private fun compileShader(type: Int, src: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, src)
        GLES20.glCompileShader(shader)
        val compiled = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0)
        if (compiled[0] == 0) {
            Log.e("StereoGLRenderer", "Shader compile failed: ${GLES20.glGetShaderInfoLog(shader)}")
        }
        return shader
    }

    fun release() {
        surfaceTexture?.setOnFrameAvailableListener(null)
        surfaceTexture?.release()
        surfaceTexture = null
        videoSurface?.release()
        videoSurface = null
    }
}
