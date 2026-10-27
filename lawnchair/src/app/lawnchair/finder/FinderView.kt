package app.lawnchair.finder

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.util.Log
import android.view.MotionEvent
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.inputmethod.InputMethodManager
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import app.lawnchair.LawnchairLauncher
import app.lawnchair.ui.theme.LawnchairTheme
import app.lawnchair.util.ProvideLifecycleState
import com.android.app.animation.Interpolators
import com.android.launcher3.AbstractFloatingView
import com.android.launcher3.Utilities
import com.android.launcher3.views.BaseDragLayer

/**
 * An iOS Spotlight style universal search, pulled down from the home screen. The home screen
 * behind it is blurred with the same depth blur as an open folder.
 *
 * It follows the finger while being pulled down ([setDragProgress]), then [settle]s open or
 * closed.
 */
class FinderView(private val launcher: LawnchairLauncher) : AbstractFloatingView(launcher, null) {

    private val composeView = ComposeView(launcher)
    private val search = FinderSearch(launcher)

    // 0 is hidden, 1 is fully open.
    private val progressState = mutableFloatStateOf(0f)
    private var progress: Float
        get() = progressState.floatValue
        set(value) {
            progressState.floatValue = value
            launcher.depthController?.folderDepth?.value = value
        }

    // Bumped to ask the content to focus the search field and show the keyboard.
    private val focusRequests = mutableIntStateOf(0)

    private var animator: ValueAnimator? = null
    private var closing = false

    init {
        layoutParams = BaseDragLayer.LayoutParams(MATCH_PARENT, MATCH_PARENT).apply { ignoreInsets = true }
        composeView.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
        composeView.setContent {
            LawnchairTheme {
                ProvideLifecycleState {
                    FinderContent(
                        progress = { progressState.floatValue },
                        focusRequests = focusRequests.intValue,
                        isDark = Utilities.isDarkTheme(launcher),
                        search = search,
                        onOpen = ::open,
                        onDismiss = { close(true) },
                        onScrolled = ::hideKeyboard,
                    )
                }
            }
        }
        addView(composeView, LayoutParams(MATCH_PARENT, MATCH_PARENT))
        search.loadSuggestions(SUGGESTION_COUNT)
    }

    private fun attach() {
        mIsOpen = true
        launcher.dragLayer.addView(this)
    }

    val dragProgress: Float get() = progress

    /** Sets the progress while the finder is being pulled down. */
    fun setDragProgress(value: Float) {
        animator?.cancel()
        progress = value.coerceIn(0f, 1f)
    }

    /** Animates open (and shows the keyboard), or closed, after a drag ends. */
    fun settle(open: Boolean, velocity: Float = 0f) {
        if (open) animateTo(1f, velocity) { focusRequests.intValue++ } else close(true)
    }

    private fun animateTo(target: Float, velocity: Float = 0f, onEnd: () -> Unit) {
        animator?.cancel()
        val distance = kotlin.math.abs(target - progress)
        animator = ValueAnimator.ofFloat(progress, target).apply {
            duration = (OPEN_DURATION_MS * distance.coerceIn(0.35f, 1f) / (1f + kotlin.math.abs(velocity))).toLong()
                .coerceAtLeast(120)
            interpolator = if (target > progress) Interpolators.DECELERATE_3 else Interpolators.ACCELERATE_DECELERATE
            addUpdateListener { progress = it.animatedValue as Float }
            addListener(object : AnimatorListenerAdapter() {
                private var cancelled = false
                override fun onAnimationCancel(animation: Animator) {
                    cancelled = true
                }

                override fun onAnimationEnd(animation: Animator) {
                    if (!cancelled) onEnd()
                }
            })
            start()
        }
    }

    override fun handleClose(animate: Boolean) {
        if (closing) return
        closing = true
        hideKeyboard()
        if (animate) {
            animateTo(0f) { removeFromParent() }
        } else {
            animator?.cancel()
            removeFromParent()
        }
    }

    private fun removeFromParent() {
        progress = 0f
        search.destroy()
        (parent as? ViewGroup)?.removeView(this)
    }

    private fun hideKeyboard() {
        val imm = launcher.getSystemService(InputMethodManager::class.java)
        imm?.hideSoftInputFromWindow(windowToken, 0)
        composeView.clearFocus()
    }

    private fun open(item: FinderItem) {
        when (item) {
            is FinderItem.App -> {
                launcher.startActivitySafely(this, item.app.intent, item.app)
                close(false)
            }

            is FinderItem.Calculation -> copy(item.result)
            is FinderItem.Converted -> copy(item.conversion.lines.first().value)
            is FinderItem.Setting -> start(item.entry.intent)
            is FinderItem.Contact -> start(item.intent)
            is FinderItem.File -> start(item.intent)
            is FinderItem.Web -> start(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(item.url)))
        }
    }

    private fun copy(text: String) {
        val clipboard = launcher.getSystemService(ClipboardManager::class.java)
        clipboard?.setPrimaryClip(ClipData.newPlainText(text, text))
        close(true)
    }

    private fun start(intent: Intent) {
        try {
            launcher.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            close(false)
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't open $intent", e)
        }
    }

    override fun onControllerInterceptTouchEvent(ev: MotionEvent?) = false

    override fun isOfType(type: Int) = type and TYPE_COMPOSE_VIEW != 0

    companion object {
        private const val TAG = "FinderView"
        private const val OPEN_DURATION_MS = 380f
        private const val SUGGESTION_COUNT = 8

        /** Shows the finder, either at [progress] 0 for a drag, or animating fully open. */
        fun show(launcher: LawnchairLauncher, animate: Boolean): FinderView {
            closeAllOpenViews(launcher, false)
            val view = FinderView(launcher)
            view.attach()
            if (animate) view.settle(open = true)
            return view
        }
    }
}
