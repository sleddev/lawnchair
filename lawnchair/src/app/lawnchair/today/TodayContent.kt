package app.lawnchair.today

import android.appwidget.AppWidgetProviderInfo
import android.text.format.DateFormat
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector2D
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.boundsInParent
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.round
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import app.lawnchair.ui.theme.Motion
import app.lawnchair.ui.theme.pressScale
import app.lawnchair.ui.theme.staggeredEntrance
import com.google.accompanist.drawablepainter.rememberDrawablePainter
import java.util.Date
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** State shared between the [TodayView] (which handles the page-level gestures) and its content. */
class TodayPageState(
    val items: StateFlow<List<TodayItem>>,
    val scrollState: ScrollState,
) {
    val editing = mutableStateOf(false)

    /** True while an item is being dragged in edit mode, so the page doesn't scroll. */
    var touchingItem = false

    val addSheetOpen = mutableStateOf(false)

    /** How far the page is open, from 0 to 1. The content rises in with it. */
    val progress = mutableFloatStateOf(0f)
}

@Composable
fun TodayContent(
    state: TodayPageState,
    host: TodayHost,
    widgets: TodayWidgets,
    repository: TodayRepository,
    isDark: Boolean,
) {
    val colors = remember(isDark) { TodayColors(isDark) }
    val items by state.items.collectAsState()
    var editing by state.editing
    var addSheetOpen by state.addSheetOpen

    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .fadeUnderStatusBar(state.scrollState)
                .verticalScroll(state.scrollState)
                .statusBarsPadding()
                .padding(top = 8.dp, bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 24.dp),
        ) {
            val progress = { state.progress.floatValue }
            TopBar(editing, colors, onAdd = { addSheetOpen = true }, onDone = { editing = false })
            DateHeader(host, colors, Modifier.staggeredEntrance(0, progress))
            TodayGrid(
                items = items,
                editing = editing,
                progress = progress,
                state = state,
                host = host,
                widgets = widgets,
                repository = repository,
                colors = colors,
            )
            AnimatedVisibility(
                !editing,
                enter = fadeIn() + scaleIn(Motion.pop(), initialScale = 0.8f),
                exit = fadeOut() + scaleOut(targetScale = 0.8f),
                modifier = Modifier.align(Alignment.CenterHorizontally),
            ) {
                Pill("Edit", colors, Modifier.padding(top = 20.dp)) { editing = true }
            }
        }

        AddSheet(
            visible = addSheetOpen,
            items = items,
            widgets = widgets,
            colors = colors,
            onAddCard = { card ->
                repository.add(TodayItem.Card(card, card.sizes.first()))
                addSheetOpen = false
            },
            onAddWidget = { info ->
                addSheetOpen = false
                widgets.add(info)
            },
            onDismiss = { addSheetOpen = false },
        )
    }
}

/**
 * Fades content out as it scrolls up under the status bar, instead of running into the clock.
 * It eases in over the first bit of scrolling, so nothing is faded while the page is at the top.
 */
private fun Modifier.fadeUnderStatusBar(scrollState: ScrollState): Modifier = composed {
    val density = LocalDensity.current
    val statusBar = WindowInsets.statusBars.getTop(density).toFloat()
    val fade = with(density) { 28.dp.toPx() }
    this
        .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        .drawWithContent {
            drawContent()
            val strength = (scrollState.value / fade).coerceIn(0f, 1f)
            if (strength == 0f) return@drawWithContent
            val height = statusBar + fade
            drawRect(
                Brush.verticalGradient(
                    0f to Color.Black.copy(alpha = 1f - strength),
                    statusBar / height to Color.Black.copy(alpha = 1f - strength),
                    1f to Color.Black,
                ),
                size = androidx.compose.ui.geometry.Size(size.width, height),
                blendMode = BlendMode.DstIn,
            )
        }
}

@Composable
private fun TopBar(editing: Boolean, colors: TodayColors, onAdd: () -> Unit, onDone: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(44.dp)
            .padding(horizontal = TodayWidgets.GRID_MARGIN_DP.dp),
    ) {
        AnimatedVisibility(editing, enter = popIn(), exit = popOut(), modifier = Modifier.align(Alignment.CenterStart)) {
            Box(
                Modifier
                    .pressScale(pressedScale = Motion.CONTROL_PRESSED_SCALE)
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(colors.pill)
                    .border(0.75.dp, colors.pillBorder, CircleShape)
                    .clickableNoRipple(onAdd),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.Add, contentDescription = "Add widget", tint = Color.White)
            }
        }
        AnimatedVisibility(editing, enter = popIn(), exit = popOut(), modifier = Modifier.align(Alignment.CenterEnd)) {
            Pill("Done", colors, bold = true, onClick = onDone)
        }
    }
}

