# Android welcome and controls (0.1.3)

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
