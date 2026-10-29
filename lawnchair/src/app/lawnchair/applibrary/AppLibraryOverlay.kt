package app.lawnchair.applibrary

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.os.Looper
import android.view.ViewGroup
import app.lawnchair.LawnchairLauncher
import com.android.app.animation.Interpolators
import com.android.launcher3.Utilities
import com.android.systemui.plugins.shared.LauncherOverlayManager.LauncherOverlay
import com.android.systemui.plugins.shared.LauncherOverlayManager.LauncherOverlayCallbacks
import kotlin.math.abs

/**
 * The App Library, past the last home page like on iOS. Swiping past the last page drives it
 * through [LauncherOverlay], the same way the Today page is driven from the other side, and the
 * drag layer slides the home screen away to the left as it opens.
 */
class AppLibraryOverlay(private val launcher: LawnchairLauncher) : LauncherOverlay {

    private val repository = AppLibraryRepository(launcher)
    private var view: AppLibraryView? = null
    private var callbacks: LauncherOverlayCallbacks? = null
    private var animator: ValueAnimator? = null
    private var progress = 0f
    private var flingVelocity = 0f
    private var destroyed = false
    private val isRtl = Utilities.isRtl(launcher.resources)

    val isOpen: Boolean get() = progress > 0f

    fun attach() {
        launcher.workspace.setEndOverlay(this)
        // Builds the page while nothing else is going on: composing it for the first time takes
        // long enough to stall the first swipe into it.
        Looper.myQueue().addIdleHandler {
            if (!destroyed) {
                ensureView()
                repository.reload()
            }
            false
        }
    }

    fun destroy() {
        destroyed = true
        hide(false)
        launcher.workspace.setEndOverlay(null)
        view?.let {
            launcher.removeTodayBackHandler(it.backHandler)
            (it.parent as? ViewGroup)?.removeView(it)
            (it.scrim.parent as? ViewGroup)?.removeView(it.scrim)
        }
        view = null
        repository.destroy()
    }

    private fun ensureView(): AppLibraryView = view ?: AppLibraryView(launcher, this, repository).also {
        view = it
        launcher.rootView.addView(it.scrim)
        launcher.rootView.addView(it)
        launcher.addTodayBackHandler(it.backHandler)
    }

    private fun setProgress(value: Float) {
        val wasClosed = progress == 0f
        progress = value.coerceIn(0f, 1f)
        if (progress > 0f) {
            ensureView()
            if (wasClosed) repository.start()
        } else if (!wasClosed) {
            repository.stop()
        }
        view?.setProgress(progress)
        callbacks?.onOverlayScrollChanged(progress)
    }

    private fun animateTo(target: Float, velocity: Float = 0f, duration: Long? = null) {
        animator?.cancel()
        val distance = abs(target - progress)
        if (distance == 0f) {
            setProgress(target)
            return
        }
        val speed = 1f + abs(velocity) / launcher.resources.displayMetrics.widthPixels
        animator = ValueAnimator.ofFloat(progress, target).apply {
            this.duration = duration
                ?: (SETTLE_DURATION_MS * distance.coerceAtLeast(0.3f) / speed).toLong().coerceAtLeast(120)
            interpolator = Interpolators.DECELERATE_3
            addUpdateListener { setProgress(it.animatedValue as Float) }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (animator == animation) animator = null
                }
            })
            start()
        }
    }

    /** [velocity] is positive towards opening. */
    private fun settle(velocity: Float) {
        val threshold = FLING_THRESHOLD_DP * launcher.resources.displayMetrics.density
        val open = if (abs(velocity) > threshold) velocity > 0 else progress > 0.5f
        animateTo(if (open) 1f else 0f, velocity)
    }

    fun open() {
        ensureView()
        animateTo(1f)
    }

    fun hide(animate: Boolean) = hide(if (animate) HIDE_DURATION_MS else 0L)

    fun hide(duration: Long) {
        if (progress == 0f && animator == null) return
        if (duration == 0L) {
            animator?.cancel()
            setProgress(0f)
        } else {
            animateTo(0f, duration = duration)
        }
    }

    /**
     * Puts the home screen back in place right away for an app being dragged out, while the
     * view fades itself out and then calls [hide].
     */
    fun hideForDrag() {
        animator?.cancel()
        animator = null
        callbacks?.onOverlayScrollChanged(0f)
    }

    fun onResume() {
        if (isOpen) repository.reload()
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
        // The workspace reports the raw horizontal velocity; this page opens towards the start.
        flingVelocity = if (isRtl) velocity else -velocity
    }

    override fun onScrollInteractionEnd() {
        settle(flingVelocity)
        flingVelocity = 0f
    }

    override fun setOverlayCallbacks(callbacks: LauncherOverlayCallbacks?) {
        this.callbacks = callbacks
    }

    companion object {
        private const val SETTLE_DURATION_MS = 350f
        private const val HIDE_DURATION_MS = 250L
        private const val FLING_THRESHOLD_DP = 500f
    }
}
