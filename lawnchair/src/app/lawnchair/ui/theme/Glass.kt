package app.lawnchair.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.core.graphics.ColorUtils

/**
 * The frosted glass look shared by the Today page, the finder and the app drawer: cards and
 * fields that are a touch lighter at the top, with a hairline border that catches the light along
 * the top edge and fades out towards the bottom, sitting on the blurred wallpaper under a scrim
 * that's darker at the top.
 *
 * Colors are ARGB ints so views can use them too; the brushes are for Compose.
 */
object Glass {

    val CardShape = RoundedCornerShape(22.dp)
    val FieldShape = RoundedCornerShape(50)
    val FieldHeight = 48.dp

    /** Side margin of search fields and cards on the finder and in the app drawer. */
    val ScreenMargin = 12.dp

    fun cardTop(dark: Boolean) = if (dark) argb(0x3A3A40, 0.50f) else argb(0xFFFFFF, 0.88f)
    fun cardBottom(dark: Boolean) = if (dark) argb(0x26262A, 0.46f) else argb(0xFFFFFF, 0.78f)
    fun borderTop(dark: Boolean) = argb(0xFFFFFF, if (dark) 0.20f else 0.65f)
    fun borderBottom(dark: Boolean) = argb(0xFFFFFF, if (dark) 0.05f else 0.18f)
    fun field(dark: Boolean) = if (dark) argb(0xFFFFFF, 0.16f) else argb(0xFFFFFF, 0.85f)

    /** The scrim over the blurred wallpaper, at the top and from about a third of the way down. */
    fun scrimTop(dark: Boolean) = argb(0x000000, if (dark) 0.40f else 0.30f)
    fun scrim(dark: Boolean) = argb(0x000000, if (dark) 0.18f else 0.10f)

    /**
     * The scrim's colors from top to bottom. Without window blurs (with battery saver, say) there's
     * no blurred wallpaper under it, so it's solid instead: the scrim over a darkened [wallpaper]
     * color, roughly what the blurred wallpaper looks like. [wallpaper] is `0` when it isn't known.
     */
    fun scrimColors(dark: Boolean, blurred: Boolean, wallpaper: Int): IntArray {
        if (blurred) return intArrayOf(scrimTop(dark), scrim(dark), scrim(dark))
        val base = solidBase(wallpaper)
        val top = ColorUtils.compositeColors(scrimTop(dark), base)
        val rest = ColorUtils.compositeColors(scrim(dark), base)
        return intArrayOf(top, rest, rest)
    }

    private fun solidBase(wallpaper: Int): Int {
        if (wallpaper == 0) return SOLID_FALLBACK
        // Darkened, so the white text on the scrim stays readable on a light wallpaper.
        return ColorUtils.blendARGB(0xFF000000.toInt() or wallpaper, SOLID_FALLBACK, 0.55f)
    }

    private const val SOLID_FALLBACK = 0xFF26262A.toInt()

    /** A card's fill when it can't sit on a blur, see [scrimColors]: the same colors, solid. */
    fun solidCardTop(dark: Boolean) = if (dark) 0xFF3A3A40.toInt() else 0xFFFFFFFF.toInt()
    fun solidCardBottom(dark: Boolean) = if (dark) 0xFF2C2C31.toInt() else 0xFFF4F4F6.toInt()

    /** Text drawn straight on the scrim, like section headers. It's white in both themes. */
    val PageText = Color.White

    /** Text on a light theme card. */
    const val CARD_TEXT_LIGHT = 0xFF111111.toInt()

    fun cardFill(dark: Boolean) = Brush.verticalGradient(listOf(Color(cardTop(dark)), Color(cardBottom(dark))))
    fun border(dark: Boolean) = Brush.verticalGradient(listOf(Color(borderTop(dark)), Color(borderBottom(dark))))
    fun scrimBrush(dark: Boolean, blurred: Boolean = true, wallpaper: Int = 0): Brush {
        val colors = scrimColors(dark, blurred, wallpaper)
        return Brush.verticalGradient(
            0f to Color(colors[0]),
            0.35f to Color(colors[1]),
            1f to Color(colors[2]),
        )
    }

    private fun argb(rgb: Int, alpha: Float) = ColorUtils.setAlphaComponent(0xFF000000.toInt() or rgb, (alpha * 255).toInt())
}
