@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.example.vrsbsplayer

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.vrsbsplayer.profile.ProfileRepository
import com.example.vrsbsplayer.profile.ViewerProfile

@Composable
fun ManualProfileScreen(repo: ProfileRepository, profileId: String, onDone: () -> Unit) {
    val existing = remember {
        if (profileId == "new") null else repo.loadAll().firstOrNull { it.id == profileId }
    }
    val isNew = existing == null
    val id = existing?.id ?: remember { repo.newId() }

    var name by remember { mutableStateOf(existing?.name ?: "My Viewer") }
    var lensSeparation by remember { mutableStateOf((existing?.lensSeparationMm ?: 45f).toString()) }
    var screenToLens by remember { mutableStateOf((existing?.screenToLensMm ?: 39f).toString()) }
    var trayToLens by remember { mutableStateOf((existing?.trayToLensMm ?: 35f).toString()) }
    var alignment by remember { mutableStateOf(existing?.verticalAlignment ?: ViewerProfile.VerticalAlignment.BOTTOM) }
    var fovLeft by remember { mutableStateOf((existing?.fovLeftDeg ?: 50f).toString()) }
    var fovRight by remember { mutableStateOf((existing?.fovRightDeg ?: 50f).toString()) }
    var fovTop by remember { mutableStateOf((existing?.fovTopDeg ?: 50f).toString()) }
    var fovBottom by remember { mutableStateOf((existing?.fovBottomDeg ?: 50f).toString()) }
    var k1 by remember { mutableStateOf((existing?.distortionK1 ?: 0.440f).toString()) }
    var k2 by remember { mutableStateOf((existing?.distortionK2 ?: 0.150f).toString()) }
    var k3 by remember { mutableStateOf((existing?.distortionK3 ?: 0.000f).toString()) }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp)
    ) {
        Text(
            if (isNew) "New Viewer Profile" else "Edit Viewer Profile",
            style = MaterialTheme.typography.titleLarge
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "These are the same fields as Google Cardboard's DeviceParams. " +
                "If you scanned a QR code instead, these are pre-filled for you.",
            style = MaterialTheme.typography.bodySmall
        )
        Spacer(Modifier.height(16.dp))

        NumField("Name", name) { name = it }
        NumField("Lens spacing / inter-lens distance (mm)", lensSeparation, KeyboardType.Decimal) { lensSeparation = it }
        NumField("Screen-to-lens distance (mm)", screenToLens, KeyboardType.Decimal) { screenToLens = it }
        NumField("Vertical lens-center distance / tray-to-lens (mm)", trayToLens, KeyboardType.Decimal) { trayToLens = it }

        Spacer(Modifier.height(8.dp))
        Text("Vertical alignment", style = MaterialTheme.typography.labelLarge)
        Row {
            ViewerProfile.VerticalAlignment.entries.forEach { a ->
                FilterChip(
                    selected = alignment == a,
                    onClick = { alignment = a },
                    label = { Text(a.name) },
                    modifier = Modifier.padding(end = 8.dp)
                )
            }
        }

        Spacer(Modifier.height(8.dp))
        Text("Field of view (degrees)", style = MaterialTheme.typography.labelLarge)
        Row {
            NumField("Left", fovLeft, KeyboardType.Decimal, Modifier.weight(1f)) { fovLeft = it }
            Spacer(Modifier.width(8.dp))
            NumField("Right", fovRight, KeyboardType.Decimal, Modifier.weight(1f)) { fovRight = it }
        }
        Row {
            NumField("Top", fovTop, KeyboardType.Decimal, Modifier.weight(1f)) { fovTop = it }
            Spacer(Modifier.width(8.dp))
            NumField("Bottom", fovBottom, KeyboardType.Decimal, Modifier.weight(1f)) { fovBottom = it }
        }

        Spacer(Modifier.height(8.dp))
        Text("Lens distortion coefficients", style = MaterialTheme.typography.labelLarge)
        Text(
            "Uses Google's convention: p' = p (1 + K1 r² + K2 r⁴ + K3 r⁶), same as your Cardboard profile's distortion_coefficients.",
            style = MaterialTheme.typography.bodySmall
        )
        NumField("Distortion K1", k1, KeyboardType.Decimal) { k1 = it }
        NumField("Distortion K2", k2, KeyboardType.Decimal) { k2 = it }
        NumField("Distortion K3", k3, KeyboardType.Decimal) { k3 = it }

        Spacer(Modifier.height(24.dp))
        Row {
            Button(onClick = {
                val profile = ViewerProfile(
                    id = id,
                    name = name,
                    lensSeparationMm = lensSeparation.toFloatOrNull() ?: 45f,
                    screenToLensMm = screenToLens.toFloatOrNull() ?: 39f,
                    trayToLensMm = trayToLens.toFloatOrNull() ?: 35f,
                    verticalAlignment = alignment,
                    fovLeftDeg = fovLeft.toFloatOrNull() ?: 50f,
                    fovRightDeg = fovRight.toFloatOrNull() ?: 50f,
                    fovTopDeg = fovTop.toFloatOrNull() ?: 50f,
                    fovBottomDeg = fovBottom.toFloatOrNull() ?: 50f,
                    distortionK1 = k1.toFloatOrNull() ?: 0.44f,
                    distortionK2 = k2.toFloatOrNull() ?: 0.15f,
                    distortionK3 = k3.toFloatOrNull() ?: 0f
                )
                repo.upsert(profile)
                repo.lastSelectedProfileId = profile.id
                onDone()
            }) { Text("Save Profile") }

            Spacer(Modifier.width(12.dp))
            if (!isNew) {
                OutlinedButton(onClick = { repo.delete(id); onDone() }) { Text("Delete") }
            }
        }
    }
}

@Composable
private fun NumField(
    label: String,
    value: String,
    keyboardType: KeyboardType = KeyboardType.Text,
    modifier: Modifier = Modifier,
    onChange: (String) -> Unit
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        singleLine = true
    )
}
