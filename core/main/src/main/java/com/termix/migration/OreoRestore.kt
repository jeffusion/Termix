package com.termix.migration

import java.io.FileOutputStream
import java.io.InputStream
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.READ
import java.nio.file.attribute.FileTime
import java.time.Instant
import java.util.UUID

internal class OreoRestore(
    private val data: Path,
    private val external: Path,
    private val work: Path,
    private val archive: OreoArchive,
    private val flushStorage: () -> Unit = {},
) {
    private val staging = work.resolve("staging")
    private val externalToken = staging.resolve("external-token")
    private val ready = work.resolve("ready")
    private val committed = work.resolve("committed")
    private val done = work.resolve("done")
    private val externalCleaned = work.resolve("external-cleaned")

    fun canImport(): Boolean = isEmptyDirectory(external) && OreoArchive.INTERNAL_ROOTS.all {
        isEmptyDirectory(data.resolve(it), profileCache = it == "files")
    }

    fun recover(): Boolean {
        if (!Files.exists(ready, NOFOLLOW_LINKS)) {
            clearIncompleteStage()
            return false
        }
        val plan = Files.readAllLines(ready)
        val token = externalPlan(plan.first()).first
        val checkpoint = if (Files.exists(committed, NOFOLLOW_LINKS)) Files.readAllLines(committed).single() else null
        requireArchive(checkpoint == null || checkpoint == "pending $token", "Invalid restore checkpoint")
        if (checkpoint == null) {
            recoverExternal(token)
            for (line in plan.drop(1)) {
                val fields = line.split(' ', limit = 2)
                val name = fields.first()
                requireArchive(fields.size == 2 && name in OreoArchive.INTERNAL_ROOTS, "Invalid restore journal")
                val source = staging.resolve(name)
                val target = data.resolve(name)
                if (Files.exists(source, NOFOLLOW_LINKS)) {
                    requireArchive(directoryInode(source) == fields[1], "Restore staging directory changed")
                    moveToEmpty(source, target, profileCache = name == "files")
                }
                requireArchive(directoryInode(target) == fields[1], "Cannot confirm the restored directory")
            }
            flushStorage()
            writeRecord(committed, "pending $token")
        }
        requireArchive(hasMarker(external, token), "Cannot confirm the restored external directory")
        return true
    }

    fun finish() {
        requireArchive(recover(), "Finish or retry the pending restore")
        writeRecord(done, "ok\n")
    }

    fun cleanupAfterDone() {
        if (!Files.exists(done, NOFOLLOW_LINKS) || !Files.exists(ready, NOFOLLOW_LINKS) ||
            Files.exists(externalCleaned, NOFOLLOW_LINKS)
        ) return
        val (token, modified) = externalPlan(Files.readAllLines(ready).first())
        if (Files.readAllLines(committed).single() != "pending $token" || !hasMarker(external, token)) return
        Files.delete(marker(external, token))
        Files.setLastModifiedTime(external, modified)
        flushStorage()
        writeRecord(externalCleaned, "ok\n")
    }

    private fun externalPlan(line: String): Pair<String, FileTime> {
        val fields = line.split(' ')
        requireArchive(fields.size == 3 && fields.first() == OreoArchive.EXTERNAL, "Invalid external restore journal")
        return UUID.fromString(fields[1]).toString() to FileTime.from(Instant.parse(fields[2]))
    }

    fun import(source: InputStream, key: ByteArray) {
        requireArchive(!Files.exists(ready, NOFOLLOW_LINKS) && canImport(), "Restore requires a fresh Termix installation")
        val decrypted = OreoBackup.Decrypted(source, key)
        Files.createDirectories(work)
        clearIncompleteStage()
        Files.createDirectory(staging)
        writeRecord(externalToken, UUID.randomUUID().toString())
        val externalStaging = externalStaging()
        try {
            archive.extract(decrypted, staging, externalStaging)
            decrypted.requireComplete()
            requireArchive(canImport(), "Restore destination already has data")
            val token = UUID.randomUUID().toString()
            val externalTime = Files.getLastModifiedTime(externalStaging, NOFOLLOW_LINKS)
            Files.write(marker(externalStaging, token), token.toByteArray(), CREATE_NEW)
            val plan = OreoArchive.INTERNAL_ROOTS.mapNotNull { name ->
                val path = staging.resolve(name)
                if (Files.exists(path, NOFOLLOW_LINKS)) "$name ${directoryInode(path)}" else null
            }
            flushStorage()
            val externalPlan = "${OreoArchive.EXTERNAL} $token ${externalTime.toInstant()}"
            writeRecord(ready, (listOf(externalPlan) + plan).joinToString("\n", postfix = "\n"))
        } finally {
            if (!Files.exists(ready, NOFOLLOW_LINKS)) clearIncompleteStage()
        }
        recover()
    }

    private fun recoverExternal(token: String) {
        val source = externalStaging()
        if (Files.exists(source, NOFOLLOW_LINKS)) {
            requireArchive(hasMarker(source, token), "External restore staging directory changed")
            moveToEmpty(source, external)
        }
        requireArchive(hasMarker(external, token), "Cannot confirm the restored external directory")
    }

    private fun hasMarker(root: Path, token: String): Boolean {
        if (!Files.isDirectory(root, NOFOLLOW_LINKS)) return false
        val proof = marker(root, token)
        if (!Files.isRegularFile(proof, NOFOLLOW_LINKS)) return false
        val expected = token.toByteArray()
        return Files.newInputStream(proof, READ, NOFOLLOW_LINKS).use { input ->
            expected.all { input.read() == (it.toInt() and 0xff) } && input.read() == -1
        }
    }

    private fun marker(root: Path, token: String) = root.resolve(".termix-restore-$token")

    private fun moveToEmpty(source: Path, target: Path, profileCache: Boolean = false) {
        requireArchive(isEmptyDirectory(target, profileCache), "Restore destination already has data")
        if (profileCache) Files.deleteIfExists(target.resolve(OreoArchive.PROFILE_CACHE))
        // Deletion fails if the directory acquired a file since the emptiness check.
        Files.deleteIfExists(target)
        Files.move(source, target)
    }

    private fun clearIncompleteStage() {
        if (Files.exists(externalToken, NOFOLLOW_LINKS)) deleteStagingTree(externalStaging())
        deleteStagingTree(staging)
    }

    private fun externalStaging(): Path {
        val name = Files.readAllLines(externalToken).single()
        return external.resolveSibling(".termix-oreo-${UUID.fromString(name)}")
    }

    companion object {
        private fun directoryInode(path: Path): String {
            requireArchive(Files.isDirectory(path, NOFOLLOW_LINKS), "Restore directory is missing")
            return Files.getAttribute(path, "unix:ino", NOFOLLOW_LINKS).toString()
        }

        private fun isEmptyDirectory(path: Path, profileCache: Boolean = false): Boolean {
            if (Files.notExists(path, NOFOLLOW_LINKS)) return true
            if (!Files.isDirectory(path, NOFOLLOW_LINKS)) return false
            return Files.newDirectoryStream(path).use { files ->
                files.all {
                    profileCache && it.fileName.toString() == OreoArchive.PROFILE_CACHE &&
                        Files.isRegularFile(it, NOFOLLOW_LINKS)
                }
            }
        }

        private fun deleteStagingTree(path: Path) {
            if (Files.isDirectory(path, NOFOLLOW_LINKS)) {
                if (!Files.isReadable(path) || !Files.isWritable(path) || !Files.isExecutable(path)) {
                    Files.setAttribute(path, "unix:mode", 0x1C0)
                }
                Files.newDirectoryStream(path).use { children -> children.forEach(::deleteStagingTree) }
            }
            Files.deleteIfExists(path)
        }

        fun writeRecord(path: Path, value: String) {
            Files.createDirectories(path.parent)
            val pending = path.resolveSibling("${path.fileName}.pending")
            FileOutputStream(pending.toFile()).use {
                it.write(value.toByteArray(Charsets.UTF_8))
                it.fd.sync()
            }
            Files.move(pending, path, ATOMIC_MOVE)
            FileChannel.open(path.parent, READ).use { it.force(true) }
        }
    }
}
