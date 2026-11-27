package com.termix.migration

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.file.AccessDeniedException
import java.nio.file.FileSystemException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.attribute.FileTime
import java.time.Instant
import java.util.zip.GZIPOutputStream
import kotlin.random.Random

class OreoArchiveTest {
    @get:Rule val folder = TemporaryFolder()

    @Test
    fun roundTripPreservesBothGuestSystemsSettingsExternalFilesAndLinks() {
        val data = folder.newFolder("data").toPath()
        val external = folder.newFolder("external").toPath()
        val userFile = data.resolve("local/alpine/root/.profile")
        write(userFile, "alpine")
        write(data.resolve("local/alpine/tmp/session"), "transient")
        write(data.resolve("local/arch/etc/passwd"), "arch")
        write(data.resolve("local/.termix-arch-installed"), "ready")
        write(data.resolve("local/colors.properties"), "background=#102030")
        write(data.resolve("files/alpine.tar.gz"), "complete archive")
        write(data.resolve("files/alpine.tar.gz.part"), "partial archive")
        write(data.resolve("files/proot"), "proot")
        write(data.resolve("files/libtalloc.so.2"), "libtalloc")
        write(data.resolve("files/font.ttf"), "font")
        write(data.resolve("files/background"), "background")
        write(data.resolve("files/${OreoArchive.PROFILE_CACHE}"), "old runtime cache")
        write(data.resolve("local/bin/user-script"), "script")
        write(data.resolve("local/lib/user-library"), "library")
        write(data.resolve("shared_prefs/Settings.xml"), "settings")
        write(data.resolve("shared_prefs/Settings.xml.bak"), "fallback settings")
        write(external.resolve("test/document.txt"), "document")
        val symlink = data.resolve("local/alpine/root/dangling")
        val longTarget = "/" + (1..30).joinToString("/") { "a-long-component-$it" }
        Files.createSymbolicLink(symlink, Paths.get(longTarget))
        val hardLink = data.resolve("local/alpine/root/profile-copy")
        val hardLinksAvailable = try {
            Files.createLink(hardLink, userFile)
            true
        } catch (_: FileSystemException) {
            false
        }
        val timestamp = FileTime.fromMillis(1691234567123)
        Files.setLastModifiedTime(userFile, timestamp)
        Files.setLastModifiedTime(userFile.parent, timestamp)
        Files.setAttribute(userFile, "unix:mode", 0x1E8)

        val backup = ByteArrayOutputStream().apply {
            archive.write(data, external, OreoBackup.encrypt(this, key))
        }.toByteArray()
        val restored = folder.newFolder("restored").toPath()
        write(restored.resolve("files/${OreoArchive.PROFILE_CACHE}"), "startup runtime cache")
        val targetExternal = folder.newFolder("restored-external").toPath()
        val state = restored.resolve("no_backup/restore")
        OreoRestore(restored, targetExternal, state, archive).import(backup.inputStream(), key)

        val paths = listOf(
            "local/alpine/root/.profile", "local/arch/etc/passwd", "local/.termix-arch-installed", "local/colors.properties",
            "files/alpine.tar.gz", "files/proot", "files/libtalloc.so.2", "files/font.ttf",
            "files/background", "local/bin/user-script", "local/lib/user-library",
            "shared_prefs/Settings.xml", "shared_prefs/Settings.xml.bak",
        )
        for (path in paths) assertArrayEquals(Files.readAllBytes(data.resolve(path)), Files.readAllBytes(restored.resolve(path)))
        assertEquals("document", String(Files.readAllBytes(targetExternal.resolve("test/document.txt"))))
        assertFalse(Files.exists(restored.resolve("files/alpine.tar.gz.part")))
        assertFalse(Files.exists(restored.resolve("files/${OreoArchive.PROFILE_CACHE}")))
        assertFalse(Files.exists(restored.resolve("local/alpine/tmp/session")))
        val result = restored.resolve("local/alpine/root")
        if (hardLinksAvailable) assertTrue(Files.isSameFile(result.resolve(".profile"), result.resolve("profile-copy")))
        assertEquals(longTarget, Files.readSymbolicLink(result.resolve("dangling")).toString())
        assertEquals(timestamp, Files.getLastModifiedTime(result.resolve(".profile")))
        assertEquals(timestamp, Files.getLastModifiedTime(result))
        assertEquals(Files.getAttribute(userFile, "unix:mode"), Files.getAttribute(result.resolve(".profile"), "unix:mode"))
        assertTrue(OreoRestore(restored, targetExternal, state, archive).recover())
    }