private fun popIn() = fadeIn() + scaleIn(Motion.pop(), initialScale = 0.6f)

private fun popOut() = fadeOut() + scaleOut(targetScale = 0.8f)

@Composable
private fun DateHeader(host: TodayHost, colors: TodayColors, modifier: Modifier = Modifier) {
    val now = remember(host.refreshKey) { Date() }
    val weekday = remember(now) { DateFormat.format("EEEE", now).toString() }
    val date = remember(now) { DateFormat.format(DateFormat.getBestDateTimePattern(java.util.Locale.getDefault(), "MMMMd"), now).toString() }
    Column(modifier.padding(horizontal = TodayWidgets.GRID_MARGIN_DP.dp + 4.dp, vertical = 6.dp)) {
        val shadow = Shadow(Color.Black.copy(alpha = 0.4f), Offset(0f, 1f), blurRadius = 10f)
        Text(
            weekday.uppercase(),
            color = colors.pageText.copy(alpha = 0.9f),
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.2.sp,
            style = TextStyle(shadow = shadow),
        )
        Text(
            date,
            color = colors.pageText,
            fontSize = 38.sp,
            fontWeight = FontWeight.Bold,
            lineHeight = 42.sp,
            letterSpacing = (-0.5).sp,
            style = TextStyle(shadow = shadow),
        )
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun Pill(text: String, colors: TodayColors, modifier: Modifier = Modifier, bold: Boolean = false, onClick: () -> Unit) {
    Text(
        text,
        color = Color.White,
        fontSize = 15.sp,
        fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Medium,
        modifier = modifier
            .pressScale(pressedScale = Motion.CONTROL_PRESSED_SCALE)
            .clip(RoundedCornerShape(50))
            .background(colors.pill)
            .border(0.75.dp, colors.pillBorder, RoundedCornerShape(50))
            .clickableNoRipple(onClick)
            .padding(horizontal = 18.dp, vertical = 7.dp),
    )
}

// ---- Grid ----

@Composable
private fun TodayGrid(
    items: List<TodayItem>,
    editing: Boolean,
    progress: () -> Float,
    state: TodayPageState,
    host: TodayHost,
    widgets: TodayWidgets,
    repository: TodayRepository,
    colors: TodayColors,
) {
    val density = LocalDensity.current
    val gapPx = with(density) { TodayWidgets.GRID_GAP_DP.dp.roundToPx() }
    val marginPx = with(density) { TodayWidgets.GRID_MARGIN_DP.dp.roundToPx() }

    // The order shown, which a drag changes live before it's saved.
    val order = remember { items.toMutableStateList() }
    var draggingId by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(items) {
        if (draggingId == null) {
            order.clear()
            order.addAll(items)
        }
    }
    val bounds = remember { mutableStateMapOf<String, Rect>() }
    val haptics = LocalHapticFeedback.current
    var dragPointer by remember { mutableStateOf(Offset.Zero) }
    var grabOffset by remember { mutableStateOf(Offset.Zero) }
    val scope = rememberCoroutineScope()

    Layout(
        modifier = Modifier.fillMaxWidth().padding(horizontal = with(density) { marginPx.toDp() }),
        content = {
            order.forEachIndexed { index, item ->
                key(item.id) {
                    val dragging = draggingId == item.id
                    // Picked up, the item lifts off the page; dropped, it settles into its slot.
                    val lift by animateFloatAsState(if (dragging) 1f else 0f, Motion.appear(), label = "lift")
                    val settle = remember { Animatable(Offset.Zero, Offset.VectorConverter) }
                    val dragTranslation = {
                        val b = bounds[item.id] ?: Rect.Zero
                        dragPointer - grabOffset - b.topLeft
                    }
                    val endDrag: () -> Unit = {
                        val from = dragTranslation()
                        state.touchingItem = false
                        draggingId = null
                        repository.set(order.toList())
                        haptics.performHapticFeedback(HapticFeedbackType.GestureEnd)
                        scope.launch {
                            settle.snapTo(from)
                            settle.animateTo(Offset.Zero, Motion.appear(Offset.VisibilityThreshold))
                        }
                    }
                    Box(
                        Modifier
                            .onGloballyPositioned { bounds[item.id] = it.boundsInParent() }
                            .animatePlacement(enabled = !dragging)
                            .animateResize(enabled = item is TodayItem.Card)
                            .zIndex(if (dragging || settle.isRunning) 1f else 0f)
                            .staggeredEntrance(index + 1, progress)
                            .graphicsLayer {
                                val t = if (dragging) dragTranslation() else settle.value
                                translationX = t.x
                                translationY = t.y
                                val scale = 1f + 0.05f * lift
                                scaleX = scale
                                scaleY = scale
                                shadowElevation = lift * 20.dp.toPx()
                                shape = TodayCardShape
                            }
                            .jiggle(editing && !dragging, index),
                    ) {
                        TodayItemView(item, host, widgets, colors, editing)
                        if (editing) {
                            // Takes every touch in edit mode: tap to resize, hold and drag to
                            // move. A plain swipe still scrolls the page.
                            Box(
                                Modifier
                                    .matchParentSize()
                                    .pointerInput(item) {
                                        detectTapGestures {
                                            val sizes = when (item) {
                                                is TodayItem.Card -> item.card.sizes
                                                is TodayItem.Widget -> widgets.sizesFor(item)
                                            }
                                            val next = sizes[(sizes.indexOf(item.size) + 1) % sizes.size]
                                            if (next != item.size) {
                                                haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
                                                repository.replace(
                                                    item,
                                                    when (item) {
                                                        is TodayItem.Card -> item.copy(size = next)
                                                        is TodayItem.Widget -> item.copy(size = next)
                                                    },
                                                )
                                            }
                                        }
                                    }
                                    .pointerInput(item.id) {
                                        detectDragGesturesAfterLongPress(
                                            onDragStart = { pos ->
                                                val b = bounds[item.id] ?: return@detectDragGesturesAfterLongPress
                                                state.touchingItem = true
                                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                                grabOffset = pos
                                                dragPointer = b.topLeft + pos
                                                draggingId = item.id
                                            },
                                            onDrag = { change, amount ->
                                                change.consume()
                                                dragPointer += amount
                                                val target = order.indexOfFirst {
                                                    it.id != item.id && bounds[it.id]?.let { b -> b.deflate(b.minDimension * 0.15f).contains(dragPointer) } == true
                                                }
                                                val from = order.indexOfFirst { it.id == item.id }
                                                if (target >= 0 && from >= 0) {
                                                    order.add(target, order.removeAt(from))
                                                    haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                                                }
                                            },
                                            onDragEnd = endDrag,
                                            onDragCancel = endDrag,
                                        )
                                    },
                            )
                        }
                        AnimatedVisibility(
                            editing,
                            enter = popIn(),
                            exit = popOut(),
                            modifier = Modifier
                                .align(Alignment.TopStart)
                                .offset((-8).dp, (-8).dp),
                        ) {
                            RemoveBadge(Modifier) {
                                when (item) {
                                    is TodayItem.Widget -> widgets.remove(item)
                                    is TodayItem.Card -> repository.remove(item)
                                }
                            }
                        }
                    }
                }
            }
        },
    ) { measurables, constraints ->
        val width = constraints.maxWidth
        val side = (width - gapPx) / 2
        var y = 0
        var halfOpen = false
        val positions = ArrayList<IntOffset>(measurables.size)
        val placeables = measurables.mapIndexed { i, measurable ->
            val size = order.getOrNull(i)?.size ?: TodaySize.Small
            val (w, h) = when (size) {
                TodaySize.Small -> side to side
                TodaySize.Medium -> width to side
                TodaySize.Large -> width to side * 2 + gapPx
            }
            if (size == TodaySize.Small) {
                if (halfOpen) {
                    positions += IntOffset(side + gapPx, y)
                    y += side + gapPx
                    halfOpen = false
                } else {
                    positions += IntOffset(0, y)
                    halfOpen = true
                }
            } else {
                if (halfOpen) {
                    y += side + gapPx
                    halfOpen = false
                }
                positions += IntOffset(0, y)
                y += h + gapPx
            }
            measurable.measure(Constraints.fixed(w, h))
        }
        if (halfOpen) y += side + gapPx
        val height = (y - gapPx).coerceAtLeast(0)
        layout(width, height) {
            placeables.forEachIndexed { i, p -> p.place(positions[i]) }
        }
    }
}

@Composable
private fun TodayItemView(item: TodayItem, host: TodayHost, widgets: TodayWidgets, colors: TodayColors, editing: Boolean) {
    val accent = (item as? TodayItem.Card)?.card?.accent()
    Box(
        Modifier
            .fillMaxSize()
            .pressScale(enabled = !editing)
            .clip(TodayCardShape)
            .background(colors.cardFill)
            .then(
                if (accent != null) {
                    // A faint glow of the card's colour from its top corner.
                    Modifier.drawBehind {
                        drawRect(
                            Brush.radialGradient(
                                listOf(accent.copy(alpha = if (colors.dark) 0.16f else 0.12f), Color.Transparent),
                                center = Offset(0f, 0f),
                                radius = size.maxDimension * 0.9f,
                            ),
                        )
                    }
                } else {
                    Modifier
                },
            )
            .border(0.75.dp, colors.cardBorder, TodayCardShape),
    ) {
        when (item) {
            is TodayItem.Card -> TodayCardContent(item.card, item.size, host, colors)
            is TodayItem.Widget -> WidgetView(item, widgets, colors)
        }
    }
}

@Composable
private fun WidgetView(item: TodayItem.Widget, widgets: TodayWidgets, colors: TodayColors) {
    val view = remember(item.appWidgetId) { widgets.getView(item) }
    if (view == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Widget unavailable", color = colors.secondary, fontSize = 13.sp)
        }
        return
    }
    val density = LocalDensity.current
    AndroidView(
        factory = {
            (view.parent as? android.view.ViewGroup)?.removeView(view)
            view
        },
        modifier = Modifier
            .fillMaxSize()
            // Only on a real size change: it tells the widget's app, and positions change on
            // every frame while the grid animates.
            .onSizeChanged {
                with(density) { view.updateSize(it.width.toDp().value, it.height.toDp().value) }
            },
    )
}

@Composable
private fun RemoveBadge(modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .pressScale(pressedScale = Motion.CONTROL_PRESSED_SCALE)
            .size(26.dp)
            .clip(CircleShape)
            .background(Color(0xFFE5E5EA))
            .border(0.5.dp, Color.Black.copy(alpha = 0.1f), CircleShape)
            .clickableNoRipple(onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Rounded.Remove, contentDescription = "Remove", tint = Color.Black, modifier = Modifier.size(18.dp))
    }
}

