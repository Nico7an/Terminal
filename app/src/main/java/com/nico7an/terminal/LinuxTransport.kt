package com.nico7an.terminal

import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import com.termux.terminal.TerminalSession
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException

/** A shell of the built-in Linux environment, as a local process on a pseudo-terminal. */
class LinuxTransport : TerminalSession.Transport {

    @Volatile private var fd = -1
    @Volatile private var pid = -1
    @Volatile private var pty: ParcelFileDescriptor? = null
    @Volatile private var columns = 80
    @Volatile private var rows = 24
    @Volatile private var closed = false

    override fun connect(session: TerminalSession, columns: Int, rows: Int) {
        this.columns = columns
        this.rows = rows
        Thread({ run(session) }, "linux").start()
    }

    override fun resize(columns: Int, rows: Int) {
        this.columns = columns
        this.rows = rows
        if (fd >= 0) runCatching { Pty.resize(fd, rows, columns) }
    }

    override fun disconnect() {
        closed = true
        // The shell is the leader of its own session: hang up the whole group.
        val p = pid
        if (p > 0) runCatching { Os.kill(-p, OsConstants.SIGHUP) }
    }

    private fun run(session: TerminalSession) {
        var reason: String? = "Linux fermé"
        try {
            Linux.prepare { status(session, it) }
            if (closed) return
            Linux.refreshAdbPort()
            val command = Linux.command()
            val pidOut = IntArray(1)
            val master = Pty.spawn(command.executable, command.cwd, command.args, command.env, pidOut, rows, columns)
            val descriptor = ParcelFileDescriptor.adoptFd(master)
            pty = descriptor
            fd = master
            pid = pidOut[0]
            if (closed) {
                disconnect()
                return
            }
            session.onConnected(FileOutputStream(descriptor.fileDescriptor))

            val input = FileInputStream(descriptor.fileDescriptor)
            val buffer = ByteArray(64 * 1024)
            while (true) {
                // EIO once the last process using the terminal is gone.
                val n = try { input.read(buffer) } catch (e: IOException) { -1 }
                if (n < 0) break
                if (n > 0) session.onRemoteData(buffer, 0, n)
            }
            val code = Pty.waitFor(pid)
            reason = if (code == 0) "Linux fermé" else "Linux fermé (code $code)"
        } catch (e: Exception) {
            reason = "Erreur : " + (e.message ?: e.javaClass.simpleName)
        } finally {
            fd = -1
            runCatching { pty?.close() }
            pty = null
            session.onDisconnected(if (closed) null else reason)
        }
    }

    private fun status(session: TerminalSession, text: String) {
        val bytes = "\u001b[0;90m$text\u001b[0m\r\n".toByteArray()
        session.onRemoteData(bytes, 0, bytes.size)
    }
}
