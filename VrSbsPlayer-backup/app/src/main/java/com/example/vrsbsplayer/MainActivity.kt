@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.example.vrsbsplayer

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.vrsbsplayer.profile.ProfileRepository
import com.example.vrsbsplayer.profile.ViewerProfile
import com.example.vrsbsplayer.render.StereoMode

/**
 * A video another app handed us via `ACTION_VIEW` (Stremio's "external player" option, a
 * file manager, a share sheet, ...), plus whatever we could read off that intent.
 * [nonce] just guarantees a fresh instance compares unequal so re-opening the same URL
 * while the app is already running still re-triggers navigation.
 */
private data class IncomingVideo(
    val uri: Uri,
    val title: String?,
    val mimeType: String?,
    val guessedMode: StereoMode?,
    val startPositionMs: Long,
    val nonce: Long = System.nanoTime()
)

class MainActivity : ComponentActivity() {

    private lateinit var repo: ProfileRepository

    /** Bumped on every resume so screens re-read the saved profiles. */
    private var refreshKey by mutableStateOf(0)

    /** Set from [extractIncomingVideo]; consumed by the composition below. */
    private var incomingVideo by mutableStateOf<IncomingVideo?>(null)

    override fun onResume() {
        super.onResume()
        refreshKey++
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // Only overwrite if this new intent actually carries a video - e.g. relaunching
        // from the home-screen icon while already open shouldn't clear anything.
        extractIncomingVideo(intent)?.let { incomingVideo = it }
    }

