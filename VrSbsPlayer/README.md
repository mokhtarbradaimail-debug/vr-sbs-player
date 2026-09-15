# VR SBS Player (Cardboard-style stereoscopic movie player)

A lightweight Android app that plays **flat, already-stereoscopic Side-by-Side
(SBS) videos** through a Google Cardboard / Wearality-style phone headset.
It is **not** a 360°/VR180 player — it splits each SBS frame into a left and
right half and renders one half per eye, with lens distortion correction for
your specific headset.

Built for a Xiaomi 14 Ultra but runs on any Android 8.0+ (API 26+) device.

---

## 0. What was fixed in this revision

The previous drop did not build, and several optical parameters were being
ignored at runtime. Everything below is now fixed:

**Build blockers**
- `MainActivity.kt`: the clickable `Card(...)` was called with a `Modifier` in
  the first positional slot. The Material 3 clickable overload takes `onClick`
  there, so neither overload matched and the module failed to compile. Now
  `Card(onClick = ..., modifier = ...)`.
- Android Gradle Plugin 8.5.2 → **8.6.1**. 8.5 is only tested up to
  `compileSdk = 34`, and the module compiles against 35.
- The GitHub Actions workflow is included at `.github/workflows/build-apk.yml`. It
  installs JDK 17 and a pinned Gradle 8.7 with `gradle/actions/setup-gradle`, then
  builds directly with `gradle`, so no `gradle-wrapper.jar` is required in the repo. The repository has no
  `gradle-wrapper.jar`, so the old `./gradlew` step was a single point of
  failure.

**Optics and rendering**
- **Inter-lens distance did nothing.** The shader's horizontal lens-centre
  offset was hardcoded to `0f`, so the lens axis always sat in the middle of
  each half of the screen no matter what the profile said. It is now computed
  as `screenWidth / 4 - interLens / 2` per eye, which is what makes your 45 mm
  value actually move the image. This was the single biggest visual bug.
- **Aspect ratio was ignored.** Each eye image was stretched to fill the whole
  lens cone, so a 16:9 movie came out vertically stretched and circles were
  ellipses. The virtual screen is now sized from the real video dimensions
  (reported by ExoPlayer through `onVideoSizeChanged`) and the stereo mode,
  and letterboxed inside the field of view.
- **Half SBS was wrong.** The old `aspectCorrectionFactor = 2.0` cropped the
  middle half of each eye image and magnified it instead of un-squeezing it.
  Squeeze is now handled by the per-eye aspect ratio, which is the only place
  it belongs.
- **The `SurfaceTexture` transform matrix is now applied.** It was ignored and
  replaced with a hardcoded vertical flip, which happens to match most
  decoders but breaks on any device whose decoder reports a crop or a
  different orientation.
- **Field of view is now clamped by the screen**, exactly like
  `LensDistortion::CalculateFov` in the Cardboard SDK, so the renderer never
  asks for screen area the eye cannot see through the lens.
- **The 3 mm border constant** (`kDefaultBorderSizeMeters`) is applied to the
  tray-to-lens distance, matching the SDK.
- **Zoom no longer corrupts the distortion.** It used to divide the screen
  position before the polynomial, which changed the distortion radius; it now
  scales the virtual screen after it.
- All of that geometry lives in one new file, `render/EyeOptics.kt`, shared by
  the video renderer and the calibration renderer so they cannot drift apart.

**Lifecycle and crashes**
- `GLSurfaceView.onPause()` / `onResume()` were never called in either VR
  activity. The GL thread leaked and the surface was not released properly.
- `QrScanActivity` read a `lateinit` `PreviewView` that is only assigned during
  composition, which runs after `onCreate`. The camera could start first and
  crash with `UninitializedPropertyAccessException`. The view is now created
  eagerly.
- `VrPlayerActivity` built a second `ExoPlayer` on every GL context recreation.
  It now reuses the player and just swaps the output surface.
- Tap-to-pause was added in the VR view (the back button still exits), the
  profile list refreshes after returning from the QR scanner, and the
  calibration screen marks the profile it saves as the active one.

Everything else in the sections below still describes the app accurately.

