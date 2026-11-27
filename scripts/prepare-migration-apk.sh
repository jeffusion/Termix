#!/bin/bash
set -euo pipefail

if [[ "$#" -ne 3 ]]; then
  printf 'Usage: %s <regular-release.apk> <signing-lineage> <output-dir/termix-v1.4.1.apk>\n' "$0" >&2
  exit 1
fi

source "$(dirname "${BASH_SOURCE[0]}")/release-signing.sh"
load_release_signing

regular_apk=$1
lineage=$2
migration_apk=$3
[[ "${migration_apk##*/}" == termix-v1.4.1.apk && ! -e "$migration_apk" && ! -L "$migration_apk" ]] ||
  signing_error 'The output must be a new file named termix-v1.4.1.apk.'

verify_release_apk_signer "$regular_apk" 26
verify_release_apk_manifest "$regular_apk"
permissions=$("$build_tools/aapt" dump permissions "$regular_apk")
if grep -Fxq 'permission: com.termix.app.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION' <<< "$permissions"; then
  signing_error 'Android 9 cannot rotate the signing key while redeclaring the legacy AndroidX receiver permission.'
fi

[[ "$release_version_name" == 1.4.1 && "$release_version_code" -eq 16 ]] ||
  signing_error 'The migration release requires versionName 1.4.1 and versionCode 16.'

lineage_info=$("$apksigner" lineage --in "$lineage" --print-certs)
lineage_certs=$(awk '/^Signer #[0-9]+ in lineage certificate SHA-256 digest: / { print $NF }' <<< "$lineage_info")
legacy_capabilities=$(awk '/^Signer #2 in lineage / { exit } /^Has / { print $NF }' <<< "$lineage_info")
[[ "$lineage_certs" == "$legacy_cert_sha256"$'\n'"$release_cert_sha256" &&
   "$legacy_capabilities" == $'true\nfalse\nfalse\nfalse\nfalse' ]] ||
  signing_error 'Expected the test certificate to rotate to the pinned release certificate with only installed-data enabled for the old certificate.'

: "${KEYSTORE_FILE:?Set KEYSTORE_FILE to the release keystore}"
: "${TERMIX_RELEASE_KEY_ALIAS:?Set TERMIX_RELEASE_KEY_ALIAS to the release key alias}"
: "${TERMIX_RELEASE_STORE_PASSWORD:?Export TERMIX_RELEASE_STORE_PASSWORD}"
: "${TERMIX_RELEASE_KEY_PASSWORD:?Export TERMIX_RELEASE_KEY_PASSWORD}"

work_dir=$(mktemp -d "$(dirname -- "$migration_apk")/.termix-migration.XXXXXX")
trap 'rm -rf -- "$work_dir"' EXIT
signed_apk="$work_dir/migration.apk"
# Android 8 reads the old v2 signer. Android 9 and newer read the rotated v3 signer.
"$apksigner" sign --ks "$(dirname "${BASH_SOURCE[0]}")/../app/testkey.keystore" --ks-key-alias testkey \
  --ks-pass pass:testkey --key-pass pass:testkey \
  --next-signer --ks "$KEYSTORE_FILE" --ks-key-alias "$TERMIX_RELEASE_KEY_ALIAS" \
  --ks-pass env:TERMIX_RELEASE_STORE_PASSWORD --key-pass env:TERMIX_RELEASE_KEY_PASSWORD \
  --lineage "$lineage" --min-sdk-version 26 --rotation-min-sdk-version 28 \
  --v1-signing-enabled false --v2-signing-enabled true --v3-signing-enabled true --v4-signing-enabled false \
  --out "$signed_apk" "$regular_apk"

verify_apk_signer "$signed_apk" 26 27 "$legacy_cert_sha256"
verify_release_apk_signer "$signed_apk" 28
verify_release_apk_manifest "$signed_apk"
embedded_lineage=$("$apksigner" lineage --in "$signed_apk" --print-certs)
[[ "$embedded_lineage" == "$lineage_info" ]] || signing_error 'The migration APK has an unexpected signing lineage.'

ln -T -- "$signed_apk" "$migration_apk"
printf 'Prepared %s for Android 8 and newer.\n' "$migration_apk"
