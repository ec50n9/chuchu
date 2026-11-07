package io.github.ec50n9.roost.ui.components

import android.app.Activity
import android.view.inputmethod.InputMethodManager
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import kotlinx.coroutines.delay

/**
 * Keeps the keyboard closed on a screen that has nothing to type into.
 *
 * The IME this app runs against does not retract when the terminal's editor goes away: `dumpsys
 * input_method` shows it served by nothing and still showing, and a hide asked from the terminal
 * screen while that screen is up is ignored. A hide asked from a screen that keeps the focus does
 * work, so screens that end up in front of a dead terminal (the session list, the ended-session
 * view) ask for as long as the IME insists on being visible.
 */
@Composable
fun KeepImeClosed() {
    val view = LocalView.current
    val context = LocalContext.current
    val imeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    LaunchedEffect(imeVisible) {
        if (!imeVisible) return@LaunchedEffect
        val window = (view.context as? Activity)?.window ?: return@LaunchedEffect
        val imm = context.getSystemService(InputMethodManager::class.java)
        repeat(3) { attempt ->
            if (attempt > 0) delay(120)
            imm?.hideSoftInputFromWindow(view.windowToken, 0)
            WindowCompat.getInsetsController(window, view).hide(WindowInsetsCompat.Type.ime())
        }
    }
}
