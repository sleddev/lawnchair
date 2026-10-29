package app.lawnchair.finder

import android.view.MotionEvent
import android.view.ViewConfiguration
import androidx.lifecycle.lifecycleScope
import app.lawnchair.LawnchairLauncher
import app.lawnchair.gestures.HorizontalSwipeFilter
import app.lawnchair.gestures.config.GestureHandlerConfig
import app.lawnchair.preferences2.PreferenceManager2
import com.android.launcher3.AbstractFloatingView
import com.android.launcher3.LauncherState
import com.android.launcher3.Utilities
import com.android.launcher3.touch.SingleAxisSwipeDetector
import com.android.launcher3.util.TouchController
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

/**
 * Pulls the finder down from the home screen, following the finger like Spotlight, when the
 * swipe down gesture is set to open it.
 */
class FinderTouchController(private val launcher: LawnchairLauncher) :
    TouchController,
    SingleAxisSwipeDetector.Listener {

    private val detector = SingleAxisSwipeDetector(launcher, this, SingleAxisSwipeDetector.VERTICAL).apply {
        // A bit more travel before grabbing, so a sideways swipe has time to show it's one.
        setTouchSlopMultiplier(TOUCH_SLOP_MULTIPLIER)
    }
    private val dragDistance = launcher.resources.displayMetrics.density * DRAG_DISTANCE_DP
    private val touchSlop = ViewConfiguration.get(launcher).scaledTouchSlop.toFloat()

    private var enabled = false
    private var noIntercept = false
    private var downX = 0f
    private var downY = 0f
    private var finder: FinderView? = null

    init {
        PreferenceManager2.getInstance(launcher).swipeDownGestureHandler.get()
            .onEach { enabled = it is GestureHandlerConfig.OpenFinder }
            .launchIn(launcher.lifecycleScope)
    }

    override fun onControllerInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            noIntercept = !canInterceptTouch(ev)
            if (noIntercept) return false
            detector.setDetectableScrollConditions(SingleAxisSwipeDetector.DIRECTION_NEGATIVE, false)
            downX = ev.x
            downY = ev.y
        }
        if (noIntercept) return false
        if (ev.actionMasked == MotionEvent.ACTION_MOVE && !detector.isDraggingOrSettling &&
            HorizontalSwipeFilter.isHorizontalSwipe(ev.x - downX, ev.y - downY, touchSlop / 2f)
        ) {
            // A sideways swipe that drifts down belongs to the workspace. The detector alone takes
            // anything steeper than 45°, which caught diagonal page swipes.
            noIntercept = true
            return false
        }
        onControllerTouchEvent(ev)
        return detector.isDraggingOrSettling
    }

    override fun onControllerTouchEvent(ev: MotionEvent): Boolean {
        // Two finger swipes stay with the regular gesture handlers.
        if (ev.pointerCount > 1 && finder == null) return false
        return detector.onTouchEvent(ev)
    }

    private fun canInterceptTouch(ev: MotionEvent): Boolean {
        if (!enabled || ev.pointerCount > 1) return false
        if ((ev.edgeFlags and Utilities.EDGE_NAV_BAR) != 0) return false
        val isIconSwipe = launcher.workspace?.let {
            val coord = floatArrayOf(ev.x, ev.y)
            launcher.dragLayer.mapCoordInSelfToDescendant(it, coord)
            it.isTouchOnIconWithSwipeGesture(coord[0], coord[1], true)
        } ?: false
        if (isIconSwipe) return false
        return AbstractFloatingView.getTopOpenView(launcher) == null &&
            launcher.isInState(LauncherState.NORMAL) &&
            !launcher.stateManager.isInTransition
    }

    override fun onDragStart(start: Boolean, startDisplacement: Float) {
        if (finder == null) {
            finder = FinderView.show(launcher, animate = false)
        }
    }

    override fun onDrag(displacement: Float): Boolean {
        finder?.setDragProgress(displacement / dragDistance)
        return true
    }

    override fun onDragEnd(velocity: Float) {
        val view = finder
        finder = null
        detector.finishedScrolling()
        if (view == null) return
        // Velocity is in px/ms, positive downwards.
        val open = when {
            velocity > FLING_VELOCITY -> true
            velocity < -FLING_VELOCITY -> false
            else -> view.isOpen && view.dragProgress > OPEN_THRESHOLD
        }
        view.settle(open, (velocity / FLING_VELOCITY).coerceIn(0f, 2f))
    }

    companion object {
        private const val DRAG_DISTANCE_DP = 180f
        private const val OPEN_THRESHOLD = 0.4f
        private const val FLING_VELOCITY = 0.6f
        private const val TOUCH_SLOP_MULTIPLIER = 1.5f
    }
}
