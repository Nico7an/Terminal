package com.nico7an.terminal

/** Local processes on a pseudo-terminal (cpp/pty.c). */
object Pty {
    init {
        System.loadLibrary("terminal-pty")
    }

    /** Starts [command] in a new session on a new pty. Returns the master fd, the pid goes to [pid]`[0]`. */
    @JvmStatic external fun spawn(
        command: String, cwd: String, args: Array<String>, env: Array<String>, pid: IntArray, rows: Int, columns: Int,
    ): Int

    @JvmStatic external fun resize(fd: Int, rows: Int, columns: Int)

    /** Exit code, or -signal. Blocking. */
    @JvmStatic external fun waitFor(pid: Int): Int
}
