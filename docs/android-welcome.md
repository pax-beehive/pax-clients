# Android welcome and controls

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
