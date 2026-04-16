#!/bin/bash
set -e

VERSION="$1"
FILE="app/build.gradle.kts"

if [ -z "$VERSION" ]; then
  echo "Usage: $0 <version>"
  exit 1
fi

# 读取当前 versionCode
CURRENT_CODE=$(grep -oP 'versionCode\s*=\s*\K\d+' "$FILE")
NEW_CODE=$((CURRENT_CODE + 1))

# 替换 versionCode
sed -i "s/versionCode = $CURRENT_CODE/versionCode = $NEW_CODE/" "$FILE"

# 替换 versionName
sed -i "s/versionName = \"[^\"]*\"/versionName = \"$VERSION\"/" "$FILE"

echo "Bumped versionCode to $NEW_CODE, versionName to $VERSION"
