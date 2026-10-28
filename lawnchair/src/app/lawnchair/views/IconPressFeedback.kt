package app.lawnchair.views

import android.animation.ObjectAnimator
import android.animation.TimeInterpolator
import android.animation.ValueAnimator
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import com.android.launcher3.icons.FastBitmapDrawable
import java.util.function.Consumer

/**
 * The press feedback of icons on the home screen, in the dock, in folders and in the app drawer:
 * the icon sinks in a little while it's held, and springs back with a small overshoot when it's
 * let go. It replaces the stock feedback, which grows the icon on press.
 */
object IconPressFeedback {

    const val PRESSED_SCALE = 0.88f

    private const val PRESS_DURATION = 140L
    private const val RELEASE_DURATION = 420L
    private val PRESS: TimeInterpolator = DecelerateInterpolator(1.5f)
    private val RELEASE: TimeInterpolator = OvershootInterpolator(3f)

    /** Animates the scale of [icon], which must have its own press animation disabled. */
    @JvmStatic
    fun animate(icon: FastBitmapDrawable, pressed: Boolean) {
        icon.scaleAnimation?.cancel()
        val target = if (pressed) PRESSED_SCALE else 1f
        // Leave it as the animation so FastBitmapDrawable.getAnimatedScale() reports the scale,
        // which the app launch animation starts from.
        icon.scaleAnimation = ObjectAnimator.ofFloat(icon, FastBitmapDrawable.SCALE, target).apply {
            setup(pressed)
            if (icon.isVisible) start() else end()
        }
    }

    /** An animator from [from] to the scale for [pressed], for views that draw the scale themselves. */
    @JvmStatic
    fun animator(from: Float, pressed: Boolean, update: Consumer<Float>): ValueAnimator =
        ValueAnimator.ofFloat(from, if (pressed) PRESSED_SCALE else 1f).apply {
            setup(pressed)
            addUpdateListener { update.accept(it.animatedValue as Float) }
        }

    private fun ValueAnimator.setup(pressed: Boolean) {
        duration = if (pressed) PRESS_DURATION else RELEASE_DURATION
        interpolator = if (pressed) PRESS else RELEASE
    }
}
