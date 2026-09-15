package com.example.vrsbsplayer.render

/**
 * Fragment shader math, explained (mirrors the documented Cardboard formula verbatim
 * from googlevr/cardboard's cardboard_device.proto):
 *
 *   p' = p * (1 + K1*r^2 + K2*r^4 + K3*r^6)
 *
 * where r is the distance from the optical centre in TAN-ANGLE units (distance on the
 * screen in mm divided by the screen-to-lens distance in mm), p is a point on the real
 * screen, and p' is the matching point on the "virtual screen" the user perceives
 * through the lens. For every physical pixel about to be drawn we walk the polynomial
 * forward, from screen space to source space, which is the correct direction for a
 * fragment shader: for every output pixel, find its source. No coefficient inversion and
 * no sign flip; K1/K2/K3 are used exactly as they appear in the Cardboard profile.
 *
 * This is mathematically the same thing the official SDK does with a pre-baked
 * distortion mesh, evaluated per fragment instead of per vertex, which is slightly more
 * accurate and avoids the intermediate render target entirely (the video is sampled once,
 * at display resolution, and never touches the CPU).
 *
 * The virtual screen is a flat rectangle of half size uImageHalfTan, centred on the lens
 * axis. Its size comes from the field of view and the true aspect ratio of one eye image,
 * so a 16:9 movie stays 16:9 instead of being stretched to fill the lens cone.
 */
object ShaderSource {

    const val VERTEX_SHADER = """
        attribute vec4 aPosition;
        varying vec2 vScreenPos; // -1..1 across this eye's viewport
        void main() {
            gl_Position = aPosition;
            vScreenPos = aPosition.xy;
        }
    """

    private const val FRAGMENT_BODY = """
        precision highp float;
        varying vec2 vScreenPos;
        uniform SAMPLER_TYPE uVideoTexture;

        // SurfaceTexture transform for video, or a V flip for the test pattern bitmap.
        uniform mat4 uTexMatrix;
        // Half size of this eye's viewport, millimetres (x,y)
        uniform vec2 uHalfSizeMm;
        // Lens optical centre relative to the viewport centre, millimetres (x,y).
        // The x component is what makes inter-lens distance actually do something.
        uniform vec2 uLensCenterOffsetMm;
        // Screen-to-lens distance, millimetres
        uniform float uScreenToLensMm;
        // Distortion coefficients K1, K2, K3
        uniform vec3 uK;
        // Clamped field of view, positive tangents: left, right, bottom, top
        uniform vec4 uFovTan;
        // Half size of the virtual movie screen, tan-angle units
        uniform vec2 uImageHalfTan;
        // SBS / TB crop rect for this eye: (u0, v0, u1, v1), top-left origin
        uniform vec4 uCropRect;
        // Parallax and recentring shift, fraction of the image (u,v)
        uniform vec2 uExtraShift;
        // 0 = flat perspective screen, 1 = cylindrical cinema screen
        uniform float uProjectionMode;
        // Half of the cylindrical screen FOV in radians.
        uniform float uCinemaHalfAngleRad;

        const vec4 BLACK = vec4(0.0, 0.0, 0.0, 1.0);

        void main() {
            // Position of this pixel on the real screen, relative to the lens centre.
            vec2 mm = vScreenPos * uHalfSizeMm - uLensCenterOffsetMm;
            vec2 t = mm / uScreenToLensMm;

            // Real screen to virtual screen.
            float r2 = dot(t, t);
            vec2 td = t * (1.0 + uK.x * r2 + uK.y * r2 * r2 + uK.z * r2 * r2 * r2);

            // Outside the viewer's field of view there is nothing to see.
            if (td.x < -uFovTan.x || td.x > uFovTan.y ||
                td.y < -uFovTan.z || td.y > uFovTan.w) {
                gl_FragColor = BLACK;
                return;
            }

            float u01;
            if (uProjectionMode > 0.5) {
                // Cylindrical cinema mapping: horizontal source position follows VIEWING
                // ANGLE rather than tan(angle). This is the critical difference from the
                // old flat 16:9 rectangle: increasing horizontal coverage no longer forces
                // a proportional increase in vertical size.
                float theta = atan(td.x);
                u01 = 0.5 + theta / (2.0 * uCinemaHalfAngleRad) + uExtraShift.x;
            } else {
                // Flat screen: preserve the source aspect ratio.
                vec2 img = td / uImageHalfTan;
                u01 = 0.5 + 0.5 * img.x + uExtraShift.x;
            }
            float v01 = 0.5 + 0.5 * (td.y / uImageHalfTan.y) + uExtraShift.y;
            if (u01 < 0.0 || u01 > 1.0 || v01 < 0.0 || v01 > 1.0) {
                gl_FragColor = BLACK;
                return;
            }

            // Inside the movie screen, sample this eye's half of the source frame.
            float texU = mix(uCropRect.x, uCropRect.z, u01);
            float texVTopLeft = mix(uCropRect.w, uCropRect.y, v01);
            vec2 uv = vec2(texU, 1.0 - texVTopLeft); // bottom-left origin
            gl_FragColor = texture2D(uVideoTexture, (uTexMatrix * vec4(uv, 0.0, 1.0)).xy);
        }
    """

    /** Samples the external texture written directly by the hardware video decoder. */
    val FRAGMENT_SHADER: String =
        "#extension GL_OES_EGL_image_external : require\n" +
            FRAGMENT_BODY.replace("SAMPLER_TYPE", "samplerExternalOES")

    /** Same math, ordinary 2D texture, used by the calibration test pattern. */
    val FRAGMENT_SHADER_2D: String = FRAGMENT_BODY.replace("SAMPLER_TYPE", "sampler2D")
}
