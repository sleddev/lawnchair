package app.lawnchair.applibrary

import android.graphics.Rect
import android.view.HapticFeedbackConstants
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AddBox
import androidx.compose.material.icons.rounded.Cancel
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.lawnchair.ui.theme.Glass
import app.lawnchair.ui.theme.Motion
import app.lawnchair.ui.theme.glassClickable
import app.lawnchair.ui.theme.staggeredEntrance
import com.android.launcher3.model.data.AppInfo
import com.google.accompanist.drawablepainter.rememberDrawablePainter
import java.text.Normalizer
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/** What the App Library asks of the view hosting it. */
interface AppLibraryHost {
    fun launch(app: AppInfo)

    /** Long press on an app, with where its icon is drawn, in the page's own coordinates. */
    fun onAppLongPress(app: AppInfo, iconBounds: Rect)
    fun addToHome(app: AppInfo)
    fun showAppInfo(app: AppInfo)
    fun canUninstall(app: AppInfo): Boolean
    fun uninstall(app: AppInfo)
    fun hideKeyboard()
}

/** The context menu of a long pressed app. */
class AppMenu(val app: AppInfo, val iconBounds: Rect)

class AppLibraryUiState {
    val progress: MutableFloatState = mutableFloatStateOf(0f)

    /** The alphabetical list, opened from the search field or by pulling the page down. */
    val searching = mutableStateOf(false)
    val query = mutableStateOf("")

    // Bumped to ask for the search field to be focused, with the keyboard up.
    val focusRequests = mutableIntStateOf(0)

    // Bumped to scroll the categories back to the top.
    val resets = mutableIntStateOf(0)

    val expanded = mutableStateOf<LibraryCategory?>(null)
    val menu = mutableStateOf<AppMenu?>(null)

    /** True while something's open over the page, which a swipe shouldn't close it from under. */
    val isModal: Boolean get() = searching.value || expanded.value != null || menu.value != null

    fun openSearch(focus: Boolean) {
        searching.value = true
        if (focus) focusRequests.intValue++
    }

    fun closeSearch() {
        searching.value = false
        query.value = ""
    }

    fun reset() {
        menu.value = null
        expanded.value = null
        closeSearch()
        resets.intValue++
    }
}

private class LibraryColors(dark: Boolean) {
    val card = Glass.cardFill(dark)
    val border = Glass.border(dark)
    val field = Color(Glass.field(dark))
    val fieldText = if (dark) Color.White else Color(Glass.CARD_TEXT_LIGHT)
    val fieldHint = if (dark) Color.White.copy(alpha = 0.6f) else Color.Black.copy(alpha = 0.45f)
    val cardText = fieldText
    val highlight = if (dark) Color.White.copy(alpha = 0.16f) else Color.Black.copy(alpha = 0.07f)
    val menuSeparator = if (dark) Color.White.copy(alpha = 0.12f) else Color.Black.copy(alpha = 0.10f)
    val danger = if (dark) Color(0xFFFF453A) else Color(0xFFFF3B30)

    // Text straight on the blurred wallpaper: white in both themes, like the Today page.
    val pageText = Glass.PageText
    val pageSecondary = Glass.PageText.copy(alpha = 0.7f)
    val pageSeparator = Glass.PageText.copy(alpha = 0.16f)
    val pageHighlight = Color.White.copy(alpha = 0.14f)
    val shadow = Shadow(Color.Black.copy(alpha = 0.35f), Offset(0f, 1f), 8f)
}

private val TileShape = RoundedCornerShape(26.dp)
private val PanelShape = RoundedCornerShape(36.dp)
private val MenuShape = RoundedCornerShape(16.dp)
private val PageMargin = 24.dp

