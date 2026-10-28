package app.lawnchair.views

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.os.Build
import android.util.Log
import android.view.View
import android.view.ViewTreeObserver
import android.view.WindowManager
import androidx.core.graphics.ColorUtils
import app.lawnchair.util.getFolderBackgroundAlpha
import app.lawnchair.util.resolveFolderBackgroundColor
import com.android.internal.graphics.drawable.BackgroundBlurDrawable
import com.android.launcher3.R
import com.android.launcher3.Utilities
import java.util.function.Consumer

/**
 * Draws an iOS style glass dock background: the wallpaper behind the dock is blurred (using a
 * window blur region), tinted like the open folder panel, with a faint border.
 *
 * The owning [view] has to forward [onAttachedToWindow], [onDetachedFromWindow] and [draw].
 */
class GlassDockBackground(private val view: View) {

    private val context: Context = view.context
    private val res = context.resources

    private var cornerRadius = 0f
    private val blurRadius = res.getDimensionPixelSize(R.dimen.dock_glass_blur_radius)
    private val borderWidth = res.getDimension(R.dimen.dock_glass_border_width)

    private val rect = RectF()

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val sheenPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = borderWidth
    }

    private var blurDrawable: BackgroundBlurDrawable? = null
    private var blurEnabled = false
    private var blurAlpha = -1

    private val blurListener = Consumer<Boolean> { enabled ->
        blurEnabled = enabled
        updateFill()
        view.invalidate()
    }

    // The blur region isn't affected by view alpha, so it follows the alpha of the view and its
    // ancestors manually (e.g. while the dock fades out when opening all apps).
    private val preDrawListener = ViewTreeObserver.OnPreDrawListener {
        updateBlurAlpha()
        true
    }

    fun onAttachedToWindow() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                blurDrawable = view.viewRootImpl?.createBackgroundBlurDrawable()?.apply {
                    setBlurRadius(blurRadius)
                    setCornerRadius(cornerRadius)
                    setColor(Color.TRANSPARENT)
                }
                context.getSystemService(WindowManager::class.java)
                    ?.addCrossWindowBlurEnabledListener(context.mainExecutor, blurListener)
            } catch (t: Throwable) {
                Log.w(TAG, "Window blur unavailable, using a tinted dock instead", t)
                blurDrawable = null
            }
        }
        blurAlpha = -1
        view.viewTreeObserver.addOnPreDrawListener(preDrawListener)
        updateBounds(rect)
        updateFill()
    }

    fun onDetachedFromWindow() {
        view.viewTreeObserver.removeOnPreDrawListener(preDrawListener)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                context.getSystemService(WindowManager::class.java)
                    ?.removeCrossWindowBlurEnabledListener(blurListener)
            } catch (_: Throwable) {
            }
        }
        blurDrawable = null
    }

    /** Sets the dock bounds, relative to [view], and its corner radius. */
    fun setBounds(left: Float, top: Float, right: Float, bottom: Float, radius: Float) {
        cornerRadius = radius
        blurDrawable?.setCornerRadius(radius)
        updateBounds(RectF(left, top, right, bottom))
    }

    private fun updateBounds(bounds: RectF) {
        rect.set(bounds)
        updateShaders()
        blurDrawable?.setBounds(
            rect.left.toInt(),
            rect.top.toInt(),
            rect.right.toInt(),
            rect.bottom.toInt(),
        )
        view.invalidate()
    }

    private fun updateFill() {
        // Same tint as the open folder panel, which also sits on top of a blur.
        val folderColor = resolveFolderBackgroundColor(context)
        val folderAlpha = Color.alpha(folderColor) * getFolderBackgroundAlpha(context) / 255
        fillPaint.color = if (blurDrawable != null && blurEnabled) {
            ColorUtils.setAlphaComponent(folderColor, folderAlpha)
        } else {
            // Without blur, a more opaque tint keeps the icons readable.
            ColorUtils.setAlphaComponent(folderColor, maxOf(folderAlpha, 0xCC))
        }
        updateShaders()
    }

    // Light falls on the glass from above: the border catches it along the top edge and fades
    // towards the bottom, and the top of the fill gets a faint sheen. Same as the Today cards.
    private fun updateShaders() {
        if (rect.isEmpty) return
        val dark = Utilities.isDarkTheme(context)
        borderPaint.shader = LinearGradient(
            0f, rect.top, 0f, rect.bottom,
            if (dark) white(0.22f) else white(0.85f),
            if (dark) white(0.05f) else ColorUtils.setAlphaComponent(Color.BLACK, 0x12),
            Shader.TileMode.CLAMP,
        )
        sheenPaint.shader = LinearGradient(
            0f, rect.top, 0f, rect.top + rect.height() * 0.6f,
            if (dark) white(0.07f) else white(0.3f),
            Color.TRANSPARENT,
            Shader.TileMode.CLAMP,
        )
    }

    private fun updateBlurAlpha() {
        val blur = blurDrawable ?: return
        var alpha = 1f
        var v: View? = view
        while (v != null) {
            alpha *= v.alpha
            v = v.parent as? View
        }
        val newAlpha = (alpha.coerceIn(0f, 1f) * 255).toInt()
        if (newAlpha != blurAlpha) {
            blurAlpha = newAlpha
            blur.alpha = newAlpha
            view.invalidate()
        }
    }

    fun draw(canvas: Canvas) {
        if (rect.isEmpty) return
        if (blurEnabled) {
            blurDrawable?.draw(canvas)
        }
        canvas.drawRoundRect(rect, cornerRadius, cornerRadius, fillPaint)
        canvas.drawRoundRect(rect, cornerRadius, cornerRadius, sheenPaint)
        val inset = borderWidth / 2
        canvas.drawRoundRect(
            rect.left + inset,
            rect.top + inset,
            rect.right - inset,
            rect.bottom - inset,
            cornerRadius - inset,
            cornerRadius - inset,
            borderPaint,
        )
    }

    private fun white(alpha: Float) = ColorUtils.setAlphaComponent(Color.WHITE, (alpha * 255).toInt())

    companion object {
        private const val TAG = "GlassDockBackground"
    }
}
