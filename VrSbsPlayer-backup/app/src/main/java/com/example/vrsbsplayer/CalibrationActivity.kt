@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.example.vrsbsplayer

import android.opengl.GLSurfaceView
import android.os.Build
import android.os.Bundle
import android.util.DisplayMetrics
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.example.vrsbsplayer.profile.ProfileRepository
import com.example.vrsbsplayer.profile.ViewerProfile
import com.example.vrsbsplayer.render.TestPatternGLRenderer

/**
 * Live calibration. The test pattern is drawn through exactly the same optical pipeline
 * as a movie, and every slider takes effect on the next frame, so you can put the phone
 * in the headset, look, take it out, nudge a value, and look again.
 */
class CalibrationActivity : ComponentActivity() {

    private lateinit var repo: ProfileRepository
    private lateinit var renderer: TestPatternGLRenderer
    private lateinit var glView: GLSurfaceView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repo = ProfileRepository(this)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
        enterImmersiveMode()

        val profiles = repo.loadAll()
        val initial = profiles.firstOrNull { it.id == repo.lastSelectedProfileId } ?: profiles.first()

        renderer = TestPatternGLRenderer().apply {
            profile = initial
            setScreenPhysicalSizeMm(screenWidthMm(), screenHeightMm())
        }

        glView = GLSurfaceView(this).apply {
            setEGLContextClientVersion(2)
            preserveEGLContextOnPause = true
            setRenderer(renderer)
            renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY
        }

