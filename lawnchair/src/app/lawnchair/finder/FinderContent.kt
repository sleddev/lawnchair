package app.lawnchair.finder

import android.graphics.drawable.Drawable
import android.graphics.drawable.Icon
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cancel
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.android.launcher3.R
import com.android.launcher3.model.data.AppInfo
import com.google.accompanist.drawablepainter.rememberDrawablePainter

private class FinderColors(dark: Boolean) {
    val scrim = if (dark) Color.Black.copy(alpha = 0.32f) else Color.White.copy(alpha = 0.22f)
    val card = if (dark) Color.White.copy(alpha = 0.11f) else Color.White.copy(alpha = 0.55f)
    val field = if (dark) Color.White.copy(alpha = 0.16f) else Color.White.copy(alpha = 0.7f)
    val highlight = if (dark) Color.White.copy(alpha = 0.16f) else Color.Black.copy(alpha = 0.07f)
    val separator = if (dark) Color.White.copy(alpha = 0.10f) else Color.Black.copy(alpha = 0.08f)
    val text = if (dark) Color.White else Color(0xFF111111)
    val secondary = if (dark) Color.White.copy(alpha = 0.6f) else Color.Black.copy(alpha = 0.5f)
}

@Composable
internal fun FinderContent(
    progress: () -> Float,
    focusRequests: Int,
    isDark: Boolean,
    search: FinderSearch,
    onOpen: (FinderItem) -> Unit,
    onAppLongPress: AppLongPress,
    onDismiss: () -> Unit,
    onScrolled: () -> Unit,
) {
    val colors = remember(isDark) { FinderColors(isDark) }
    val density = LocalDensity.current
    var query by rememberSaveable { mutableStateOf("") }
    val results by search.results.collectAsState()
    val suggestions by search.suggestions.collectAsState()
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val listState = rememberLazyListState()

    LaunchedEffect(query) { search.search(query) }
    LaunchedEffect(focusRequests) {
        if (focusRequests > 0) {
            focusRequester.requestFocus()
            keyboard?.show()
        }
    }
    LaunchedEffect(listState.isScrollInProgress) {
        if (listState.isScrollInProgress) onScrolled()
    }

    // Keep showing the last results while the new ones load, but only let enter open a
    // result that's actually for the current text.
    val current = results
    val primary = current.primary
    val openPrimary = {
        if (query.isNotBlank() && current.query == query.trim()) primary?.let(onOpen)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer { alpha = progress().coerceIn(0f, 1f) }
            .background(colors.scrim)
            .pointerInput(Unit) { detectTapGestures { onDismiss() } },
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    val p = progress().coerceIn(0f, 1f)
                    translationY = -(1f - p) * with(density) { 56.dp.toPx() }
                    val scale = 0.94f + 0.06f * p
                    scaleX = scale
                    scaleY = scale
                }
                .statusBarsPadding()
                .padding(top = 12.dp),
        ) {
            SearchField(
                query = query,
                onQueryChange = { query = it },
                onGo = openPrimary,
                onCancel = onDismiss,
                focusRequester = focusRequester,
                colors = colors,
            )

            val bottomInsets = WindowInsets.ime.union(WindowInsets.navigationBars).asPaddingValues()
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = 4.dp,
                    bottom = bottomInsets.calculateBottomPadding() + 24.dp,
                ),
            ) {
                if (query.isBlank()) {
                    if (suggestions.isNotEmpty()) {
                        header("App Suggestions", colors)
                        item(key = "suggestions") {
                            Card(colors) { AppGrid(suggestions, null, colors, onOpen, onAppLongPress) }
                        }
                    }
                } else {
                    results(current, primary?.key, colors, search, onOpen, onAppLongPress)
                }
            }
        }
    }
}

@Composable
private fun SearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    onGo: () -> Unit,
    onCancel: () -> Unit,
    focusRequester: FocusRequester,
    colors: FinderColors,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .height(46.dp)
                .clip(RoundedCornerShape(23.dp))
                .background(colors.field)
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.Search, contentDescription = null, tint = colors.secondary, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(8.dp))
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                if (query.isEmpty()) {
                    Text("Search", color = colors.secondary, fontSize = 17.sp, maxLines = 1)
                }
                BasicTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    singleLine = true,
                    textStyle = TextStyle(color = colors.text, fontSize = 17.sp),
                    cursorBrush = SolidColor(colors.text),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { onGo() }, onDone = { onGo() }, onSearch = { onGo() }),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester)
                        .onPreviewKeyEvent { event ->
                            val isEnter = event.key == Key.Enter || event.key == Key.NumPadEnter
                            if (isEnter && event.type == KeyEventType.KeyUp) onGo()
                            isEnter
                        },
                )
            }
            if (query.isNotEmpty()) {
                Icon(
                    Icons.Rounded.Cancel,
                    contentDescription = "Clear",
                    tint = colors.secondary,
                    modifier = Modifier
                        .size(20.dp)
                        .clip(CircleShape)
                        .clickable { onQueryChange("") },
                )
            }
        }
        Text(
            text = "Cancel",
            color = colors.text,
            fontSize = 17.sp,
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable(onClick = onCancel)
                .padding(start = 14.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
        )
    }
}

