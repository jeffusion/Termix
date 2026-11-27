#!/bin/bash
set -euo pipefail

fail() {
  printf '::error::%s\n' "$1" >&2
  exit 1
}

: "${GH_TOKEN:?Release App token is required}"
: "${GITHUB_REPOSITORY:?GitHub repository is required}"
: "${RELEASE_SHA:?Verified CI commit SHA is required}"
[[ "$RELEASE_SHA" =~ ^[a-f0-9]{40}$ && "$(git rev-parse HEAD)" == "$RELEASE_SHA" ]] ||
  fail 'Checkout does not match the verified CI commit.'

tag=v1.4.1
tag_ref="refs/tags/$tag"
repo="repos/$GITHUB_REPOSITORY"
name='Termix 1.4.1 signing migration'
apk_name="termix-$tag.apk"
audio=third_party/termux-audio/termix-audio-sources.tar.xz

release_notes() {
  local sha=$1
  cat <<EOF
Termix 1.4.1 is the signing migration release for installations signed with the former test key. The next regular stable release is still pending.

**Android 9 and newer**
Close your terminal sessions, install \`$apk_name\` over your existing Termix installation, and check your Linux environments and settings. If you installed the 1.4.0 signing migration preview, you can update to 1.4.1 from **Settings → About Termix**. After migrating, use the same menu to check for subsequent stable releases.

**Android 8.0 and 8.1**
Wait for a later regular stable release before uninstalling. Once its APK is available, install \`$apk_name\` over your existing app, export your data, and verify that you have both the backup and recovery key. Then uninstall Termix, install the later regular APK, and restore your data. Follow the [Android 8 migration guide](https://github.com/$GITHUB_REPOSITORY/blob/$sha/docs/android-8-data-migration.md).
EOF
}

check_main() {
  local sha=$1 main status
  main=$(gh api "$repo/git/ref/heads/main" --jq '.object.sha')
  [[ "$main" =~ ^[a-f0-9]{40}$ ]] || fail 'Invalid remote main commit SHA.'
  if [[ "$main" != "$sha" ]]; then
    status=$(gh api "$repo/compare/$sha...$main?per_page=1" --jq .status)
    [[ "$status" == ahead ]] || fail "Migration commit $sha is outside remote main history."
  fi
}

tag_target() {
  gh api "$repo/git/matching-refs/tags/$tag" |
    jq -r --arg ref "$tag_ref" --arg sha "$1" '
      if type != "array" then error("Unexpected GitHub ref list") else
        map(select(.ref == $ref)) |
        if length == 0 then "missing"
        elif length == 1 and (.[0].object | .type == "commit" and .sha == $sha)
        then "matches"
        else error("Migration tag does not match the expected release commit") end
      end'
}

# Published notes may be updated when the regular APK becomes available.
check_release_metadata() {
  local expected_draft=$1 sha=$2 notes
  notes=$(release_notes "$sha")
  jq -e --arg tag "$tag" --arg sha "$sha" --arg name "$name" --arg notes "$notes" \
    --arg apk "$apk_name" --arg audio "${audio##*/}" --argjson draft "$expected_draft" '
    def positive_integer: type == "number" and . > 0 and floor == .;
    .tag_name == $tag and .target_commitish == $sha and
    (if $draft then .name == $name and .body == $notes else true end) and
    .prerelease == false and .draft == $draft and (.id | positive_integer) and
    (if $draft then .published_at == null else (.published_at | type == "string" and length > 0) end) and
    (.assets | type == "array" and length == (map(.name) | unique | length) and
      length == (map(.id) | unique | length) and
      all(.[]; (.id | positive_integer) and (.name == $apk or .name == $audio)))
  ' > /dev/null || fail "Conflicting release metadata for $tag."
}

check_local_assets() {
  jq -e --argjson complete "$1" --argjson assets "$expected_assets" '
    all(.assets[];
      if .state == "starter" then ($complete | not) and .size == 0
      else {name, state, size, digest} as $asset | $assets | index($asset) != null end) and
    (if $complete then (.assets | length) == ($assets | length) else true end)
  ' > /dev/null || fail "Conflicting metadata or assets for $tag."
}

# Release-by-tag can omit drafts. Check all pages before deciding whether to create one.
release=$(gh api --paginate --slurp "$repo/releases?per_page=100" |
  jq -c --arg tag "$tag" '
    if type != "array" or length == 0 or any(.[]; type != "array") then error("Unexpected GitHub release list")
    else [.[][] | select(.tag_name == $tag)] |
      if length > 1 then error("Multiple releases match the migration tag") else .[0] end
    end')
if [[ $(jq -r '.draft' <<< "$release") == false ]]; then
  published_sha=$(jq -r '.target_commitish' <<< "$release")
  [[ "$published_sha" =~ ^[a-f0-9]{40}$ ]] || fail 'Invalid published migration commit SHA.'
  check_release_metadata false "$published_sha" <<< "$release"
  jq -e '(.assets | length) == 2 and all(.assets[]; .state == "uploaded" and
    (.size | type == "number" and . > 0 and floor == .) and
    (.digest | type == "string" and test("^sha256:[0-9a-f]{64}$")))' <<< "$release" >/dev/null ||
    fail "Invalid assets for published migration release $tag."
  tag_status=$(tag_target "$published_sha")
  [[ "$tag_status" == matches ]] || fail 'Published migration release is missing its tag.'
  check_main "$published_sha"
  if [[ ${1:-} == --check-published ]]; then
    printf 'published=true\n' >> "${GITHUB_OUTPUT:?GitHub Actions step output file is required}"
  fi
  printf 'Verified existing migration release %s.\n' "$tag"
  exit 0
fi

if [[ "$release" != null ]]; then
  draft_sha=$(jq -r '.target_commitish' <<< "$release")
  [[ "$draft_sha" =~ ^[a-f0-9]{40}$ ]] || fail 'Invalid migration draft commit SHA.'
  check_release_metadata true "$draft_sha" <<< "$release"
  [[ "$draft_sha" == "$RELEASE_SHA" ]] ||
    fail "Migration draft targets $draft_sha. Re-run the release workflow triggered by successful main CI for that commit."
fi
tag_status=$(tag_target "$RELEASE_SHA")
if [[ ${1:-} == --check-published ]]; then
  exit 0
fi

apk=${1:-}
[[ ${apk##*/} == "$apk_name" ]] || fail 'Expected the verified Termix migration APK.'
assets=("$apk" "$audio")
for asset in "${assets[@]}"; do
  [[ -f "$asset" && ! -L "$asset" && -s "$asset" ]] || fail "Missing release asset ${asset##*/}."
done
(cd third_party/termux-audio && sha256sum --strict -c termix-audio-sources.tar.xz.sha256)
expected_assets=$(for asset in "${assets[@]}"; do
  size=$(stat -c '%s' -- "$asset")
  digest=$(sha256sum < "$asset")
  jq -cn --arg name "${asset##*/}" --argjson size "$size" --arg digest "sha256:${digest%% *}" \
    '{name: $name, state: "uploaded", size: $size, digest: $digest}'
done | jq -ces 'select(length == 2)')

check_main "$RELEASE_SHA"
if [[ "$release" == null ]]; then
  notes=$(release_notes "$RELEASE_SHA")
  release=$(gh api --method POST "$repo/releases" -f tag_name="$tag" -f name="$name" \
    -f target_commitish="$RELEASE_SHA" -f body="$notes" -F draft=true -F prerelease=false -f make_latest=false)
fi
check_release_metadata true "$RELEASE_SHA" <<< "$release"
check_local_assets false <<< "$release"
release_id=$(jq -r '.id' <<< "$release")

for asset in "${assets[@]}"; do
  filename=${asset##*/}
  remote_asset=$(jq -c --arg filename "$filename" '[.assets[] | select(.name == $filename)][0]' <<< "$release")
  asset_state=$(jq -r '.state' <<< "$remote_asset")
  if [[ "$asset_state" == uploaded ]]; then
    continue
  fi
  if [[ "$asset_state" == starter ]]; then
    # GitHub can leave an empty starter asset after a failed upload.
    draft=$(gh api "$repo/releases/$release_id")
    check_release_metadata true "$RELEASE_SHA" <<< "$draft"
    check_local_assets false <<< "$draft"
    [[ $(jq -r '.id' <<< "$draft") == "$release_id" ]] || fail 'Migration draft identity changed.'
    starter_id=$(jq -er '.id' <<< "$remote_asset")
    jq -e --arg filename "$filename" --argjson id "$starter_id" \
      'any(.assets[]; .id == $id and .name == $filename and .state == "starter" and .size == 0)' \
      <<< "$draft" >/dev/null || fail "Migration starter asset $filename changed."
    gh api --method DELETE "$repo/releases/assets/$starter_id" >/dev/null ||
      fail "Could not delete the empty starter asset $filename from migration draft $release_id."
  fi
  content_type=application/x-xz
  if [[ "$asset" == "$apk" ]]; then
    content_type=application/vnd.android.package-archive
  fi
  curl --fail --silent --show-error --max-time 180 --header @- \
    --header "Content-Type: $content_type" --data-binary "@$asset" \
    "https://uploads.github.com/$repo/releases/$release_id/assets?name=$filename" \
    > /dev/null <<< "Authorization: Bearer $GH_TOKEN" ||
    fail "Upload of $filename failed. Migration draft $release_id remains unpublished."
done

release=$(gh api "$repo/releases/$release_id")
check_release_metadata true "$RELEASE_SHA" <<< "$release"
check_local_assets true <<< "$release"
[[ $(jq -r '.id' <<< "$release") == "$release_id" ]] || fail 'Migration draft identity changed.'
tag_status=$(tag_target "$RELEASE_SHA")
if [[ "$tag_status" == missing ]]; then
  check_main "$RELEASE_SHA"
  gh api --method POST "$repo/git/refs" -f ref="$tag_ref" -f sha="$RELEASE_SHA" > /dev/null
fi
tag_status=$(tag_target "$RELEASE_SHA")
[[ "$tag_status" == matches ]] || fail 'Could not verify the migration tag.'
check_main "$RELEASE_SHA"
release=$(gh api --method PATCH "$repo/releases/$release_id" -F draft=false -f make_latest=true)
check_release_metadata false "$RELEASE_SHA" <<< "$release"
check_local_assets true <<< "$release"
printf 'Published migration release %s at %s.\n' "$tag" "$RELEASE_SHA"
