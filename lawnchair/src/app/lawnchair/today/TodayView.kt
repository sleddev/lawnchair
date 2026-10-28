package app.lawnchair.today

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.util.Log
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.ViewConfiguration
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.widget.FrameLayout
import android.window.BackEvent
import androidx.compose.animation.core.AnimationState
import androidx.compose.animation.core.animateDecay
import androidx.compose.animation.splineBasedDecay
import androidx.compose.foundation.ScrollState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.platform.AndroidUiDispatcher
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.Density
import androidx.lifecycle.lifecycleScope
import app.lawnchair.LawnchairLauncher
import app.lawnchair.smartspace.model.SmartspaceAction
import app.lawnchair.ui.theme.Glass
import app.lawnchair.ui.theme.LawnchairTheme
import app.lawnchair.util.ProvideLifecycleState
import com.android.launcher3.InsettableFrameLayout
import com.android.launcher3.Utilities
import com.android.launcher3.util.BackPressHandler
import kotlin.math.abs
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * The Today page itself. It sits above the drag layer in the launcher's root view and slides in
 * from the left as [progress] goes from 0 to 1, while the wallpaper behind it blurs.
 *
 * It arbitrates its own touches: a horizontal swipe back towards home closes it, a vertical drag
 * scrolls the page (even when it starts on an app widget), and a long press enters edit mode.
 */
