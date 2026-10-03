package com.nico7an.terminal

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import android.window.OnBackInvokedDispatcher
import com.termux.terminal.TerminalSession
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient

class TerminalActivity : Activity(), Sessions.Listener, TerminalViewClient {

    private lateinit var terminal: TerminalView
    private lateinit var tabsView: LinearLayout
    private lateinit var tabsScroll: HorizontalScrollView
    private lateinit var extraKeys: ExtraKeysView
    private lateinit var disconnectedBar: View
    private lateinit var disconnectedText: TextView

    private val prefs by lazy { getSharedPreferences("ui", Context.MODE_PRIVATE) }
    private var fontSize = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_terminal)
        edgeToEdge(findViewById(R.id.root))

        terminal = findViewById(R.id.terminal)
        tabsView = findViewById(R.id.tabs)
        tabsScroll = findViewById(R.id.tabs_scroll)
        extraKeys = findViewById(R.id.extra_keys)
        disconnectedBar = findViewById(R.id.disconnected)
        disconnectedText = findViewById(R.id.disconnected_text)

        fontSize = prefs.getInt("fontSize", dp(12))
        terminal.setTerminalViewClient(this)
        terminal.setTextSize(fontSize)
        terminal.setTypeface(resources.getFont(R.font.cascadia_mono))
        terminal.keepScreenOn = false
        extraKeys.terminalView = terminal

        findViewById<View>(R.id.new_tab).setOnClickListener {
            startActivity(Intent(this, ServerListActivity::class.java).putExtra(ServerListActivity.EXTRA_PICK, true))
        }
        findViewById<View>(R.id.reconnect).setOnClickListener { Sessions.current?.session?.reconnect(); updateStatus() }
        findViewById<View>(R.id.close_tab).setOnClickListener { Sessions.current?.let { Sessions.close(it) } }

        if (Build.VERSION.SDK_INT >= 33) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT) {
                moveTaskToBack(true)
            }
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
                !prefs.getBoolean("askedNotifications", false)
            ) {
                prefs.edit().putBoolean("askedNotifications", true).apply()
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 0)
            }
        }
        updateExtraKeysVisibility(resources.configuration)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        moveTaskToBack(true)
    }

    override fun onStart() {
        super.onStart()
        Sessions.attach(this, this)
        onTabsChanged()
    }

    override fun onStop() {
        super.onStop()
        Sessions.detach(this)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        onTabsChanged()
        showKeyboard()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        updateExtraKeysVisibility(newConfig)
    }

    /** No extra keys row when a physical keyboard is plugged in (tablets). */
    private fun updateExtraKeysVisibility(config: Configuration) {
        val hardware = config.keyboard == Configuration.KEYBOARD_QWERTY &&
            config.hardKeyboardHidden == Configuration.HARDKEYBOARDHIDDEN_NO
        extraKeys.visibility = if (hardware) View.GONE else View.VISIBLE
    }

    // --- Tabs ---

    override fun onTabsChanged() {
        val tabs = Sessions.tabs
        if (tabs.isEmpty()) {
            if (!isFinishing) {
                startActivity(Intent(this, ServerListActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))
                finish()
            }
            return
        }
        val current = Sessions.current ?: tabs.last().also { Sessions.current = it }
        if (terminal.currentSession !== current.session) {
            terminal.attachSession(current.session)
        }
        renderTabs()
        updateStatus()
        terminal.onScreenUpdated()
    }

    private fun renderTabs() {
        tabsView.removeAllViews()
        var selectedView: View? = null
        for (tab in Sessions.tabs) {
            val view = layoutInflater.inflate(R.layout.item_tab, tabsView, false)
            val selected = tab === Sessions.current
            view.setBackgroundResource(if (selected) R.drawable.bg_tab_active else R.drawable.bg_tab_inactive)
            view.findViewById<TextView>(R.id.label).apply {
                text = tab.server.name
                setTextColor(getColor(if (selected) R.color.text_primary else R.color.text_secondary))
            }
            paintDot(view.findViewById(R.id.dot), tab.session)
            view.findViewById<View>(R.id.close).setOnClickListener { Sessions.close(tab) }
            view.setOnClickListener { select(tab) }
            tabsView.addView(view)
            if (selected) selectedView = view
        }
        selectedView?.let { v ->
            tabsScroll.post {
                // The tabs may have been rebuilt in the meantime.
                if (v.parent !== tabsView) return@post
                tabsScroll.requestChildFocus(tabsView, v)
                tabsScroll.smoothScrollTo(v.left - dp(24), 0)
            }
        }
    }

    private fun select(tab: Tab) {
        if (Sessions.current === tab) return
        Sessions.current = tab
        onTabsChanged()
    }

    private fun updateStatus() {
        val session = Sessions.current?.session ?: return
        val down = !session.isRunning && !session.isConnecting
        disconnectedBar.visibility = if (down) View.VISIBLE else View.GONE
        if (down) disconnectedText.text = "${Sessions.current?.server?.name} — déconnecté"
    }

    override fun onScreenChanged(session: TerminalSession) {
        if (session === terminal.currentSession) terminal.onScreenUpdated()
        // Connection state changes come with output (status lines), refresh the dots cheaply.
        val tab = Sessions.tabs.indexOfFirst { it.session === session }
        if (tab >= 0 && tab < tabsView.childCount) paintDot(tabsView.getChildAt(tab).findViewById(R.id.dot), session)
        if (session === terminal.currentSession) updateStatus()
    }

    /** Green connected, yellow connecting, red down. Only touches the view when the state changes. */
    private fun paintDot(dot: View, session: TerminalSession) {
        val color = when {
            session.isRunning -> R.color.ok
            session.isConnecting -> R.color.warn
            else -> R.color.error
        }
        if (dot.tag == color) return
        dot.tag = color
        dot.background = dot.background.mutate().apply { setTint(getColor(color)) }
    }

    private fun showKeyboard() {
        terminal.requestFocus()
        getSystemService(InputMethodManager::class.java).showSoftInput(terminal, 0)
    }

    // --- TerminalViewClient ---

    override fun onScale(scale: Float): Float {
        if (scale < 0.9f || scale > 1.1f) {
            val next = (fontSize + if (scale > 1f) dp(1) else -dp(1)).coerceIn(dp(7), dp(28))
            if (next != fontSize) {
                fontSize = next
                terminal.setTextSize(fontSize)
                prefs.edit().putInt("fontSize", fontSize).apply()
            }
            return 1f
        }
        return scale
    }

    override fun onSingleTapUp(e: MotionEvent) = showKeyboard()

    override fun shouldBackButtonBeMappedToEscape() = false
    override fun shouldEnforceCharBasedInput() = true
    override fun shouldUseCtrlSpaceWorkaround() = false
    override fun isTerminalViewSelected() = true
    override fun copyModeChanged(copyMode: Boolean) = Unit

    override fun onKeyDown(keyCode: Int, e: KeyEvent, session: TerminalSession): Boolean {
        // Enter on a dead session reconnects, like the button.
        if (!session.isRunning && !session.isConnecting &&
            (keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER)
        ) {
            session.reconnect()
            updateStatus()
            return true
        }
        // Ctrl+Shift+T / Ctrl+Shift+W on a hardware keyboard, like Windows Terminal.
        if (e.isCtrlPressed && e.isShiftPressed) {
            when (keyCode) {
                KeyEvent.KEYCODE_T -> { findViewById<View>(R.id.new_tab).performClick(); return true }
                KeyEvent.KEYCODE_W -> { Sessions.current?.let { Sessions.close(it) }; return true }
                KeyEvent.KEYCODE_TAB -> { cycle(-1); return true }
            }
        }
        if (e.isCtrlPressed && keyCode == KeyEvent.KEYCODE_TAB) {
            cycle(1)
            return true
        }
        return false
    }

    private fun cycle(delta: Int) {
        val tabs = Sessions.tabs
        if (tabs.size < 2) return
        val i = tabs.indexOf(Sessions.current)
        select(tabs[(i + delta + tabs.size) % tabs.size])
    }

    override fun onKeyUp(keyCode: Int, e: KeyEvent) = false
    override fun onLongPress(event: MotionEvent) = false
    override fun readControlKey() = extraKeys.readCtrl()
    override fun readAltKey() = extraKeys.readAlt()
    override fun readShiftKey() = false
    override fun readFnKey() = false
    override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession) = false
    override fun onEmulatorSet() = Unit

    override fun logError(tag: String?, message: String?) = Unit
    override fun logWarn(tag: String?, message: String?) = Unit
    override fun logInfo(tag: String?, message: String?) = Unit
    override fun logDebug(tag: String?, message: String?) = Unit
    override fun logVerbose(tag: String?, message: String?) = Unit
    override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) = Unit
    override fun logStackTrace(tag: String?, e: Exception?) = Unit
}
