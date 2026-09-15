package com.example.vrsbsplayer.render

import android.graphics.Bitmap
import android.opengl.GLES20
import android.opengl.GLUtils
import android.util.Log
import com.example.vrsbsplayer.profile.ViewerProfile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * The calibration screen. Exactly the same optics as the video renderer, but the source
 * is a generated Full SBS test pattern bitmap instead of a decoded frame, so what you see
 * through the lenses is a direct measurement of the current profile.
 */
class TestPatternGLRenderer : android.opengl.GLSurfaceView.Renderer {

    @Volatile var profile: ViewerProfile = ViewerProfile(id = "tmp", name = "tmp")

    private var program = 0
    private var textureId = 0

    private var aPositionLoc = 0
    private var uTextureLoc = 0
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

    /** The generated pattern is square per eye, so one eye image is 1:1. */
    private val eyeImageAspect = 1.0f

    private val quadVertices = floatArrayOf(-1f, -1f, 0f, 1f, -1f, 0f, -1f, 1f, 0f, 1f, 1f, 0f)
    private val quadBuffer: FloatBuffer = ByteBuffer.allocateDirect(quadVertices.size * 4)
        .order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(quadVertices); position(0) }

    fun setScreenPhysicalSizeMm(w: Float, h: Float) {
        screenWidthMm = w
        screenHeightMm = h
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glDisable(GLES20.GL_CULL_FACE)

        val bitmap: Bitmap = TestPatternGenerator.generate()
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        textureId = textures[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
        bitmap.recycle()

        program = buildProgram(ShaderSource.VERTEX_SHADER, ShaderSource.FRAGMENT_SHADER_2D)
        aPositionLoc = GLES20.glGetAttribLocation(program, "aPosition")
        uTextureLoc = GLES20.glGetUniformLocation(program, "uVideoTexture")
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
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        screenWidthPx = if (width > 0) width else 1
        screenHeightPx = if (height > 0) height else 1
        GLES20.glViewport(0, 0, width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        if (screenWidthPx == 0) return

        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
        GLES20.glUniform1i(uTextureLoc, 0)
        // The bitmap's first row is its top row, so flip V to match the video convention.
        GLES20.glUniformMatrix4fv(uTexMatrixLoc, 1, false, EyeOptics.FLIP_V, 0)

        quadBuffer.position(0)
        GLES20.glVertexAttribPointer(aPositionLoc, 3, GLES20.GL_FLOAT, false, 12, quadBuffer)
        GLES20.glEnableVertexAttribArray(aPositionLoc)

        drawEye(true, 0)
        drawEye(false, screenWidthPx / 2)

        GLES20.glDisableVertexAttribArray(aPositionLoc)
    }

    private fun drawEye(isLeftEye: Boolean, viewportX: Int) {
        GLES20.glViewport(viewportX, 0, screenWidthPx / 2, screenHeightPx)

        val p = profile
        val eye = EyeOptics.compute(
            profile = p,
            screenWidthMm = screenWidthMm,
            screenHeightMm = screenHeightMm,
            isLeftEye = isLeftEye,
            imageAspect = eyeImageAspect
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

        val crop = StereoMode.FULL_SBS.eyeCropRect(isLeftEye)
        GLES20.glUniform4f(uCropRectLoc, crop[0], crop[1], crop[2], crop[3])

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
        if (linked[0] == 0) Log.e("TestPatternGLRenderer", "Link failed: ${GLES20.glGetProgramInfoLog(prog)}")
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
        if (compiled[0] == 0) Log.e("TestPatternGLRenderer", "Compile failed: ${GLES20.glGetShaderInfoLog(shader)}")
        return shader
    }
}
