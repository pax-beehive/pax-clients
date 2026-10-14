# Android 0.1.2: user login and foldables

## Authentication correction

Mobile is a user client, like Web. The previous paxl device-login flow created
an offline node as a side effect; it has been removed from the app. WorkOS
AuthKit login uses a random persisted state and S256 PKCE, a fixed HTTPS callback
relay and an app callback. Duplicate, expired and mismatched callbacks fail
before token exchange. No client secret or WorkOS server key is shipped.

Refresh is serialized across parallel resource requests; rotation is persisted
before the new access token is used. Access tokens stay in memory. Long-lived
sessions obtain a current token before each request/reconnection. Mutations
never automatically retry after an ambiguous network failure.

The gateway validates JWT signature, issuer, expiry, subject and session claims
against client-specific WorkOS JWKS. Account identity and region come from the
same directory binding as Web. Upstream receives a signed user assertion and
server Access credentials, never the WorkOS bearer, browser cookies or caller
identity hints. Bootstrap creates/ensures a user only, never a node or API key.
Logout revokes the verified WorkOS session, then clears local secure storage.

On upgrade, the client revokes its own old API key and clears pending device
login. Account-scoped E2EE keys are retained across the migration. Existing
incorrect offline node records are not deleted by key revocation; deleting
unrelated nodes by matching display names would be unsafe.

## Foldable behavior

At 720dp and above, show a session list and detail pane together. Narrow windows
show one pane. WindowManager folding features define separating/occluding hinge
bounds. Book posture places panes on either side; sufficiently tall tabletop
posture uses top/bottom panes. If space is insufficient, use the larger
unobstructed area. Window size, not a device model name, selects the layout.

Pane composition identity remains stable while resizing. Drafts, filters and
scroll position use Compose saveable state. The Activity requests adjustResize
so the IME does not pan the whole two-pane window. Activity recreation retains the
ViewModel and does not cancel/restart the stream solely for a configuration
change. Genuine backgrounding still suspends observation. No plaintext fallback
was added for encrypted sessions.

## Verification

- Android debug APK and instrumentation APK build.
- 25 JVM tests pass, including PKCE vector/state rejection, one-time callbacks,
  refresh serialization/rotation, legacy own-key revocation, hinge geometries,
  transport and encryption interoperability fixtures.
- Android lint passes (existing warnings remain).
- Companion Worker: 159 tests, TypeScript and production dry-run pass.
- Worker deployed as `59e2dd0e-1ff9-4c61-b20b-50cac28a92a9`.
- The opt-in Android production smoke test passes without touching the vault.
- Live Android-UA requests: unauthenticated new user routes 401, old native
  device-login routes 410, Web auth/session remains 401 when unauthenticated.
- 0.1.2 installed on USB Pixel 11 Pro Fold, serial ending 001EA. Real user login
  and physical fold/unfold acceptance are pending user interaction.
- UI window-resize and Activity tests run only on dedicated `emulator-5584`, AVD
  `pax-foldout-integration`, with synthetic credentials. Three UI tests passed: sign-in, session send/approval,
  and expanded/compact resizing with a draft plus Activity recreation. Screenshots
  were inspected with the keyboard visible and after restoration.

## Reproduce safely

```sh
./scripts/android.sh :app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest :app:lintDebug
ANDROID_SERIAL=emulator-5584 ./scripts/android.sh :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=net.paxtech.workspace.WorkspaceFlowTest \
  -Pandroid.testInstrumentationRunnerArguments.paxFixtureTests=true
```

Never run fixture tests against the personal Pixel or real-account emulator.
The separate `LiveGatewaySmokeTest` uses no vault and accepts `paxLiveSmoke=true`.
No Git remote is created or pushed. iOS/macOS remain deferred.

## Emulator limitation

The API 35 AOSP Pixel Fold AVD entered an invalid display-mode state when
switching to its outer display (both physical displays OFF; LocalDisplayAdapter
reported invalid base mode IDs). This blocked posture instrumentation and is
not counted as passed. Window resizing runs on a separate 8-inch foldable AVD;
synthetic hinge tests cover occlusion geometry. Physical Pixel fold/unfold
validation remains distinct.

APK: `artifacts/paxworkspace-android-0.1.2-debug.apk`.
SHA-256: `4e820c03ebf6d19317e61af3a391768800ae4f4823f5297758c5b1cbb6e08016`.
