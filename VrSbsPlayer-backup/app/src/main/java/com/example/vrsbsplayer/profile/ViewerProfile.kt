package com.example.vrsbsplayer.profile

import org.json.JSONObject

/**
 * A Cardboard viewer optical profile, corresponding 1:1 to the fields of
 * Google's `cardboard.DeviceParams` proto (see CardboardProto.kt) PLUS a few
 * app-level fields that are deliberately kept separate because they are a
 * different kind of quantity:
 *
 *  - lensSeparationMm / screenToLensMm / trayToLensMm / fovXDeg / distortionK1-3
 *    describe the PHYSICAL HEADSET AND LENSES. They come from the Cardboard
 *    profile (QR-imported or typed in) and should match your headset, not
 *    the video.
 *
 *  - stereoSeparationPercent, horizontalOffsetPx, verticalOffsetPx describe
 *    how the two halves of the SOURCE VIDEO are re-aligned before the lens
 *    correction is applied. This is parallax/convergence adjustment, and has
 *    nothing to do with the physical lens spacing above. Two different
 *    Cardboard headsets can share the same viewer profile but need different
 *    stereo separation for a specific badly-authored video, and vice versa.
 *
 *  - horizontalStretch / verticalStretch independently scale one axis at a
 *    time, on top of zoomScale. They exist for headsets whose FOV shape
 *    doesn't match the video's own aspect ratio: zoomScale alone can't fill
 *    a gap on one axis without also cropping the other, since it scales both
 *    together. These trade geometric accuracy (circles become ovals) for
 *    coverage - same idea as "stretch to fill" on a widescreen TV. 1.0 = off
 *    (default) for both.
 */
data class ViewerProfile(
    val id: String,
    var name: String,
    // --- Optical / physical (from Cardboard DeviceParams) ---
    var vendor: String = "",
    var model: String = "",
    var lensSeparationMm: Float = 60f,          // inter_lens_distance (field 4)
    var screenToLensMm: Float = 42f,            // screen_to_lens_distance (field 3)
    var trayToLensMm: Float = 35f,              // tray_to_lens_distance (field 6)
    var verticalAlignment: VerticalAlignment = VerticalAlignment.BOTTOM, // field 11
    var fovLeftDeg: Float = 40f,                 // left_eye_field_of_view_angles[0]
    var fovRightDeg: Float = 40f,                // [1]
    var fovBottomDeg: Float = 40f,                // [2]
    var fovTopDeg: Float = 40f,                   // [3]
    var distortionK1: Float = 0.34f,             // distortion_coefficients[0]
    var distortionK2: Float = 0.55f,             // distortion_coefficients[1]
    var distortionK3: Float = 0.0f,               // distortion_coefficients[2] (optional 3rd term)
    // --- App-level stereo image adjustments (NOT part of DeviceParams) ---
    var stereoSeparationPercent: Float = 0f,     // extra parallax shift, -100..100
    var horizontalOffsetPercent: Float = 0f,     // per-eye horizontal recentring, -50..50
    var verticalOffsetPercent: Float = 0f,       // per-eye vertical recentring, -50..50
    var zoomScale: Float = 1.0f,                 // 0.3..3.0, overall (both-axis) magnification
    var horizontalStretch: Float = 1.0f,         // 0.5..3.0, horizontal-only extra scale
    var verticalStretch: Float = 1.0f,           // 0.5..3.0, vertical-only extra scale
    var projectionMode: ProjectionMode = ProjectionMode.CINEMA,
    var cinemaHorizontalFovDeg: Float = 120f      // total horizontal virtual cinema FOV
) {
    enum class VerticalAlignment(val protoValue: Int) { BOTTOM(0), CENTER(1), TOP(2) }
    enum class ProjectionMode { FLAT, CINEMA }

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("vendor", vendor)
        put("model", model)
        put("lensSeparationMm", lensSeparationMm.toDouble())
        put("screenToLensMm", screenToLensMm.toDouble())
        put("trayToLensMm", trayToLensMm.toDouble())
        put("verticalAlignment", verticalAlignment.name)
        put("fovLeftDeg", fovLeftDeg.toDouble())
        put("fovRightDeg", fovRightDeg.toDouble())
        put("fovBottomDeg", fovBottomDeg.toDouble())
        put("fovTopDeg", fovTopDeg.toDouble())
        put("distortionK1", distortionK1.toDouble())
        put("distortionK2", distortionK2.toDouble())
        put("distortionK3", distortionK3.toDouble())
        put("stereoSeparationPercent", stereoSeparationPercent.toDouble())
        put("horizontalOffsetPercent", horizontalOffsetPercent.toDouble())
        put("verticalOffsetPercent", verticalOffsetPercent.toDouble())
        put("zoomScale", zoomScale.toDouble())
        put("horizontalStretch", horizontalStretch.toDouble())
        put("verticalStretch", verticalStretch.toDouble())
        put("projectionMode", projectionMode.name)
        put("cinemaHorizontalFovDeg", cinemaHorizontalFovDeg.toDouble())
    }

    companion object {
        fun fromJson(o: JSONObject): ViewerProfile = ViewerProfile(
            id = o.getString("id"),
            name = o.getString("name"),
            vendor = o.optString("vendor", ""),
            model = o.optString("model", ""),
            lensSeparationMm = o.optDouble("lensSeparationMm", 60.0).toFloat(),
            screenToLensMm = o.optDouble("screenToLensMm", 42.0).toFloat(),
            trayToLensMm = o.optDouble("trayToLensMm", 35.0).toFloat(),
            verticalAlignment = try {
                VerticalAlignment.valueOf(o.optString("verticalAlignment", "BOTTOM"))
            } catch (e: Exception) { VerticalAlignment.BOTTOM },
            fovLeftDeg = o.optDouble("fovLeftDeg", 40.0).toFloat(),
            fovRightDeg = o.optDouble("fovRightDeg", 40.0).toFloat(),
            fovBottomDeg = o.optDouble("fovBottomDeg", 40.0).toFloat(),
            fovTopDeg = o.optDouble("fovTopDeg", 40.0).toFloat(),
            distortionK1 = o.optDouble("distortionK1", 0.34).toFloat(),
            distortionK2 = o.optDouble("distortionK2", 0.55).toFloat(),
            distortionK3 = o.optDouble("distortionK3", 0.0).toFloat(),
            stereoSeparationPercent = o.optDouble("stereoSeparationPercent", 0.0).toFloat(),
            horizontalOffsetPercent = o.optDouble("horizontalOffsetPercent", 0.0).toFloat(),
            verticalOffsetPercent = o.optDouble("verticalOffsetPercent", 0.0).toFloat(),
            zoomScale = o.optDouble("zoomScale", 1.0).toFloat(),
            horizontalStretch = o.optDouble("horizontalStretch", 1.0).toFloat(),
            verticalStretch = o.optDouble("verticalStretch", 1.0).toFloat(),
            projectionMode = try {
                ProjectionMode.valueOf(o.optString("projectionMode", "CINEMA"))
            } catch (e: Exception) { ProjectionMode.CINEMA },
            cinemaHorizontalFovDeg = o.optDouble("cinemaHorizontalFovDeg", 120.0).toFloat()
        )
    }
}

