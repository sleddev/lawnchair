package app.lawnchair.util

import android.content.Context
import com.android.app.animation.Interpolators
import com.android.launcher3.R
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The settle timing of a home page snap (PagedView.snapToPageWithVelocity), for pages driven
 * outside the workspace, like the Today page and the App Library, so they come to rest at the
 * same speed as a swipe between home pages.
 */
object PageSnap {

    val interpolator = Interpolators.SCROLL

    /**
     * Duration for settling [remaining] (a fraction of the screen width) after a release at
     * [velocity] px/s.
     */
    fun duration(context: Context, remaining: Float, velocity: Float): Long {
        val res = context.resources
        if (abs(velocity) < res.getDimensionPixelSize(R.dimen.min_fling_velocity)) {
            return res.getInteger(R.integer.config_pageSnapAnimationDuration).toLong()
        }
        val halfScreen = res.displayMetrics.widthPixels / 2f
        val ratio = min(1f, abs(remaining))
        // Keeps the distance near half a screen, like PagedView, so the duration varies less.
        val distance = halfScreen + halfScreen * sin((ratio - 0.5f) * 0.3f * PI / 2).toFloat()
        val speed = max(res.getDimensionPixelSize(R.dimen.min_page_snap_velocity).toFloat(), abs(velocity))
        return 4L * (1000 * distance / speed).roundToInt()
    }
}