@Composable
internal fun AppLibraryContent(
    state: AppLibraryUiState,
    repository: AppLibraryRepository,
    host: AppLibraryHost,
    isDark: Boolean,
) {
    val colors = remember(isDark) { LibraryColors(isDark) }
    val data by repository.state.collectAsState()
    val progress = { state.progress.floatValue }
    val searching by state.searching
    // The page blurs away behind an open category or menu, like iOS.
    val covered = state.expanded.value != null || state.menu.value != null
    val coverBlur by animateDpAsState(if (covered) 24.dp else 0.dp, tween(220), label = "coverBlur")

    Box(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .then(if (coverBlur > 0.dp) Modifier.blur(coverBlur) else Modifier)
                .statusBarsPadding()
                .padding(top = 12.dp),
        ) {
            SearchHeader(state, colors, data, host, Modifier.staggeredEntrance(0, progress))
            Crossfade(searching, animationSpec = tween(200), label = "libraryMode") { list ->
                if (list) {
                    AppList(state, data.allApps, colors, host)
                } else {
                    CategoryGrid(state, data.categories, colors, host, progress)
                }
            }
        }
        ExpandedCategory(state, colors, host)
        AppMenuOverlay(state, colors, host)
    }
}

// ---- Search field ----

@Composable
private fun SearchHeader(
    state: AppLibraryUiState,
    colors: LibraryColors,
    data: AppLibraryState,
    host: AppLibraryHost,
    modifier: Modifier = Modifier,
) {
    val searching by state.searching
    var query by state.query
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(state.focusRequests.intValue) {
        if (state.focusRequests.intValue > 0) {
            focusRequester.requestFocus()
            keyboard?.show()
        }
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = PageMargin)
            .padding(bottom = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .height(44.dp)
                .clip(Glass.FieldShape)
                .background(colors.field)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                    state.openSearch(focus = true)
                }
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = if (searching) Arrangement.Start else Arrangement.Center,
        ) {
            Icon(Icons.Rounded.Search, contentDescription = null, tint = colors.fieldHint, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(6.dp))
            Box(
                modifier = if (searching) Modifier.weight(1f) else Modifier,
                contentAlignment = Alignment.CenterStart,
            ) {
                if (query.isEmpty()) {
                    Text("App Library", color = colors.fieldHint, fontSize = 17.sp, maxLines = 1)
                }
                if (searching) {
                    BasicTextField(
                        value = query,
                        onValueChange = { query = it },
                        singleLine = true,
                        textStyle = TextStyle(color = colors.fieldText, fontSize = 17.sp),
                        cursorBrush = SolidColor(colors.fieldText),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                        keyboardActions = KeyboardActions(onGo = {
                            filterApps(data.allApps, query).firstOrNull()?.let(host::launch)
                        }),
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(focusRequester),
                    )
                }
            }
            if (searching && query.isNotEmpty()) {
                Icon(
                    Icons.Rounded.Cancel,
                    contentDescription = "Clear",
                    tint = colors.fieldHint,
                    modifier = Modifier
                        .size(20.dp)
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                            query = ""
                        },
                )
            }
        }
        AnimatedVisibility(visible = searching, enter = fadeIn(), exit = fadeOut()) {
            Text(
                "Cancel",
                color = colors.pageText,
                fontSize = 17.sp,
                style = TextStyle(shadow = colors.shadow),
                modifier = Modifier
                    .padding(start = 14.dp)
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                        host.hideKeyboard()
                        state.closeSearch()
                    },
            )
        }
    }
}

// ---- Categories ----

