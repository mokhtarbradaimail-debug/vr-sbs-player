package com.example.vrsbsplayer.render

import com.example.vrsbsplayer.profile.ViewerProfile
import kotlin.math.atan
import kotlin.math.min
import kotlin.math.tan

/**
 * All of the Cardboard optical geometry, in one place, shared by the video renderer and
 * the calibration renderer so the two can never drift apart.
 *
 * This is a direct port of googlevr/cardboard sdk/lens_distortion.cc, with lengths in
 * millimetres instead of metres (the app stores millimetres, the proto stores metres).
 *
 * Distortion convention, copied from cardboard_device.proto:
 *
 *     p' = p * (1 + K1 r^2 + K2 r^4 + K3 r^6)
 *
 * with r in tan-angle units (distance on the screen divided by the screen-to-lens
 * distance). The polynomial maps REAL SCREEN to VIRTUAL SCREEN, which is exactly the
 * direction a fragment shader needs: for the pixel being drawn, find the point of the
 * source image it must show. So K1/K2/K3 are used exactly as they appear in the
 * Cardboard profile, with no inversion and no sign flip.
 */
object EyeOptics {

    /** kDefaultBorderSizeMeters from the Cardboard SDK, in millimetres. */
    const val BORDER_MM = 3f

    data class Eye(
        /** Half size of this eye's viewport, millimetres. */
        val halfWidthMm: Float,
        val halfHeightMm: Float,
        /** Lens optical centre, millimetres from the centre of this eye's viewport. */
        val lensOffsetXMm: Float,
        val lensOffsetYMm: Float,
        /** Clamped field of view of this eye as positive tangents. */
        val fovTanLeft: Float,
        val fovTanRight: Float,
        val fovTanBottom: Float,
        val fovTanTop: Float,
        /** Half size of the virtual movie screen, tan-angle units (flat mode / vertical cinema scale). */
        val imageHalfTanX: Float,
        val imageHalfTanY: Float,
        /** Half-angle of the virtual cylindrical cinema screen. */
        val cinemaHalfAngleRad: Float
    )

    fun distortionFactor(k1: Float, k2: Float, k3: Float, r2: Float): Float =
        1f + k1 * r2 + k2 * r2 * r2 + k3 * r2 * r2 * r2

    fun distortRadius(k1: Float, k2: Float, k3: Float, r: Float): Float =
        r * distortionFactor(k1, k2, k3, r * r)

    /** Distance from the bottom edge of the screen to the lens centres, millimetres. */
    fun lensCentreFromBottomMm(profile: ViewerProfile, screenHeightMm: Float): Float =
        when (profile.verticalAlignment) {
            ViewerProfile.VerticalAlignment.BOTTOM -> profile.trayToLensMm - BORDER_MM
            ViewerProfile.VerticalAlignment.TOP -> screenHeightMm - profile.trayToLensMm - BORDER_MM
            ViewerProfile.VerticalAlignment.CENTER -> screenHeightMm / 2f
        }

