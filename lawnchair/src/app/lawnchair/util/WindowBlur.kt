package app.lawnchair.util

import android.content.Context
import android.view.View
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import app.lawnchair.wallpaper.WallpaperManagerCompat
import com.android.launcher3.Utilities
import java.util.function.Consumer

/**
 * Whether window blurs, like the blurred wallpaper behind the Today page or an open menu, are on.
 * The system turns them off with battery saver, for example, and then the glass that sits on the
 * blur is drawn in solid colors instead.
 */
fun isWindowBlurEnabled(context: Context): Boolean {
    if (!Utilities.ATLEAST_S) return false
    return try {
        context.getSystemService(WindowManager::class.java)?.isCrossWindowBlurEnabled == true
    } catch (_: Throwable) {
        false
    }
}

/**
 * Calls [onChange] with whether window blurs are on, right away and whenever that changes, until
 * the returned handle is closed.
 */
fun observeWindowBlur(context: Context, onChange: (Boolean) -> Unit): AutoCloseable {
    onChange(isWindowBlurEnabled(context))
    if (!Utilities.ATLEAST_S) return AutoCloseable {}
    val windowManager = context.getSystemService(WindowManager::class.java) ?: return AutoCloseable {}
    val listener = Consumer<Boolean> { onChange(it) }
    try {
        windowManager.addCrossWindowBlurEnabledListener(context.mainExecutor, listener)
    } catch (_: Throwable) {
        return AutoCloseable {}
    }
    return AutoCloseable {
        try {
            windowManager.removeCrossWindowBlurEnabledListener(listener)
        } catch (_: Throwable) {
        }
    }
}

/** Like [observeWindowBlur], while this view is attached to a window. */
fun View.observeWindowBlur(onChange: (Boolean) -> Unit) {
    var handle: AutoCloseable? = null
    val listener = object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(v: View) {
            handle?.close()
            handle = observeWindowBlur(context, onChange)
        }

        override fun onViewDetachedFromWindow(v: View) {
            handle?.close()
            handle = null
        }
    }
    addOnAttachStateChangeListener(listener)
    if (isAttachedToWindow) listener.onViewAttachedToWindow(this)
}

/** Whether window blurs are on, see [isWindowBlurEnabled]. */
@Composable
fun rememberWindowBlurEnabled(): State<Boolean> {
    val context = LocalContext.current
    val state = remember(context) { mutableStateOf(isWindowBlurEnabled(context)) }
    DisposableEffect(context) {
        val handle = observeWindowBlur(context) { state.value = it }
        onDispose { handle.close() }
    }
    return state
}

/** The wallpaper's main color, or `0` when it isn't known. */
fun wallpaperPrimaryColor(context: Context): Int =
    WallpaperManagerCompat.INSTANCE.get(context).wallpaperColors?.primaryColor ?: 0
