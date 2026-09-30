package com.nico7an.terminal

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.Log
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import java.lang.ref.WeakReference
import java.util.concurrent.CountDownLatch

class Tab(val server: Server) {
    val transport = SshTransport(server, Sessions)
    val session = TerminalSession(transport, 5000, Sessions).apply { mSessionName = server.name }
}

/** Process-wide list of open tabs. Sessions outlive activities; the foreground service keeps the process alive. */
object Sessions : TerminalSessionClient, Prompter {

    interface Listener {
        fun onScreenChanged(session: TerminalSession)
        fun onTabsChanged()
    }

    val tabs = mutableListOf<Tab>()
    var current: Tab? = null

    var listener: Listener? = null
    private var activity = WeakReference<Activity>(null)
    private val main = Handler(Looper.getMainLooper())

    fun attach(activity: Activity, listener: Listener) {
        this.activity = WeakReference(activity)
        this.listener = listener
    }

    fun detach(listener: Listener) {
        if (this.listener === listener) {
            this.listener = null
            activity = WeakReference(null)
        }
    }

    fun open(server: Server): Tab {
        val tab = Tab(server)
        tabs.add(tab)
        current = tab
        SshService.sync(App.instance)
        listener?.onTabsChanged()
        return tab
    }

    fun close(tab: Tab) {
        val index = tabs.indexOf(tab)
        if (index < 0) return
        tabs.removeAt(index)
        tab.session.finishIfRunning()
        if (current === tab) current = tabs.getOrNull(index) ?: tabs.lastOrNull()
        SshService.sync(App.instance)
        listener?.onTabsChanged()
    }

    fun closeAll() {
        tabs.toList().forEach { it.session.finishIfRunning() }
        tabs.clear()
        current = null
        SshService.sync(App.instance)
        listener?.onTabsChanged()
    }

    fun countFor(serverId: String) = tabs.count { it.server.id == serverId }

    private fun tabOf(session: TerminalSession) = tabs.find { it.session === session }

    // --- TerminalSessionClient ---

    override fun onTextChanged(changedSession: TerminalSession) {
        listener?.onScreenChanged(changedSession)
    }

    override fun onTitleChanged(changedSession: TerminalSession) {
        listener?.onTabsChanged()
    }

    override fun onSessionFinished(finishedSession: TerminalSession) {
        if (tabOf(finishedSession) != null) listener?.onTabsChanged()
    }

    override fun onCopyTextToClipboard(session: TerminalSession, text: String?) {
        if (text.isNullOrEmpty()) return
        val clipboard = App.instance.getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText("Terminal", text))
    }

    override fun onPasteTextFromClipboard(session: TerminalSession?) {
        val clipboard = App.instance.getSystemService(ClipboardManager::class.java)
        val text = clipboard.primaryClip?.getItemAt(0)?.coerceToText(App.instance)?.toString()
        if (!text.isNullOrEmpty()) session?.emulator?.paste(text)
    }

    override fun onBell(session: TerminalSession) = Unit

    override fun onColorsChanged(session: TerminalSession) {
        listener?.onScreenChanged(session)
    }

    override fun onTerminalCursorStateChange(state: Boolean) = Unit

    override fun getTerminalCursorStyle(): Int = TerminalEmulator.TERMINAL_CURSOR_STYLE_BAR

    override fun logError(tag: String?, message: String?) { Log.e(tag, message ?: "") }
    override fun logWarn(tag: String?, message: String?) { Log.w(tag, message ?: "") }
    override fun logInfo(tag: String?, message: String?) = Unit
    override fun logDebug(tag: String?, message: String?) = Unit
    override fun logVerbose(tag: String?, message: String?) = Unit
    override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) { Log.e(tag, message, e) }
    override fun logStackTrace(tag: String?, e: Exception?) { Log.e(tag, "", e) }

    // --- Prompter: blocking dialogs asked from the SSH threads ---

    override fun confirm(title: String, message: String, positive: String, danger: Boolean): Boolean {
        var result = false
        val latch = CountDownLatch(1)
        main.post {
            val a = activity.get()
            if (a == null || a.isFinishing) {
                latch.countDown()
                return@post
            }
            val dialog = AlertDialog.Builder(a)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton(positive) { _, _ -> result = true }
                .setNegativeButton("Annuler", null)
                .setOnDismissListener { latch.countDown() }
                .show()
            if (danger) dialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(a.getColor(R.color.error))
        }
        latch.await()
        return result
    }

    override fun askPassword(title: String): String? {
        var result: String? = null
        val latch = CountDownLatch(1)
        main.post {
            val a = activity.get()
            if (a == null || a.isFinishing) {
                latch.countDown()
                return@post
            }
            val input = EditText(a).apply {
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                typeface = Typeface.MONOSPACE
                setBackgroundResource(R.drawable.bg_field)
                setPadding(a.dp(12), a.dp(10), a.dp(12), a.dp(10))
            }
            val box = FrameLayout(a).apply {
                setPadding(a.dp(20), a.dp(8), a.dp(20), 0)
                addView(input)
            }
            AlertDialog.Builder(a)
                .setTitle(title)
                .setView(box)
                .setPositiveButton("Connexion") { _, _ -> result = input.text.toString() }
                .setNegativeButton("Annuler", null)
                .setOnDismissListener { latch.countDown() }
                .show()
            input.requestFocus()
        }
        latch.await()
        return result
    }

    fun toast(text: String) = main.post { Toast.makeText(App.instance, text, Toast.LENGTH_SHORT).show() }
}