@SuppressLint("ViewConstructor")
class TodayView(
    private val launcher: LawnchairLauncher,
    private val overlay: TodayOverlay,
    repository: TodayRepository,
    private val widgets: TodayWidgets,
) : FrameLayout(launcher),
    TodayHost {

    private val composeView = ComposeView(launcher)
    private val state = TodayPageState(repository.items, ScrollState(0))
    private val refreshState = mutableIntStateOf(0)
    override val refreshKey: Int get() = refreshState.intValue

    private val isRtl = Utilities.isRtl(resources)
    private val isDark = Utilities.isDarkTheme(launcher)

    var progress = 0f
        private set

    /**
     * Dims the blurred wallpaper. It sits behind the page and stays put while the page slides, so
     * it only fades in, like the blur, instead of sliding in with a hard edge. It's darker at the
     * top, so the white date header stays readable on a light wallpaper.
     */
    val scrim = android.view.View(launcher).apply {
        layoutParams = InsettableFrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT).apply { ignoreInsets = true }
        background = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(Glass.scrimTop(isDark), Glass.scrim(isDark), Glass.scrim(isDark)),
        )
        alpha = 0f
        visibility = GONE
    }

    init {
        layoutParams = InsettableFrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT).apply { ignoreInsets = true }
        visibility = GONE
        composeView.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
        composeView.setContent {
            LawnchairTheme {
                ProvideLifecycleState {
                    TodayContent(state, this@TodayView, widgets, repository, isDark)
                }
            }
        }
        addView(composeView, LayoutParams(MATCH_PARENT, MATCH_PARENT))
        widgets.onLongPress = ::enterEditMode
    }

    fun setProgress(value: Float) {
        val wasHidden = progress == 0f
        progress = value
        state.progress.floatValue = value
        if (value > 0f && wasHidden) refresh()
        visibility = if (value > 0f) VISIBLE else GONE
        val width = width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
        translationX = (if (isRtl) 1 else -1) * width * (1f - value)
        scrim.alpha = value
        scrim.visibility = visibility
        launcher.depthController?.folderDepth?.value = value
        if (value == 0f) {
            state.editing.value = false
            state.addSheetOpen.value = false
            flingJob?.cancel()
            launch { state.scrollState.scrollTo(0) }
        }
    }

    fun refresh() {
        refreshState.intValue++
    }

    private fun enterEditMode() {
        if (!state.editing.value) {
            state.editing.value = true
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        }
    }

    // ---- TodayHost ----

    override fun startIntent(intent: Intent) {
        if (state.editing.value) return
        try {
            launcher.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't open $intent", e)
        }
    }

    override fun requestPermissions(permissions: Array<String>) {
        if (state.editing.value) return
        launcher.requestPermissions(permissions, REQUEST_PERMISSIONS)
    }

    override fun sendAction(action: SmartspaceAction) {
        if (state.editing.value) return
        runCatching {
            when {
                action.pendingIntent != null -> action.pendingIntent.send()
                action.intent != null -> launcher.startActivity(action.intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                else -> action.onClick?.run()
            }
        }.onFailure { Log.w(TAG, "Couldn't run $action", it) }
    }

    // ---- Back ----

    val backHandler = object : BackPressHandler {
        override fun canHandleBack() = progress > 0f

        override fun onBackInvoked() {
            when {
                state.addSheetOpen.value -> state.addSheetOpen.value = false
                state.editing.value -> state.editing.value = false
                else -> overlay.hideOverlay(true)
            }
        }

        override fun onBackStarted(backEvent: BackEvent) = Unit
        override fun onBackProgressed(backEvent: BackEvent) = Unit
        override fun onBackCancelled() = Unit
    }

    // ---- Touch ----

    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private val minFling = ViewConfiguration.get(context).scaledMinimumFlingVelocity
    private var mode = Mode.None
    private var downX = 0f
    private var downY = 0f
    private var lastY = 0f
    private var velocityTracker: VelocityTracker? = null
    private var flingJob: Job? = null
    private val longPressRunnable = Runnable {
        if (mode == Mode.None) {
            enterEditMode()
            mode = Mode.Consumed
        }
    }

    private enum class Mode { None, Close, Scroll, Ignore, Consumed }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean = track(ev)

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(ev: MotionEvent): Boolean {
        // A down no child took was already seen by onInterceptTouchEvent.
        if (ev.actionMasked != MotionEvent.ACTION_DOWN) track(ev)
        return true
    }

    /**
     * Follows the gesture, and returns true once this view should take it from its children.
     *
     * It works in screen coordinates: this view moves while it's dragged closed, so its own
     * coordinates would shift under the finger and make the drag jitter back and forth.
     */
    private fun track(ev: MotionEvent): Boolean {
        if (velocityTracker == null) velocityTracker = VelocityTracker.obtain()
        val screenEvent = MotionEvent.obtain(ev).apply { setLocation(ev.rawX, ev.rawY) }
        velocityTracker?.addMovement(screenEvent)
        screenEvent.recycle()
        val x = ev.rawX
        val y = ev.rawY
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = x
                downY = y
                lastY = y
                mode = Mode.None
                flingJob?.cancel()
                if (!state.editing.value && !state.addSheetOpen.value) {
                    postDelayed(longPressRunnable, ViewConfiguration.getLongPressTimeout().toLong())
                }
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = x - downX
                val dy = y - downY
                if (mode == Mode.None && (abs(dx) > slop || abs(dy) > slop)) {
                    removeCallbacks(longPressRunnable)
                    val towardsHome = if (isRtl) dx > 0 else dx < 0
                    mode = when {
                        state.addSheetOpen.value || state.touchingItem -> Mode.Ignore
                        abs(dx) > abs(dy) && towardsHome && !state.editing.value -> Mode.Close
                        abs(dy) > abs(dx) -> Mode.Scroll
                        else -> Mode.Ignore
                    }
                    lastY = y
                    if (mode == Mode.Close) overlay.onPageDragStart()
                }
                when (mode) {
                    Mode.Close -> overlay.onPageDrag(1f - abs(dx) / width.coerceAtLeast(1))
                    Mode.Scroll -> {
                        state.scrollState.dispatchRawDelta(lastY - y)
                        lastY = y
                    }

                    else -> Unit
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(longPressRunnable)
                velocityTracker?.computeCurrentVelocity(1000)
                val vx = velocityTracker?.xVelocity ?: 0f
                val vy = velocityTracker?.yVelocity ?: 0f
                when (mode) {
                    // Positive velocity means opening.
                    Mode.Close -> overlay.onPageDragEnd(if (isRtl) -vx else vx)
                    Mode.Scroll -> if (abs(vy) > minFling) fling(-vy)
                    else -> Unit
                }
                velocityTracker?.recycle()
                velocityTracker = null
                val took = mode == Mode.Close || mode == Mode.Scroll || mode == Mode.Consumed
                mode = Mode.None
                return took
            }
        }
        return mode == Mode.Close || mode == Mode.Scroll || mode == Mode.Consumed
    }

    private fun fling(velocity: Float) {
        flingJob?.cancel()
        flingJob = launch {
            state.scrollState.scroll {
                var last = 0f
                AnimationState(0f, velocity).animateDecay(splineBasedDecay(Density(context))) {
                    val delta = value - last
                    val consumed = scrollBy(delta)
                    last = value
                    // Stop at either end.
                    if (abs(delta - consumed) > 0.5f) cancelAnimation()
                }
            }
        }
    }

    // Compose's dispatcher brings the frame clock that scroll animations need.
    private fun launch(block: suspend () -> Unit): Job =
        launcher.lifecycleScope.launch(AndroidUiDispatcher.Main) { block() }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        removeCallbacks(longPressRunnable)
    }

    companion object {
        private const val TAG = "TodayView"
        const val REQUEST_PERMISSIONS = 0x7D03
    }
}
