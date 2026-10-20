/*
 * Copyright (C) 2014-2026 Arpit Khurana <arpitkh96@gmail.com>, Vishal Nehra <vishalmeham2@gmail.com>,
 * Emmanuel Messulam<emmanuelbendavid@gmail.com>, Raymond Lai <airwave209gt at gmail.com> and Contributors.
 *
 * This file is part of Amaze File Manager.
 *
 * Amaze File Manager is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.amaze.filemanager.transfer

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.util.zip.CRC32

/**
 * Wire protocol for sending files between two devices over a TCP connection (Wi-Fi Direct group,
 * same Wi-Fi network or hotspot). One connection carries one hello or one batch of files.
 *
 * Batch: header (names and sizes) -> receiver accepts or declines -> for every file the raw bytes,
 * a CRC32 and a one byte acknowledgement. Everything is length checked, nothing is deserialised.
 */
object TransferProtocol {
    const val PORT = 8990
    const val CONNECT_TIMEOUT_MS = 8000
    private const val MAGIC = 0x414D5A54
    private const val VERSION: Short = 1
    private const val TYPE_HELLO: Byte = 1
    private const val TYPE_OFFER: Byte = 2
    private const val MAX_FILES = 20000
    private const val MAX_NAME_BYTES = 1024
    private const val BUFFER = 256 * 1024
    private const val READ_TIMEOUT_MS = 30000
    private const val DECISION_TIMEOUT_MS = 120000
    private const val DECIDE_DECLINED: Int = 0
    private const val DECIDE_ACCEPTED: Int = 1
    private const val DECIDE_NO_SPACE: Int = 2

    class OutgoingFile(val name: String, val size: Long, val open: () -> InputStream)

    class OfferedFile(val name: String, val size: Long)

    class Offer(val senderName: String, val files: List<OfferedFile>) {
        val totalBytes: Long get() = files.sumOf { it.size }
    }

    /** Progress and cancel hook, implemented by the UI. Called from the transfer thread. */
    interface Progress {
        val cancelled: Boolean

        fun onProgress(
            doneBytes: Long,
            totalBytes: Long,
            fileName: String,
            fileIndex: Int,
            fileCount: Int,
        )
    }

    enum class SendStatus { OK, DECLINED, NO_SPACE, CANCELLED, FAILED }

    class SendResult(val status: SendStatus, val filesOk: Int, val filesFailed: Int, val message: String = "")

    enum class ReceiveKind { HELLO, TRANSFER, DECLINED, CANCELLED, FAILED }

    class ReceiveResult(
        val kind: ReceiveKind,
        val peerName: String = "",
        val peerAddress: String = "",
        val files: List<File> = emptyList(),
        val failed: Int = 0,
        val message: String = "",
    )

    // ------------------------------------------------------------------ client side

    /** Asks the device at [host] for its name. Returns null if nobody answers. */
    fun hello(
        host: String,
        port: Int,
        myName: String,
    ): String? =
        try {
            Socket().use { s ->
                s.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
                s.soTimeout = READ_TIMEOUT_MS
                val out = DataOutputStream(BufferedOutputStream(s.getOutputStream()))
                val input = DataInputStream(BufferedInputStream(s.getInputStream()))
                writeHeader(out, TYPE_HELLO)
                out.writeUTF(myName.take(100))
                out.flush()
                if (input.readInt() != MAGIC || input.readByte() != TYPE_HELLO) {
                    null
                } else {
                    input.readUTF()
                }
            }
        } catch (e: IOException) {
            null
        }

