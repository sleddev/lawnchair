package app.lawnchair.today

import android.content.Intent
import app.lawnchair.LawnchairLauncher
import app.lawnchair.nexuslauncher.OverlayCallbackImpl
import com.android.systemui.plugins.shared.LauncherOverlayManager
import java.io.PrintWriter

/**
 * The launcher's -1 screen: either the built-in [TodayOverlay], or an external feed app through
 * [OverlayCallbackImpl], depending on the feed provider preference.
 */
class SwitchingOverlayManager(
    private val launcher: LawnchairLauncher,
    useToday: Boolean,
    feedEnabled: Boolean,
) : LauncherOverlayManager {

    private var useToday = useToday
    private var feedEnabled = feedEnabled
    private var external: OverlayCallbackImpl? = null
    private var today: TodayOverlay? = null

    // The lifecycle the current delegate has been told about, to catch a new one up.
    private var windowAttached = false
    private var started = false
    private var resumed = false

    private val current: LauncherOverlayManager
        get() = (if (useToday) today else external) ?: NoOverlay

    init {
        apply(useToday, feedEnabled)
    }

    /** Picks the provider and whether the -1 screen is on at all. */
    fun apply(useToday: Boolean, feedEnabled: Boolean) {
        val switching = useToday != this.useToday || (today == null && external == null)
        val enableChanged = feedEnabled != this.feedEnabled
        this.useToday = useToday
        this.feedEnabled = feedEnabled
        if (switching) {
            if (useToday) {
                external?.onActivityDestroyed()
                external = null
                launcher.setLauncherOverlay(null)
                today = TodayOverlay(launcher)
            } else {
                today?.detach()
                today = null
                external = OverlayCallbackImpl(launcher).also { catchUp(it) }
            }
        }
        if (useToday) {
            if (feedEnabled) today?.attach() else today?.detach()
        } else if (switching || enableChanged) {
            external?.setEnableFeed(feedEnabled)
        }
    }

    fun reconnect() {
        external?.reconnect()
    }

    /** Returns true when the result was for the Today page. */
    fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean =
        today?.onActivityResult(requestCode, resultCode, data) == true

    private fun catchUp(manager: LauncherOverlayManager) {
        if (windowAttached) manager.onAttachedToWindow()
        if (started) manager.onActivityStarted()
        if (resumed) manager.onActivityResumed()
    }

    override fun onDeviceProvideChanged() = current.onDeviceProvideChanged()

    override fun onAttachedToWindow() {
        windowAttached = true
        current.onAttachedToWindow()
    }

    override fun onDetachedFromWindow() {
        windowAttached = false
        current.onDetachedFromWindow()
    }

    override fun dump(prefix: String, w: PrintWriter) = current.dump(prefix, w)

    override fun openOverlay() = current.openOverlay()

    override fun hideOverlay(animate: Boolean) = current.hideOverlay(animate)

    override fun hideOverlay(duration: Int) = current.hideOverlay(duration)

    override fun onActivityStarted() {
        started = true
        current.onActivityStarted()
    }

    override fun onActivityResumed() {
        resumed = true
        current.onActivityResumed()
    }

    override fun onActivityPaused() {
        resumed = false
        current.onActivityPaused()
    }

    override fun onActivityStopped() {
        started = false
        current.onActivityStopped()
    }

    override fun onActivityDestroyed() = current.onActivityDestroyed()

    override fun onDisallowSwipeToMinusOnePage() = current.onDisallowSwipeToMinusOnePage()

    private object NoOverlay : LauncherOverlayManager

    companion object {
        /** The feed provider value that picks the built-in Today page. */
        const val TODAY_PROVIDER = "app.lawnchair.today"
    }
}