private fun LazyListScope.header(title: String, colors: FinderColors) {
    item(key = "header:$title") {
        Text(
            text = title,
            color = colors.secondary,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 6.dp, top = 18.dp, bottom = 8.dp),
        )
    }
}

private fun LazyListScope.results(
    results: FinderResults,
    highlighted: String?,
    colors: FinderColors,
    search: FinderSearch,
    onOpen: (FinderItem) -> Unit,
    onAppLongPress: AppLongPress,
) {
    results.topHit?.let { hit ->
        header("Top Hit", colors)
        item(key = "top:${hit.key}") {
            Card(colors) { TopHit(hit, colors, search, onOpen, onAppLongPress) }
        }
    }
    if (results.apps.isNotEmpty()) {
        header("Apps", colors)
        item(key = "apps") {
            Card(colors) { AppGrid(results.apps, highlighted, colors, onOpen, onAppLongPress) }
        }
    }
    section("Settings", results.settings, colors) { item, first ->
        ResultRow(
            icon = { SettingsIcon(search, 34.dp) },
            title = item.entry.title,
            subtitle = "Settings › ${item.entry.section}",
            highlighted = item.key == highlighted,
            showDivider = !first,
            colors = colors,
            onClick = { onOpen(item) },
        )
    }
    section("Contacts", results.contacts, colors) { item, first ->
        ResultRow(
            icon = { IconImage(item.icon, 34.dp, CircleShape) },
            title = item.name,
            subtitle = item.detail,
            highlighted = item.key == highlighted,
            showDivider = !first,
            colors = colors,
            onClick = { onOpen(item) },
        )
    }
    section("Files", results.files, colors) { item, first ->
        ResultRow(
            icon = { IconImage(item.icon, 34.dp, RoundedCornerShape(6.dp)) },
            title = item.name,
            subtitle = item.detail,
            highlighted = item.key == highlighted,
            showDivider = !first,
            colors = colors,
            onClick = { onOpen(item) },
        )
    }
    section("Search the Web", results.web, colors) { item, first ->
        ResultRow(
            icon = {
                Icon(Icons.Rounded.Search, contentDescription = null, tint = colors.secondary, modifier = Modifier.size(24.dp))
            },
            title = item.text,
            subtitle = if (item.isSuggestion) null else "Search ${results.webProviderName}",
            highlighted = item.key == highlighted,
            showDivider = !first,
            colors = colors,
            onClick = { onOpen(item) },
        )
    }
}

private fun <T : FinderItem> LazyListScope.section(
    title: String,
    items: List<T>,
    colors: FinderColors,
    row: @Composable (T, Boolean) -> Unit,
) {
    if (items.isEmpty()) return
    header(title, colors)
    item(key = "section:$title") {
        Card(colors) {
            items.forEachIndexed { index, item -> row(item, index == 0) }
        }
    }
}

@Composable
private fun Card(colors: FinderColors, content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(colors.card)
            // Taps between the rows shouldn't dismiss the finder.
            .pointerInput(Unit) { detectTapGestures { } }
            .padding(6.dp),
    ) {
        content()
    }
}

@Composable
private fun TopHit(
    hit: FinderItem,
    colors: FinderColors,
    search: FinderSearch,
    onOpen: (FinderItem) -> Unit,
    onAppLongPress: AppLongPress,
) {
    when (hit) {
        is FinderItem.App -> {
            val iconBounds = remember { IconBounds() }
            ResultRow(
                icon = { AppIcon(hit.app, 54.dp, iconBounds) },
                title = hit.app.title?.toString().orEmpty(),
                subtitle = null,
                highlighted = true,
                colors = colors,
                large = true,
                onClick = { onOpen(hit) },
                onLongClick = { onAppLongPress(hit.app, iconBounds.rect) },
            )
        }

        is FinderItem.Calculation -> BigValue(
            icon = { Image(painterResource(R.drawable.calculator), null, Modifier.size(44.dp).clip(RoundedCornerShape(11.dp))) },
            caption = hit.expression,
            value = "= ${hit.result}",
            extra = emptyList(),
            colors = colors,
            onClick = { onOpen(hit) },
        )

        is FinderItem.Converted -> BigValue(
            icon = { VectorBadge(Icons.Rounded.SwapHoriz, colors) },
            caption = hit.conversion.lines.first().from,
            value = hit.conversion.lines.first().to,
            extra = hit.conversion.lines.drop(1).map { it.to },
            colors = colors,
            onClick = { onOpen(hit) },
        )

        is FinderItem.Setting -> ResultRow(
            icon = { SettingsIcon(search, 54.dp) },
            title = hit.entry.title,
            subtitle = "Settings › ${hit.entry.section}",
            highlighted = true,
            colors = colors,
            large = true,
            onClick = { onOpen(hit) },
        )

        is FinderItem.Contact -> ResultRow(
            icon = { IconImage(hit.icon, 54.dp, CircleShape) },
            title = hit.name,
            subtitle = hit.detail,
            highlighted = true,
            colors = colors,
            large = true,
            onClick = { onOpen(hit) },
        )

        is FinderItem.File -> ResultRow(
            icon = { IconImage(hit.icon, 54.dp, RoundedCornerShape(10.dp)) },
            title = hit.name,
            subtitle = hit.detail,
            highlighted = true,
            colors = colors,
            large = true,
            onClick = { onOpen(hit) },
        )

        is FinderItem.Web -> ResultRow(
            icon = { VectorBadge(Icons.Rounded.Search, colors) },
            title = hit.text,
            subtitle = null,
            highlighted = true,
            colors = colors,
            large = true,
            onClick = { onOpen(hit) },
        )
    }
}

