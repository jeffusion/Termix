#!/bin/bash

legacy_cert_sha256=a40da80a59d170caa950cf15c18c454d47a39b26989d8b640ecd745ba71bf5dc
legacy_key_sha256=ef57b690165cb561b5026922c00d2d6574e8b184fa7d161e076f06e06e6d35db

signing_error() {
  printf 'Android signing: %s\n' "$1" >&2
  exit 1
}

load_release_signing() {
  release_cert_sha256=${TERMIX_RELEASE_CERT_SHA256:-}
  release_cert_sha256=${release_cert_sha256,,}
  [[ "$release_cert_sha256" =~ ^[0-9a-f]{64}$ && "$release_cert_sha256" != "$legacy_cert_sha256" ]] ||
    signing_error 'TERMIX_RELEASE_CERT_SHA256 must identify a non-test release certificate (64 hexadecimal characters).'

  build_tools="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}/build-tools/36.0.0"
  apksigner="$build_tools/apksigner"
  [[ -x "$apksigner" && -x "$build_tools/aapt" ]] || signing_error 'Set ANDROID_HOME to an SDK with Build Tools 36.0.0.'
}

verify_release_apk_signer() {
  verify_apk_signer "$1" "$2" 36 "$release_cert_sha256"
}

verify_apk_signer() {
  local apk=$1 min_sdk=$2 max_sdk=$3 expected_cert=$4 sdk verification cert key
  for ((sdk = min_sdk; sdk <= max_sdk; sdk++)); do
    verification=$("$apksigner" verify --min-sdk-version "$sdk" --max-sdk-version "$sdk" --verbose --print-certs "$apk") ||
      signing_error "$apk failed verification at API $sdk."
    cert=$(awk '/^Signer #[0-9]+ certificate SHA-256 digest: / { print $NF }' <<< "$verification")
    key=$(awk '/^Signer #[0-9]+ public key SHA-256 digest: / { print $NF }' <<< "$verification")
    [[ "$cert" == "$expected_cert" && "$key" =~ ^[0-9a-f]{64}$ ]] ||
      signing_error "$apk must use only the expected signer ($expected_cert) at API $sdk."
    if [[ "$expected_cert" != "$legacy_cert_sha256" && "$key" == "$legacy_key_sha256" ]]; then
      signing_error "$apk uses the legacy test public key at API $sdk."
    fi
  done
}

verify_release_apk_manifest() {
  local apk=$1 manifest fields version_part='(0|[1-9][0-9]{0,31})'
  local -a manifest_fields
  manifest=$("$build_tools/aapt" dump xmltree "$apk" AndroidManifest.xml) ||
    signing_error "Could not read AndroidManifest.xml from $apk."

  # aapt escapes newlines in strings and indents both elements and namespace declarations.
  fields=$(awk '
    BEGIN {
      updates = "com.termix.GITHUB_APP_UPDATES"
    }
    function xml_string(value, parts) {
      if (value !~ /^"[^"]*"( \(Raw: .*\))?$/) return ""
      split(value, parts, "\"")
      return parts[2]
    }
    function finish_metadata() {
      # A resource-backed name could resolve to the updates key at install time.
      if (app_metadata && metadata_name == "") invalid_metadata_name = 1
      if (metadata_name == updates) {
        update_count++
        update_value = metadata_resource ? "" : metadata_value
      }
      metadata_name = metadata_value = metadata_resource = ""
    }
    /^[ ]*N: / {
      if (!namespace_pending) namespace_indent = match($0, /[^ ]/) - 1
      namespace_pending = 1
    }
    /^[ ]*E: / {
      finish_metadata()
      indent = namespace_pending ? namespace_indent : match($0, /[^ ]/) - 1
      namespace_pending = 0
      while (depth && indent <= indentation[depth]) depth--
      indentation[++depth] = indent
      element[depth] = $2
      if (depth == 2 && element[1] == "manifest" && $2 == "application") applications++
      app_metadata = depth == 3 && element[1] == "manifest" && element[2] == "application" && $2 == "meta-data"
    }
    /^[ ]*A: / {
      attribute = $0
      sub(/^[ ]*A: /, "", attribute)
      delimiter = index(attribute, "=")
      name = substr(attribute, 1, delimiter - 1)
      value = substr(attribute, delimiter + 1)
      if (depth == 1 && element[1] == "manifest" && name == "package") package_name = xml_string(value)
      # The framework attribute IDs are independent of namespace prefixes.
      sub(/^[^:]*:/, "", name)
      if (depth == 1 && element[1] == "manifest") {
        if (name == "versionName(0x0101021c)") version_name = xml_string(value)
        if (name == "versionCode(0x0101021b)" && value ~ /^\(type 0x1[01]\)0x[0-9a-f]+$/) {
          sub(/^\(type 0x1[01]\)/, "", value)
          version_code = value
        }
      }
      if (app_metadata) {
        if (name == "name(0x01010003)") metadata_name = xml_string(value)
        if (name == "value(0x01010024)") metadata_value = value
        if (name == "resource(0x01010025)") metadata_resource = 1
      }
    }
    END {
      finish_metadata()
      if (applications != 1 || invalid_metadata_name || update_count != 1 ||
          update_value !~ /^\(type 0x12\)0x[1-9a-f][0-9a-f]*$/) exit 1
      printf "%s\n%s\n%s\n", package_name, version_name, version_code
    }
  ' <<< "$manifest") ||
    signing_error "$apk must declare boolean application metadata com.termix.GITHUB_APP_UPDATES=true exactly once with a literal name."

  mapfile -t manifest_fields <<< "$fields"
  release_version_name=${manifest_fields[1]:-}
  release_version_code=${manifest_fields[2]:-}
  [[ "${manifest_fields[0]:-}" == com.termix.app && "$release_version_name" =~ ^$version_part\.$version_part\.$version_part$ &&
     "$release_version_code" =~ ^0x[0-9a-f]{1,8}$ && "$release_version_code" -gt 0 && "$release_version_code" -le 2147483647 ]] ||
    signing_error "$apk must declare package com.termix.app, a stable versionName and a positive versionCode."
}