    @Test
    fun sandboxDeniedHardLinkRestoresASeparateCopyWithTheSameContentsAndMetadata() {
        val data = folder.newFolder("no-hard-links").toPath()
        val external = folder.newFolder("no-hard-links-external").toPath()
        val payload = fixture {
            entry(1, "files")
            entry(2, "files/original", contents = "backup contents".toByteArray())
            entry(4, "files/alias", "files/original")
            entry(1, "external")
        }
        val limitedArchive = OreoArchive(
            readLink = { error("No symlink in fixture") },
            createLink = { _, _ -> error("No symlink in fixture") },
            createHardLink = { path, _ -> throw AccessDeniedException(path.toString()) },
        )
        OreoRestore(data, external, data.resolve("state"), limitedArchive).import(encrypted(payload).inputStream(), key)

        val original = data.resolve("files/original")
        val copy = data.resolve("files/alias")
        assertFalse(Files.isSameFile(original, copy))
        assertArrayEquals(Files.readAllBytes(original), Files.readAllBytes(copy))
        assertEquals(Files.getLastModifiedTime(original), Files.getLastModifiedTime(copy))
        assertEquals(Files.getAttribute(original, "unix:mode"), Files.getAttribute(copy, "unix:mode"))
    }

    @Test
    fun rejectsTraversalSymlinkWritesUnsafeHardLinksAndTruncatedArchives() {
        val base = folder.newFolder("traversal").toPath()
        val sentinel = base.resolve("sentinel")
        write(sentinel, "existing")
        val cases = listOf(
            fixture { entry(2, "../sentinel") },
            fixture { entry(1, "files"); entry(2, "files/../../sentinel") },
            fixture { entry(1, "files"); entry(3, "files/link", base.toString()); entry(2, "files/link/sentinel") },
            fixture { entry(1, "files"); entry(3, "files/link", base.toString()); entry(4, "files/alias", "files/link/sentinel") },
            fixture { entry(1, "files"); entry(3, "files/link", sentinel.toString()); entry(4, "files/alias", "files/link") },
            fixture { entry(1, "shared_prefs"); entry(3, "shared_prefs/Settings.xml", sentinel.toString()) },
            fixture { entry(1, "files"); entry(2, "files/font.ttf"); entry(1, "external") }.let { it.copyOf(it.size - 6) },
        )
        for ((index, backup) in cases.withIndex()) {
            val target = Files.createDirectory(base.resolve("target-$index"))
            val external = Files.createDirectory(base.resolve("external-$index"))
            fails { OreoRestore(target, external, target.resolve("state"), archive).import(encrypted(backup).inputStream(), key) }
            assertFalse(Files.exists(target.resolve("files")))
            assertFalse(Files.exists(target.resolve("shared_prefs")))
            assertFalse(Files.exists(target.resolve("state/ready")))
            assertEquals("existing", String(Files.readAllBytes(sentinel)))
        }
    }

    @Test
    fun encryptedFramesRejectWrongKeysTamperingReorderingTruncationAndTrailingData() {
        val plaintext = Random(42).nextBytes(180_000)
        val backup = encrypted(plaintext)
        assertArrayEquals(key, OreoBackup.decodeKey(OreoBackup.encodeKey(key)))
        assertArrayEquals(plaintext, OreoBackup.Decrypted(backup.inputStream(), key).readBytes())
        fails { OreoBackup.Decrypted(plaintext.inputStream(), key).readBytes() }
        fails { OreoBackup.Decrypted(backup.inputStream(), OreoBackup.newKey()).readBytes() }

        val first = 13
        val frameSize = 8 + ByteBuffer.wrap(backup, first + 4, 4).int + 16
        val second = first + frameSize
        val third = second + frameSize
        val rewrites = listOf(
            backup.copyOf().also { it[first + 10] = (it[first + 10].toInt() xor 1).toByte() },
            backup.copyOfRange(0, first) + backup.copyOfRange(second, third) +
                backup.copyOfRange(first, second) + backup.copyOfRange(third, backup.size),
            backup.copyOf(backup.size - 1),
            backup + 0.toByte(),
        )
        for (rewrite in rewrites) fails { OreoBackup.Decrypted(rewrite.inputStream(), key).readBytes() }
    }