    fun send(
        host: String,
        port: Int,
        myName: String,
        files: List<OutgoingFile>,
        progress: Progress,
    ): SendResult {
        if (files.isEmpty() || files.size > MAX_FILES) return SendResult(SendStatus.FAILED, 0, 0, "Nothing to send")
        val total = files.sumOf { it.size }
        try {
            Socket().use { s ->
                s.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
                s.soTimeout = DECISION_TIMEOUT_MS
                s.sendBufferSize = BUFFER
                val out = DataOutputStream(BufferedOutputStream(s.getOutputStream(), BUFFER))
                val input = DataInputStream(BufferedInputStream(s.getInputStream()))
                writeHeader(out, TYPE_OFFER)
                out.writeUTF(myName.take(100))
                out.writeInt(files.size)
                for (f in files) {
                    out.writeUTF(f.name.take(MAX_NAME_BYTES / 4))
                    out.writeLong(f.size)
                }
                out.flush()
                when (input.readUnsignedByte()) {
                    DECIDE_ACCEPTED -> Unit
                    DECIDE_NO_SPACE -> return SendResult(SendStatus.NO_SPACE, 0, 0, "The other device has not enough free space")
                    else -> return SendResult(SendStatus.DECLINED, 0, 0, "The other device declined")
                }
                s.soTimeout = READ_TIMEOUT_MS

                var done = 0L
                var ok = 0
                var failed = 0
                val buf = ByteArray(BUFFER)
                for ((index, f) in files.withIndex()) {
                    val crc = CRC32()
                    var sent = 0L
                    f.open().use { src ->
                        while (sent < f.size) {
                            if (progress.cancelled) return SendResult(SendStatus.CANCELLED, ok, failed)
                            val want = minOf(buf.size.toLong(), f.size - sent).toInt()
                            val n = src.read(buf, 0, want)
                            if (n < 0) throw IOException("${f.name} is shorter than announced")
                            out.write(buf, 0, n)
                            crc.update(buf, 0, n)
                            sent += n
                            done += n
                            progress.onProgress(done, total, f.name, index, files.size)
                        }
                    }
                    out.writeInt(crc.value.toInt())
                    out.flush()
                    if (input.readUnsignedByte() == 1) ok++ else failed++
                }
                return SendResult(if (failed == 0) SendStatus.OK else SendStatus.FAILED, ok, failed)
            }
        } catch (e: IOException) {
            return SendResult(SendStatus.FAILED, 0, 0, e.message ?: "Connection lost")
        }
    }

    // ------------------------------------------------------------------ server side

    /**
     * Handles one incoming connection. [decide] is asked (and may block, up to two minutes) whether
     * to accept an offer. [freeSpace] reports the bytes free in [destDir].
     */
    fun handle(
        socket: Socket,
        myName: String,
        destDir: File,
        decide: (Offer) -> Boolean,
        progress: Progress,
        freeSpace: () -> Long = { destDir.usableSpace },
    ): ReceiveResult {
        val peer = socket.inetAddress?.hostAddress ?: ""
        try {
            socket.soTimeout = READ_TIMEOUT_MS
            val input = DataInputStream(BufferedInputStream(socket.getInputStream(), BUFFER))
            val out = DataOutputStream(BufferedOutputStream(socket.getOutputStream()))
            if (input.readInt() != MAGIC) return ReceiveResult(ReceiveKind.FAILED, message = "Not a transfer connection")
            if (input.readShort() != VERSION) return ReceiveResult(ReceiveKind.FAILED, message = "Different protocol version")
            when (input.readByte()) {
                TYPE_HELLO -> {
                    val name = input.readUTF()
                    out.writeInt(MAGIC)
                    out.writeByte(TYPE_HELLO.toInt())
                    out.writeUTF(myName.take(100))
                    out.flush()
                    return ReceiveResult(ReceiveKind.HELLO, peerName = name, peerAddress = peer)
                }
                TYPE_OFFER -> return receiveBatch(socket, input, out, peer, destDir, decide, progress, freeSpace)
                else -> return ReceiveResult(ReceiveKind.FAILED, message = "Unknown request")
            }
        } catch (e: IOException) {
            return ReceiveResult(ReceiveKind.FAILED, peerAddress = peer, message = e.message ?: "Connection lost")
        }
    }

