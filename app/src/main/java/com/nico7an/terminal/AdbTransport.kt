package com.nico7an.terminal

import com.termux.terminal.TerminalSession
import io.github.muntashirakon.adb.AdbStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.OutputStream
import java.util.concurrent.Executors

/**
 * Interactive `adb shell` on this device. Uses the shell protocol v2 (Android 7+), which carries window
 * size changes, unlike the raw v1 stream: every message is [id: 1 byte][length: 4 bytes LE][data].
 */
class AdbTransport(private val prompter: AdbPrompter) : TerminalSession.Transport {

    private val control = Executors.newSingleThreadExecutor()
    /** Input and resizes come from two threads: a message must never be split. Not the stream's own monitor, it waits on it. */
    private val writeLock = Any()

    @Volatile private var stream: AdbStream? = null
    @Volatile private var columns = 80
    @Volatile private var rows = 24
    @Volatile private var closed = false

    override fun connect(session: TerminalSession, columns: Int, rows: Int) {
        this.columns = columns
        this.rows = rows
        Thread({ run(session) }, "adb-shell").start()
    }

    override fun resize(columns: Int, rows: Int) {
        this.columns = columns
        this.rows = rows
        val s = stream ?: return
        runCatching { control.execute { runCatching { sendWindowSize(s) } } }
    }

    override fun disconnect() {
        closed = true
        control.execute { close() }
        control.shutdown()
    }

    private fun close() {
        runCatching { stream?.close() }
        stream = null
    }

    private fun run(session: TerminalSession) {
        var reason: String? = "Connexion fermée"
        try {
            status(session, "Connexion à adb (débogage sans fil)…")
            val adb = Adb.connect()
            if (closed) return
            val s = adb.openStream("shell,v2,TERM=xterm-256color,pty:")
            stream = s
            if (closed) return
            sendWindowSize(s)
            session.onConnected(StdinStream(s))
            prompter.onAdbConnected()

            val input = DataInputStream(s.openInputStream())
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val id = input.read()
                if (id < 0) break
                val length = Integer.reverseBytes(input.readInt())
                if (length < 0) break
                var left = length
                var exit: Int? = null
                while (left > 0) {
                    val n = input.read(buffer, 0, minOf(left, buffer.size))
                    if (n < 0) throw EOFException()
                    when (id) {
                        ID_STDOUT, ID_STDERR -> session.onRemoteData(buffer, 0, n)
                        ID_EXIT -> exit = buffer[0].toInt() and 0xff
                    }
                    left -= n
                }
                if (id == ID_EXIT) {
                    reason = if (exit == 0) "Session terminée" else "Session terminée (code $exit)"
                    break
                }
            }
        } catch (e: AdbSetupException) {
            reason = e.message
            if (!closed) prompter.onAdbBlocked(e.message ?: "")
        } catch (e: Exception) {
            reason = if (closed) null else "Erreur adb : " + (e.message ?: e.javaClass.simpleName)
        } finally {
            close()
            session.onDisconnected(if (closed) null else reason)
        }
    }

    private fun sendWindowSize(s: AdbStream) = packet(s, ID_WINDOW_SIZE, "${rows}x${columns},0x0".toByteArray())

    private fun status(session: TerminalSession, text: String) {
        val bytes = "\u001b[0;90m$text\u001b[0m\r\n".toByteArray()
        session.onRemoteData(bytes, 0, bytes.size)
    }

    /** Typed input, wrapped into stdin messages. */
    private inner class StdinStream(private val s: AdbStream) : OutputStream() {
        override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)
        override fun write(b: ByteArray, off: Int, len: Int) = packet(s, ID_STDIN, b.copyOfRange(off, off + len))
        override fun flush() = Unit
    }

    private fun packet(s: AdbStream, id: Int, data: ByteArray) {
        val message = ByteArray(5 + data.size)
        message[0] = id.toByte()
        for (i in 0 until 4) message[1 + i] = (data.size shr (8 * i)).toByte()
        data.copyInto(message, 5)
        synchronized(writeLock) {
            s.write(message, 0, message.size)
            s.flush()
        }
    }

    companion object {
        private const val ID_STDIN = 0
        private const val ID_STDOUT = 1
        private const val ID_STDERR = 2
        private const val ID_EXIT = 3
        private const val ID_WINDOW_SIZE = 5
    }
}

/** What the adb terminal needs from the UI. Called from background threads. */
interface AdbPrompter {
    /** The device is not ready: show the setup screen. */
    fun onAdbBlocked(reason: String)

    /** Connected: time to check that HyperOS lets adb act on the system. */
    fun onAdbConnected()
}