    @Test
    fun savedRecoveryKeyMustMatchInFull() {
        val encoded = OreoBackup.encodeKey(key)
        assertTrue(OreoBackup.matchesKey(key, encoded))
        assertFalse(OreoBackup.matchesKey(key, encoded.dropLast(1)))
        assertFalse(OreoBackup.matchesKey(key, OreoBackup.encodeKey(OreoBackup.newKey())))
        assertFalse(OreoBackup.matchesKey(key, encoded.replaceRange(0, 1, "!")))
    }

    @Test
    fun missingAuthenticatedEndCannotCommitValidArchiveContents() {
        val data = folder.newFolder("truncated-data").toPath()
        val external = folder.newFolder("truncated-external").toPath()
        val state = data.resolve("state")
        val payload = fixture { entry(1, "files"); entry(2, "files/font.ttf"); entry(1, "external") }
        val backup = encrypted(payload)
        val encryptedWithoutEnd = backup.copyOf(backup.size - 24)
        val restore = OreoRestore(data, external, state, archive)

        fails { restore.import(encryptedWithoutEnd.inputStream(), key) }
        fails { restore.import(payload.inputStream(), key) }
        fails { restore.import(backup.inputStream(), OreoBackup.newKey()) }
        assertFalse(Files.exists(data.resolve("files")))
        assertFalse(Files.exists(state.resolve("ready")))
        assertFalse(restore.recover())
        restore.import(backup.inputStream(), key)
        assertTrue(Files.exists(data.resolve("files/font.ttf")))
    }

    @Test
    fun interruptedCommitCanResumeAndNeverReplacesExistingData() {
        val data = folder.newFolder("fresh").toPath()
        val external = folder.newFolder("already-moved-external").toPath()
        write(external.resolve("public.txt"), "moved before interruption")
        val state = data.resolve("migration")
        val staged = state.resolve("staging")
        val stageToken = "702faccc-5133-40ca-891a-b8bd6678bb79"
        val markerToken = "a108217b-d12d-4bf4-8c5a-0fe9f8cb2900"
        write(staged.resolve("external-token"), stageToken)
        val originalExternal = folder.newFolder("original-external-inode").toPath()
        write(originalExternal.resolve(".termix-restore-$markerToken"), markerToken)
        Files.copy(originalExternal.resolve(".termix-restore-$markerToken"), external.resolve(".termix-restore-$markerToken"))
        write(staged.resolve("local/bin/restored"), "archive")
        write(data.resolve("local/bin/existing"), "existing")
        fun inode(path: Path) = Files.getAttribute(path, "unix:ino", NOFOLLOW_LINKS)
        assertFalse(inode(originalExternal) == inode(external))
        write(state.resolve("ready"), "external $markerToken ${Instant.EPOCH}\nlocal ${inode(staged.resolve("local"))}\n")
        val restore = OreoRestore(data, external, state, archive)

        fails { restore.recover() }
        fails { restore.import(encrypted(fixture { entry(1, "external") }).inputStream(), key) }
        assertEquals("existing", String(Files.readAllBytes(data.resolve("local/bin/existing"))))
        assertEquals("moved before interruption", String(Files.readAllBytes(external.resolve("public.txt"))))
        assertFalse(Files.exists(state.resolve("committed")))
        assertTrue(Files.exists(external.resolve(".termix-restore-$markerToken")))

        Files.delete(data.resolve("local/bin/existing"))
        Files.delete(data.resolve("local/bin"))
        assertTrue(restore.recover())
        assertEquals("archive", String(Files.readAllBytes(data.resolve("local/bin/restored"))))
        restore.finish()
        restore.cleanupAfterDone()
        assertFalse(Files.exists(external.resolve(".termix-restore-$markerToken")))
        assertEquals(FileTime.from(Instant.EPOCH), Files.getLastModifiedTime(external))
    }

