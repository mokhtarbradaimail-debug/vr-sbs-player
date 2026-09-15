package com.example.vrsbsplayer.render

/**
 * How the two eye images are packed into the single source video frame.
 *
 * FULL_SBS: frame width is twice one eye's width, each half already has the correct
 *           aspect ratio. A 3840x1080 frame holding two 1920x1080 views.
 *
 * HALF_SBS: the frame has normal resolution and each half is squeezed horizontally,
 *           so a 1920x1080 frame holds two 960x1080 views that must be stretched back
 *           out. This is the common 3D Blu-ray packing.
 *
 * TOP_BOTTOM: squeezed over/under, a 1920x1080 frame holding two 1920x540 views.
 *             Left eye is the top half.
 *
 * TOP_BOTTOM_FULL: full over/under, a 1920x2160 frame holding two 1920x1080 views.
 */
enum class StereoMode(val label: String) {
    FULL_SBS("Full SBS"),
    HALF_SBS("Half SBS"),
    TOP_BOTTOM("Top / Bottom"),
    TOP_BOTTOM_FULL("Top / Bottom full");

    /** UV crop rectangle (u0,v0,u1,v1), top-left origin, for one eye. */
    fun eyeCropRect(isLeftEye: Boolean): FloatArray = when (this) {
        FULL_SBS, HALF_SBS ->
            if (isLeftEye) floatArrayOf(0f, 0f, 0.5f, 1f) else floatArrayOf(0.5f, 0f, 1f, 1f)
        TOP_BOTTOM, TOP_BOTTOM_FULL ->
            if (isLeftEye) floatArrayOf(0f, 0f, 1f, 0.5f) else floatArrayOf(0f, 0.5f, 1f, 1f)
    }

    /**
     * Display aspect ratio (width / height) of ONE eye image, given the aspect ratio of
     * the whole decoded frame. This is what undoes the horizontal or vertical squeeze:
     * the crop rectangle picks the pixels, this decides the shape they are shown in.
     */
    fun eyeAspect(frameAspect: Float): Float = when (this) {
        FULL_SBS -> frameAspect / 2f
        HALF_SBS -> frameAspect
        TOP_BOTTOM -> frameAspect
        TOP_BOTTOM_FULL -> frameAspect * 2f
    }

    companion object {
        /**
         * Best-effort guess of the packing format from a filename or stream title, using
         * the tags common in 3D remux/rip release names (e.g. "Movie.2020.1080p.3D.HSBS.mkv").
         * Returns null when nothing recognisable is found. This never picks the mode on its
         * own: it only pre-selects a default on the format screen, which the user can still
         * change before entering VR, exactly as for locally-picked videos.
         */
        fun guessFromName(name: String): StereoMode? {
            // Split on anything that isn't a letter/digit so ".", "-", "_", "[", "]", " "
            // all act as separators and a tag has to appear as a whole word, e.g. so
            // "megatable.mkv" doesn't false-positive on "tab".
            val tokens = name.lowercase().split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() }
            fun any(vararg needles: String) = tokens.any { it in needles }

            return when {
                any("hsbs", "halfsbs") -> HALF_SBS
                any("hou", "htab", "halfou", "halftab", "halftb") -> TOP_BOTTOM
                any("fsbs", "fullsbs") -> FULL_SBS
                any("fou", "ftab", "fullou", "fulltab", "fulltb") -> TOP_BOTTOM_FULL
                any("sbs", "3dsbs") -> FULL_SBS
                any("tab", "ou", "tb", "overunder", "topbottom", "3dtab") -> TOP_BOTTOM
                else -> null
            }
        }
    }
}