@Composable
private fun CategoryGrid(
    state: AppLibraryUiState,
    categories: List<LibraryCategory>,
    colors: LibraryColors,
    host: AppLibraryHost,
    progress: () -> Float,
) {
    val gridState = rememberLazyGridState()
    val density = LocalDensity.current
    LaunchedEffect(state.resets.intValue) { gridState.scrollToItem(0) }

    // Pulling the page down from the top opens the list, like on iOS.
    val pullConnection = remember(state) {
        val threshold = with(density) { PULL_TO_LIST.toPx() }
        object : NestedScrollConnection {
            var pulled = 0f

            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput && available.y > 0f) {
                    pulled += available.y
                    if (pulled > threshold) {
                        pulled = 0f
                        state.openSearch(focus = false)
                    }
                } else if (consumed.y != 0f) {
                    pulled = 0f
                }
                return Offset.Zero
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                pulled = 0f
                return Velocity.Zero
            }
        }
    }

    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        state = gridState,
        modifier = Modifier
            .fillMaxSize()
            .nestedScroll(pullConnection),
        contentPadding = PaddingValues(start = PageMargin, end = PageMargin, top = 4.dp, bottom = bottom + 24.dp),
        horizontalArrangement = Arrangement.spacedBy(22.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        itemsIndexed(categories, key = { _, it -> it.key }) { index, category ->
            CategoryTile(
                category = category,
                colors = colors,
                host = host,
                onExpand = {
                    host.hideKeyboard()
                    state.expanded.value = category
                },
                modifier = Modifier
                    .animateItem()
                    .staggeredEntrance(index / 2 + 1, progress),
            )
        }
    }
}

