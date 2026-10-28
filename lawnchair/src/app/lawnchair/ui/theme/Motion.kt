package app.lawnchair.ui.theme

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * The motion shared by the Today page, the finder and the app drawer. Things react to touch the
 * moment they're pressed and settle with a little spring, rather than fading a ripple.
 */
object Motion {

    /** How far a card or row sinks in while it's pressed. */
    const val CARD_PRESSED_SCALE = 0.97f

    /** How far a small control, like a pill or an icon, sinks in while it's pressed. */
    const val CONTROL_PRESSED_SCALE = 0.9f

    /** Sinking in: quick, without bounce. */
    val press: AnimationSpec<Float> = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMedium)

    /** Letting go: springs back past where it was, once. */
    val release: AnimationSpec<Float> = spring(dampingRatio = 0.5f, stiffness = Spring.StiffnessMediumLow)

    /** Things appearing, like a sheet sliding up or a badge popping in. */
    fun <T> appear(visibilityThreshold: T? = null) = spring(dampingRatio = 0.78f, stiffness = Spring.StiffnessMediumLow, visibilityThreshold = visibilityThreshold)

    /** Pops in a little past full size. */
    fun <T> pop(visibilityThreshold: T? = null) = spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessMedium, visibilityThreshold = visibilityThreshold)

    /** Gentle ease out, for things that follow a gesture's progress. */
    val EaseOut = CubicBezierEasing(0.2f, 0.9f, 0.3f, 1f)

    /** The delay, as a fraction of the opening gesture, between one item rising in and the next. */
    const val STAGGER = 0.06f
    private const val MAX_STAGGERED = 6

    /**
     * Where item [index] is in its own entrance, as the page it's on goes from closed (0) to open
     * (1). Later items start a little later and catch up by the end.
     */
    fun entrance(progress: Float, index: Int): Float {
        val delay = STAGGER * index.coerceAtMost(MAX_STAGGERED)
        val span = 1f - STAGGER * MAX_STAGGERED
        return EaseOut.transform(((progress - delay) / span).coerceIn(0f, 1f))
    }
}

/**
 * Sinks in a little while pressed and springs back when let go, without taking the touch from
 * what's inside, so it works on top of any click handling (and on app widgets).
 */
fun Modifier.pressScale(enabled: Boolean = true, pressedScale: Float = Motion.CARD_PRESSED_SCALE): Modifier = composed {
    val scale = remember { Animatable(1f) }
    val scope = rememberCoroutineScope()
    this
        .pointerInput(enabled, pressedScale) {
            if (!enabled) return@pointerInput
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                scope.launch { scale.animateTo(pressedScale, Motion.press) }
                do {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                } while (event.changes.any { it.pressed })
                scope.launch { scale.animateTo(1f, Motion.release) }
            }
        }
        .graphicsLayer {
            scaleX = scale.value
            scaleY = scale.value
        }
}

/**
 * A click (and long click) handler for rows and cells on glass: instead of a ripple, the row sinks
 * in a little and a soft [highlight] fades in under it while it's pressed. [selected] keeps the
 * highlight on, like for the result that enter opens.
 */
@OptIn(ExperimentalFoundationApi::class)
fun Modifier.glassClickable(
    highlight: Color,
    shape: Shape,
    selected: Boolean = false,
    pressedScale: Float = Motion.CARD_PRESSED_SCALE,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
): Modifier = composed {
    val interactions = remember { MutableInteractionSource() }
    val pressed by interactions.collectIsPressedAsState()
    val scale by animateFloatAsState(
        if (pressed) pressedScale else 1f,
        if (pressed) Motion.press else Motion.release,
        label = "pressScale",
    )
    val highlightAlpha by animateFloatAsState(
        when {
            pressed -> 1f
            selected -> 1f
            else -> 0f
        },
        spring(stiffness = if (pressed) Spring.StiffnessHigh else Spring.StiffnessLow),
        label = "highlight",
    )
    this
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
        .background(highlight.copy(alpha = highlight.alpha * highlightAlpha), shape)
        .combinedClickable(
            interactionSource = interactions,
            indication = null,
            onLongClick = onLongClick,
            onClick = onClick,
        )
}

/**
 * Rises into place as the page it's on opens: item [index] of the page fades in and drifts up a
 * little, a beat after the one before it. [progress] is the page's open progress, read while
 * drawing only.
 */
fun Modifier.staggeredEntrance(index: Int, progress: () -> Float): Modifier = graphicsLayer {
    val t = Motion.entrance(progress(), index)
    if (t < 1f) {
        alpha = t
        translationY = (1f - t) * 28.dp.toPx()
        val scale = 0.96f + 0.04f * t
        scaleX = scale
        scaleY = scale
    }
}
