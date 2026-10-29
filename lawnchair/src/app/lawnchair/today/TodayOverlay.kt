package app.lawnchair.today

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Intent
import android.view.ViewGroup
import app.lawnchair.LawnchairLauncher
import app.lawnchair.util.PageSnap
import com.android.systemui.plugins.shared.LauncherOverlayManager
import com.android.systemui.plugins.shared.LauncherOverlayManager.LauncherOverlay
import com.android.systemui.plugins.shared.LauncherOverlayManager.LauncherOverlayCallbacks
import kotlin.math.abs

/**
 * The in-launcher -1 screen: an iOS-style Today page. Swiping past the first home page drives
 * it through [LauncherOverlay], the same way an external feed is driven, and Workspace/DragLayer
 * slide the home screen away as [LauncherOverlayCallbacks.onOverlayScrollChanged] reports it.
 */
class TodayOverlay(private val launcher: LawnchairLauncher) :
    LauncherOverlayManager,
    LauncherOverlay {

    private val repository by lazy { TodayRepository(launcher) }
    private val widgets by lazy { TodayWidgets(launcher, repository) }
    private var view: TodayView? = null
    private var callbacks: LauncherOverlayCallbacks? = null
    private var animator: ValueAnimator? = null
    private var progress = 0f
    private var flingVelocity = 0f
    private var started = false

    /** Hooks the page up to the workspace swipe. */
    fun attach() {
        launcher.setLauncherOverlay(this)
    }

    /** Unhooks the page, e.g. when another feed is picked. */
    fun detach() {
        hideOverlay(0)
        launcher.setLauncherOverlay(null)
        view?.let {
            launcher.removeTodayBackHandler(it.backHandler)
            (it.parent as? ViewGroup)?.removeView(it)
            (it.scrim.parent as? ViewGroup)?.removeView(it.scrim)
        }
        view = null
        if (started) widgets.stopListening()
        started = false
    }

    private fun ensureView(): TodayView = view ?: TodayView(launcher, this, repository, widgets).also {
        view = it
        launcher.rootView.addView(it.scrim)
        launcher.rootView.addView(it)
        launcher.addTodayBackHandler(it.backHandler)
        if (!started && launcher.isStarted) {
            widgets.startListening()
            started = true
        }
    }

    private fun setProgress(value: Float) {
        progress = value.coerceIn(0f, 1f)
        if (progress > 0f) ensureView()
        view?.setProgress(progress)
        callbacks?.onOverlayScrollChanged(progress)
    }

    private fun animateTo(target: Float, velocity: Float = 0f, duration: Long? = null) {
        animator?.cancel()
        if (view == null && target == 0f) {
            setProgress(0f)
            return
        }
        val distance = abs(target - progress)
        if (distance == 0f) {
            setProgress(target)
            return
        }
        animator = ValueAnimator.ofFloat(progress, target).apply {
            this.duration = duration ?: PageSnap.duration(launcher, distance, velocity)
            interpolator = PageSnap.interpolator
            addUpdateListener { setProgress(it.animatedValue as Float) }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (animator == animation) animator = null
                }
            })
            start()
        }
    }

    private fun settle(velocity: Float) {
        val threshold = FLING_THRESHOLD_DP * launcher.resources.displayMetrics.density
        val open = if (abs(velocity) > threshold) velocity > 0 else progress > 0.5f
        animateTo(if (open) 1f else 0f, velocity)
    }

    // ---- Dragging from the page itself (swiping back to home) ----

    fun onPageDragStart() {
        animator?.cancel()
    }

    fun onPageDrag(value: Float) = setProgress(value)

    fun onPageDragEnd(velocity: Float) = settle(velocity)

    // ---- LauncherOverlay: dragging from the workspace ----

    override fun onScrollInteractionBegin() {
        animator?.cancel()
        flingVelocity = 0f
        ensureView()
    }

    override fun onScrollChange(progress: Float, rtl: Boolean) = setProgress(progress)

    override fun onFlingVelocity(velocity: Float) {
        flingVelocity = velocity
    }

    override fun onScrollInteractionEnd() {
        settle(flingVelocity)
        flingVelocity = 0f
    }

    override fun setOverlayCallbacks(callbacks: LauncherOverlayCallbacks?) {
        this.callbacks = callbacks
    }

    // ---- LauncherOverlayManager ----

    override fun openOverlay() {
        ensureView()
        animateTo(1f)
    }

    override fun hideOverlay(animate: Boolean) = hideOverlay(if (animate) HIDE_DURATION_MS else 0)

    override fun hideOverlay(duration: Int) {
        if (progress == 0f && animator == null) return
        if (duration == 0) {
            animator?.cancel()
            setProgress(0f)
        } else {
            animateTo(0f, duration = duration.toLong())
        }
    }

    override fun onActivityStarted() {
        if (view != null && !started) {
            widgets.startListening()
            started = true
        }
    }

    override fun onActivityResumed() {
        view?.refresh()
    }

    override fun onActivityStopped() {
        if (started) widgets.stopListening()
        started = false
    }

    override fun onActivityDestroyed() = detach()

    /** Returns true when the result was for a widget being added to the page. */
    fun onActivityResult(requestCode: Int, resultCode: Int, @Suppress("UNUSED_PARAMETER") data: Intent?): Boolean =
        view != null && widgets.onActivityResult(requestCode, resultCode)

    companion object {
        private const val HIDE_DURATION_MS = 250
        private const val FLING_THRESHOLD_DP = 500f
    }
}
