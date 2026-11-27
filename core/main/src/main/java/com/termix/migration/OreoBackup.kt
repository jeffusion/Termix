package com.termix.migration

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

internal object OreoBackup {
    private val magic = byteArrayOf(0x54, 0x4D, 0x58, 0x38, 0x01)
    private const val keyBytes = 32
    private const val noncePrefixBytes = 8
    private const val tagBytes = 16
    private const val chunkBytes = 64 * 1024

    fun newKey(): ByteArray = ByteArray(keyBytes).also(SecureRandom()::nextBytes)

    fun encodeKey(key: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(key)

    fun decodeKey(encoded: CharSequence): ByteArray? {
        val text = encoded.filterNot(Char::isWhitespace).toString()
        if (text.length != 43) return null
        return try {
            Base64.getUrlDecoder().decode(text).takeIf { it.size == keyBytes && encodeKey(it) == text }
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    fun matchesKey(expected: ByteArray, encoded: CharSequence): Boolean {
        val decoded = decodeKey(encoded) ?: return false
        return try {
            MessageDigest.isEqual(expected, decoded)
        } finally {
            decoded.fill(0)
        }
    }

    fun encrypt(destination: OutputStream, key: ByteArray): OutputStream {
        val output = DataOutputStream(destination)
        val noncePrefix = ByteArray(noncePrefixBytes).also(SecureRandom()::nextBytes)
        output.write(magic)
        output.write(noncePrefix)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val secret = SecretKeySpec(key, "AES")
        return object : OutputStream() {
            private val chunk = ByteArray(chunkBytes)
            private var size = 0
            private var sequence = 0
            private var closed = false

            override fun write(value: Int) {
                chunk[size++] = value.toByte()
                if (size == chunk.size) flushChunk()
            }

            override fun write(bytes: ByteArray, offset: Int, length: Int) {
                var copied = 0
                while (copied < length) {
                    val count = minOf(length - copied, chunk.size - size)
                    bytes.copyInto(chunk, size, offset + copied, offset + copied + count)
                    size += count
                    copied += count
                    if (size == chunk.size) flushChunk()
                }
            }

            private fun flushChunk() {
                val encoded = crypt(cipher, Cipher.ENCRYPT_MODE, secret, noncePrefix, sequence, chunk, size)
                output.writeInt(sequence++)
                output.writeInt(size)
                output.write(encoded)
                size = 0
            }

            override fun close() {
                if (closed) return
                closed = true
                if (size != 0) flushChunk()
                // A zero-length, authenticated frame terminates the archive.
                flushChunk()
                output.flush()
            }
        }
    }

    class Decrypted(source: InputStream, key: ByteArray) : InputStream() {
        private val input = DataInputStream(source)
        private val noncePrefix = ByteArray(noncePrefixBytes)
        private val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        private val secret = SecretKeySpec(key, "AES")
        private val encoded = ByteArray(chunkBytes + tagBytes)
        private var chunk = ByteArray(0)
        private var offset = 0
        private var sequence = 0
        private var complete = false
        private var failed = false

        init {
            val header = ByteArray(magic.size)
            input.readFully(header)
            requireArchive(header.contentEquals(magic), "Unrecognized encrypted backup")
            input.readFully(noncePrefix)
        }

        private fun hasContent(): Boolean {
            requireArchive(!failed, "Backup authentication failed")
            if (offset < chunk.size) return true
            if (complete) return false
            try {
                requireArchive(input.readInt() == sequence, "Invalid backup frame order")
                val size = input.readInt()
                requireArchive(size in 0..chunkBytes, "Invalid backup frame length")
                input.readFully(encoded, 0, size + tagBytes)
                chunk = crypt(cipher, Cipher.DECRYPT_MODE, secret, noncePrefix, sequence++, encoded, size)
                offset = 0
                if (size == 0) {
                    requireArchive(input.read() == -1, "Unexpected data after authenticated backup end")
                    complete = true
                }
            } catch (error: IOException) {
                failed = true
                throw error
            }
            return !complete
        }

        override fun read(): Int = if (hasContent()) chunk[offset++].toInt() and 0xff else -1

        override fun read(bytes: ByteArray, target: Int, length: Int): Int {
            if (length == 0) return 0
            if (!hasContent()) return -1
            val count = minOf(length, chunk.size - offset)
            chunk.copyInto(bytes, target, offset, offset + count)
            offset += count
            return count
        }

        fun requireComplete() {
            requireArchive(read() == -1 && complete, "Incomplete encrypted backup")
        }
    }

    private fun crypt(
        cipher: Cipher, mode: Int, key: SecretKeySpec, prefix: ByteArray, sequence: Int,
        bytes: ByteArray, size: Int,
    ): ByteArray {
        requireArchive(sequence >= 0, "Backup exceeds format size limit")
        val nonce = ByteBuffer.allocate(12).put(prefix).putInt(sequence).array()
        val header = ByteBuffer.allocate(8).putInt(sequence).putInt(size).array()
        try {
            cipher.init(mode, key, GCMParameterSpec(tagBytes * 8, nonce))
            cipher.updateAAD(magic)
            cipher.updateAAD(header)
            return cipher.doFinal(bytes, 0, size + if (mode == Cipher.DECRYPT_MODE) tagBytes else 0)
        } catch (error: GeneralSecurityException) {
            throw IOException("Backup authentication failed", error)
        }
    }
}
