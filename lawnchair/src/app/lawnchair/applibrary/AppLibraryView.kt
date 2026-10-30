package app.lawnchair.applibrary

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.LauncherApps
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.util.Log
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.window.BackEvent
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import app.lawnchair.LawnchairLauncher
import app.lawnchair.preferences2.PreferenceManager2
import app.lawnchair.preferences2.firstCached
import app.lawnchair.ui.theme.Glass
import app.lawnchair.ui.theme.LawnchairTheme
import app.lawnchair.util.ProvideLifecycleState
import app.lawnchair.util.observeWindowBlur
import app.lawnchair.util.wallpaperPrimaryColor
import com.android.launcher3.BubbleTextView
import com.android.launcher3.DragSource
import com.android.launcher3.DropTarget
import com.android.launcher3.InsettableFrameLayout
import com.android.launcher3.R
import com.android.launcher3.Utilities
import com.android.launcher3.dragndrop.DragController
import com.android.launcher3.dragndrop.DragOptions
import com.android.launcher3.graphics.DragPreviewProvider
import com.android.launcher3.model.ItemInstallQueue
import com.android.launcher3.model.data.AppInfo
import com.android.launcher3.util.BackPressHandler
import com.android.launcher3.views.BaseDragLayer
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * The App Library page. Like the Today page it sits above the drag layer in the launcher's root
 * view, and slides in, here from the right, as [setProgress] goes from 0 to 1 while the wallpaper
 * behind it blurs.
 *
 * Compose handles scrolling and taps. This view takes a horizontal swipe back towards home, and
 * when a long pressed app is dragged, it hands the gesture to the launcher's drag and drop so the
 * app can be dropped on the home screen.
 */