    @Test
    fun stagedExternalDirectoryCannotReplaceAnExistingDestination() {
        val base = folder.newFolder("external-collision").toPath()
        val data = Files.createDirectory(base.resolve("data"))
        val external = base.resolve("external")
        val state = data.resolve("state")
        val stageToken = "702faccc-5133-40ca-891a-b8bd6678bb79"
        val markerToken = "a108217b-d12d-4bf4-8c5a-0fe9f8cb2900"
        val staged = base.resolve(".termix-oreo-$stageToken")
        write(state.resolve("staging/external-token"), stageToken)
        write(state.resolve("ready"), "external $markerToken ${Instant.EPOCH}\n")
        write(staged.resolve(".termix-restore-$markerToken"), markerToken)
        write(staged.resolve("backup.txt"), "archive")
        write(external.resolve("existing.txt"), "existing")
        val restore = OreoRestore(data, external, state, archive)

        fails { restore.recover() }
        assertEquals("existing", String(Files.readAllBytes(external.resolve("existing.txt"))))
        assertFalse(Files.exists(external.resolve("backup.txt")))
        assertFalse(Files.exists(state.resolve("committed")))
        Files.delete(external.resolve("existing.txt"))
        assertTrue(restore.recover())
        assertEquals("archive", String(Files.readAllBytes(external.resolve("backup.txt"))))
        restore.finish()
        restore.cleanupAfterDone()
        assertFalse(Files.exists(external.resolve(".termix-restore-$markerToken")))
    }

    @Test
    fun replacingExternalStorageAfterCheckpointBlocksCompletionUntilOriginalReturns() {
        val data = folder.newFolder("cleanup-data").toPath()
        val external = folder.newFolder("cleanup-external").toPath()
        val state = data.resolve("state")
        val stageToken = "702faccc-5133-40ca-891a-b8bd6678bb79"
        val markerToken = "a108217b-d12d-4bf4-8c5a-0fe9f8cb2900"
        val marker = external.resolve(".termix-restore-$markerToken")
        write(state.resolve("staging/external-token"), stageToken)
        write(state.resolve("ready"), "external $markerToken ${Instant.EPOCH}\n")
        write(marker, markerToken)
        write(external.resolve("restored.txt"), "archive")
        val restore = OreoRestore(data, external, state, archive)
        assertTrue(restore.recover())
        assertEquals("pending $markerToken", String(Files.readAllBytes(state.resolve("committed"))))
        restore.cleanupAfterDone()
        assertTrue(Files.exists(marker))
        val originalExternal = external.resolveSibling("external-offline")
        Files.move(external, originalExternal)
        Files.createDirectory(external)
        val restarted = OreoRestore(data, external, state, archive)
        fails { restarted.recover() }
        fails { restarted.finish() }
        assertFalse(Files.exists(state.resolve("done")))
        assertFalse(Files.exists(external.resolve("restored.txt")))

        Files.delete(external)
        Files.move(originalExternal, external)
        restarted.finish()
        assertTrue(Files.exists(state.resolve("done")))
        assertTrue(Files.exists(marker))
        val afterRestart = OreoRestore(data, external, state, archive)
        afterRestart.cleanupAfterDone()
        afterRestart.cleanupAfterDone()
        assertFalse(Files.exists(marker))
        assertEquals("archive", String(Files.readAllBytes(external.resolve("restored.txt"))))
    }

    private val key = OreoBackup.newKey()

    private fun encrypted(bytes: ByteArray): ByteArray = ByteArrayOutputStream().apply {
        OreoBackup.encrypt(this, key).use { it.write(bytes) }
    }.toByteArray()

    private val archive = OreoArchive(
        readLink = { Files.readSymbolicLink(it).toString() },
        createLink = { path, target -> Files.createSymbolicLink(path, Paths.get(target)) },
    )

    private fun write(path: Path, value: String) {
        Files.createDirectories(path.parent)
        Files.write(path, value.toByteArray())
    }

    private fun fixture(entries: DataOutputStream.() -> Unit): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(GZIPOutputStream(bytes)).use {
            it.writeUTF(OreoArchive.MAGIC)
            it.entries()
            it.writeByte(0)
        }
        return bytes.toByteArray()
    }

    private fun DataOutputStream.entry(type: Int, name: String, target: String? = null, contents: ByteArray = byteArrayOf()) {
        writeByte(type)
        writeUTF(name)
        writeShort(0x1C0)
        writeLong(0)
        writeInt(0)
        if (type == 2) {
            writeLong(contents.size.toLong())
            write(contents)
        }
        if (target != null) writeUTF(target)
    }

    private fun fails(check: () -> Unit) {
        try {
            check()
            fail("Invalid backup should be rejected")
        } catch (_: IOException) {
            // The caller verifies no destination data was changed.
        }
    }
}
