#!/bin/bash
set -euo pipefail

VERSION="${1:-}"
if [ -z "$VERSION" ]; then
  echo "Usage: $0 <version>" >&2
  exit 1
fi

source "$(dirname "${BASH_SOURCE[0]}")/release-signing.sh"
load_release_signing

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

verify_release_apk_signer "${apks[0]}" 26
verify_release_apk_manifest "${apks[0]}"
[[ "$release_version_name" == "$VERSION" ]] ||
  signing_error "APK versionName $release_version_name does not match release version $VERSION."

audio_sources="third_party/termux-audio/termix-audio-sources.tar.xz"
if [[ ! -f "$audio_sources" || -L "$audio_sources" || ! -s "$audio_sources" ]]; then
  echo "No audio runtime source archive found at $audio_sources" >&2
  exit 1
fi
(cd third_party/termux-audio && sha256sum --strict -c termix-audio-sources.tar.xz.sha256)

release_apk="app/termix-v${VERSION}.apk"
for existing_apk in app/termix-v*.apk; do
  [[ "$existing_apk" == "$release_apk" && -f "$existing_apk" && ! -L "$existing_apk" ]] ||
    signing_error "Unexpected release asset: $existing_apk"
done
cp "${apks[0]}" "$release_apk"
