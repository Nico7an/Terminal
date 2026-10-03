package com.nico7an.terminal

import android.content.Context
import android.os.Build
import android.view.View
import android.view.WindowInsets

fun Context.dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

/** French quotes never end up alone at the end of a line. */
fun String.nbsp(): String = replace("« ", "«\u00A0").replace(" »", "\u00A0»")

/**
 * Edge-to-edge is enforced from Android 15: pad the root view with the system bars, the display
 * cutout and the keyboard so nothing is drawn under them.
 */
fun View.applySystemInsets() {
    setOnApplyWindowInsetsListener { v, insets ->
        if (Build.VERSION.SDK_INT >= 30) {
            val bars = insets.getInsets(
                WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout() or WindowInsets.Type.ime()
            )
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
        } else {
            @Suppress("DEPRECATION")
            v.setPadding(
                insets.systemWindowInsetLeft, insets.systemWindowInsetTop,
                insets.systemWindowInsetRight, insets.systemWindowInsetBottom,
            )
        }
        @Suppress("DEPRECATION")
        if (Build.VERSION.SDK_INT >= 30) WindowInsets.CONSUMED else insets.consumeSystemWindowInsets()
    }
}

/** Draw behind the system bars on every Android version, insets are applied by [applySystemInsets]. */
fun android.app.Activity.edgeToEdge(root: View) {
    if (Build.VERSION.SDK_INT >= 30) {
        window.setDecorFitsSystemWindows(false)
    } else {
        @Suppress("DEPRECATION")
        root.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
    }
    root.applySystemInsets()
}