    private fun receiveBatch(
        socket: Socket,
        input: DataInputStream,
        out: DataOutputStream,
        peer: String,
        destDir: File,
        decide: (Offer) -> Boolean,
        progress: Progress,
        freeSpace: () -> Long,
    ): ReceiveResult {
        val senderName = input.readUTF()
        val count = input.readInt()
        if (count < 1 || count > MAX_FILES) return ReceiveResult(ReceiveKind.FAILED, senderName, peer, message = "Invalid file count")
        val offered = ArrayList<OfferedFile>(count)
        for (i in 0 until count) {
            val name = input.readUTF()
            val size = input.readLong()
            if (size < 0 || name.length > MAX_NAME_BYTES) return ReceiveResult(ReceiveKind.FAILED, senderName, peer, message = "Invalid file entry")
            offered.add(OfferedFile(name, size))
        }
        val offer = Offer(senderName, offered)
        if (offer.totalBytes < 0) return ReceiveResult(ReceiveKind.FAILED, senderName, peer, message = "Invalid sizes")

        if (offer.totalBytes > freeSpace()) {
            out.writeByte(DECIDE_NO_SPACE)
            out.flush()
            return ReceiveResult(ReceiveKind.DECLINED, senderName, peer, message = "Not enough free space")
        }
        // keep the sender's connection quiet while the person decides
        val accepted = decide(offer)
        if (!accepted) {
            out.writeByte(DECIDE_DECLINED)
            out.flush()
            return ReceiveResult(ReceiveKind.DECLINED, senderName, peer)
        }
        out.writeByte(DECIDE_ACCEPTED)
        out.flush()

        destDir.mkdirs()
        val saved = ArrayList<File>()
        var failed = 0
        var done = 0L
        val buf = ByteArray(BUFFER)
        for ((index, f) in offered.withIndex()) {
            val rel = sanitizeRelativePath(f.name)
            val target = uniqueFile(destDir, rel)
            target.parentFile?.mkdirs()
            val part = File(target.path + ".amazepart")
            val crc = CRC32()
            var got = 0L
            try {
                FileOutputStream(part).use { fos ->
                    val bos = BufferedOutputStream(fos, BUFFER)
                    while (got < f.size) {
                        if (progress.cancelled) {
                            part.delete()
                            return ReceiveResult(ReceiveKind.CANCELLED, senderName, peer, saved, failed)
                        }
                        val want = minOf(buf.size.toLong(), f.size - got).toInt()
                        val n = input.read(buf, 0, want)
                        if (n < 0) throw IOException("Connection closed during ${f.name}")
                        bos.write(buf, 0, n)
                        crc.update(buf, 0, n)
                        got += n
                        done += n
                        progress.onProgress(done, offer.totalBytes, rel, index, offered.size)
                    }
                    bos.flush()
                }
                val expected = input.readInt()
                if (expected == crc.value.toInt() && part.renameTo(target)) {
                    saved.add(target)
                    out.writeByte(1)
                } else {
                    part.delete()
                    failed++
                    out.writeByte(0)
                }
                out.flush()
            } catch (e: IOException) {
                part.delete()
                return ReceiveResult(ReceiveKind.FAILED, senderName, peer, saved, failed + 1, e.message ?: "Connection lost")
            }
        }
        return ReceiveResult(ReceiveKind.TRANSFER, senderName, peer, saved, failed)
    }

    // ------------------------------------------------------------------ helpers

    private fun writeHeader(
        out: DataOutputStream,
        type: Byte,
    ) {
        out.writeInt(MAGIC)
        out.writeShort(VERSION.toInt())
        out.writeByte(type.toInt())
    }

    /** Makes a name received from another device safe: no absolute paths, no "..", no control characters. */
    fun sanitizeRelativePath(raw: String): String {
        val parts =
            raw.replace('\\', '/').split('/')
                .map { seg -> seg.filter { it.code >= 0x20 && it.code != 0x7f }.trim().take(200) }
                .filter { it.isNotEmpty() && it != "." && it != ".." }
        return if (parts.isEmpty()) "file" else parts.joinToString("/")
    }

    /** Returns a file path inside [dir] that does not exist yet, adding " (1)", " (2)"... if needed. */
    fun uniqueFile(
        dir: File,
        relativePath: String,
    ): File {
        val base = File(dir, relativePath)
        // defence in depth: whatever the name was, stay inside dir
        val dirPath = dir.canonicalPath + File.separator
        require((base.canonicalPath + File.separator).startsWith(dirPath)) { "Path escapes the target folder" }
        if (!base.exists() && !File(base.path + ".amazepart").exists()) return base
        val name = base.name
        val dot = name.lastIndexOf('.')
        val stem = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var n = 1
        while (true) {
            val candidate = File(base.parentFile, "$stem ($n)$ext")
            if (!candidate.exists() && !File(candidate.path + ".amazepart").exists()) return candidate
            n++
        }
    }

    /** IPv4 addresses of this device that another device could connect to. */
    fun localAddresses(): List<String> =
        try {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { nic -> nic.inetAddresses.toList().filter { it.address.size == 4 && !it.isLoopbackAddress } }
                .mapNotNull { it.hostAddress }
        } catch (e: IOException) {
            emptyList()
        }
}
