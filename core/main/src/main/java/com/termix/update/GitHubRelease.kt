package com.termix.update

import org.json.JSONObject
import java.math.BigInteger

internal const val LATEST_RELEASE_URL = "https://api.github.com/repos/jeffusion/Termix/releases/latest"
private const val DOWNLOAD_ROOT = "https://github.com/jeffusion/Termix/releases/download"
private const val MAX_APK_SIZE = 1024L * 1024 * 1024
private val stableVersion = Regex("(0|[1-9][0-9]{0,31})\\.(0|[1-9][0-9]{0,31})\\.(0|[1-9][0-9]{0,31})")
private val sha256Digest = Regex("sha256:([a-fA-F0-9]{64})")

internal data class GitHubRelease(
    val version: String,
    val url: String,
    val size: Long,
    val sha256: String,
)

internal fun latestUpdate(json: String, installedVersion: String): GitHubRelease? {
    val release = JSONObject(json)
    require(release.get("draft") == false && release.get("prerelease") == false)
    val tag = release.get("tag_name") as? String ?: error("Release tag is missing")
    require(tag.startsWith("v"))
    val version = tag.removePrefix("v")
    val installed = versionParts(installedVersion)
    val candidate = versionParts(version)
    val difference = candidate.zip(installed).firstOrNull { (next, current) -> next != current }
    if (difference == null || difference.first < difference.second) return null

    val filename = "termix-$tag.apk"
    val assets = release.getJSONArray("assets")
    val asset = (0 until assets.length()).map(assets::getJSONObject)
        .single { it.opt("name") == filename }
    require(asset.get("state") == "uploaded")
    val url = "$DOWNLOAD_ROOT/$tag/$filename"
    require(asset.get("browser_download_url") == url)
    val size = when (val value = asset.get("size")) {
        is Int -> value.toLong()
        is Long -> value
        else -> error("APK size is not an integer")
    }
    require(size in 1..MAX_APK_SIZE)
    val digest = asset.opt("digest") as? String
    val sha256 = digest?.let(sha256Digest::matchEntire)?.groupValues?.get(1)
        ?: error("APK SHA-256 digest is missing or invalid")
    return GitHubRelease(version, url, size, sha256.lowercase())
}

private fun versionParts(version: String): List<BigInteger> =
    requireNotNull(stableVersion.matchEntire(version))
        .groupValues.drop(1).map(::BigInteger)
