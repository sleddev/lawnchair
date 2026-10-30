package app.lawnchair.ui.popup

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.StateListDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import androidx.core.graphics.ColorUtils
import app.lawnchair.ui.theme.Glass
import com.android.launcher3.BubbleTextView
import com.android.launcher3.R
import com.android.launcher3.Utilities
import com.android.launcher3.shortcuts.DeepShortcutView

/**
 * The look of the launcher's long press menus, matching the App Library's: one frosted card of
 * rows, label first and icon at the end, with hairlines between rows and a thicker band between
 * groups. It opens over a dimmed, blurred home screen, with the pressed icon lifted above it.
 */
object GlassPopup {

    const val CORNER_RADIUS_DP = 16f
    const val WIDTH_DP = 250
    const val ROW_HEIGHT_DP = 48

    /** The band between groups of rows. */
    const val SECTION_GAP_DP = 8

    /** Between the pressed icon and the menu, and between the menu and the screen's edges. */
    const val TARGET_GAP_DP = 12
    const val SCREEN_MARGIN_DP = 16

    const val SCRIM_ALPHA = 0.3f
    const val LIFTED_SCALE = 1.08f

    /** How far the home screen behind is blurred, the same as behind an open folder. */
    const val BLUR_DEPTH = 0.3f

    private const val TEXT_SIZE_SP = 16f
    private const val ROW_PADDING_DP = 16
    private const val SYSTEM_ICON_DP = 20
    private const val SHORTCUT_ICON_DP = 24
    private const val ICON_TEXT_GAP_DP = 12

    @JvmStatic
    fun isDark(context: Context) = Utilities.isDarkTheme(context)

    @JvmStatic
    fun textColor(dark: Boolean) = if (dark) Color.WHITE else Glass.CARD_TEXT_LIGHT
    fun dangerColor(dark: Boolean) = if (dark) 0xFFFF453A.toInt() else 0xFFFF3B30.toInt()
    @JvmStatic
    fun separatorColor(dark: Boolean) = if (dark) argb(Color.WHITE, 0.12f) else argb(Color.BLACK, 0.10f)
    @JvmStatic
    fun bandColor(dark: Boolean) = if (dark) argb(Color.BLACK, 0.22f) else argb(Color.BLACK, 0.07f)
    private fun highlightColor(dark: Boolean) = if (dark) argb(Color.WHITE, 0.16f) else argb(Color.BLACK, 0.07f)

    @JvmStatic
    fun dp(context: Context, value: Number): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP,
        value.toFloat(),
        context.resources.displayMetrics,
    ).toInt()

    /**
     * Restyles a menu row: the label at the start in the card's text color (red for [destructive]
     * actions) and its icon at the end. System action icons are tinted with the label, app
     * shortcut icons keep their colors.
     */
    @JvmStatic
    fun styleRow(row: DeepShortcutView, destructive: Boolean) {
        val context = row.context
        val dark = isDark(context)
        val color = if (destructive) dangerColor(dark) else textColor(dark)
        val isAppShortcut = row.id == R.id.deep_shortcut_material
        val iconSize = dp(context, if (isAppShortcut) SHORTCUT_ICON_DP else SYSTEM_ICON_DP)
        val padding = dp(context, ROW_PADDING_DP)

        row.background = null
        row.elevation = 0f
        row.layoutParams?.let {
            it.width = dp(context, WIDTH_DP)
            it.height = FrameLayout.LayoutParams.WRAP_CONTENT
        }
        row.minimumHeight = dp(context, ROW_HEIGHT_DP)

        row.bubbleText.apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, TEXT_SIZE_SP)
            setTextColor(color)
            minHeight = dp(context, ROW_HEIGHT_DP)
            setPaddingRelative(padding, 0, padding + iconSize + dp(context, ICON_TEXT_GAP_DP), 0)
            background = StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_pressed), ColorDrawable(highlightColor(dark)))
                addState(intArrayOf(), ColorDrawable(Color.TRANSPARENT))
                setExitFadeDuration(150)
            }
        }

        row.iconView.apply {
            (layoutParams as FrameLayout.LayoutParams).apply {
                width = iconSize
                height = iconSize
                gravity = Gravity.END or Gravity.CENTER_VERTICAL
                marginStart = 0
                marginEnd = padding
            }
            backgroundTintList = if (isAppShortcut) null else ColorStateList.valueOf(color)
        }
    }

    private fun argb(color: Int, alpha: Float) = ColorUtils.setAlphaComponent(color, (alpha * 255).toInt())
}

/** The menu's frosted card: lighter at the top, with a hairline that catches the light along the top. */
class GlassCardDrawable(context: Context) : Drawable() {

    private val dark = GlassPopup.isDark(context)
    private val radius = GlassPopup.dp(context, GlassPopup.CORNER_RADIUS_DP).toFloat()
    private val stroke = context.resources.displayMetrics.density * 0.75f
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = stroke
    }
    private val rect = RectF()

    override fun onBoundsChange(bounds: Rect) {
        val top = bounds.top.toFloat()
        val bottom = bounds.bottom.toFloat()
        fill.shader = LinearGradient(0f, top, 0f, bottom, Glass.cardTop(dark), Glass.cardBottom(dark), Shader.TileMode.CLAMP)
        border.shader = LinearGradient(0f, top, 0f, bottom, Glass.borderTop(dark), Glass.borderBottom(dark), Shader.TileMode.CLAMP)
    }

    override fun draw(canvas: Canvas) {
        rect.set(bounds)
        canvas.drawRoundRect(rect, radius, radius, fill)
        val inset = stroke / 2
        rect.inset(inset, inset)
        canvas.drawRoundRect(rect, radius - inset, radius - inset, border)
    }

    override fun getOutline(outline: Outline) {
        outline.setRoundRect(bounds, radius)
    }

    override fun setAlpha(alpha: Int) {
        fill.alpha = alpha
        border.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        fill.colorFilter = colorFilter
        border.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}

/**
 * What goes under an open menu: a dim over the whole screen and, when the menu is for an icon, a
 * slightly larger copy of that icon lifted above the dim. It's drawn from the icon itself, so it
 * stays put even while the original is hidden for a drag.
 */
@SuppressLint("ViewConstructor")
class GlassPopupBackdrop(
    context: Context,
    private val icon: BubbleTextView?,
    private val iconBounds: RectF?,
) : View(context) {

    init {
        setBackgroundColor(ColorUtils.setAlphaComponent(Color.BLACK, (GlassPopup.SCRIM_ALPHA * 255).toInt()))
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    override fun onDraw(canvas: Canvas) {
        val drawable = icon?.icon ?: return
        val target = iconBounds ?: return
        val source = drawable.bounds
        if (source.isEmpty) return
        val scale = target.width() / source.width() * GlassPopup.LIFTED_SCALE
        canvas.save()
        canvas.translate(target.centerX(), target.centerY())
        canvas.scale(scale, scale)
        canvas.translate(-source.exactCenterX(), -source.exactCenterY())
        drawable.draw(canvas)
        canvas.restore()
    }
}
