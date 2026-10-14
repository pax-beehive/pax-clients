#!/usr/bin/env bash
# Secrets are passed only to this signing step, never as command-line arguments.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
: "${ANDROID_HOME:?Android SDK is required}"
: "${ANDROID_KEYSTORE_BASE64:?Missing release keystore}"
: "${ANDROID_KEYSTORE_PASSWORD:?Missing keystore password}"
: "${ANDROID_KEY_ALIAS:?Missing key alias}"
: "${ANDROID_KEY_PASSWORD:?Missing key password}"
TAG="${1:?Pass the release tag}"
python3 "$ROOT/scripts/prepare-android-release.py" "$TAG"
VERSION="${TAG#v}"
BUILD_TOOLS="$ANDROID_HOME/build-tools/35.0.0"
SIGNING_TEMP="$(mktemp -d "${RUNNER_TEMP:-${TMPDIR:-/tmp}}/pax-signing.XXXXXX")"
trap 'rm -rf "$SIGNING_TEMP"' EXIT
export SIGNING_TEMP
umask 077
python3 - <<'PY'
import base64, os
from pathlib import Path
Path(os.environ['SIGNING_TEMP'], 'release.p12').write_bytes(base64.b64decode(os.environ['ANDROID_KEYSTORE_BASE64'], validate=True))
PY
mkdir -p "$ROOT/dist"
APK="$ROOT/dist/paxworkspace-$VERSION.apk"
"$BUILD_TOOLS/zipalign" -f -P 16 4 "$ROOT/android/app/build/outputs/apk/release/app-release-unsigned.apk" "$SIGNING_TEMP/aligned.apk"
"$BUILD_TOOLS/apksigner" sign --ks "$SIGNING_TEMP/release.p12" \
  --ks-key-alias "$ANDROID_KEY_ALIAS" --ks-pass env:ANDROID_KEYSTORE_PASSWORD \
  --key-pass env:ANDROID_KEY_PASSWORD --out "$APK" "$SIGNING_TEMP/aligned.apk"
"$BUILD_TOOLS/apksigner" verify --verbose --print-certs "$APK" > "$ROOT/dist/signing-certificate.txt"
"$BUILD_TOOLS/zipalign" -c -P 16 4 "$APK"
"$BUILD_TOOLS/aapt" dump badging "$APK" > "$SIGNING_TEMP/badging.txt"
export ROOT APK
python3 - <<'PY'
import hashlib, os, re
from pathlib import Path
root = Path(os.environ['ROOT'])
props = dict(line.split('=', 1) for line in (root/'android/version.properties').read_text().splitlines() if line and not line.startswith('#'))
badging = (Path(os.environ['SIGNING_TEMP'])/'badging.txt').read_text()
expected = f"package: name='net.paxtech.workspace' versionCode='{props['versionCode']}' versionName='{props['versionName']}'"
assert badging.startswith(expected), 'Release identity/version mismatch'
assert 'application-debuggable' not in badging, 'Refusing to publish a debuggable APK'
cert = (root/'dist/signing-certificate.txt').read_text()
digest = re.search(r'Signer #1 certificate SHA-256 digest: ([0-9a-f]+)', cert).group(1)
assert digest == (root/'android/release-certificate.sha256').read_text().strip(), 'Unexpected signing certificate'
apk = Path(os.environ['APK'])
(root/'dist/SHA256SUMS').write_text(f'{hashlib.sha256(apk.read_bytes()).hexdigest()}  {apk.name}\n')
print(f'Verified signed release: {apk.name}')
PY