    /**
     * @param screenWidthMm  width of the whole display in landscape, millimetres
     * @param screenHeightMm height of the whole display in landscape, millimetres
     * @param imageAspect    display aspect ratio (w/h) of ONE eye image
     */
    fun compute(
        profile: ViewerProfile,
        screenWidthMm: Float,
        screenHeightMm: Float,
        isLeftEye: Boolean,
        imageAspect: Float
    ): Eye {
        val w = if (screenWidthMm > 1f) screenWidthMm else 150f
        val h = if (screenHeightMm > 1f) screenHeightMm else 70f
        val d = if (profile.screenToLensMm > 1f) profile.screenToLensMm else 39f
        val ild = profile.lensSeparationMm.coerceIn(20f, w)

        val k1 = profile.distortionK1
        val k2 = profile.distortionK2
        val k3 = profile.distortionK3

        // Each eye owns one half of the display. The lens is NOT in the middle of that
        // half: it sits inter-lens-distance / 2 away from the centre line of the screen.
        val lensOffsetXMm = (w / 4f - ild / 2f).let { if (isLeftEye) it else -it }
        val lensOffsetYMm = lensCentreFromBottomMm(profile, h) - h / 2f

        // Clamp the nominal field of view by what the screen can physically show through
        // the lens, exactly like LensDistortion::CalculateFov.
        val outerMm = (w - ild) / 2f
        val innerMm = ild / 2f
        val bottomMm = lensCentreFromBottomMm(profile, h)
        val topMm = h - bottomMm

        val outerAngle = atan(distortRadius(k1, k2, k3, outerMm / d))
        val innerAngle = atan(distortRadius(k1, k2, k3, innerMm / d))
        val bottomAngle = atan(distortRadius(k1, k2, k3, bottomMm / d))
        val topAngle = atan(distortRadius(k1, k2, k3, topMm / d))

        // Clamped strictly below 90 deg: tan() diverges to infinity at exactly 90, and the
        // manual-profile text entry screen accepts any number with no upper bound of its
        // own, so a typo or a bad imported profile must not be able to reach that singularity.
        fun safeFovRad(deg: Float) = Math.toRadians(deg.coerceIn(0.1f, 89.5f).toDouble()).toFloat()

        val outer = min(safeFovRad(profile.fovLeftDeg), outerAngle)
        val inner = min(safeFovRad(profile.fovRightDeg), innerAngle)
        val bottom = min(safeFovRad(profile.fovBottomDeg), bottomAngle)
        val top = min(safeFovRad(profile.fovTopDeg), topAngle)

        // The profile's angles describe the left eye; the right eye is its mirror.
        val fovTanLeft = tan(if (isLeftEye) outer else inner)
        val fovTanRight = tan(if (isLeftEye) inner else outer)
        val fovTanBottom = tan(bottom)
        val fovTanTop = tan(top)

        // Flat mode fits the movie's true aspect ratio into the available lens cone.
        // Cinema mode deliberately decouples horizontal and vertical coverage: the movie
        // is mapped to a cylindrical virtual screen, so widening the horizontal FOV does
        // not force the source image to become taller and crop the actors at the top/bottom.
        val aspect = imageAspect.coerceIn(0.2f, 8f)
        val maxHalfX = min(fovTanLeft, fovTanRight)
        val maxHalfY = min(fovTanBottom, fovTanTop)
        val zoom = profile.zoomScale.coerceIn(0.3f, 3f)
        val hStretch = profile.horizontalStretch.coerceIn(0.5f, 3f)
        val vStretch = profile.verticalStretch.coerceIn(0.5f, 3f)

        val halfXFlatBase = if (maxHalfX / aspect <= maxHalfY) maxHalfX else maxHalfY * aspect
        val halfX = halfXFlatBase / zoom / hStretch
        val halfYFlat = halfXFlatBase / aspect / zoom / vStretch

        // Cinema keeps vertical coverage tied only to the physical vertical FOV.
        // Horizontal coverage is angular (cylindrical), not tied to the video's 16:9 aspect.
        val finalHalfY = if (profile.projectionMode == ViewerProfile.ProjectionMode.CINEMA)
            maxHalfY / zoom / vStretch
        else
            halfYFlat

        // Requested value is total horizontal cinema FOV. Never ask the renderer to use
        // more horizontal angle than the physical eye can actually see.
        val requestedCinemaHalfAngle = safeFovRad(profile.cinemaHorizontalFovDeg / 2f)
        val physicalHalfAngle = min(atan(fovTanLeft), atan(fovTanRight))
        val cinemaHalfAngle = (min(requestedCinemaHalfAngle, physicalHalfAngle) / hStretch)
            .coerceAtLeast(safeFovRad(0.1f))

        return Eye(
            halfWidthMm = w / 4f,
            halfHeightMm = h / 2f,
            lensOffsetXMm = lensOffsetXMm,
            lensOffsetYMm = lensOffsetYMm,
            fovTanLeft = fovTanLeft,
            fovTanRight = fovTanRight,
            fovTanBottom = fovTanBottom,
            fovTanTop = fovTanTop,
            imageHalfTanX = halfX,
            imageHalfTanY = finalHalfY,
            cinemaHalfAngleRad = cinemaHalfAngle
        )
    }

    /** Flips V, used when sampling a Bitmap whose first row is the top row. */
    val FLIP_V = floatArrayOf(
        1f, 0f, 0f, 0f,
        0f, -1f, 0f, 0f,
        0f, 0f, 1f, 0f,
        0f, 1f, 0f, 1f
    )

    val IDENTITY = floatArrayOf(
        1f, 0f, 0f, 0f,
        0f, 1f, 0f, 0f,
        0f, 0f, 1f, 0f,
        0f, 0f, 0f, 1f
    )
}
