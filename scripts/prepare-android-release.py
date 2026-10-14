#!/usr/bin/env python3
"""Check a release tag against the one source of Android version metadata."""
import json
import os
from pathlib import Path
import re
import sys

root = Path(__file__).resolve().parents[1]
props = dict(line.split("=", 1) for line in (root / "android/version.properties").read_text().splitlines() if line and not line.startswith("#"))
version = props["versionName"]
code = int(props["versionCode"])
if not re.fullmatch(r"\d+\.\d+\.\d+", version) or code <= 0:
    sys.exit("Invalid Android version metadata")
tag = sys.argv[1] if len(sys.argv) == 2 else ""
if tag != f"v{version}":
    sys.exit(f"Tag must equal v{version}, found {tag!r}")
metadata = {"tag": tag, "version": version, "versionCode": code, "apk": f"paxworkspace-{version}.apk"}
if output := os.environ.get("GITHUB_OUTPUT"):
    with open(output, "a") as stream:
        for key, value in metadata.items():
            stream.write(f"{key}={value}\n")
print(json.dumps(metadata))
