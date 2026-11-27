#!/bin/bash
set -euo pipefail

[[ "$#" -eq 1 ]] || { printf 'Usage: %s <termix-migration-v1.4.0.apk>\n' "$0" >&2; exit 1; }
: "${KEYSTORE_FILE:?Set KEYSTORE_FILE to the release keystore}"
: "${SIGNING_PROPERTIES_FILE:?Set SIGNING_PROPERTIES_FILE to the release signing properties}"

source "$(dirname "${BASH_SOURCE[0]}")/release-signing.sh"
load_release_signing

unset GH_TOKEN GITHUB_TOKEN
TERMIX_OFFICIAL_RELEASE=true ./gradlew --no-daemon :app:assembleRelease
bash scripts/prepare-release-apk.sh 1.4.0

work_dir=$(mktemp -d)
trap 'rm -rf -- "$work_dir"' EXIT

# Use the same Properties loader as Gradle and pass passwords only to the signing process.
cat > "$work_dir/MigrationSigning.java" <<'JAVA'
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;
import java.util.Properties;

class MigrationSigning {
    public static void main(String[] args) throws Exception {
        var properties = new Properties();
        try (var input = Files.newInputStream(Path.of(args[0]))) {
            properties.load(input);
        }

        var command = new ProcessBuilder(Arrays.copyOfRange(args, 1, args.length));
        var names = Map.of(
            "keyAlias", "TERMIX_RELEASE_KEY_ALIAS",
            "storePassword", "TERMIX_RELEASE_STORE_PASSWORD",
            "keyPassword", "TERMIX_RELEASE_KEY_PASSWORD"
        );
        for (var name : names.entrySet()) {
            var value = properties.getProperty(name.getKey());
            if (value == null || value.isBlank() || value.indexOf('\0') >= 0) {
                throw new IllegalArgumentException("Invalid signing property " + name.getKey());
            }
            command.environment().put(name.getValue(), value);
        }
        System.exit(command.inheritIO().start().waitFor());
    }
}
JAVA

java "$work_dir/MigrationSigning.java" "$SIGNING_PROPERTIES_FILE" bash -euo pipefail -c '
  apksigner=$1
  lineage=$2
  migration_apk=$3
  "$apksigner" rotate --out "$lineage" \
    --old-signer --ks app/testkey.keystore --ks-key-alias testkey \
    --ks-pass pass:testkey --key-pass pass:testkey \
    --set-installed-data true --set-shared-uid false --set-permission false \
    --set-rollback false --set-auth false \
    --new-signer --ks "$KEYSTORE_FILE" --ks-key-alias "$TERMIX_RELEASE_KEY_ALIAS" \
    --ks-pass env:TERMIX_RELEASE_STORE_PASSWORD --key-pass env:TERMIX_RELEASE_KEY_PASSWORD
  bash scripts/prepare-migration-apk.sh app/termix-v1.4.0.apk "$lineage" "$migration_apk"
' migration-signing "$apksigner" "$work_dir/lineage" "$1"
