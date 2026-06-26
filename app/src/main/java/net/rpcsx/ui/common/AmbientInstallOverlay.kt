package net.rpcsx.ui.common

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import net.rpcsx.GameProgressType
import net.rpcsx.GameRepository

private fun Context.findActivity(): Activity? {
    var ctx: Context? = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}

/**
 * Keep the screen awake for as long as an install/firmware-install is running, WITHOUT dimming.
 * This is what the library screen (MainActivity) shows during install: installs finish in a few
 * seconds, so a screensaver there is pointless and (when the display slept) made the install look
 * hung. We only hold FLAG_KEEP_SCREEN_ON; we never dim and never draw anything.
 *
 * SAFETY: a window flag toggle only; cannot touch the install logic (core/PrecompilerService).
 * The flag is always cleared on dispose and when no install progress remains.
 */
@Composable
fun KeepScreenOnDuringInstall() {
    val installing: Boolean by remember {
        derivedStateOf {
            GameRepository.games.any { game ->
                game.findProgress(arrayOf(GameProgressType.Install)) != null
            }
        }
    }

    val context = LocalContext.current
    val window = remember(context) { context.findActivity()?.window }

    DisposableEffect(window, installing) {
        if (window != null && installing) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }
}

/**
 * Library-screen (MainActivity) overlay. Only keeps the screen on while installing so the install
 * never looks hung; it never dims or draws anything. (The boot screensaver was removed entirely.)
 */
@Composable
fun AmbientInstallOverlay() {
    KeepScreenOnDuringInstall()
}