    /** Pulls a playable URI out of an `ACTION_VIEW` intent from another app, if present. */
    private fun extractIncomingVideo(intent: Intent): IncomingVideo? {
        if (intent.action != Intent.ACTION_VIEW) return null
        val uri = intent.data ?: return null

        if (uri.scheme == "content") {
            // Best-effort: a one-off grant from another app's ACTION_VIEW usually can't be
            // made persistable, but it's already valid for this session either way, so a
            // failure here is fine to ignore.
            try {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (e: SecurityException) { /* not persistable; fine for one-off playback */ }
        }

        val title = intent.getStringExtra("title")
        // Wildcard types like "video/*" don't help ExoPlayer pick a concrete media source;
        // only pass through a real one.
        val mimeType = intent.type?.takeIf { !it.endsWith("/*") }
        // Follows the same "position" (Int, ms) extra used by mpv-android/MX Player-style
        // external-player intents, in case the sender wants playback to resume mid-stream.
        val startPositionMs = intent.getIntExtra("position", 0).toLong()
        val nameForGuessing = title ?: uri.lastPathSegment ?: uri.toString()

        return IncomingVideo(
            uri = uri,
            title = title,
            mimeType = mimeType,
            guessedMode = StereoMode.guessFromName(nameForGuessing),
            startPositionMs = startPositionMs
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repo = ProfileRepository(this)
        incomingVideo = extractIncomingVideo(intent)

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                val nav = rememberNavController()
                var pickedVideoUri by remember { mutableStateOf<Uri?>(null) }
                var pickedTitle by remember { mutableStateOf<String?>(null) }
                var pickedMimeType by remember { mutableStateOf<String?>(null) }
                var pickedStartPositionMs by remember { mutableStateOf(0L) }
                var initialMode by remember { mutableStateOf(StereoMode.FULL_SBS) }
                var modeWasDetected by remember { mutableStateOf(false) }

                // A video handed in by another app jumps straight to the format picker
                // instead of the main menu, with a best-effort guess at the stereo mode
                // already selected so there's usually just one tap to VR.
                LaunchedEffect(incomingVideo) {
                    val video = incomingVideo ?: return@LaunchedEffect
                    pickedVideoUri = video.uri
                    pickedTitle = video.title
                    pickedMimeType = video.mimeType
                    pickedStartPositionMs = video.startPositionMs
                    initialMode = video.guessedMode ?: StereoMode.FULL_SBS
                    modeWasDetected = video.guessedMode != null
                    nav.navigate("video_mode")
                    incomingVideo = null // consumed
                }

                NavHost(nav, startDestination = "main") {
                    composable("main") {
                        MainMenuScreen(
                            nav = nav,
                            onVideoPicked = { uri ->
                                pickedVideoUri = uri
                                pickedTitle = null
                                pickedMimeType = null
                                pickedStartPositionMs = 0L
                                initialMode = StereoMode.FULL_SBS
                                modeWasDetected = false
                                nav.navigate("video_mode")
                            }
                        )
                    }
                    composable("video_mode") {
                        VideoModeScreen(
                            initialMode = initialMode,
                            detected = modeWasDetected,
                            sourceLabel = pickedTitle,
                            onEnterVr = { mode ->
                                val uri = pickedVideoUri ?: return@VideoModeScreen
                                val intent = Intent(this@MainActivity, VrPlayerActivity::class.java).apply {
                                    putExtra(VrPlayerActivity.EXTRA_VIDEO_URI, uri.toString())
                                    putExtra(VrPlayerActivity.EXTRA_STEREO_MODE, mode.name)
                                    pickedMimeType?.let { putExtra(VrPlayerActivity.EXTRA_MIME_TYPE, it) }
                                    pickedTitle?.let { putExtra(VrPlayerActivity.EXTRA_TITLE, it) }
                                    if (pickedStartPositionMs > 0L) {
                                        putExtra(VrPlayerActivity.EXTRA_START_POSITION_MS, pickedStartPositionMs)
                                    }
                                }
                                startActivity(intent)
                            },
                            onBack = { nav.popBackStack() }
                        )
                    }
                    composable("profiles") {
                        ProfileListScreen(
                            repo = repo,
                            refreshKey = refreshKey,
                            onAddManual = { nav.navigate("manual_profile/new") },
                            onEditProfile = { id -> nav.navigate("manual_profile/$id") },
                            onScanQr = {
                                startActivity(Intent(this@MainActivity, QrScanActivity::class.java))
                            },
                            onBack = { nav.popBackStack() }
                        )
                    }
                    composable("manual_profile/{id}") { backStackEntry ->
                        val id = backStackEntry.arguments?.getString("id") ?: "new"
                        ManualProfileScreen(
                            repo = repo,
                            profileId = id,
                            onDone = { nav.popBackStack("main", inclusive = false) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun MainMenuScreen(nav: NavHostController, onVideoPicked: (Uri) -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val pickVideoLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (e: SecurityException) { /* some providers don't support persistable perms; fine for one-off playback */ }
            onVideoPicked(uri)
        }
    }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("VR SBS Player", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(32.dp))

            Button(
                onClick = { pickVideoLauncher.launch(arrayOf("video/*")) },
                modifier = Modifier.fillMaxWidth(0.8f)
            ) { Text("Open Video") }

            Spacer(Modifier.height(16.dp))
            OutlinedButton(
                onClick = { context.startActivity(Intent(context, CalibrationActivity::class.java)) },
                modifier = Modifier.fillMaxWidth(0.8f)
            ) { Text("Calibration") }

            Spacer(Modifier.height(16.dp))
            OutlinedButton(
                onClick = { nav.navigate("profiles") },
                modifier = Modifier.fillMaxWidth(0.8f)
            ) { Text("Viewer Profile") }

            Spacer(Modifier.height(16.dp))
            OutlinedButton(
                onClick = { nav.navigate("profiles") }, // settings folded into profile list for this lightweight app
                modifier = Modifier.fillMaxWidth(0.8f)
            ) { Text("Settings") }
        }
    }
}

@Composable
fun VideoModeScreen(
    initialMode: StereoMode = StereoMode.FULL_SBS,
    detected: Boolean = false,
    sourceLabel: String? = null,
    onEnterVr: (StereoMode) -> Unit,
    onBack: () -> Unit
) {
    var selectedMode by remember(initialMode) { mutableStateOf(initialMode) }

    Column(
        Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("Select Stereo Format", style = MaterialTheme.typography.titleLarge)
        sourceLabel?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, style = MaterialTheme.typography.bodyMedium, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        }
        if (detected) {
            Spacer(Modifier.height(4.dp))
            Text(
                "Guessed from the filename — change it below if it looks wrong",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.height(24.dp))

        StereoMode.entries.forEach { mode ->
            OutlinedButton(
                onClick = { selectedMode = mode },
                modifier = Modifier.fillMaxWidth(0.8f).padding(vertical = 4.dp),
                colors = if (selectedMode == mode) ButtonDefaults.outlinedButtonColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                ) else ButtonDefaults.outlinedButtonColors()
            ) { Text(mode.label) }
        }

        Spacer(Modifier.height(24.dp))
        Button(
            onClick = { onEnterVr(selectedMode) },
            modifier = Modifier.fillMaxWidth(0.8f)
        ) { Text("Enter VR View") }

        Spacer(Modifier.height(12.dp))
        TextButton(onClick = onBack) { Text("Back") }
    }
}

@Composable
fun ProfileListScreen(
    repo: ProfileRepository,
    refreshKey: Int,
    onAddManual: () -> Unit,
    onEditProfile: (String) -> Unit,
    onScanQr: () -> Unit,
    onBack: () -> Unit
) {
    val profiles = remember(refreshKey) { repo.loadAll() }
    var selectedId by remember(refreshKey) {
        mutableStateOf(repo.lastSelectedProfileId ?: profiles.firstOrNull()?.id)
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("Viewer Profiles", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(12.dp))

        Row {
            Button(onClick = onScanQr) { Text("Scan Cardboard QR") }
            Spacer(Modifier.width(8.dp))
            Button(onClick = onAddManual) { Text("Add Manual") }
        }

        Spacer(Modifier.height(12.dp))

        LazyColumn(Modifier.weight(1f)) {
            items(profiles) { p ->
                Card(
                    onClick = {
                        selectedId = p.id
                        repo.lastSelectedProfileId = p.id
                    },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                ) {
                    Row(
                        Modifier.padding(12.dp).fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(p.name, style = MaterialTheme.typography.titleMedium)
                            Text(
                                "Lens spacing ${p.lensSeparationMm}mm  FOV ${p.fovLeftDeg}°  K1 ${p.distortionK1}",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        Row {
                            if (selectedId == p.id) {
                                AssistChip(onClick = {}, label = { Text("Active") })
                            }
                            TextButton(onClick = { onEditProfile(p.id) }) { Text("Edit") }
                        }
                    }
                }
            }
        }

        TextButton(onClick = onBack) { Text("Back") }
    }
}
