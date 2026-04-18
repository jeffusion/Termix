#!/bin/bash
set -euo pipefail

VERSION="${1:-}"
if [ -z "$VERSION" ]; then
  echo "Usage: $0 <version>" >&2
  exit 1
fi

shopt -s nullglob
apks=(app/build/outputs/apk/release/*.apk)

if [ "${#apks[@]}" -eq 0 ]; then
  echo "No release APK found under app/build/outputs/apk/release" >&2
  exit 1
fi

if [ "${#apks[@]}" -ne 1 ]; then
  echo "Expected exactly one release APK, found ${#apks[@]}" >&2
  printf 'Matched files:\n' >&2
  printf '  %s\n' "${apks[@]}" >&2
  exit 1
fi

cp "${apks[0]}" "app/termix-v${VERSION}.apk"
