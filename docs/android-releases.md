# Android APK releases

Public repository: https://github.com/pax-beehive/pax-clients

## Workflows

- **Verify native clients** runs on main, pull requests and manual dispatch.
  It builds the Android debug APK, runs JVM tests and lint, verifies bundled NASA
  assets, and builds the shared Apple package and iOS simulator shell.
- **Release Android APK** runs when a `v*` tag is pushed. The tag must exactly
  match `android/version.properties` and its commit must be reachable from main.
  It builds the release variant, runs its JVM tests/lint, signs the APK, verifies
  its certificate, package, version and non-debuggable flag, and publishes a
  GitHub prerelease with APK, `SHA256SUMS` and `signing-certificate.txt`.
  Manual dispatch is also supported when selecting an existing version tag.

The publisher receives only the signed artifacts and has `contents: write`.
The build job has read-only repository access; signing secrets are passed only
into the signing step. Pull-request verification does not use signing secrets.
Actions are pinned to commit SHAs. Existing releases are never overwritten.

## Publish a version

1. Update both `versionName` and monotonically increasing `versionCode` in
   `android/version.properties`; update relevant release documentation.
2. Commit and push to main. Confirm **Verify native clients** passes.
3. Create and push the matching tag, for example:

   ```sh
   git tag -a v0.1.12 -m 'PaxWorkspace Android 0.1.12'
   git push origin v0.1.12
   ```

4. Wait for **Release Android APK**, then download the APK from GitHub Releases.
   Verify it with `sha256sum --check SHA256SUMS` (macOS: `shasum -a 256 -c SHA256SUMS`).

A failed run before publication can be rerun on the same tag. Once published,
create a new version rather than replacing the release or moving its tag.
Releases remain prereleases while the client is in preview.

## Signing

The repository has four encrypted Actions secrets:

- `ANDROID_KEYSTORE_BASE64`
- `ANDROID_KEYSTORE_PASSWORD`
- `ANDROID_KEY_ALIAS`
- `ANDROID_KEY_PASSWORD`

`android/release-certificate.sha256` records the public certificate fingerprint.
The private key and passwords must never be committed or uploaded as artifacts.
The initial signing material is retained in the ignored development-machine
`.local-tools/release-signing/` directory with restrictive permissions; maintain
an independent secure backup before replacing the development machine. Losing
this key prevents in-place updates to existing sideloaded release installations.

A local release can be built using JDK 17 and Android SDK 36/build-tools 35.0.0:

```sh
./scripts/android.sh :app:assembleRelease :app:testReleaseUnitTest :app:lintRelease
# Set the four signing environment variables securely, then:
bash scripts/sign-android-release.sh v0.1.11
```

The signing script takes secrets from the environment, creates a temporary
keystore, verifies the APK and removes the temporary keystore on exit.

## Installation and validation boundaries

Release APKs use a dedicated stable key, different from the local debug key.
Android cannot install the release over an existing debug build with the same
application ID. Uninstalling the debug app removes its local data and requires
signing in again. Future release APKs signed with the same release key can
upgrade each other in place.

CI runs JVM tests and lint, not the physical Pixel or emulator UI suite. The
separate fixture UI tests must run only on an isolated emulator, never on a
personal device; see README. The iOS app is still a shell and is not released.
