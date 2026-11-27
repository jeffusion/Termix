package com.termix.migration

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.FileSystemException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.COPY_ATTRIBUTES
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.FileTime
import java.time.Instant
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

internal class OreoArchive(
    private val readLink: (Path) -> String,
    private val createLink: (Path, String) -> Unit,
    private val createHardLink: (Path, Path) -> Unit = { path, target -> Files.createLink(path, target) },
) {
    private data class Metadata(val type: Int, val mode: Int, val modified: FileTime, val size: Long)
    private data class Inode(val device: Long, val number: Long)
    private data class Snapshot(val metadata: Metadata, val inode: Inode, val links: Int)

    fun write(data: Path, external: Path, destination: OutputStream) {
        DataOutputStream(GZIPOutputStream(destination.buffered())).use { archive ->
            archive.writeUTF(MAGIC)
            val visited = mutableMapOf<Inode, String>()
            val buffer = ByteArray(BUFFER_SIZE)

            fun add(path: Path, name: String) {
                if (!allowed(name.split('/'))) return
                val before = snapshot(path)
                val (type, mode, modified, size) = before.metadata
                if (type == SOCKET || type == FIFO || type == CHARACTER || type == BLOCK) return
                requireEntryType(name, type)
                val earlier = if (type == FILE && before.links > 1) {
                    visited.putIfAbsent(before.inode, name)
                } else {
                    null
                }
                archive.writeByte(if (earlier == null) type else HARD_LINK)
                archive.writeUTF(name)
                archive.writeShort(mode)
                val time = modified.toInstant()
                archive.writeLong(time.epochSecond)
                archive.writeInt(time.nano)
                when {
                    earlier != null -> archive.writeUTF(earlier)
                    type == DIRECTORY -> Files.newDirectoryStream(path).use { children ->
                        for (child in children) {
                            val fileName = child.fileName
                            requireArchive(fileName == child.fileSystem.getPath(fileName.toString()), "Cannot encode a source filename")
                            add(child, "$name/$fileName")
                        }
                    }
                    type == SYMLINK -> archive.writeUTF(readLink(path))
                    type == FILE -> {
                        archive.writeLong(size)
                        Files.newInputStream(path, READ, NOFOLLOW_LINKS).use { input ->
                            copyFile(input, archive, size, buffer)
                            requireArchive(input.read() == -1, "Source file changed during backup")
                        }
                    }
                }
                requireArchive(snapshot(path) == before, "Source file changed during backup")
            }

            for (root in INTERNAL_ROOTS) {
                val path = data.resolve(root)
                if (Files.exists(path, NOFOLLOW_LINKS)) add(path, root)
            }
            add(external, EXTERNAL)
            archive.writeByte(END)
        }
    }

    fun extract(source: InputStream, staging: Path, externalStaging: Path) {
        DataInputStream(GZIPInputStream(source.buffered())).use { archive ->
            requireArchive(archive.readUTF() == MAGIC, "Unrecognized backup format")
            val buffer = ByteArray(BUFFER_SIZE)
            val directories = mutableListOf<Pair<Path, Metadata>>()
            while (true) {
                val type = archive.readUnsignedByte()
                if (type == END) break
                val name = archive.readUTF()
                requireEntryType(name, type)
                val output = entryPath(name, staging, externalStaging)
                val mode = archive.readUnsignedShort()
                requireArchive(mode <= MODE_BITS, "Invalid file mode")
                val seconds = archive.readLong()
                val nanos = archive.readInt()
                requireArchive(nanos in 0..999_999_999, "Invalid modification time")
                val modified = Instant.ofEpochSecond(seconds, nanos.toLong())
                val metadata = Metadata(type, mode, FileTime.from(modified), 0)
                val external = output.startsWith(externalStaging)
                when (type) {
                    DIRECTORY -> {
                        Files.createDirectory(output)
                        directories += output to metadata
                    }
                    SYMLINK -> {
                        val target = archive.readUTF()
                        requireArchive(target.isNotEmpty() && '\u0000' !in target, "Invalid symlink target")
                        createLink(output, target)
                    }
                    HARD_LINK -> {
                        val target = entryPath(archive.readUTF(), staging, externalStaging)
                        requireArchive(
                            external == target.startsWith(externalStaging) &&
                                Files.isRegularFile(target, NOFOLLOW_LINKS),
                            "Invalid hard-link target",
                        )
                        try {
                            createHardLink(output, target)
                        } catch (_: FileSystemException) {
                            // Oreo SELinux can deny hard-link creation even in the app's own directory.
                            Files.copy(target, output, NOFOLLOW_LINKS, COPY_ATTRIBUTES)
                        }
                    }
                    FILE -> {
                        val size = archive.readLong()
                        requireArchive(size >= 0, "Invalid file size")
                        Files.newOutputStream(output, CREATE_NEW, WRITE).use { file ->
                            copyFile(archive, file, size, buffer)
                        }
                        applyMetadata(output, metadata, external)
                    }
                }
            }
            requireArchive(archive.read() == -1, "Unexpected backup data after the end marker")
            requireArchive(Files.isDirectory(externalStaging, NOFOLLOW_LINKS), "Missing external storage record")
            for ((path, metadata) in directories.asReversed()) {
                applyMetadata(path, metadata, path.startsWith(externalStaging))
            }
        }
    }

    private fun entryPath(name: String, staging: Path, externalStaging: Path): Path {
        val parts = name.split('/')
        requireArchive(parts.none { it.isEmpty() || it == "." || it == ".." || '\u0000' in it }, "Invalid backup path")
        requireArchive(allowed(parts), "File is outside the backup scope")
        var path = if (parts.first() == EXTERNAL) externalStaging else staging.resolve(parts.first())
        for (part in parts.drop(1)) {
            requireArchive(Files.isDirectory(path, NOFOLLOW_LINKS), "Missing or unsafe backup directory")
            path = path.resolve(part)
        }
        return path
    }

    private fun requireEntryType(name: String, type: Int) {
        val parts = name.split('/')
        val valid = when {
            parts.size == 1 -> type == DIRECTORY
            parts.first() == "shared_prefs" || name == "local/.termix-arch-installed" -> type == FILE || type == HARD_LINK
            name == "local/colors.properties" -> type == FILE || type == HARD_LINK || type == SYMLINK
            parts.first() == "local" && parts.size == 2 -> type == DIRECTORY
            else -> type in DIRECTORY..HARD_LINK
        }
        requireArchive(valid, "Invalid backup entry type")
    }

    private fun applyMetadata(path: Path, metadata: Metadata, external: Boolean) {
        Files.setLastModifiedTime(path, metadata.modified)
        // Emulated external storage supplies its own mode from the mount rather than chmod.
        if (!external || snapshot(path).metadata.mode != metadata.mode) {
            Files.setAttribute(path, "unix:mode", metadata.mode, NOFOLLOW_LINKS)
        }
    }

    companion object {
        val INTERNAL_ROOTS = listOf("local", "files", "shared_prefs")
        const val EXTERNAL = "external"
        const val PROFILE_CACHE = "profileinstaller_profileWrittenFor_lastUpdateTime.dat"
        private const val DIRECTORY = 1
        private const val FILE = 2
        private const val SYMLINK = 3
        private const val HARD_LINK = 4
        private const val SOCKET = 5
        private const val FIFO = 6
        private const val CHARACTER = 7
        private const val BLOCK = 8
        private const val END = 0
        private const val MODE_BITS = 0xFFF
        internal const val MAGIC = "Termix Oreo migration 1"
        private const val BUFFER_SIZE = 64 * 1024

        private fun allowed(parts: List<String>): Boolean {
            val name = parts.getOrNull(1) ?: return parts.first() in INTERNAL_ROOTS || parts.first() == EXTERNAL
            return when (parts.first()) {
                EXTERNAL -> true
                "shared_prefs" -> parts.size == 2 && name in setOf("Settings.xml", "Settings.xml.bak")
                "local" -> when (name) {
                    ".termix-arch-installed", "colors.properties" -> parts.size == 2
                    "bin", "lib" -> true
                    "alpine", "arch" -> {
                        val inGuest = parts.drop(2).joinToString("/")
                        !inGuest.startsWith("tmp/") && !inGuest.startsWith("run/") &&
                            !inGuest.startsWith("var/run/") && !inGuest.startsWith("var/cache/") &&
                            inGuest != "etc/termix-arch-init.lock"
                    }
                    else -> false
                }
                "files" -> name !in setOf(PROFILE_CACHE, "proot.part", "libtalloc.so.2.part", "alpine.tar.gz.part", "arch.tar.gz.part", "arch.tar.gz") &&
                    !name.startsWith("pulse-17.0-5-")
                else -> false
            }
        }

        private fun snapshot(path: Path): Snapshot {
            val attributes = Files.readAttributes(path, "unix:mode,size,dev,ino,nlink,lastModifiedTime", NOFOLLOW_LINKS)
            val mode = attributes.getValue("mode") as Int
            val type = when (mode and 0xF000) {
                0x4000 -> DIRECTORY
                0x8000 -> FILE
                0xA000 -> SYMLINK
                0xC000 -> SOCKET
                0x1000 -> FIFO
                0x2000 -> CHARACTER
                0x6000 -> BLOCK
                else -> throw IOException("Unsupported file type")
            }
            return Snapshot(
                Metadata(type, mode and MODE_BITS, attributes.getValue("lastModifiedTime") as FileTime, attributes.getValue("size") as Long),
                Inode(attributes.getValue("dev") as Long, attributes.getValue("ino") as Long),
                attributes.getValue("nlink") as Int,
            )
        }

        private fun copyFile(input: InputStream, output: OutputStream, size: Long, buffer: ByteArray) {
            var remaining = size
            while (remaining > 0L) {
                val read = input.read(buffer, 0, minOf(remaining, buffer.size.toLong()).toInt())
                if (read == -1) throw EOFException("Incomplete backup or source file")
                output.write(buffer, 0, read)
                remaining -= read
            }
        }
    }
}

internal fun requireArchive(condition: Boolean, message: String) {
    if (!condition) throw IOException(message)
}