@SuppressLint("ViewConstructor")
class AppLibraryView(
    private val launcher: LawnchairLauncher,
    private val overlay: AppLibraryOverlay,
    repository: AppLibraryRepository,
) : FrameLayout(launcher),
    AppLibraryHost,
    DragSource {

    private val composeView = ComposeView(launcher)
    private val state = AppLibraryUiState()
    private val isRtl = Utilities.isRtl(resources)
    private val isDark = Utilities.isDarkTheme(launcher)

    var progress = 0f
        private set

    /** Dims the blurred wallpaper, fading in rather than sliding in with the page. */
    val scrim = View(launcher).apply {
        layoutParams = InsettableFrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT).apply { ignoreInsets = true }
        val gradient = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, null)
        background = gradient
        // Solid when there's no blur under it.
        observeWindowBlur { blurred ->
            gradient.colors = Glass.scrimColors(isDark, blurred, wallpaperPrimaryColor(launcher))
        }
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
                    AppLibraryContent(state, repository, this@AppLibraryView, isDark)
                }
            }
        }
        addView(composeView, LayoutParams(MATCH_PARENT, MATCH_PARENT))
    }

    fun setProgress(value: Float) {
        progress = value
        state.progress.floatValue = value
        visibility = if (value > 0f) VISIBLE else GONE
        val width = width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
        translationX = (if (isRtl) -1 else 1) * width * (1f - value)
        if (fadeAnimator == null) {
            alpha = 1f
            scrim.alpha = value
            launcher.depthController?.folderDepth?.value = value
        }
        scrim.visibility = visibility
        if (value == 0f) {
            hideKeyboard()
            state.reset()
        }
    }

    // ---- AppLibraryHost ----

    override fun launch(app: AppInfo) {
        if (state.menu.value != null) return
        hideKeyboard()
        launcher.startActivitySafely(this, app.intent, app)
    }

    override fun onAppLongPress(app: AppInfo, iconBounds: Rect) {
        if (iconBounds.isEmpty) return
        hideKeyboard()
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        state.menu.value = AppMenu(app, Rect(iconBounds))
        pendingDrag = PendingDrag(app, Rect(iconBounds), lastRawX, lastRawY)
    }

    override fun addToHome(app: AppInfo) {
        state.menu.value = null
        ItemInstallQueue.INSTANCE.get(launcher).queueItem(app.targetPackage, app.user)
        overlay.hide(true)
    }

    override fun showAppInfo(app: AppInfo) {
        state.menu.value = null
        runCatching {
            launcher.getSystemService(LauncherApps::class.java)
                .startAppDetailsActivity(app.componentName, app.user, null, null)
        }.onFailure { Log.w(TAG, "Couldn't open app info for ${app.componentName}", it) }
    }

    override fun canUninstall(app: AppInfo): Boolean {
        val info = runCatching {
            launcher.getSystemService(LauncherApps::class.java).getApplicationInfo(app.targetPackage!!, 0, app.user)
        }.getOrNull() ?: return false
        return info.flags and ApplicationInfo.FLAG_SYSTEM == 0
    }

    override fun uninstall(app: AppInfo) {
        state.menu.value = null
        runCatching {
            val intent = Intent(Intent.ACTION_DELETE, Uri.fromParts("package", app.targetPackage, null))
                .putExtra(Intent.EXTRA_USER, app.user)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
            launcher.startActivity(intent)
        }.onFailure { Log.w(TAG, "Couldn't uninstall ${app.targetPackage}", it) }
    }

    override fun hideKeyboard() {
        val imm = launcher.getSystemService(InputMethodManager::class.java)
        imm?.hideSoftInputFromWindow(windowToken, 0)
        composeView.clearFocus()
    }

    // ---- Back ----

    val backHandler = object : BackPressHandler {
        override fun canHandleBack() = progress > 0f

        override fun onBackInvoked() {
            when {
                state.menu.value != null -> state.menu.value = null
                state.expanded.value != null -> state.expanded.value = null
                state.searching.value -> state.closeSearch()
                else -> overlay.hide(true)
            }
        }

        override fun onBackStarted(backEvent: BackEvent) = Unit
        override fun onBackProgressed(backEvent: BackEvent) = Unit
        override fun onBackCancelled() = Unit
    }

    // ---- Touch ----

    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var lastRawX = 0f
    private var lastRawY = 0f
    private var closing = false
    private var undecided = false
    private var velocityTracker: android.view.VelocityTracker? = null

    private class PendingDrag(val app: AppInfo, val iconBounds: Rect, val x: Float, val y: Float)

    // A long pressed app that turns into a drag once the finger moves.
    private var pendingDrag: PendingDrag? = null

    // Set while the launcher's drag and drop owns the gesture.
    private var forwardingDrag = false

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        lastRawX = ev.rawX
        lastRawY = ev.rawY
        if (forwardingDrag) {
            forwardToDrag(ev)
            return true
        }
        val pending = pendingDrag
        if (pending != null) {
            when (ev.actionMasked) {
                MotionEvent.ACTION_MOVE -> if (hypot(ev.rawX - pending.x, ev.rawY - pending.y) > slop) {
                    pendingDrag = null
                    if (startDrag(pending, ev)) {
                        // Compose stops tracking the gesture; the drag gets the rest of it.
                        val cancel = MotionEvent.obtain(ev).apply { action = MotionEvent.ACTION_CANCEL }
                        super.dispatchTouchEvent(cancel)
                        cancel.recycle()
                        return true
                    }
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> pendingDrag = null
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean = trackClose(ev)

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked != MotionEvent.ACTION_DOWN) trackClose(ev)
        return true
    }

    /**
     * Watches for a horizontal swipe back to home, and returns true once it's taken the gesture
     * from Compose. It works in screen coordinates, as this view moves under the finger.
     */
    private fun trackClose(ev: MotionEvent): Boolean {
        if (velocityTracker == null) velocityTracker = android.view.VelocityTracker.obtain()
        val screenEvent = MotionEvent.obtain(ev).apply { setLocation(ev.rawX, ev.rawY) }
        velocityTracker?.addMovement(screenEvent)
        screenEvent.recycle()
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.rawX
                downY = ev.rawY
                closing = false
                undecided = true
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = ev.rawX - downX
                val dy = ev.rawY - downY
                if (undecided && (abs(dx) > slop || abs(dy) > slop)) {
                    undecided = false
                    val towardsHome = if (isRtl) dx < 0 else dx > 0
                    closing = abs(dx) > abs(dy) && towardsHome && !state.isModal
                    if (closing) {
                        // Restart from here so the page doesn't jump by the slop.
                        downX = ev.rawX
                        overlay.onPageDragStart()
                    }
                }
                if (closing) {
                    overlay.onPageDrag(1f - abs(ev.rawX - downX) / width.coerceAtLeast(1))
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                velocityTracker?.computeCurrentVelocity(1000)
                val vx = velocityTracker?.xVelocity ?: 0f
                velocityTracker?.recycle()
                velocityTracker = null
                val took = closing
                // Positive velocity means opening, which is towards the start.
                if (closing) overlay.onPageDragEnd(if (isRtl) vx else -vx)
                closing = false
                return took
            }
        }
        return closing
    }

    // ---- Dragging an app out onto the home screen ----

    private var fadeAnimator: ValueAnimator? = null
    private var dragIcon: BubbleTextView? = null
    private var dragListener: DragController.DragListener? = null

    /**
     * Starts a launcher drag of [pending]'s app from where its icon is drawn, and gets the library
     * out of the way: the home screen snaps back under it right away so the drop lands where the
     * finger is, while the library itself fades out.
     */
    private fun startDrag(pending: PendingDrag, ev: MotionEvent): Boolean {
        if (launcher.isWorkspaceLocked || launcher.dragController.isDragging) return false
        if (PreferenceManager2.getInstance(launcher).lockHomeScreen.firstCached()) return false
        state.menu.value = null
        hideKeyboard()
        overlay.hideForDrag()
        fadeOut()

        val dragLayer = launcher.dragLayer
        val selfOnScreen = IntArray(2).also(::getLocationOnScreen)
        val layerOnScreen = IntArray(2).also(dragLayer::getLocationOnScreen)
        val bounds = Rect(pending.iconBounds).apply {
            offset(selfOnScreen[0] - layerOnScreen[0], selfOnScreen[1] - layerOnScreen[1])
        }

        // A real, invisible app icon laid over the library's icon, for the drag code to start from.
        val icon = launcher.layoutInflater.inflate(R.layout.all_apps_icon, dragLayer, false) as BubbleTextView
        icon.applyFromApplicationInfo(pending.app)
        icon.alpha = 0f
        icon.isClickable = false
        icon.isLongClickable = false
        icon.isFocusable = false
        icon.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        val dp = launcher.deviceProfile
        val width = dp.allAppsProfile.cellWidthPx
        val height = dp.allAppsProfile.cellHeightPx
        icon.measure(
            MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY),
        )
        icon.layout(0, 0, width, height)
        val ownBounds = Rect().also(icon::getIconBounds)
        val scale = bounds.width().toFloat() / ownBounds.width()
        icon.pivotX = 0f
        icon.pivotY = 0f
        icon.scaleX = scale
        icon.scaleY = scale
        val lp = BaseDragLayer.LayoutParams(width, height).apply {
            customPosition = true
            x = (bounds.left - ownBounds.left * scale).roundToInt()
            y = (bounds.top - ownBounds.top * scale).roundToInt()
        }
        icon.layout(lp.x, lp.y, lp.x + width, lp.y + height)
        dragLayer.addView(icon, lp)
        dragIcon = icon

        // Where the drag started, so a cancelled drag flies back there.
        val down = MotionEvent.obtain(ev).apply {
            action = MotionEvent.ACTION_DOWN
            setLocation(pending.x - layerOnScreen[0], pending.y - layerOnScreen[1])
        }
        launcher.dragController.onControllerInterceptTouchEvent(down)
        down.recycle()

        val listener = object : DragController.DragListener {
            override fun onDragStart(dragObject: DropTarget.DragObject, options: DragOptions) = Unit
            override fun onDragEnd() = removeDragIcon()
        }
        dragListener = listener
        launcher.dragController.addDragListener(listener)

        val preview = DragPreviewProvider(icon)
        val drawable = preview.createDrawable()
        val xy = IntArray(2)
        val previewScale = preview.getScaleAndPosition(drawable, xy)
        val dragRect = Rect().also(icon::getSourceVisualDragBounds)
        launcher.dragController.startDrag(
            drawable,
            icon,
            xy[0],
            xy[1] + dragRect.top,
            this,
            pending.app,
            dragRect,
            previewScale,
            previewScale,
            DragOptions(),
        )
        forwardingDrag = true
        forwardToDrag(ev)
        return true
    }

    private fun forwardToDrag(ev: MotionEvent) {
        val layerOnScreen = IntArray(2).also(launcher.dragLayer::getLocationOnScreen)
        val event = MotionEvent.obtain(ev).apply { setLocation(ev.rawX - layerOnScreen[0], ev.rawY - layerOnScreen[1]) }
        launcher.dragController.onControllerTouchEvent(event)
        event.recycle()
        if (ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL) {
            forwardingDrag = false
        }
    }

    private fun fadeOut() {
        fadeAnimator?.cancel()
        val startDepth = launcher.depthController?.folderDepth?.value ?: progress
        fadeAnimator = ValueAnimator.ofFloat(1f, 0f).apply {
            duration = DRAG_FADE_DURATION_MS
            addUpdateListener {
                val t = it.animatedValue as Float
                alpha = t
                scrim.alpha = t
                launcher.depthController?.folderDepth?.value = startDepth * t
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    fadeAnimator = null
                    overlay.hide(false)
                    alpha = 1f
                }
            })
            start()
        }
    }

    private fun removeDragIcon() {
        dragListener?.let(launcher.dragController::removeDragListener)
        dragListener = null
        dragIcon?.let { (it.parent as? ViewGroup)?.removeView(it) }
        dragIcon = null
    }

    override fun onDropCompleted(target: View?, d: DropTarget.DragObject?, success: Boolean) = Unit

    companion object {
        private const val TAG = "AppLibraryView"
        private const val DRAG_FADE_DURATION_MS = 180L
    }
}