/** The iOS edit mode wobble. Neighbouring items wobble out of step. */
private fun Modifier.jiggle(enabled: Boolean, index: Int): Modifier = composed {
    // Eases in and out instead of starting and stopping dead.
    val amplitude by animateFloatAsState(if (enabled) 1f else 0f, tween(220), label = "jiggleAmplitude")
    if (amplitude == 0f) return@composed this
    val transition = rememberInfiniteTransition(label = "jiggle")
    val angle by transition.animateFloat(
        initialValue = -1f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            tween(durationMillis = 130 + (index % 3) * 15, easing = LinearEasing),
            RepeatMode.Reverse,
        ),
        label = "angle",
    )
    graphicsLayer { rotationZ = amplitude * angle * if (index % 2 == 0) 0.9f else -0.9f }
}

/**
 * Grows or shrinks to a new size with a spring, like when a card is resized in edit mode, instead
 * of jumping. The space it takes in the layout changes right away.
 */
private fun Modifier.animateResize(enabled: Boolean): Modifier = composed {
    val scope = rememberCoroutineScope()
    val animatable = remember { mutableStateOf<Animatable<IntSize, AnimationVector2D>?>(null) }
    var target by remember { mutableStateOf<IntSize?>(null) }
    layout { measurable, constraints ->
        val size = IntSize(constraints.maxWidth, constraints.maxHeight)
        val anim = animatable.value ?: Animatable(size, IntSize.VectorConverter).also { animatable.value = it }
        if (target != size) {
            val first = target == null
            target = size
            scope.launch {
                if (enabled && !first) anim.animateTo(size, Motion.appear(IntSize.VisibilityThreshold)) else anim.snapTo(size)
            }
        }
        val current = anim.value
        val placeable = measurable.measure(Constraints.fixed(current.width.coerceAtLeast(0), current.height.coerceAtLeast(0)))
        layout(size.width, size.height) { placeable.place(0, 0) }
    }
}

