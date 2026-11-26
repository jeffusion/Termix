#!/bin/bash
set -euo pipefail

fail() {
  printf '::error::%s\n' "$1" >&2
  exit 1
}

: "${GH_TOKEN:?Release App token is required}"
: "${GITHUB_REPOSITORY:?GitHub repository is required}"
ci_sha=${1:-}
[[ "$ci_sha" =~ ^[a-f0-9]{40}$ ]] || fail 'Invalid verified CI commit SHA.'

release_sha=$(git rev-parse HEAD)
[[ $(git show -s --format=%P "$release_sha") == "$ci_sha" ]] ||
  fail 'The local release commit must have the verified CI commit as its only parent.'
[[ $(git diff --name-only "$ci_sha" "$release_sha") == $'CHANGELOG.md\napp/build.gradle.kts' ]] ||
  fail 'The release commit must change exactly CHANGELOG.md and app/build.gradle.kts.'

number='(0|[1-9][0-9]*)'
release_subject="^chore\(release\): ($number\.$number\.$number) \[skip ci\]$"
[[ $(git show -s --format=%s "$release_sha") =~ $release_subject ]] ||
  fail 'The local commit has no stable semantic-release version.'
version=${BASH_REMATCH[1]}
tag="v$version"
apk="app/termix-$tag.apk"
audio_sources="third_party/termux-audio/termix-audio-sources.tar.xz"
release_assets=("$apk" "$audio_sources")

version_name=$(git show "$release_sha:app/build.gradle.kts" |
  sed -nE 's/^[[:space:]]*versionName[[:space:]]*=[[:space:]]*"([^"]*)"[[:space:]]*$/\1/p')
[[ "$version_name" == "$version" ]] || fail 'The committed Android version does not match the release version.'
[[ -f "$apk" && ! -L "$apk" && -s "$apk" ]] || fail "No completed APK found at $apk."
[[ -f "$audio_sources" && ! -L "$audio_sources" && -s "$audio_sources" ]] ||
  fail "No audio runtime source archive found at $audio_sources."
notes=$(git show -s --format=%b "$release_sha")
[[ "$notes" =~ [^[:space:]] ]] || fail "The release commit for $tag has no release notes."

tag_ref="refs/tags/$tag"
[[ $(git rev-parse --verify "$tag_ref^{commit}") == "$release_sha" ]] ||
  fail "The local $tag tag does not point to the release commit."
local_tag=$(git rev-parse --verify "$tag_ref")
repo="repos/$GITHUB_REPOSITORY"

verify_remote_tag() {
  local remote_tag
  remote_tag=$(gh api "$repo/git/ref/tags/$tag" --jq '.object.sha')
  [[ "$remote_tag" == "$local_tag" ]] || fail "The remote $tag tag does not match the local tag."
}

verify_remote_tag
remote_main=$(gh api "$repo/git/ref/heads/main" --jq '.object.sha')
[[ "$remote_main" =~ ^[a-f0-9]{40}$ ]] || fail 'Invalid remote main commit SHA.'
if [[ "$remote_main" != "$release_sha" ]]; then
  status=$(gh api "$repo/compare/$release_sha...$remote_main?per_page=1" --jq .status)
  [[ "$status" == ahead ]] || fail 'The release commit is no longer in remote main history.'
fi

matching_releases() {
  # The list includes drafts that GitHub's release-by-tag endpoint can miss.
  gh api --paginate --slurp "$repo/releases?per_page=100" |
    jq -ce --arg tag "$tag" '
      if type == "array" and length > 0 and all(.[]; type == "array")
      then [.[][] | select(.tag_name == $tag)]
      else error("Unexpected GitHub release list")
      end'
}

verify_asset() {
  local asset=$1 size sum
  size=$(stat -c '%s' -- "$asset")
  sum=$(sha256sum -- "$asset")
  jq -e --arg name "${asset##*/}" --argjson size "$size" --arg digest "sha256:${sum%% *}" '
    [.assets[] | select(.name == $name)] |
    length == 1 and (.[0] | .state == "uploaded" and .size == $size and .digest == $digest)
  ' > /dev/null || fail "GitHub Release $tag does not have the matching ${asset##*/}."
}

releases=$(matching_releases)
if [[ "$releases" == '[]' ]]; then
  draft=$(gh api --method POST "$repo/releases" -f tag_name="$tag" -f name="$tag" \
    -f target_commitish="$release_sha" -f body="$notes" -F draft=true)
  draft_id=$(jq -er --arg tag "$tag" '
    select(.draft == true and .prerelease == false and .tag_name == $tag) |
    .id | select(type == "number" and . > 0)
  ' <<< "$draft") || fail "Could not verify the identity of the new $tag draft."
  printf 'Created GitHub Release draft %s with ID %s.\n' "$tag" "$draft_id"

  for asset in "${release_assets[@]}"; do
    content_type=application/x-xz
    if [[ "$asset" == "$apk" ]]; then
      content_type=application/vnd.android.package-archive
    fi
    curl --fail --silent --show-error --max-time 180 --header @- \
      --header "Content-Type: $content_type" --data-binary "@$asset" \
      "https://uploads.github.com/$repo/releases/$draft_id/assets?name=${asset##*/}" \
      > /dev/null <<< "Authorization: Bearer $GH_TOKEN" ||
      fail "${asset##*/} upload failed. GitHub Release draft $draft_id remains unpublished."
  done

  draft=$(gh api "$repo/releases/$draft_id")
  jq -e --arg tag "$tag" --argjson id "$draft_id" '
    .id == $id and .tag_name == $tag and .draft == true and .prerelease == false
  ' <<< "$draft" > /dev/null || fail "GitHub Release $draft_id is no longer the expected $tag draft."
  for asset in "${release_assets[@]}"; do
    verify_asset "$asset" <<< "$draft"
  done
  verify_remote_tag
  gh api --method PATCH "$repo/releases/$draft_id" -F draft=false -f make_latest=legacy > /dev/null
  published=$(gh api "$repo/releases/$draft_id")
else
  published=$(jq -ce 'select(length == 1) | .[0]' <<< "$releases") ||
    fail "More than one GitHub Release exists for $tag."
fi

jq -e --arg tag "$tag" '
  .tag_name == $tag and .draft == false and .prerelease == false and (.published_at | type == "string" and length > 0)
' <<< "$published" > /dev/null || fail "GitHub Release $tag is not a published stable release."
for asset in "${release_assets[@]}"; do
  verify_asset "$asset" <<< "$published"
done

printf 'Verified GitHub Release %s with the matching APK and audio source archive.\n' "$tag"