@Composable
private fun CategoryTile(
    category: LibraryCategory,
    colors: LibraryColors,
    host: AppLibraryHost,
    onExpand: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(TileShape)
                .background(colors.card)
                .border(1.dp, colors.border, TileShape),
        ) {
            val pad = maxWidth * 0.1f
            val gap = maxWidth * 0.09f
            val iconSize = (maxWidth - pad * 2 - gap) / 2
            Column(Modifier.padding(pad), verticalArrangement = Arrangement.spacedBy(gap)) {
                repeat(2) { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                        repeat(2) { col ->
                            val index = row * 2 + col
                            if (index == 3 && !category.allLarge) {
                                MiniGrid(category.apps.drop(3).take(4), iconSize, onExpand)
                            } else {
                                val app = category.apps.getOrNull(index)
                                if (app != null) {
                                    LibraryIcon(app, iconSize, host)
                                } else {
                                    Spacer(Modifier.size(iconSize))
                                }
                            }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            category.title,
            color = colors.pageText,
            fontSize = 13.sp,
            style = TextStyle(shadow = colors.shadow),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}

/** The small icons in a category's corner, which open the whole category. */
@Composable
private fun MiniGrid(apps: List<AppInfo>, size: Dp, onExpand: () -> Unit) {
    val gap = size * 0.1f
    val mini = (size - gap) / 2
    Column(
        modifier = Modifier
            .size(size)
            .glassClickable(Color.Transparent, RoundedCornerShape(12.dp), pressedScale = Motion.CONTROL_PRESSED_SCALE, onClick = onExpand),
        verticalArrangement = Arrangement.spacedBy(gap),
    ) {
        repeat(2) { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                repeat(2) { col ->
                    val app = apps.getOrNull(row * 2 + col)
                    if (app != null) AppIconImage(app, mini) else Spacer(Modifier.size(mini))
                }
            }
        }
    }
}

/** An app icon that opens the app, and shows its menu (or starts a drag) on a long press. */
@Composable
private fun LibraryIcon(app: AppInfo, size: Dp, host: AppLibraryHost, modifier: Modifier = Modifier) {
    val bounds = remember { Rect() }
    AppIconImage(
        app,
        size,
        modifier
            .onGloballyPositioned { coords ->
                val r = coords.boundsInRoot()
                bounds.set(r.left.roundToInt(), r.top.roundToInt(), r.right.roundToInt(), r.bottom.roundToInt())
            }
            .glassClickable(
                Color.Transparent,
                RoundedCornerShape(size * 0.25f),
                pressedScale = Motion.CONTROL_PRESSED_SCALE,
                onLongClick = { host.onAppLongPress(app, bounds) },
                onClick = { host.launch(app) },
            ),
    )
}

@Composable
private fun AppIconImage(app: AppInfo, size: Dp, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val drawable = remember(app) { app.newIcon(context) }
    Image(
        painter = rememberDrawablePainter(drawable),
        contentDescription = app.title?.toString(),
        modifier = modifier.size(size),
    )
}

// ---- The whole category ----

@Composable
private fun ExpandedCategory(state: AppLibraryUiState, colors: LibraryColors, host: AppLibraryHost) {
    val expanded = state.expanded.value
    // Kept through the exit animation.
    var shown by remember { mutableStateOf<LibraryCategory?>(null) }
    if (expanded != null) shown = expanded

    AnimatedVisibility(visible = expanded != null, enter = fadeIn(tween(180)), exit = fadeOut(tween(160))) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.35f))
                .pointerInput(Unit) { detectTapGestures { state.expanded.value = null } },
            contentAlignment = Alignment.Center,
        ) {
            val category = shown ?: return@Box
            Column(
                modifier = Modifier
                    .animateEnterExit(
                        enter = scaleIn(Motion.appear(), initialScale = 0.85f),
                        exit = scaleOut(tween(160), targetScale = 0.9f),
                    )
                    .padding(horizontal = 20.dp),
            ) {
                Text(
                    category.title,
                    color = colors.pageText,
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold,
                    style = TextStyle(shadow = colors.shadow),
                    modifier = Modifier.padding(start = 12.dp, bottom = 14.dp),
                )
                val apps = category.apps
                LazyVerticalGrid(
                    columns = GridCells.Fixed(EXPANDED_COLUMNS),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 520.dp)
                        .clip(PanelShape)
                        .background(colors.card)
                        .border(1.dp, colors.border, PanelShape)
                        // Taps between icons stay in the panel.
                        .pointerInput(Unit) { detectTapGestures { } },
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 22.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                ) {
                    items(apps.size, key = { apps[it].toComponentKey().toString() }) { index ->
                        val app = apps[index]
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            LibraryIcon(app, 58.dp, host)
                            Spacer(Modifier.height(6.dp))
                            Text(
                                app.title?.toString().orEmpty(),
                                color = colors.cardText,
                                fontSize = 12.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(horizontal = 4.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

// ---- Alphabetical list ----

private sealed interface ListEntry {
    val key: String

    data class Header(val letter: String) : ListEntry {
        override val key get() = "header:$letter"
    }

    data class App(val app: AppInfo, val last: Boolean) : ListEntry {
        override val key get() = app.toComponentKey().toString()
    }
}

private fun normalize(text: String) = Normalizer.normalize(text, Normalizer.Form.NFKD)
    .replace(Regex("\\p{M}"), "")
    .lowercase(Locale.getDefault())

private fun sectionOf(app: AppInfo): String {
    val first = normalize(app.title?.toString().orEmpty()).firstOrNull { !it.isWhitespace() } ?: return "#"
    return if (first in 'a'..'z') first.uppercase() else "#"
}

/** Apps whose name matches [query]: those starting with it first, then words starting with it. */
private fun filterApps(apps: List<AppInfo>, query: String): List<AppInfo> {
    val q = normalize(query.trim())
    if (q.isEmpty()) return apps
    return apps.mapNotNull { app ->
        val title = normalize(app.title?.toString().orEmpty())
        when {
            title.startsWith(q) -> app to 0
            title.split(' ', '-', '.').any { it.startsWith(q) } -> app to 1
            q in title -> app to 2
            else -> null
        }
    }.sortedBy { it.second }.map { it.first }
}

@Composable
private fun AppList(state: AppLibraryUiState, allApps: List<AppInfo>, colors: LibraryColors, host: AppLibraryHost) {
    val query by state.query
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val view = LocalView.current

    val entries = remember(allApps, query) {
        if (query.isNotBlank()) {
            val matches = filterApps(allApps, query)
            matches.mapIndexed { i, app -> ListEntry.App(app, i == matches.lastIndex) }
        } else {
            val sections = allApps.groupBy(::sectionOf).toSortedMap(compareBy { if (it == "#") "￿" else it })
            sections.flatMap { (letter, apps) ->
                listOf(ListEntry.Header(letter)) + apps.mapIndexed { i, app -> ListEntry.App(app, i == apps.lastIndex) }
            }
        }
    }
    val letters = remember(entries) {
        entries.withIndex().filter { it.value is ListEntry.Header }.map { (it.value as ListEntry.Header).letter to it.index }
    }

    LaunchedEffect(listState.isScrollInProgress) {
        if (listState.isScrollInProgress) host.hideKeyboard()
    }
    LaunchedEffect(query) { listState.scrollToItem(0) }

    val bottom = WindowInsets.ime.union(WindowInsets.navigationBars).asPaddingValues().calculateBottomPadding()
    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = PageMargin, end = PageMargin + 8.dp, bottom = bottom + 24.dp),
        ) {
            items(entries, key = { it.key }) { entry ->
                when (entry) {
                    is ListEntry.Header -> Text(
                        entry.letter,
                        color = colors.pageSecondary,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        style = TextStyle(shadow = colors.shadow),
                        modifier = Modifier.padding(start = 4.dp, top = 14.dp, bottom = 4.dp),
                    )

                    is ListEntry.App -> AppRow(entry.app, entry.last, colors, host)
                }
            }
        }
        if (query.isBlank() && letters.size > 1) {
            LetterIndex(
                letters = letters.map { it.first },
                onLetter = { i ->
                    view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    scope.launch { listState.scrollToItem(letters[i].second) }
                },
                colors = colors,
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 2.dp, bottom = bottom),
            )
        }
    }
}

@Composable
private fun AppRow(app: AppInfo, last: Boolean, colors: LibraryColors, host: AppLibraryHost) {
    val bounds = remember { Rect() }
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .glassClickable(
                    colors.pageHighlight,
                    RoundedCornerShape(12.dp),
                    pressedScale = Motion.CARD_PRESSED_SCALE,
                    onLongClick = { host.onAppLongPress(app, bounds) },
                    onClick = { host.launch(app) },
                )
                .padding(horizontal = 4.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AppIconImage(
                app,
                42.dp,
                Modifier.onGloballyPositioned { coords ->
                    val r = coords.boundsInRoot()
                    bounds.set(r.left.roundToInt(), r.top.roundToInt(), r.right.roundToInt(), r.bottom.roundToInt())
                },
            )
            Spacer(Modifier.width(14.dp))
            Text(
                app.title?.toString().orEmpty(),
                color = colors.pageText,
                fontSize = 17.sp,
                style = TextStyle(shadow = colors.shadow),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (!last) {
            Box(
                Modifier
                    .padding(start = 60.dp)
                    .fillMaxWidth()
                    .height(0.5.dp)
                    .background(colors.pageSeparator),
            )
        }
    }
}

/** The A to Z strip along the edge: tap or slide along it to jump to a letter. */
@Composable
private fun LetterIndex(letters: List<String>, onLetter: (Int) -> Unit, colors: LibraryColors, modifier: Modifier = Modifier) {
    var current by remember { mutableIntStateOf(-1) }
    var height by remember { mutableFloatStateOf(1f) }
    val pick = { y: Float ->
        val i = (y / height * letters.size).toInt().coerceIn(0, letters.lastIndex)
        if (i != current) {
            current = i
            onLetter(i)
        }
    }
    Column(
        modifier = modifier
            .width(20.dp)
            .onGloballyPositioned { height = it.size.height.toFloat().coerceAtLeast(1f) }
            .pointerInput(letters) {
                detectTapGestures(onPress = { pick(it.y) }, onTap = { current = -1 })
            }
            .pointerInput(letters) {
                detectVerticalDragGestures(
                    onDragStart = { pick(it.y) },
                    onDragEnd = { current = -1 },
                    onDragCancel = { current = -1 },
                    onVerticalDrag = { change, _ -> pick(change.position.y) },
                )
            },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        letters.forEach { letter ->
            Text(
                letter,
                color = colors.pageText,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                style = TextStyle(shadow = colors.shadow),
                modifier = Modifier.padding(vertical = 1.dp),
            )
        }
    }
}

// ---- Context menu ----

@Composable
private fun AppMenuOverlay(state: AppLibraryUiState, colors: LibraryColors, host: AppLibraryHost) {
    val menu = state.menu.value
    var shown by remember { mutableStateOf<AppMenu?>(null) }
    if (menu != null) shown = menu

    AnimatedVisibility(visible = menu != null, enter = fadeIn(tween(150)), exit = fadeOut(tween(150))) {
        val current = shown ?: return@AnimatedVisibility
        val density = LocalDensity.current
        val iconSize = with(density) { current.iconBounds.width().toDp() }
        val canUninstall = remember(current) { host.canUninstall(current.app) }
        Layout(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.3f))
                .pointerInput(Unit) { detectTapGestures { state.menu.value = null } },
            content = {
                // The pressed icon, lifted above the dimmed page.
                AppIconImage(current.app, iconSize, Modifier.graphicsLayer { scaleX = 1.08f; scaleY = 1.08f })
                Column(
                    modifier = Modifier
                        .animateEnterExit(enter = scaleIn(Motion.pop(), initialScale = 0.6f), exit = scaleOut(targetScale = 0.8f))
                        .widthIn(min = 240.dp, max = 280.dp)
                        .clip(MenuShape)
                        .background(colors.card)
                        .border(1.dp, colors.border, MenuShape)
                        .pointerInput(Unit) { detectTapGestures { } },
                ) {
                    MenuItem("Add to Home Screen", Icons.Rounded.AddBox, colors.cardText, colors) { host.addToHome(current.app) }
                    MenuDivider(colors)
                    MenuItem("App Info", Icons.Rounded.Info, colors.cardText, colors) { host.showAppInfo(current.app) }
                    if (canUninstall) {
                        MenuDivider(colors)
                        MenuItem("Delete App", Icons.Rounded.Delete, colors.danger, colors) { host.uninstall(current.app) }
                    }
                }
            },
        ) { measurables, constraints ->
            val loose = constraints.copy(minWidth = 0, minHeight = 0)
            val icon = measurables[0].measure(loose)
            val panel = measurables[1].measure(Constraints(maxWidth = constraints.maxWidth, maxHeight = constraints.maxHeight))
            layout(constraints.maxWidth, constraints.maxHeight) {
                val bounds = current.iconBounds
                icon.place(bounds.left, bounds.top)
                val margin = 16.dp.roundToPx()
                val gap = 12.dp.roundToPx()
                // Below the icon if it fits, else above it; lined up with whichever side of the
                // screen the icon is on.
                val below = bounds.bottom + gap
                val y = if (below + panel.height + margin < constraints.maxHeight) below else bounds.top - gap - panel.height
                val x = if (bounds.centerX() < constraints.maxWidth / 2) bounds.left else bounds.right - panel.width
                panel.place(
                    x.coerceIn(margin, (constraints.maxWidth - panel.width - margin).coerceAtLeast(margin)),
                    y.coerceAtLeast(margin),
                )
            }
        }
    }
}

@Composable
private fun MenuItem(label: String, icon: ImageVector, color: Color, colors: LibraryColors, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .glassClickable(colors.highlight, RoundedCornerShape(0.dp), pressedScale = 1f, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = color, fontSize = 16.sp, modifier = Modifier.weight(1f))
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun MenuDivider(colors: LibraryColors) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(0.5.dp)
            .background(colors.menuSeparator),
    )
}

private val PULL_TO_LIST = 72.dp
private const val EXPANDED_COLUMNS = 4
