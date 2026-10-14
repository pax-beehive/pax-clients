# Android welcome and controls

## Coherent orbits and camera (0.1.9)

Replaces the 0.1.8 independently compressed circles, inclinations and integer
speeds with two-body elliptical propagation from the J2000 orbital elements in
[JPL SSD Table 1](https://ssd.jpl.nasa.gov/planets/approx_pos.html). The Sun is at
the heliocentric origin. All planets use the same elapsed time, distance unit
(AU), ecliptic frame and Kepler period relationship. The camera follows Earth's
position with a fixed inertial orientation and a nearby offset. Distant objects
use a pinhole projection, are depth-sorted, and disappear when outside the
viewport or behind the camera; they no longer fade against the hero text bounds.
Their small inclinations and Earth-relative parallax determine apparent paths,
including reversals, rather than hand-authored screen motion.

This is an orbital illustration, not a live ephemeris or a true-scale sky:
initial time is J2000 + 0.3 years, Earth uses the Earth-Moon barycentre, and
secular evolution, perturbations and light travel time are omitted. A simulated
year takes 120 seconds at system animation scale 1. Display radii alone are
exaggerated (Sun about 12x, other distant planets about 300x); positions, orbital
radii and relative periods are not compressed. There is no guarantee that every
planet enters the view during a short visit. Earth remains a nearby close-up.

Orbital time survives recreation, pauses when the welcome page is not resumed,
honours disabled/scaled animations and has no 14-minute reset. Planet shadows
now include the Sun-observer phase angle. When the Sun is behind Earth in the
view, the Earth shader shows a dark hemisphere and lit rim, with restrained fill
light to retain surface detail. API 26–32 still uses the previously documented
fixed-light cached Earth; the live globe lighting is API 33+. Local satellites,
foldable layouts and the signed-in approach remain in place.

Validation: debug build, lint, all 31 JVM tests and all 9 isolated-emulator
UI/rendering tests passed. Orbital checks cover perihelion/aphelion distances,
variable speed and equal swept areas, period ratios, inclinations, a reference
Earth position, camera depth/culling and continuity past the former reset.
The new UI clock check covers pause/resume and saved-state restoration; the
existing login routes, duplicate-entry guard, zoom, foldable draft retention
and both Earth renderers also pass.

Installed 0.1.9 (10) on Pixel with account data retained, and verified the
installed APK SHA-256 against `artifacts/paxworkspace-android-0.1.9-debug.apk`:
`eb0a84b7fa12efcce7f2d2a5c7136f8ba5f47e6b2f99aa4fa2345c1ce7e0ca41`.
The phone remained locked, so this version has no new physical-device visual
or frame-time acceptance. Reviewed compact/expanded captures and a full-cycle
contact sheet from a 130-second recording: the Sun exits and returns from the
other side, Earth passes through night/day, and distant bodies remain slower.
Local evidence is in `artifacts/validation-0.1.9/`, including
`orbit-full-cycle.mp4`, `orbit-preview.mp4` and `full-cycle-review.png`.

## Earth-tracking solar system (0.1.8, superseded paths)

The welcome camera now translates with Earth as it revolves around the Sun.
Earth keeps the same screen position and apparent size while heliocentric
positions for the Sun, Mercury, Venus, Mars, Jupiter and Saturn are transformed
into Earth-relative coordinates. Perspective makes neighbouring bodies grow
and shrink as they cross the view. Local satellite orbits and the signed-in
zoom transition remain centred on Earth.

This is a cinematic model, not an astronomical ephemeris: sizes, distances,
inclinations and periods are compressed, and the distant-body layer is kept
behind the enlarged Earth focal subject. Earth completes a revolution in 84
seconds; all orbital phases close continuously at the 14-minute master-loop
boundary. No solar-system orbit lines or labels are added to the interface.

The Sun has a warm corona and a textured surface. Jupiter has cloud bands and
a storm, Saturn has separated front/back rings, and rocky planets have surface
detail. Bodies fade near the hero's vertical edges so they do not obscure
branding or entry text. The Earth shader and spacecraft lighting follow the
Sun's direction; restrained fill light keeps the night side readable. API
26–32 retains the cached still Earth and its fixed lighting, while the Sun,
neighbouring planets and satellites still move. No network assets are used.

Validation: a clean debug build, lint and all 28 JVM tests passed. Three new
model checks cover the Earth-relative camera, neighbours crossing the viewing
field, and continuity at the master-loop boundary. All 8 emulator UI/rendering
tests passed, including signed-in zoom, signed-out routing, restored entry,
foldable resize/draft retention and the older-Android rendering fallback.
An intermediate concurrent build damaged disposable dex intermediates; the
final build was rebuilt cleanly and the complete UI suite rerun serially.
Initial emulator recording also showed stalls under host contention, so it is
not evidence of physical-device frame rate.

Installed 0.1.8 (9) on the Pixel without clearing its account, and matched its
APK hash. The phone remained locked; this version has no new physical-device
playback or frame-time measurement. Artifact:
`artifacts/paxworkspace-android-0.1.8-debug.apk`, SHA-256:
`f9c17a23121f62321728b881a2fb6293f9b5a9cd9f612ef2b6766914df1e1566`.
Review captures and test results are in `artifacts/validation-0.1.8/`.
The final emulator preview is `solar-preview.mp4`; earlier review material is
kept separately in `initial/`. Local work only; no remote push or backend change.

## Enter through the planet (0.1.7)

For a restored signed-in account, **Enter workspace** now starts a 1.05-second
camera approach. Labels fade first; the planet grows exponentially and moves
toward the window center, filling the screen before dissolving into the
workspace background. The workspace then fades in over 220ms. The scene draws
at window level so the zoom is not clipped by the scrolling hero slot; its
resting bounds still follow the responsive layout. These durations follow the
system animation scale, with no artificial delay when animations are disabled.

Signed-out entry still opens login immediately. Repeated taps cannot schedule
multiple entries. Back cancels the approach; an in-progress entry survives
saved-state restoration and finishes after returning to the resumed lifecycle.

Validation: debug build, lint and all 25 JVM tests passed. All 8 isolated
emulator UI/rendering tests passed, including 3 new checks for delayed signed-in
navigation with duplicate taps, immediate signed-out navigation, and entry
completion after saved-state restoration. The existing 5 flow/rendering tests
also passed with system animations disabled. Reviewed the normal expanded
layout and an intermediate full-screen planet frame from the controlled
animation clock. The Pixel was locked during this update, so no new physical
device transition playback or performance claim is made.

Installed 0.1.7 (8) on the Pixel without clearing account data, and verified the
installed APK hash against `artifacts/paxworkspace-android-0.1.7-debug.apk`:
`4a3e7e0d3daa80a852f7989cb7512733036eef7e2268a02e941855cc1711524f`.
Evidence: `artifacts/validation-0.1.7/`. Local changes only; no remote push.

## Shaded planet and visual review (0.1.6)

The planet is now the main visual subject: continuous terrain and clouds,
ocean highlights, a day/night boundary, sparse night lights and a thin blue
atmosphere replace the polygonal land patches. Three spacecraft orbit on
different inclined planes with perspective, front/back occlusion, shaded
materials, solar-panel detail on both sides and short fading trails. Tracks
and particles are quieter, and the smaller title leaves the scene more room.
The 2.2-second entrance never delays an available entry button.

Android 13/API 33+ uses a native RuntimeShader with two samples from a bundled
1024×512 field texture. Terrain/cloud noise is baked at development time rather
than recomputed for every pixel of every frame. The 650,570-byte texture is
procedurally generated by `scripts/generate-planet.py` (NumPy and Pillow); it
uses no external imagery or network requests. The shader adds spherical
projection, rotation and lighting. API 26–32 shades a cached 512px still off the
main thread; spacecraft and particles continue moving on those releases, but
the globe itself does not rotate. Motion still follows system animation
settings and stops when the welcome screen leaves the resumed lifecycle state.

Validation:

- Debug build, lint and all 25 JVM tests passed.
- Five isolated emulator UI/rendering tests passed: logged-out entry; logged-in
  entry, messaging and approval; resize/recreation with draft retention; the
  actual shader's lit surface and changing terrain; forced software fallback.
  The unrelated opt-in live gateway smoke test was skipped. API 26–32 was
  covered through the forced fallback on API 35, not a physical older device.
- Reviewed compact Pixel screenshots, expanded emulator screenshots, both
  rendering paths, entrance frames and a 12-second Pixel orbit recording.
- On the unlocked Pixel 11 Pro Fold, a steady-state sample recorded 425 frames:
  p50 12ms, p95 15ms, p99 17ms, zero missed frame deadlines by current Android
  gfxinfo counters. Legacy counters report differently and are retained in the
  raw evidence. This short sample is not a sustained thermal/battery benchmark.
- The separate 11.959-second Pixel recording contains 807 frames; median
  timestamp interval 16.539ms, p95 17.844ms, maximum 49.933ms. Recording timestamps
  are separate evidence from the application's gfxinfo frame timings.
- SwiftShader emulator timings were poor (roughly 100ms+); switching the
  isolated emulator to the Mac's GPU removed that software-renderer bottleneck.
  Physical-device measurements above are the relevant performance evidence.

Installed 0.1.6 (7) on the Pixel, preserved its signed-in state, and verified that
the installed APK matches the archived artifact. No backend changes or remote
push. This is ready for the user's visual acceptance, not a store release.

Artifact: `artifacts/paxworkspace-android-0.1.6-debug.apk`.
SHA-256: `0099f5b4cd3af31de483a735b0e247b3c5d8eb85dd5c1906904c3d295d9a12be`.
Evidence: `artifacts/validation-0.1.6/`, including `pixel-preview.mp4`,
`pixel-compact.png`, raw frame timings, UI results and rendering screenshots.

## Planetary system (0.1.5)

The central subject is now a blue-green rotating planet. Four spacecraft travel
on independent inclined orbital planes, with different directions, speeds,
phases and sizes. The models include a communications satellite with a dish,
an observation satellite with an optical instrument, and a compact CubeSat
with long antenna booms. Perspective changes their apparent size with depth.

Far-side tracks, particles and spacecraft render first, then the opaque planet,
then near-side tracks and spacecraft. This lets the planet hide satellites
passing behind it. Land patches rotate on the sphere and clip at the horizon.
Each orbital speed is an integer multiple of the shared animation cycle so
positions stay continuous at the loop boundary. The existing entry routing,
system-motion settings and dark system splash are retained.

Debug build, lint, 25 JVM tests and all 3 UI tests passed. The initial UI run
timed out waiting for a draft after immediate activity recreation; the test now
asserts that input is displayed before requesting recreation, and the complete
suite passed with the original post-recreation checks intact. Initial failure
logs are retained separately in the local evidence folder. Reviewed compact and
expanded screenshots and a 12-second recording showing multiple orbital positions.
Land contours are combined into one shaded draw to avoid per-cell draw calls.

Installed 0.1.5 (6) on the USB-connected Pixel 11 Pro Fold without clearing account
data, and matched the installed APK hash. The phone was locked; physical-device
visual acceptance remains for the user. No backend deployment or remote push.

Artifact: `artifacts/paxworkspace-android-0.1.5-debug.apk`.
SHA-256: `e019293044de08582c6131f1ef275f1811a8b1f7e98e2346f40cbc0e6e364d11`.
Evidence: `artifacts/validation-0.1.5/`, including `planetary-preview.mp4`.

## Satellite arrival (0.1.4, superseded composition)

The welcome scene now shows a shaded satellite with twin solar arrays, a metal
bus, a recessed communication dish and an antenna. Native Canvas projects a
cached 3D mesh with perspective and depth-sorted faces. The satellite drifts and
turns slowly; 180 orbit particles pass behind and in front of it, with another
100 background stars. Cyan and violet light sit on a deep navy background.

A 1.8-second arrival brings the satellite and particles together, followed by
the title and entry button. The button is usable as soon as account restoration
finishes; animation never imposes an extra navigation delay. The entrance is
remembered across activity recreation. Motion follows the system animator scale
and stops when the welcome screen is not resumed. No dependencies or network
assets were added. The platform splash screen shares the navy background and
uses a cyan orbit mark, avoiding a white flash before Compose starts.

Validation: debug build and lint passed, with 25 JVM tests and all 3 isolated
emulator UI tests passing. Reviewed expanded and compact screenshots and
recorded the actual animated entrance. The scene also renders completely when
system animations are disabled. Solar-array surfaces were corrected after
visual review to avoid overlapping coplanar faces hiding individual cells.

Installed 0.1.4 (5) on the USB-connected Pixel 11 Pro Fold without clearing
account data; the installed package matches the local APK. The phone was locked
at installation, so physical-device visual acceptance remains with the user.

Artifact: `artifacts/paxworkspace-android-0.1.4-debug.apk`. SHA-256:
`ca5195faefd01705b0e730dc8ba88021d108e261df541bbf45e324a4cff4f088`.
Evidence: `artifacts/validation-0.1.4/`, including `satellite-preview.mp4`.
No gateway deployment or remote push was required.

## Original welcome and controls (0.1.3)

The launch destination is now a welcome page with a native Canvas animation:
six agent points orbit the PAX wordmark, with connecting lines, a light sweep,
and a soft monochrome glow. No videos, downloaded assets, or new dependencies
are used. Animation values are read during drawing rather than recomposing the
screen each frame. Animation stops when the page is not resumed, and follows
the system animator duration scale.

Once stored account restoration finishes, the primary action is **Enter
workspace** for a signed-in account and **Sign in to PaxWorkspace** otherwise.
The latter opens the existing sign-in page. Pending browser login/region
selection resumes directly. Entering is remembered across activity recreation,
so folding or rotating does not send the user back to the welcome page. The
welcome content scrolls on short windows and with larger accessibility text.

Primary actions now use a brighter fill, dark text, elevation, and a spring
press response. Secondary actions have a visible border and filled surface.
Toolbar icons have bordered touch targets. Session rows are outlined cards
with a direction arrow and a distinct selected background. Disabled actions
remain visibly muted. All action targets are at least 48 dp tall.

Authentication, node enrollment, encryption-key approval, and gateway contracts
are unchanged from 0.1.2. This is a local development build, not a store release.

## Verification

- Debug build and lint passed; 25 JVM tests passed.
- All 3 isolated emulator UI tests passed: signed-out welcome to login;
  signed-in welcome to workspace, messaging and approvals; folded-width / expanded-width
  transitions with draft and selection retention across activity recreation.
- Reviewed welcome screenshots at expanded and compact widths, plus workspace
  controls. Two compact-width captures with animations enabled show the orbit
  moving. Fixture tests use disabled system animations for deterministic checks.
- Installed 0.1.3 (versionCode 4) on the USB-connected Pixel 11 Pro Fold without
  clearing its account data. The installed APK SHA-256 matches the local artifact.
  The phone remained on its lock-screen dream activity; visual acceptance on the
  physical device is still for the user to check after unlocking.

Local artifact: `artifacts/paxworkspace-android-0.1.3-debug.apk`.
SHA-256: `fd5f2515183746233b6a327ed8a341008744be66d20d641b5c376a59e380fc0c`.
Screenshots, test reports, and install proof: `artifacts/validation-0.1.3/`
(ignored local artifacts).

The existing real-account emulator was not changed. No gateway deployment was
needed, and no Git remote was pushed.
