package com.nico7an.terminal

import android.annotation.SuppressLint
import android.content.Context
import android.util.AttributeSet
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.widget.LinearLayout
import android.widget.TextView
import com.termux.view.TerminalView

/** The two rows of keys a phone keyboard lacks. CTRL and ALT apply to the next key, from here or the keyboard. */
class ExtraKeysView(context: Context, attrs: AttributeSet?) : LinearLayout(context, attrs) {

    private class Key(val label: String, val keyCode: Int = 0, val char: Int = 0, val repeat: Boolean = false)

    private val rows = listOf(
        listOf(
            Key("ESC", KeyEvent.KEYCODE_ESCAPE), Key("/", char = '/'.code), Key("-", char = '-'.code),
            Key("HOME", KeyEvent.KEYCODE_MOVE_HOME), Key("↑", KeyEvent.KEYCODE_DPAD_UP, repeat = true),
            Key("END", KeyEvent.KEYCODE_MOVE_END), Key("PGUP", KeyEvent.KEYCODE_PAGE_UP, repeat = true),
        ),
        listOf(
            Key("TAB", KeyEvent.KEYCODE_TAB), Key(CTRL), Key(ALT),
            Key("←", KeyEvent.KEYCODE_DPAD_LEFT, repeat = true), Key("↓", KeyEvent.KEYCODE_DPAD_DOWN, repeat = true),
            Key("→", KeyEvent.KEYCODE_DPAD_RIGHT, repeat = true), Key("PGDN", KeyEvent.KEYCODE_PAGE_DOWN, repeat = true),
        ),
    )

    var terminalView: TerminalView? = null

    private var ctrl = false
    private var alt = false
    private lateinit var ctrlView: TextView
    private lateinit var altView: TextView

    init {
        orientation = VERTICAL
        setPadding(context.dp(2), context.dp(2), context.dp(2), context.dp(2))
        val font = context.resources.getFont(R.font.cascadia_mono)
        rows.forEach { row ->
            val line = LinearLayout(context).apply { orientation = HORIZONTAL }
            row.forEach { key ->
                val view = TextView(context).apply {
                    text = key.label
                    typeface = font
                    textSize = 13f
                    gravity = Gravity.CENTER
                    setTextColor(context.getColor(R.color.text_primary))
                    setBackgroundResource(R.drawable.bg_key)
                    isSoundEffectsEnabled = false
                }
                when (key.label) {
                    CTRL -> ctrlView = view
                    ALT -> altView = view
                }
                bind(view, key)
                line.addView(view, LayoutParams(0, context.dp(38), 1f))
            }
            addView(line, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun bind(view: TextView, key: Key) {
        val repeater = object : Runnable {
            override fun run() {
                send(key)
                view.postDelayed(this, 45)
            }
        }
        view.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.isPressed = true
                    v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    when (key.label) {
                        CTRL -> { ctrl = !ctrl; refresh() }
                        ALT -> { alt = !alt; refresh() }
                        else -> {
                            send(key)
                            if (key.repeat) v.postDelayed(repeater, 400)
                        }
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.isPressed = false
                    v.removeCallbacks(repeater)
                }
            }
            true
        }
    }

    private fun send(key: Key) {
        val view = terminalView ?: return
        if (key.char != 0) {
            view.inputCodePoint(TerminalView.KEY_EVENT_SOURCE_SOFT_KEYBOARD, key.char, false, false)
        } else {
            var meta = 0
            if (ctrl) meta = meta or KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
            if (alt) meta = meta or KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON
            view.onKeyDown(key.keyCode, KeyEvent(0, 0, KeyEvent.ACTION_DOWN, key.keyCode, 0, meta))
        }
        if (ctrl || alt) {
            ctrl = false
            alt = false
            refresh()
        }
    }

    /** Read by the terminal view for the next key typed: one-shot. */
    fun readCtrl(): Boolean = ctrl.also { if (it) { ctrl = false; refresh() } }

    fun readAlt(): Boolean = alt.also { if (it) { alt = false; refresh() } }

    private fun refresh() {
        style(ctrlView, ctrl)
        style(altView, alt)
    }

    private fun style(view: TextView, on: Boolean) {
        view.setBackgroundResource(if (on) R.drawable.bg_key_on else R.drawable.bg_key)
        view.setTextColor(context.getColor(if (on) R.color.on_accent else R.color.text_primary))
    }

    private companion object {
        const val CTRL = "CTRL"
        const val ALT = "ALT"
    }
}