## 1. Why there's no APK attached

This project was generated in a sandboxed build environment whose network
egress is limited to a short allow-list (GitHub, npm, PyPI, crates.io, a
couple of Ubuntu mirrors). It cannot reach `dl.google.com`, `maven.google.com`,
`repo.maven.apache.org`, or `services.gradle.org` — i.e. it cannot download
the Android SDK, the Gradle distribution, or a single one of AndroidX/Media3/
CameraX/ML Kit. Every real Android toolchain needs at least one of those.
So an APK could not be compiled here, and rather than claim otherwise, this
package gives you the **complete, real source project** plus the exact
commands to build it yourself (Section 3).

The fastest path to an installable APK without installing anything locally:
push this folder to a GitHub repository. `.github/workflows/build-apk.yml`
builds a debug APK on every push to `main`/`master` (and on demand from the
Actions tab) and uploads it as the `app-debug-apk` artifact.

## 2. What's actually implemented

- **Main menu** → Open Video / Calibration / Viewer Profile / Settings, per your spec.
- **SAF file picker** (`ACTION_OPEN_DOCUMENT`) — no server, no account, works with local files, Downloads, SD card, cloud-backed providers, etc.
- **External player target** — registered for `ACTION_VIEW` so the app shows up when another app (Stremio's "open in external player", a file manager, a browser download, a share sheet, ...) hands over a video. See Section 9.
- **Media3 ExoPlayer** decodes the video (hardware decoder path) onto a `SurfaceTexture`, MP4/MKV/H.264/H.265 as supported by the device's codecs, plus HLS/DASH/RTSP for adaptive or network streams handed in from other apps.
- **Movie playback controls**: tap to show/hide an overlay with play/pause, ±10/20/30s seek buttons, and a scrubber bar with time labels (Section 10) — auto-hides while playing, stays put while paused.
- **Stereo formats**: Full SBS, Half SBS, Top/Bottom — selectable per video, not auto-detected (per your requirement).
- **OpenGL ES 2.0 renderer** (`StereoGLRenderer`) draws the left half of the phone screen from the left half of the source frame, and the right half of the screen from the right half of the source frame — one GL viewport per eye, one texture crop per eye, **never duplicating the frame into both eyes** and never wrapping it on a sphere.
- **Cardboard lens distortion**, implemented from Google's own published formula (Section 4).
- **Manual viewer-profile screen** with exactly the fields you listed (lens spacing, screen-to-lens, tray-to-lens, vertical alignment, FOV L/R/T/B, K1/K2/K3), pre-filled with your `test/test` profile as the default.
- **QR import** via CameraX + ML Kit barcode scanning, decoding the real Cardboard QR payload format (Section 5) — no root, no reading the Google Cardboard app's private storage.
- **Calibration screen**: a generated SBS test pattern (grid, concentric circles, crosshair, L/R labels, three parallax "depth" markers per eye) rendered through the same distortion pipeline, with live sliders for every parameter (including independent horizontal/vertical stretch, Section 10), a slide-away panel so you can see the pattern unobstructed, a reset-to-neutral button, and a Save button.
- **Immersive fullscreen, keep-screen-on, locked landscape** in VR view.
- Head tracking is **not implemented** (per your requirement that it be optional/off by default) — the image is fixed in front of the user. The `headTrackingEnabled` flag already exists in `ProfileRepository` as a hook if you want to add a gyroscope-driven yaw/pitch offset later.

## 3. Building the APK yourself

**Easiest path — Android Studio:**
1. Install Android Studio (Koala/2024.1 or newer) — it bundles a compatible JDK and can auto-install the Android SDK.
2. `File → Open` and select the `VrSbsPlayer/` folder from this download.
3. Let Gradle sync (this is when the AndroidX/Media3/CameraX/ML Kit dependencies actually download — needs a normal internet connection, which this sandbox didn't have).
4. Click the green **Run ▶** button with your Xiaomi 14 Ultra plugged in (USB debugging on), or:
5. `Build → Build Bundle(s)/APK(s) → Build APK(s)`.

**Command line**, from the `VrSbsPlayer/` folder, once you have the Android SDK installed and `ANDROID_HOME`/`local.properties` pointing at it:
```bash
gradle wrapper --gradle-version 8.7   # generates gradlew + the wrapper jar (one-time, needs network)
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```
The debug APK will be at:
```
app/build/outputs/apk/debug/app-debug.apk
```
No signing config is required for a debug build; `adb install` or sideloading works as-is.

## 4. Cardboard lens distortion — the actual math used

The distortion coefficients (K1/K2/K3) and their meaning are taken directly
from Google's open-source Cardboard SDK proto,
`googlevr/cardboard/proto/cardboard_device.proto`:

```
// Coefficients Ki for pincushion distortion function which maps
// from position on real screen to virtual screen (i.e. texture) relative
// to optical center:
//
//   p' = p (1 + K1 r^2 + K2 r^4 + ... + Kn r^(2n))
//
// where r is the distance in tan-angle units from the optical center,
// p the input point, and p' the output point.
```

For every screen pixel the app is about to draw (per eye), the fragment
shader (`ShaderSource.kt`):
1. Converts the pixel's position to millimeters from that eye's optical
   (lens) center, using the phone's real physical screen size (from
   `DisplayMetrics` + `xdpi`/`ydpi`), the profile's `inter_lens_distance`
   horizontally, and its `tray_to_lens_distance` / `vertical_alignment`
   vertically (minus the SDK's 3 mm border constant). The lens centre is
   deliberately *not* the centre of each half of the screen: it sits
   `inter_lens_distance / 2` from the screen's centre line.
2. Divides by `screen_to_lens_distance` to get **tan-angle units** (exactly
   as the proto defines `r`).
3. Applies `p' = p·(1 + K1·r² + K2·r⁴ + K3·r⁶)` — Google's own polynomial,
   verbatim, no reinterpretation.
4. Rejects the pixel (draws black) if the result falls outside the profile's
   four FOV angles (`left_eye_field_of_view_angles`), after those angles have
   been clamped by what the screen can physically show through the lens, the
   same way `LensDistortion::CalculateFov` does it.
5. Intersects the remaining direction with the flat virtual movie screen. That
   screen is centred on the lens axis and sized to fit inside the FOV at the
   true aspect ratio of one eye image, divided by the zoom factor, so the
   picture keeps its proportions instead of being stretched to the lens cone.
6. Samples the corresponding half of the video texture at that coordinate,
   through the `SurfaceTexture` transform matrix reported by the decoder.

**One documented deviation from the official SDK, stated explicitly per your
instructions:** the official Cardboard SDK bakes this polynomial into a
triangle mesh once, then lets the GPU rasterizer interpolate between mesh
vertices (cheaper, some approximation error). This app evaluates the exact
polynomial **per fragment** instead (more accurate, negligible extra GPU cost
on a 2024-era phone SoC). The formula, field semantics, and coefficient
values are unchanged — only the interpolation approach differs, and only in
a way that makes the correction *more* faithful to the formula, not less.

## 5. Cardboard QR profile format

Per Google's documented behavior for `QrCodeContentProcessor` (the class the
official SDK uses for this):
- A QR code containing exactly `https://google.com/cardboard` means
  "use the stock Cardboard V1 viewer" — this app then loads the well-known
  Cardboard V1 defaults (60mm IPD, 42mm screen-to-lens, 40° FOV, K1=0.441/K2=0.156).
- A QR code containing `https://google.com/cardboard/cfg?p=<payload>` carries
  a **base64url-encoded, protobuf-serialized `DeviceParams` message** in
  `<payload>`. This app decodes that payload with a small hand-written
  protobuf-wire-format reader (`CardboardProto.kt`) that implements exactly
  the eight fields defined in the real `.proto` file (vendor, model,
  screen-to-lens distance, inter-lens distance, FOV angles, tray-to-lens
  distance, distortion coefficients, vertical alignment, primary button) —
  field numbers copied verbatim from Google's source, not guessed.
- Any other URL is treated as a redirect to follow (also matching the
  documented "follow HTTP redirects until a matching URL is found" behavior),
  then re-checked against the two patterns above.
- We do **not**, and cannot without root, read the Google Cardboard app's own
  private storage — this app scans the physical QR code itself instead,
  which is the supported, sandbox-safe approach and is what you asked for as
  the fallback.

Your own profile (`test`/`test`, 45mm IPD, 39mm screen-to-lens, 35mm
tray-to-lens, Bottom alignment, 50°/50°/50°/50° FOV, K1=0.440, K2=0.150,
K3=0) is pre-loaded as the default profile (`builtInUserProfile()` in
`ViewerProfile.kt`) so you don't have to type it in on first run — but the
manual-entry screen is fully wired up if you want to change or re-enter it.

## 6. How SBS video is split

- **Full SBS**: the frame is twice the width of a single eye image (e.g. a
  3840×1080 frame = two full 1920×1080 images side by side). Each eye simply
  samples texture U in `[0, 0.5]` (left) or `[0.5, 1]` (right); no rescale
  needed since each half already has the correct aspect ratio.
- **Half SBS**: the frame is the *original* single-eye resolution but each
  half has been horizontally squeezed 2:1 to fit (the common Blu-ray-3D
  packing, e.g. a 1920×1080 frame containing two 960-wide squeezed images).
  Same texture crop as Full SBS, but the eye image is displayed at the *frame*
  aspect ratio rather than the cropped-pixel aspect ratio
  (`StereoMode.eyeAspect()`), which stretches it back out so people and
  objects aren't rendered squashed.
- **Top/Bottom** (the easy bonus mode you allowed): left eye = top half
  (`v ∈ [0, 0.5]`), right eye = bottom half (`v ∈ [0.5, 1]`). Both the
  squeezed variant (1920×1080 holding two 1920×540 views) and the full variant
  (1920×2160) are offered, since they differ only in the eye aspect ratio.

The rule in one line: **the crop rectangle picks the pixels, the eye aspect
ratio decides the shape they are displayed in.** The virtual screen is then
fitted inside the field of view of the profile, centred on the lens axis, and
scaled by the zoom slider.

## 7. Parameters kept deliberately separate

As requested, these are **not** conflated anywhere in the code or UI:

| Concept | Where it lives | What it changes |
|---|---|---|
| Physical lens spacing / inter-lens distance | `ViewerProfile.lensSeparationMm` (from Cardboard `DeviceParams`) | Where the shader thinks your eyes/lenses physically are, for the distortion math |
| Screen-to-lens distance | `ViewerProfile.screenToLensMm` | Converts mm → tan-angle units (`r` in the distortion formula) |
| Stereo image separation / parallax | `ViewerProfile.stereoSeparationPercent` | Shifts what the two eyes *sample from the video*, i.e. artificial convergence/parallax adjustment for badly-authored 3D sources — has nothing to do with your physical lenses |
| Field of view | `fovLeftDeg/RightDeg/TopDeg/BottomDeg` | How much of the corrected image maps across the visible lens cone |
| Lens distortion coefficients | `distortionK1/K2/K3` | The pincushion pre-correction curve itself |

## 8. Project layout

```
VrSbsPlayer/
├── app/src/main/java/com/example/vrsbsplayer/
│   ├── MainActivity.kt            # main menu, video-mode picker, profile list (Compose+Navigation)
│   ├── ManualProfileScreen.kt     # manual DeviceParams entry form
│   ├── QrScanActivity.kt          # CameraX + ML Kit QR scanning
│   ├── VrPlayerActivity.kt        # immersive fullscreen stereo playback (ExoPlayer + GLSurfaceView)
│   ├── CalibrationActivity.kt     # test-pattern screen with live sliders
│   ├── profile/
│   │   ├── CardboardProto.kt      # hand-written protobuf codec for DeviceParams
│   │   ├── ViewerProfile.kt       # app-level profile model + conversions
│   │   ├── ProfileRepository.kt   # local JSON persistence (no server/account)
│   │   └── QrProfileImporter.kt   # QR URL matching + redirect following
│   └── render/
│       ├── StereoMode.kt          # Full SBS / Half SBS / Top-Bottom crop + aspect math
│       ├── EyeOptics.kt           # Cardboard lens geometry, shared by both renderers
│       ├── ShaderSource.kt        # GLSL: distortion formula, documented inline
│       ├── StereoGLRenderer.kt    # video-texture renderer (two eye viewports)
│       ├── TestPatternGLRenderer.kt / TestPatternGenerator.kt  # calibration screen
└── README.md                      # this file
```

## 9. Opening videos from other apps (e.g. Stremio)

The app now registers `MainActivity` for `android.intent.action.VIEW`, so it
appears in the picker whenever another app offers a "play in external
player" / "open with" option for a video — Stremio, a file manager, a
browser download, another app's share sheet.

**How the match works.** Apps that offer an external-player chooser set an
explicit video MIME type on the intent (that's what makes Android list only
video-capable apps in the first place), so the manifest matches on
scheme (`http`, `https`, `content`, `file`) + `video/*`. `content://` /
`file://` URIs with no MIME type at all, and `rtsp://` streams, are also
matched without requiring one, since those senders often don't set one.

**What happens when a video comes in:**
1. `MainActivity.extractIncomingVideo()` reads the URI from `intent.data`,
   plus three optional extras other players commonly send: `title` (String),
   `position` (Int, ms — playback resumes from there), and the intent's own
   MIME type (used so ExoPlayer can pick the right source type even when the
   URL has no file extension, which is normal for links generated by
   streaming/transcoding addons).
2. It's routed straight to the existing **Select Stereo Format** screen —
   the same screen used for locally-picked videos — instead of the main
   menu, so there's no need to dig through the app first.
3. The stereo packing (Full SBS / Half SBS / Top-Bottom / Top-Bottom full) is
   **guessed from the filename or title** using the tags real 3D releases are
   named with (`HSBS`, `FSBS`, `SBS`, `HOU`/`HTAB`, `TAB`/`OU`, `FTAB`/`FOU`,
   ...) via `StereoMode.guessFromName()`, and pre-selected. This is a
   convenience default only — exactly like a locally-picked video, **the
   format is still shown and can be changed** before entering VR, it's just
   not left on the first option when the name gives a strong hint. If
   nothing matches, it falls back to Full SBS as before.
4. Tapping **Enter VR View** launches `VrPlayerActivity` with that URI, mode,
   and any title/start-position/MIME-type extras — the same code path as
   locally-picked videos.

**Quality/container coverage.** Progressive MP4/MKV remuxes (the common case
for "various qualities" torrent/debrid links) are handled by the extractors
already bundled with `media3-exoplayer`, regardless of URL extension, since
ExoPlayer sniffs the container from the actual bytes. `media3-exoplayer-hls`,
`-dash`, and `-rtsp` were added to `build.gradle.kts` so adaptive-quality
streams (`.m3u8`/`.mpd`) and live RTSP sources also resolve to the correct
media source instead of failing silently.

**Cleartext (plain `http://`) traffic is explicitly allowed**
(`android:usesCleartextTraffic="true"` in the manifest). Android blocks
non-HTTPS network requests by default for apps targeting recent SDKs, which
surfaces as `ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED` from ExoPlayer. Stremio's
own local streaming server (`http://127.0.0.1:<port>/...`) and several
addons hand out plain-HTTP links rather than HTTPS, so this player needs
cleartext allowed app-wide to actually be usable as an external player —
the same reason general-purpose players like VLC ship with it enabled.

**Known gap:** subtitle passthrough (the `subs` extra some external-player
integrations send) is intentionally not implemented — this renderer draws
raw decoded video frames onto a GL texture with no overlay/compositing layer
at all, so accepting subtitle URIs without ever drawing them would be
misleading. Wiring in caption rendering would mean adding a second layer
that composites `SubtitleView` (or drawn glyphs) onto the same GL surface the
stereo shader reads from — a real feature addition, not a one-line fix, so
it's left out rather than half-done.

## 10. Advanced image controls, playback controls, and the app rename

**Vertical stretch** joins horizontal stretch (Section 9's zoom discussion): both live in
Calibration, both default to 1.0 (off, zero behavior change for existing profiles), and
they're independent of each other and of Zoom - Zoom scales both axes together, these
scale one axis only. Between them you can now fill a mismatched-aspect headset's FOV on
either axis without cropping the other, at the honest cost of geometric stretching on
whichever axis you push.

**Calibration panel can now slide out of the way.** Tap the test pattern anywhere to hide
the settings column and see the full, unobstructed picture; tap again (or use the small
`›`/`‹` handle pinned to the right edge) to bring it back. There's also a **"Reset image
placement"** button that zeroes out zoom/stretch/offsets/stereo-separation back to neutral
without touching the physical headset numbers above it (lens spacing, screen-to-lens,
FOV, distortion K1-3) - those took real effort to dial in and shouldn't be one misclick
from being wiped.

**Movie playback now has real transport controls.** `VrPlayerActivity` previously had
nothing but "tap to pause" with no way to jump around. A tap now shows/hides an overlay
with: play/pause, seek back/forward 10/20/30s buttons, and a scrubber bar with current
time and duration. The overlay auto-hides after 4 seconds *while playing* (it stays put
while paused, since you're presumably still interacting with it). This overlay is plain
Android views (`res/layout/vr_player_controls.xml`), not Compose, since that Activity is
GLSurfaceView-based and didn't otherwise need Compose interop.

**The app can now be installed alongside the original.** `app/build.gradle.kts`'s
`applicationId` changed from `com.example.vrsbsplayer` to `com.example.vrsbsplayer2`, and
the display name changed to "VR SBS Player Pro" (`strings.xml`). The Kotlin package
(`namespace`) is untouched, so no source files needed renaming - Android only uses
`applicationId` to tell installed apps apart, so this is the minimal change that achieves
a conflict-free parallel install. Bump `applicationId` again (e.g. `...vrsbsplayer3`) if
you ever want a third copy.

## 11. Known limitations / next steps

- Distortion is evaluated per-fragment against a flat quad rather than a
  pre-baked mesh; per-fragment is if anything more accurate, and it avoids an
  intermediate render target entirely, but for a first-generation
  Cardboard-class app this is the standard approach and matches what most
  open-source Cardboard SBS players do, but if you want bit-for-bit parity
  with Google's official renderer you'd swap in the real `cardboard` AAR
  (`com.github.googlevr:cardboard`) — kept out here to avoid a JitPack
  dependency that also wasn't reachable from this sandbox to verify.
- Gyroscope head tracking is stubbed out (`headTrackingEnabled` flag exists)
  but not wired to the renderer — add a `SensorManager` rotation-vector
  listener feeding a yaw/pitch offset into the shader's `uLensCenterOffsetMm`
  equivalent if/when you want it, with a hard toggle to fully disable it.
- MKV support depends entirely on the phone's OS media extractors; Media3
  will use hardware decode when the device codec list supports the codec
  inside the container.
- `StereoMode.guessFromName()` (Section 9) only recognises common release-name
  tags and only pre-selects a default — it can't detect the format from pixel
  content, and an untagged filename just falls back to Full SBS like before.
  Always double check the format screen before entering VR on a new source.

### Cinema projection

The player supports a cylindrical **Cinema** projection in addition to the original flat
projection. Cinema mode maps the source image horizontally by viewing angle instead of
by a flat 16:9 rectangle, so increasing horizontal coverage does not inherently increase
vertical image size or crop the actors at the top/bottom. The calibration screen has a
Cinema/Flat selector and a total horizontal cinema-FOV slider (80-170 degrees). The
requested cinema FOV is still capped by the physical per-eye FOV from the headset profile.
Stereo separation remains an independent source-image/parallax adjustment and should not
be used as a screen-size control.

### GitHub Actions

`.github/workflows/build-apk.yml` builds the debug APK automatically on pushes, pull
requests, and manual workflow dispatch. It uses JDK 17 and Gradle 8.7 through the GitHub
Actions Gradle setup action, so the repository does not need a checked-in Gradle wrapper.
The generated APK is uploaded as the `VrSbsPlayer-debug-apk` workflow artifact.