        setContent {
            var state by remember { mutableStateOf(initial) }
            var panelVisible by remember { mutableStateOf(true) }

            fun push(updated: ViewerProfile) {
                state = updated
                renderer.profile = updated
                glView.requestRender()
            }

            Box(Modifier.fillMaxSize().background(Color.Black)) {
                // Tapping the pattern itself toggles the panel - lets you glance at the
                // full, unobstructed test pattern without hunting for a tiny button.
                AndroidView(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) {
                            detectTapGestures(onTap = { panelVisible = !panelVisible })
                        },
                    factory = { glView }
                )

                val panelWidth = 300.dp
                val panelOffsetX by animateDpAsState(
                    targetValue = if (panelVisible) 0.dp else panelWidth,
                    label = "calibrationPanelSlide"
                )

                Column(
                    Modifier
                        .align(Alignment.CenterEnd)
                        .offset(x = panelOffsetX)
                        .fillMaxHeight()
                        .width(panelWidth)
                        .background(Color(0xCC000000))
                        .verticalScroll(rememberScrollState())
                        .padding(12.dp)
                ) {
                    Text("Calibration", color = Color.White, style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Physical headset values first, then image placement. Tap the pattern " +
                            "anywhere to hide this panel and see it unobstructed; tap again to bring it back.",
                        color = Color(0xFFBBBBBB),
                        style = MaterialTheme.typography.bodySmall
                    )

                    Text("Projection", color = Color.White, style = MaterialTheme.typography.bodySmall)
                    Row(Modifier.padding(vertical = 4.dp)) {
                        ViewerProfile.ProjectionMode.entries.forEach { mode ->
                            FilterChip(
                                selected = state.projectionMode == mode,
                                onClick = { push(state.copy(projectionMode = mode)) },
                                label = { Text(if (mode == ViewerProfile.ProjectionMode.CINEMA) "Cinema" else "Flat") },
                                modifier = Modifier.padding(end = 4.dp)
                            )
                        }
                    }
                    if (state.projectionMode == ViewerProfile.ProjectionMode.CINEMA) {
                        LabeledSlider("Cinema horizontal FOV (deg, total)", state.cinemaHorizontalFovDeg, 80f, 170f) {
                            push(state.copy(cinemaHorizontalFovDeg = it))
                        }
                        Text(
                            "Cinema mode maps the movie horizontally by viewing angle on a cylinder. " +
                                "It lets you widen the screen without making it taller. Keep stereo separation " +
                                "only as high as needed for the two eyes to fuse.",
                            color = Color(0xFFBBBBBB),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }

                    LabeledSlider("Inter-lens distance (mm)", state.lensSeparationMm, 30f, 90f) {
                        push(state.copy(lensSeparationMm = it))
                    }
                    // Standard Cardboard lenses sit ~35-45mm from the screen. Ultra-wide-FOV
                    // designs (Wearality Sky and similar Fresnel-based viewers) deliberately
                    // put the lens much closer - as little as ~15mm - which is exactly what
                    // lets the same screen subtend a much wider angle. The old 20mm floor
                    // made those headsets physically impossible to represent correctly here.
                    LabeledSlider("Screen-to-lens (mm)", state.screenToLensMm, 10f, 90f) {
                        push(state.copy(screenToLensMm = it))
                    }
                    LabeledSlider(
                        label = if (state.verticalAlignment == ViewerProfile.VerticalAlignment.CENTER)
                            "Tray-to-lens (mm) — unused with Center alignment below"
                        else "Tray-to-lens (mm)",
                        value = state.trayToLensMm, min = 10f, max = 60f,
                        enabled = state.verticalAlignment != ViewerProfile.VerticalAlignment.CENTER
                    ) {
                        push(state.copy(trayToLensMm = it))
                    }
                    // Each of these is the angle from your straight-ahead gaze OUT to that
                    // edge, not a total/combined FOV - a 150 deg-class headset typically
                    // needs roughly 70-85 deg per edge, not 150 in a single field. Capped
                    // just under 90 deg: at 90 the underlying tan() blows up to infinity.
                    LabeledSlider("FOV left (deg)", state.fovLeftDeg, 10f, 89f) {
                        push(state.copy(fovLeftDeg = it))
                    }
                    LabeledSlider("FOV right (deg)", state.fovRightDeg, 10f, 89f) {
                        push(state.copy(fovRightDeg = it))
                    }
                    LabeledSlider("FOV top (deg)", state.fovTopDeg, 10f, 89f) {
                        push(state.copy(fovTopDeg = it))
                    }
                    LabeledSlider("FOV bottom (deg)", state.fovBottomDeg, 10f, 89f) {
                        push(state.copy(fovBottomDeg = it))
                    }
                    LabeledSlider("Distortion K1", state.distortionK1, -0.5f, 1.2f) {
                        push(state.copy(distortionK1 = it))
                    }
                    LabeledSlider("Distortion K2", state.distortionK2, -0.5f, 1.2f) {
                        push(state.copy(distortionK2 = it))
                    }
                    LabeledSlider("Distortion K3", state.distortionK3, -0.5f, 1.2f) {
                        push(state.copy(distortionK3 = it))
                    }
                    // Match EyeOptics' own clamp (0.3-3.0) exactly - the old 0.5-2.0 cap here
                    // was tighter than what the renderer actually supports, so it was
                    // stopping people short of how far the app can really push zoom.
                    LabeledSlider("Zoom / scale", state.zoomScale, 0.3f, 3.0f) {
                        push(state.copy(zoomScale = it))
                    }
                    // Fills sideways gaps left by Zoom (which moves both axes together)
                    // without cropping the top/bottom - at the cost of stretching the image
                    // horizontally. Useful when the headset's FOV is much wider than the
                    // video's own aspect ratio. 1.0 = off, matches plain Zoom behavior.
                    LabeledSlider("Horizontal stretch (fills sides, no vertical crop)", state.horizontalStretch, 0.5f, 3.0f) {
                        push(state.copy(horizontalStretch = it))
                    }
                    LabeledSlider("Vertical stretch (fills top/bottom, no side crop)", state.verticalStretch, 0.5f, 3.0f) {
                        push(state.copy(verticalStretch = it))
                    }
                    LabeledSlider("Stereo separation (%)", state.stereoSeparationPercent, -100f, 100f) {
                        push(state.copy(stereoSeparationPercent = it))
                    }
                    LabeledSlider("Horizontal offset (%)", state.horizontalOffsetPercent, -50f, 50f) {
                        push(state.copy(horizontalOffsetPercent = it))
                    }
                    LabeledSlider("Vertical offset (%)", state.verticalOffsetPercent, -50f, 50f) {
                        push(state.copy(verticalOffsetPercent = it))
                    }

                    Spacer(Modifier.height(12.dp))
                    Text("Vertical alignment", color = Color.White, style = MaterialTheme.typography.bodySmall)
                    Row {
                        ViewerProfile.VerticalAlignment.entries.forEach { align ->
                            FilterChip(
                                selected = state.verticalAlignment == align,
                                onClick = { push(state.copy(verticalAlignment = align)) },
                                label = { Text(align.name.take(1)) },
                                modifier = Modifier.padding(end = 4.dp)
                            )
                        }
                    }

                    Spacer(Modifier.height(16.dp))
                    OutlinedButton(onClick = {
                        // Only the image-placement knobs, deliberately not the physical
                        // headset numbers above (lens spacing, screen-to-lens, FOV, K1-3) -
                        // those took real effort to dial in and a "reset everything" button
                        // that also wipes them would be actively harmful.
                        push(
                            state.copy(
                                zoomScale = 1.0f,
                                horizontalStretch = 1.0f,
                                verticalStretch = 1.0f,
                                stereoSeparationPercent = 0f,
                                horizontalOffsetPercent = 0f,
                                verticalOffsetPercent = 0f
                            )
                        )
                    }) { Text("Reset image placement (zoom/stretch/offsets only)") }

                    Spacer(Modifier.height(16.dp))
                    Row {
                        Button(onClick = {
                            repo.upsert(state)
                            repo.lastSelectedProfileId = state.id
                            finish()
                        }) { Text("Save & Exit") }
                        Spacer(Modifier.width(8.dp))
                        OutlinedButton(onClick = { finish() }) { Text("Cancel") }
                    }
                }

                // Stays fixed to the edge regardless of panelVisible (it's a sibling of the
                // sliding Column, not part of it), so there's always an obvious, discoverable
                // way to bring the panel back besides remembering "tap the pattern".
                SmallFloatingActionButton(
                    onClick = { panelVisible = !panelVisible },
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .padding(end = if (panelVisible) panelWidth else 0.dp)
                ) {
                    Text(if (panelVisible) "›" else "‹")
                }
            }
        }
    }

    @Composable
    private fun LabeledSlider(
        label: String,
        value: Float,
        min: Float,
        max: Float,
        enabled: Boolean = true,
        onChange: (Float) -> Unit
    ) {
        Column(Modifier.padding(vertical = 4.dp)) {
            Text(
                "$label: ${"%.3f".format(value)}",
                color = if (enabled) Color.White else Color(0xFF777777),
                style = MaterialTheme.typography.bodySmall
            )
            Slider(
                value = value.coerceIn(min, max),
                onValueChange = onChange,
                valueRange = min..max,
                enabled = enabled
            )
        }
    }

    private fun realMetrics(): DisplayMetrics {
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(metrics)
        return metrics
    }

    private fun screenWidthMm(): Float {
        val m = realMetrics()
        val longPx = maxOf(m.widthPixels, m.heightPixels).toFloat()
        return if (m.xdpi > 1f) longPx / m.xdpi * 25.4f else 150f
    }

    private fun screenHeightMm(): Float {
        val m = realMetrics()
        val shortPx = minOf(m.widthPixels, m.heightPixels).toFloat()
        return if (m.ydpi > 1f) shortPx / m.ydpi * 25.4f else 70f
    }

    private fun enterImmersiveMode() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) enterImmersiveMode()
    }

    override fun onResume() {
        super.onResume()
        if (::glView.isInitialized) {
            renderer.setScreenPhysicalSizeMm(screenWidthMm(), screenHeightMm())
            glView.onResume()
            glView.requestRender()
        }
    }

    override fun onPause() {
        super.onPause()
        if (::glView.isInitialized) glView.onPause()
    }
}