/** Convert a parsed Cardboard DeviceParams protobuf into our ViewerProfile (mm, not meters). */
fun CardboardProto.DeviceParams.toViewerProfile(id: String, defaultName: String): ViewerProfile {
    val fov = fovAnglesDeg
    val dist = distortionCoefficients
    return ViewerProfile(
        id = id,
        name = if (!model.isNullOrBlank()) "$vendor $model".trim() else defaultName,
        vendor = vendor ?: "",
        model = model ?: "",
        lensSeparationMm = (interLensDistanceM ?: 0.060f) * 1000f,
        screenToLensMm = (screenToLensDistanceM ?: 0.042f) * 1000f,
        trayToLensMm = (trayToLensDistanceM ?: 0.035f) * 1000f,
        verticalAlignment = when (verticalAlignment) {
            1 -> ViewerProfile.VerticalAlignment.CENTER
            2 -> ViewerProfile.VerticalAlignment.TOP
            else -> ViewerProfile.VerticalAlignment.BOTTOM
        },
        fovLeftDeg = fov?.getOrNull(0) ?: 40f,
        fovRightDeg = fov?.getOrNull(1) ?: 40f,
        fovBottomDeg = fov?.getOrNull(2) ?: 40f,
        fovTopDeg = fov?.getOrNull(3) ?: 40f,
        distortionK1 = dist?.getOrNull(0) ?: 0.34f,
        distortionK2 = dist?.getOrNull(1) ?: 0.55f,
        distortionK3 = dist?.getOrNull(2) ?: 0.0f
    )
}

/** Convert our ViewerProfile back to Google's DeviceParams shape (for export/debugging). */
fun ViewerProfile.toDeviceParams(): CardboardProto.DeviceParams = CardboardProto.DeviceParams(
    vendor = vendor,
    model = model,
    screenToLensDistanceM = screenToLensMm / 1000f,
    interLensDistanceM = lensSeparationMm / 1000f,
    fovAnglesDeg = floatArrayOf(fovLeftDeg, fovRightDeg, fovBottomDeg, fovTopDeg),
    trayToLensDistanceM = trayToLensMm / 1000f,
    distortionCoefficients = floatArrayOf(distortionK1, distortionK2, distortionK3),
    verticalAlignment = verticalAlignment.protoValue,
    primaryButton = 0
)

/** The exact profile the user supplied in the prompt, pre-loaded as a starting point. */
fun builtInUserProfile(): ViewerProfile = ViewerProfile(
    id = "user-test-profile",
    name = "test test (imported)",
    vendor = "test",
    model = "test",
    lensSeparationMm = 45f,
    screenToLensMm = 39f,
    trayToLensMm = 35f,
    verticalAlignment = ViewerProfile.VerticalAlignment.BOTTOM,
    fovLeftDeg = 50f,
    fovRightDeg = 50f,
    fovBottomDeg = 50f,
    fovTopDeg = 50f,
    distortionK1 = 0.440f,
    distortionK2 = 0.150f,
    distortionK3 = 0.0f
)

/** Google's published default parameters for the original Cardboard V1 viewer (used when a QR
 * code just encodes the bare "https://google.com/cardboard" URL with no `p=` payload). */
fun cardboardV1DefaultProfile(id: String): ViewerProfile = ViewerProfile(
    id = id,
    name = "Cardboard V1 (default)",
    vendor = "Google, Inc.",
    model = "Cardboard v1",
    lensSeparationMm = 60f,
    screenToLensMm = 42f,
    trayToLensMm = 35f,
    verticalAlignment = ViewerProfile.VerticalAlignment.BOTTOM,
    fovLeftDeg = 40f, fovRightDeg = 40f, fovBottomDeg = 40f, fovTopDeg = 40f,
    distortionK1 = 0.441f, distortionK2 = 0.156f, distortionK3 = 0.0f
)