/** Slides an item from its old spot to its new one when the grid reflows. */
private fun Modifier.animatePlacement(enabled: Boolean): Modifier = composed {
    val scope = rememberCoroutineScope()
    var target by remember { mutableStateOf<IntOffset?>(null) }
    val animatable = remember { mutableStateOf<Animatable<IntOffset, *>?>(null) }
    this
        .onPlaced { coordinates ->
            val position = coordinates.positionInParent().round()
            val previous = target
            target = position
            val anim = animatable.value
            if (anim == null || previous == null) {
                animatable.value = Animatable(position, IntOffset.VectorConverter)
            } else if (anim.targetValue != position) {
                scope.launch {
                    if (enabled) {
                        anim.animateTo(position, Motion.appear(IntOffset.VisibilityThreshold))
                    } else {
                        anim.snapTo(position)
                    }
                }
            }
        }
        .offset {
            val anim = animatable.value
            val t = target
            if (anim == null || t == null) IntOffset.Zero else anim.value - t
        }
}

// ---- Add sheet ----

@Composable
private fun AddSheet(
    visible: Boolean,
    items: List<TodayItem>,
    widgets: TodayWidgets,
    colors: TodayColors,
    onAddCard: (TodayCard) -> Unit,
    onAddWidget: (AppWidgetProviderInfo) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    AnimatedVisibility(visible, enter = fadeIn(), exit = fadeOut()) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.3f))
                .pointerInput(Unit) { detectTapGestures { onDismiss() } },
        )
    }
    AnimatedVisibility(
        visible,
        enter = slideInVertically(Motion.appear(IntOffset.VisibilityThreshold)) { it },
        exit = slideOutVertically(spring(stiffness = Spring.StiffnessMedium, visibilityThreshold = IntOffset.VisibilityThreshold)) { it },
        modifier = Modifier.fillMaxSize(),
    ) {
        val sheetColor = if (colors.dark) Color(0xFF1C1C1E) else Color(0xFFF2F2F7)
        val shown = remember(items) { items.filterIsInstance<TodayItem.Card>().map { it.card }.toSet() }
        val cards = TodayCard.entries.filter { it !in shown }
        val groups = remember {
            val pm = context.packageManager
            widgets.installedProviders()
                .groupBy { it.provider.packageName }
                .map { (pkg, list) ->
                    val label = runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }
                        .getOrDefault(pkg)
                    label to list.sortedBy { it.loadLabel(pm) }
                }
                .sortedBy { it.first.lowercase() }
        }
        Box(Modifier.fillMaxSize()) {
            Column(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(top = 64.dp)
                    .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
                    .background(sheetColor)
                    .pointerInput(Unit) { detectTapGestures { } },
            ) {
                Box(Modifier.fillMaxWidth().padding(16.dp)) {
                    Text(
                        "Add Widget",
                        color = colors.text,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.align(Alignment.Center),
                    )
                    Text(
                        "Cancel",
                        color = Color(0xFF0A84FF),
                        fontSize = 17.sp,
                        modifier = Modifier.align(Alignment.CenterStart).clickableNoRipple(onDismiss),
                    )
                }
                LazyColumn(
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        start = 16.dp,
                        end = 16.dp,
                        bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 16.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (cards.isNotEmpty()) {
                        item { SheetHeader("Lawnchair", colors) }
                        items(cards, key = { "card:${it.name}" }) { card ->
                            SheetRow(card.title, null, colors) { onAddCard(card) }
                        }
                    }
                    groups.forEach { (label, providers) ->
                        item(key = "header:$label") { SheetHeader(label, colors) }
                        items(providers, key = { it.provider.flattenToString() }) { info ->
                            val pm = context.packageManager
                            val preview = remember(info) {
                                runCatching { info.loadPreviewImage(context, 0) ?: info.loadIcon(context, 0) }.getOrNull()
                            }
                            SheetRow(info.loadLabel(pm), preview, colors) { onAddWidget(info) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SheetHeader(title: String, colors: TodayColors) {
    Text(
        title,
        color = colors.secondary,
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = 4.dp, top = 12.dp, bottom = 2.dp),
    )
}

@Composable
private fun SheetRow(title: String, preview: android.graphics.drawable.Drawable?, colors: TodayColors, onClick: () -> Unit) {
    val rowColor = if (colors.dark) Color(0xFF2C2C2E) else Color.White
    Row(
        Modifier
            .fillMaxWidth()
            .pressScale()
            .clip(RoundedCornerShape(12.dp))
            .background(rowColor)
            .clickableNoRipple(onClick)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (preview != null) {
            Image(
                rememberDrawablePainter(preview),
                contentDescription = null,
                modifier = Modifier.size(width = 72.dp, height = 56.dp),
            )
            Spacer(Modifier.width(12.dp))
        }
        Text(
            title,
            color = colors.text,
            fontSize = 16.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Icon(Icons.Rounded.Add, contentDescription = "Add", tint = Color(0xFF0A84FF))
    }
}
