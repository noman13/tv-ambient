package io.github.noman13.tvambient.adb

import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * A minimal ADB client for the TV's own adbd on 127.0.0.1:5555 (TECHNICAL.md §4, replaces dadb + okio): connect,
 * authenticate with [AdbKey], and run one `exec:` command whose stdout we read and whose stdin we write.
 * One stream per connection is all the app needs (the capture helper). Not thread-safe except as documented: one
 * thread reads ([Stream.read]), any thread may write ([Stream.write]) and [close].
 */
class AdbClient(private val key: AdbKey, private val name: String) : AutoCloseable {
    private var socket: Socket? = null
    private lateinit var input: DataInputStream
    private lateinit var output: OutputStream
    private val header = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
    private var maxPayload = 4096

    /** Connects and authenticates. Throws [IOException] if adbd isn't there or the key isn't accepted in time. */
    fun connect(host: String = "127.0.0.1", port: Int = 5555, timeoutMs: Int = 3000, authWaitMs: Int = 60_000) {
        val s = Socket()
        s.tcpNoDelay = true
        s.connect(InetSocketAddress(host, port), timeoutMs)
        socket = s
        input = DataInputStream(BufferedInputStream(s.getInputStream(), 64 * 1024))
        output = s.getOutputStream()
        send(CNXN, VERSION, MAX_DATA, "host::$name\u0000".toByteArray())
        var sentKey = false
        var triedSignature = false
        s.soTimeout = timeoutMs
        while (true) {
            readPacket()
            when (pCmd) {
                CNXN -> {
                    maxPayload = pArg1.coerceIn(4096, MAX_DATA)
                    s.soTimeout = 0
                    return
                }
                AUTH -> {
                    if (pArg0 != AUTH_TOKEN) throw IOException("unexpected AUTH type $pArg0")
                    if (!triedSignature) {
                        triedSignature = true
                        send(AUTH, AUTH_SIGNATURE, 0, key.sign(data.copyOf(pLen)))
                    } else if (!sentKey) {
                        // the signature wasn't accepted: offer the public key; the TV asks the user once
                        sentKey = true
                        send(AUTH, AUTH_RSAPUBLICKEY, 0, key.publicKeyPayload(name))
                        s.soTimeout = authWaitMs
                    } else {
                        throw IOException("ADB key not accepted")
                    }
                }
                else -> Unit
            }
        }
    }

    /** Opens `exec:<command>` (raw stdin/stdout, no pty). */
    fun exec(command: String): Stream {
        val local = 1
        send(OPEN, local, 0, "exec:$command\u0000".toByteArray())
        while (true) {
            readPacket()
            when (pCmd) {
                OKAY -> if (pArg1 == local) return Stream(local, pArg0)
                CLSE -> throw IOException("adbd refused exec")
                else -> Unit
            }
        }
    }

    // The packet just read: header fields and payload, in buffers reused for every packet (no allocation per frame).
    private val headerIn = ByteArray(24)
    private val headerView = ByteBuffer.wrap(headerIn).order(ByteOrder.LITTLE_ENDIAN)
    private val data = ByteArray(MAX_DATA)
    private var pCmd = 0
    private var pArg0 = 0
    private var pArg1 = 0
    private var pLen = 0

    private fun readPacket() {
        input.readFully(headerIn)
        headerView.rewind()
        pCmd = headerView.int; pArg0 = headerView.int; pArg1 = headerView.int; pLen = headerView.int
        if (pLen < 0 || pLen > data.size) throw IOException("bad ADB packet length $pLen")
        input.readFully(data, 0, pLen)
    }

    // The reader thread (OKAY replies) and the control writer both send: one packet at a time.
    @Synchronized
    private fun send(command: Int, arg0: Int, arg1: Int, data: ByteArray, off: Int = 0, len: Int = data.size) {
        var sum = 0
        for (i in off until off + len) sum += data[i].toInt() and 0xff
        header.clear()
        header.putInt(command).putInt(arg0).putInt(arg1).putInt(len).putInt(sum).putInt(command.inv())
        output.write(header.array(), 0, 24)
        if (len > 0) output.write(data, off, len)
        output.flush()
    }

    inner class Stream(private val local: Int, private val remote: Int) {
        private var pos = 0
        private var end = 0
        @Volatile private var closed = false
        private val writeLock = Object()
        @Volatile private var writeAcked = true

        /** Blocks for the helper's output; returns bytes read, or -1 when the stream ends. Single reader thread. */
        fun read(dst: ByteArray, off: Int, len: Int): Int {
            while (pos >= end) {
                if (closed) return -1
                try { readPacket() } catch (e: EOFException) { closed = true; return -1 }
                when (pCmd) {
                    WRTE -> if (pArg1 == local) {
                        pos = 0; end = pLen // served from [data] until used up; the next packet is read only then
                        send(OKAY, local, remote, EMPTY)
                    }
                    OKAY -> synchronized(writeLock) { writeAcked = true; writeLock.notifyAll() }
                    CLSE -> { closed = true; synchronized(writeLock) { writeLock.notifyAll() }; return -1 }
                    else -> Unit
                }
            }
            val n = minOf(len, end - pos)
            System.arraycopy(data, pos, dst, off, n)
            pos += n
            return n
        }

        /** Writes to the helper's stdin (waits for the previous write's OKAY, as ADB requires). */
        fun write(data: ByteArray, off: Int = 0, len: Int = data.size) {
            var at = off
            while (at < off + len) {
                synchronized(writeLock) {
                    val deadline = System.currentTimeMillis() + 2000
                    while (!writeAcked && !closed) {
                        val left = deadline - System.currentTimeMillis()
                        if (left <= 0) throw IOException("ADB write not acknowledged")
                        writeLock.wait(left)
                    }
                    if (closed) throw IOException("stream closed")
                    writeAcked = false
                }
                val n = minOf(maxPayload, off + len - at)
                send(WRTE, local, remote, data, at, n)
                at += n
            }
        }

        fun close() {
            if (!closed) {
                closed = true
                runCatching { send(CLSE, local, remote, EMPTY) }
            }
        }
    }

    override fun close() {
        runCatching { socket?.close() }
        socket = null
    }

    companion object {
        private const val CNXN = 0x4e584e43
        private const val AUTH = 0x48545541
        private const val OPEN = 0x4e45504f
        private const val OKAY = 0x59414b4f
        private const val CLSE = 0x45534c43
        private const val WRTE = 0x45545257
        private const val VERSION = 0x01000001
        private const val AUTH_TOKEN = 1
        private const val AUTH_SIGNATURE = 2
        private const val AUTH_RSAPUBLICKEY = 3
        private const val MAX_DATA = 256 * 1024
        private val EMPTY = ByteArray(0)
    }
}