@Composable
private fun BigValue(
    icon: @Composable () -> Unit,
    caption: String,
    value: String,
    extra: List<String>,
    colors: FinderColors,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(colors.highlight)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        icon()
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(caption, color = colors.secondary, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                value,
                color = colors.text,
                fontSize = 30.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            extra.forEach {
                Text(it, color = colors.secondary, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun VectorBadge(vector: ImageVector, colors: FinderColors) {
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(RoundedCornerShape(11.dp))
            .background(colors.field),
        contentAlignment = Alignment.Center,
    ) {
        Icon(vector, contentDescription = null, tint = colors.text, modifier = Modifier.size(26.dp))
    }
}

@Composable
private fun ResultRow(
    icon: @Composable () -> Unit,
    title: String,
    subtitle: String?,
    highlighted: Boolean,
    colors: FinderColors,
    onClick: () -> Unit,
    showDivider: Boolean = false,
    large: Boolean = false,
    onLongClick: (() -> Unit)? = null,
) {
    Column {
        if (showDivider && !highlighted) {
            Box(
                Modifier
                    .padding(start = 60.dp, end = 12.dp)
                    .fillMaxWidth()
                    .height(0.5.dp)
                    .background(colors.separator),
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(if (highlighted) colors.highlight else Color.Transparent)
                .combinedClickable(onLongClick = onLongClick, onClick = onClick)
                .padding(horizontal = 10.dp, vertical = if (large) 10.dp else 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            icon()
            Spacer(Modifier.width(if (large) 14.dp else 12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    color = colors.text,
                    fontSize = if (large) 19.sp else 16.sp,
                    fontWeight = if (large) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!subtitle.isNullOrEmpty()) {
                    Text(subtitle, color = colors.secondary, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
private fun AppGrid(
    apps: List<AppInfo>,
    highlighted: String?,
    colors: FinderColors,
    onOpen: (FinderItem) -> Unit,
    onAppLongPress: AppLongPress,
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        apps.chunked(GRID_COLUMNS).forEach { row ->
            Row(Modifier.fillMaxWidth()) {
                row.forEach { app ->
                    val item = FinderItem.App(app)
                    val iconBounds = remember { IconBounds() }
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(16.dp))
                            .background(if (item.key == highlighted) colors.highlight else Color.Transparent)
                            .combinedClickable(
                                onLongClick = { onAppLongPress(app, iconBounds.rect) },
                                onClick = { onOpen(item) },
                            )
                            .padding(vertical = 10.dp, horizontal = 4.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        AppIcon(app, 56.dp, iconBounds)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            app.title?.toString().orEmpty(),
                            color = colors.text,
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                repeat(GRID_COLUMNS - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/** Long press on an app, with where its icon is drawn, in the finder's own coordinates. */
internal typealias AppLongPress = (AppInfo, android.graphics.Rect) -> Unit

/** Where an [AppIcon] was last laid out, so a long press can pick it up from there. */
private class IconBounds {
    val rect = android.graphics.Rect()
}

@Composable
private fun AppIcon(app: AppInfo, size: Dp, bounds: IconBounds? = null) {
    val context = LocalContext.current
    val drawable = remember(app) { app.newIcon(context) }
    val modifier = if (bounds == null) {
        Modifier
    } else {
        Modifier.onGloballyPositioned { coords ->
            val r = coords.boundsInRoot()
            bounds.rect.set(r.left.toInt(), r.top.toInt(), r.right.toInt(), r.bottom.toInt())
        }
    }
    DrawableImage(drawable, size, modifier)
}

@Composable
private fun SettingsIcon(search: FinderSearch, size: Dp) {
    val app = search.settingsApp
    if (app != null) {
        AppIcon(app, size)
    } else {
        Icon(Icons.Rounded.Settings, contentDescription = null, modifier = Modifier.size(size))
    }
}

@Composable
private fun IconImage(icon: Icon, size: Dp, shape: androidx.compose.ui.graphics.Shape) {
    val context = LocalContext.current
    val drawable = remember(icon) { icon.loadDrawable(context) }
    DrawableImage(drawable, size, Modifier.clip(shape))
}

@Composable
private fun DrawableImage(drawable: Drawable?, size: Dp, modifier: Modifier = Modifier) {
    Image(
        painter = rememberDrawablePainter(drawable),
        contentDescription = null,
        modifier = modifier.size(size),
    )
}

private const val GRID_COLUMNS = 4
