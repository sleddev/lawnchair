package app.lawnchair.today

import android.content.Intent
import android.provider.Settings
import android.text.format.DateFormat
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.HourglassBottom
import androidx.compose.material.icons.rounded.Photo
import androidx.compose.material.icons.rounded.Smartphone
import androidx.compose.material.icons.rounded.Watch
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.lawnchair.smartspace.BcSmartSpaceUtil
import app.lawnchair.smartspace.model.SmartspaceAction
import app.lawnchair.smartspace.model.SmartspaceTarget
import app.lawnchair.smartspace.provider.SmartspaceProvider
import app.lawnchair.ui.preferences.PreferenceActivity
import app.lawnchair.ui.preferences.navigation.Smartspace
import com.android.launcher3.Utilities
import com.google.accompanist.drawablepainter.rememberDrawablePainter
import java.util.Date
import kotlinx.coroutines.flow.emptyFlow

/** What the cards need from the page: opening things and asking for permissions. */
interface TodayHost {
    /** Bumped each time the page opens or the launcher resumes, so cards reload their data. */
    val refreshKey: Int
    fun startIntent(intent: Intent)
    fun requestPermissions(permissions: Array<String>)
    fun sendAction(action: SmartspaceAction)
}

class TodayColors(val dark: Boolean) {
    val card = if (dark) Color(0xFF1C1C1E).copy(alpha = 0.55f) else Color.White.copy(alpha = 0.6f)

    // Glass: a touch lighter at the top, like light falling on it.
    val cardFill = if (dark) {
        Brush.verticalGradient(listOf(Color(0xFF2C2C30).copy(alpha = 0.62f), Color(0xFF161618).copy(alpha = 0.58f)))
    } else {
        Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.72f), Color.White.copy(alpha = 0.52f)))
    }

    // A hairline that catches the light along the top edge and fades out towards the bottom.
    val cardBorder = Brush.verticalGradient(
        listOf(
            Color.White.copy(alpha = if (dark) 0.20f else 0.65f),
            Color.White.copy(alpha = if (dark) 0.05f else 0.18f),
        ),
    )
    val text = if (dark) Color.White else Color(0xFF111111)
    val secondary = if (dark) Color.White.copy(alpha = 0.6f) else Color.Black.copy(alpha = 0.5f)
    val track = if (dark) Color.White.copy(alpha = 0.15f) else Color.Black.copy(alpha = 0.1f)
    val pageText = Color.White
    val pill = if (dark) Color.White.copy(alpha = 0.18f) else Color.White.copy(alpha = 0.35f)
    val pillBorder = Color.White.copy(alpha = if (dark) 0.16f else 0.4f)
}

/** The faint glow a card has in its own colour, or null when it draws its own background. */
fun TodayCard.accent(): Color? = when (this) {
    TodayCard.Battery -> BatteryGreen
    TodayCard.Calendar -> CalendarRed
    TodayCard.ScreenTime -> ScreenTimePurple
    TodayCard.Weather, TodayCard.Photo -> null
}

val TodayCardShape = RoundedCornerShape(22.dp)

@Composable
fun TodayCardContent(card: TodayCard, size: TodaySize, host: TodayHost, colors: TodayColors) {
    when (card) {
        TodayCard.Weather -> WeatherCard(size, host, colors)
        TodayCard.Battery -> BatteryCard(size, host, colors)
        TodayCard.Calendar -> CalendarCard(size, host, colors)
        TodayCard.Photo -> PhotoCard(size, host, colors)
        TodayCard.ScreenTime -> ScreenTimeCard(size, host, colors)
    }
}

@Composable
private fun CardTitle(icon: ImageVector, title: String, tint: Color, colors: TodayColors) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(15.dp))
        Spacer(Modifier.width(5.dp))
        Text(
            title.uppercase(),
            color = colors.secondary,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Shown in place of a card's data when it needs a permission first. */
@Composable
private fun PermissionPrompt(icon: ImageVector, title: String, message: String, tint: Color, colors: TodayColors) {
    Column(Modifier.fillMaxSize().padding(14.dp)) {
        CardTitle(icon, title, tint, colors)
        Spacer(Modifier.weight(1f))
        Text(message, color = colors.text, fontSize = 15.sp, fontWeight = FontWeight.Medium, lineHeight = 19.sp)
        Spacer(Modifier.height(4.dp))
        Text("Tap to allow", color = tint, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

// ---- Weather ----


@Composable
private fun WeatherCard(size: TodaySize, host: TodayHost, colors: TodayColors) {
    val context = LocalContext.current
    val provider = remember { runCatching { SmartspaceProvider.INSTANCE.get(context) }.getOrNull() }
    val targets by (provider?.targets ?: emptyFlow()).collectAsState(initial = emptyList())
    val weather = targets.firstOrNull { it.featureType == SmartspaceTarget.FeatureType.FEATURE_WEATHER }
    val action = weather?.headerAction ?: weather?.baseAction
    val temperature = action?.let { a -> a.subtitle?.takeIf { it.isNotBlank() } ?: a.title.takeIf { it.isNotBlank() } }
    val gradient = remember(host.refreshKey) { weatherGradient() }

    Box(
        Modifier
            .fillMaxSize()
            .background(gradient)
            .clickableNoRipple {
                if (action != null && temperature != null) {
                    host.sendAction(action)
                } else {
                    host.startIntent(PreferenceActivity.createIntent(context, Smartspace))
                }
            }
            .padding(14.dp),
    ) {
        if (action == null || temperature == null) {
            Column(Modifier.fillMaxSize()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Cloud, contentDescription = null, tint = Color.White, modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(5.dp))
                    Text("WEATHER", color = Color.White.copy(alpha = 0.8f), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }
                Spacer(Modifier.weight(1f))
                Text("No weather yet", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(4.dp))
                Text(
                    "Set up At a Glance",
                    color = Color.White.copy(alpha = 0.85f),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            return@Box
        }
        val icon = remember(action.icon) { action.icon?.loadDrawable(context) }
        val clock = remember(host.refreshKey) { DateFormat.getTimeFormat(context).format(Date()) }
        if (size == TodaySize.Small) {
            Column(Modifier.fillMaxSize()) {
                Text("Now", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text(temperature.toString(), color = Color.White, fontSize = 44.sp, fontWeight = FontWeight.Light)
                Spacer(Modifier.weight(1f))
                if (icon != null) {
                    Image(rememberDrawablePainter(icon), contentDescription = null, modifier = Modifier.size(28.dp))
                }
                Text("Updated $clock", color = Color.White.copy(alpha = 0.8f), fontSize = 12.sp, maxLines = 1)
            }
        } else {
            Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Now", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    Text(temperature.toString(), color = Color.White, fontSize = 52.sp, fontWeight = FontWeight.Light)
                    Text("Updated $clock", color = Color.White.copy(alpha = 0.8f), fontSize = 12.sp)
                }
                if (icon != null) {
                    Image(rememberDrawablePainter(icon), contentDescription = null, modifier = Modifier.size(64.dp))
                }
            }
        }
    }
}

/** A sky for the time of day: blue by day, warm around sunrise and sunset, deep blue at night. */
private fun weatherGradient(): Brush {
    val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
    val colors = when (hour) {
        in 7..16 -> listOf(Color(0xFF5AA9F5), Color(0xFF2F6FD0))
        5, 6, 17, 18 -> listOf(Color(0xFF6C7BD0), Color(0xFFC97B7B))
        else -> listOf(Color(0xFF26346B), Color(0xFF111A3A))
    }
    return Brush.verticalGradient(colors)
}

// ---- Battery ----

private val BatteryGreen = Color(0xFF34C759)
private val BatteryLow = Color(0xFFFF3B30)

@Composable
private fun BatteryCard(size: TodaySize, host: TodayHost, colors: TodayColors) {
    val context = LocalContext.current
    val entries by remember(host.refreshKey) { batteryLevels(context) }.collectAsState(initial = emptyList())
    val needsBluetooth = remember(host.refreshKey) { Utilities.ATLEAST_S && !context.hasPermission(BLUETOOTH_PERMISSION) }
    val slots = if (size == TodaySize.Small) 2 else 4
    val shown = entries.take(slots)

    if (size == TodaySize.Small && shown.size <= 1) {
        Column(
            Modifier.fillMaxSize().padding(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            shown.firstOrNull()?.let { entry ->
                BatteryRing(entry, colors, 84.dp)
                Spacer(Modifier.height(10.dp))
                Text("${entry.level}%", color = colors.text, fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    } else if (size == TodaySize.Small) {
        Column(Modifier.fillMaxSize().padding(14.dp), verticalArrangement = Arrangement.SpaceBetween) {
            shown.forEach { entry ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    BatteryRing(entry, colors, 44.dp)
                    Spacer(Modifier.width(10.dp))
                    Text("${entry.level}%", color = colors.text, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    } else {
        Row(
            Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 16.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            for (i in 0 until slots) {
                val entry = shown.getOrNull(i)
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f)) {
                    if (entry != null) {
                        BatteryRing(entry, colors, 58.dp)
                        Spacer(Modifier.height(8.dp))
                        Text("${entry.level}%", color = colors.text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                    } else if (i == shown.size && needsBluetooth) {
                        Box(
                            Modifier
                                .size(58.dp)
                                .clip(CircleShape)
                                .background(colors.track)
                                .clickableNoRipple { host.requestPermissions(arrayOf(BLUETOOTH_PERMISSION)) },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(Icons.Rounded.Bluetooth, contentDescription = "Show Bluetooth devices", tint = colors.secondary)
                        }
                        Spacer(Modifier.height(8.dp))
                        Text("Allow", color = colors.secondary, fontSize = 13.sp)
                    } else {
                        Box(Modifier.size(58.dp).clip(CircleShape).background(colors.track.copy(alpha = colors.track.alpha / 2)))
                        Spacer(Modifier.height(8.dp))
                        Text(" ", fontSize = 17.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun BatteryRing(entry: BatteryEntry, colors: TodayColors, size: Dp) {
    val color = if (entry.level <= 20 && !entry.charging) BatteryLow else BatteryGreen
    val icon = when (entry.type) {
        BatteryDeviceType.Phone -> Icons.Rounded.Smartphone
        BatteryDeviceType.Headphones -> Icons.Rounded.Headphones
        BatteryDeviceType.Watch -> Icons.Rounded.Watch
        BatteryDeviceType.Other -> Icons.Rounded.Bluetooth
    }
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = this.size.minDimension * 0.1f
            val inset = stroke / 2
            val arcSize = Size(this.size.width - stroke, this.size.height - stroke)
            drawArc(colors.track, 0f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(stroke))
            drawArc(
                Brush.sweepGradient(
                    0f to color.copy(alpha = 0.75f),
                    entry.level / 100f to color,
                    1f to color.copy(alpha = 0.75f),
                    center = center,
                ),
                -90f,
                360f * entry.level / 100f,
                false,
                Offset(inset, inset),
                arcSize,
                style = Stroke(stroke, cap = StrokeCap.Round),
            )
        }
        Icon(icon, contentDescription = entry.name, tint = colors.text, modifier = Modifier.size(size * 0.42f))
        if (entry.charging) {
            Icon(
                Icons.Rounded.Bolt,
                contentDescription = "Charging",
                tint = color,
                modifier = Modifier.align(Alignment.TopEnd).size(size * 0.3f),
            )
        }
    }
}

// ---- Calendar ----

private val CalendarRed = Color(0xFFFF3B30)

@Composable
private fun CalendarCard(size: TodaySize, host: TodayHost, colors: TodayColors) {
    val context = LocalContext.current
    val granted = remember(host.refreshKey) { context.hasPermission(CALENDAR_PERMISSION) }
    if (!granted) {
        Box(Modifier.fillMaxSize().clickableNoRipple { host.requestPermissions(arrayOf(CALENDAR_PERMISSION)) }) {
            PermissionPrompt(Icons.Rounded.CalendarMonth, "Calendar", "See what's up next", CalendarRed, colors)
        }
        return
    }
    val events by remember(host.refreshKey) { calendarEvents(context) }.collectAsState(initial = emptyList())
    val now = Date()
    val dayName = remember(host.refreshKey) { DateFormat.format("EEEE", now).toString() }
    val dayNumber = remember(host.refreshKey) { DateFormat.format("d", now).toString() }
    val limit = when (size) {
        TodaySize.Small -> 1
        TodaySize.Medium -> 2
        TodaySize.Large -> 6
    }
    val shown = events.take(limit)

    @Composable
    fun DateBlock() {
        Column {
            Text(dayName.uppercase(), color = CalendarRed, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Text(dayNumber, color = colors.text, fontSize = 36.sp, fontWeight = FontWeight.Light, lineHeight = 38.sp)
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .clickableNoRipple { host.startIntent(BcSmartSpaceUtil.getOpenCalendarIntent()) }
            .padding(14.dp),
    ) {
        if (size == TodaySize.Medium) {
            Row(Modifier.fillMaxSize()) {
                Box(Modifier.width(92.dp).fillMaxHeight()) { DateBlock() }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (shown.isEmpty()) NoEvents(colors) else shown.forEachIndexed { i, e -> EventRow(e, host, colors, highlight = i == 0) }
                }
            }
        } else {
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                DateBlock()
                if (shown.isEmpty()) NoEvents(colors) else shown.forEachIndexed { i, e -> EventRow(e, host, colors, highlight = i == 0) }
            }
        }
    }
}

@Composable
private fun NoEvents(colors: TodayColors) {
    Text("No more events today", color = colors.secondary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
}

@Composable
private fun EventRow(event: TodayEvent, host: TodayHost, colors: TodayColors, highlight: Boolean = false) {
    val context = LocalContext.current
    val format = DateFormat.getTimeFormat(context)
    val isTomorrow = event.begin >= startOfToday() + DAY_MS
    val time = when {
        event.allDay -> if (isTomorrow) "Tomorrow · All day" else "All day"
        else -> (if (isTomorrow) "Tomorrow · " else "") + format.format(Date(event.begin)) + " – " + format.format(Date(event.end))
    }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .then(
                if (highlight) {
                    Modifier.background(
                        Brush.horizontalGradient(
                            listOf(Color(event.color or 0xFF000000.toInt()).copy(alpha = 0.22f), Color.Transparent),
                        ),
                    )
                } else {
                    Modifier
                },
            )
            .clickableNoRipple { host.startIntent(eventIntent(event)) },
    ) {
        Box(
            Modifier
                .width(4.dp)
                .height(36.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(Color(event.color or 0xFF000000.toInt())),
        )
        Spacer(Modifier.width(7.dp))
        Column {
            Text(
                event.title,
                color = colors.text,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(time, color = colors.secondary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

// ---- Photos ----

@Composable
private fun PhotoCard(size: TodaySize, host: TodayHost, colors: TodayColors) {
    val context = LocalContext.current
    val granted = remember(host.refreshKey) { context.hasPhotoPermission() }
    if (!granted) {
        Box(Modifier.fillMaxSize().clickableNoRipple { host.requestPermissions(PHOTO_PERMISSIONS) }) {
            PermissionPrompt(Icons.Rounded.Photo, "Photos", "Revisit your recent photos", Color(0xFFFF9500), colors)
        }
        return
    }
    val sizePx = with(LocalDensity.current) { (if (size == TodaySize.Small) 180.dp else 360.dp).roundToPx() }
    // A new photo every hour, or whenever the page reloads after that.
    val seed = remember(host.refreshKey) { System.currentTimeMillis() / (60 * 60 * 1000) }
    val photo by produceState<TodayPhoto?>(null, seed, sizePx) { value = loadPhoto(context, seed, sizePx) }
    val current = photo
    if (current == null) {
        Box(Modifier.fillMaxSize().padding(14.dp)) {
            CardTitle(Icons.Rounded.Photo, "Photos", Color(0xFFFF9500), colors)
            Text(
                "No photos yet",
                color = colors.secondary,
                fontSize = 14.sp,
                modifier = Modifier.align(Alignment.BottomStart),
            )
        }
        return
    }
    Box(
        Modifier
            .fillMaxSize()
            .clickableNoRipple {
                host.startIntent(
                    Intent(Intent.ACTION_VIEW)
                        .setDataAndType(current.uri, "image/*")
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                )
            },
    ) {
        Image(
            bitmap = remember(current) { current.bitmap.asImageBitmap() },
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        Box(
            Modifier
                .fillMaxWidth()
                .height(56.dp)
                .align(Alignment.BottomCenter)
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.45f)))),
        )
        Column(Modifier.align(Alignment.BottomStart).padding(12.dp)) {
            Text("For You", color = Color.White.copy(alpha = 0.85f), fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            Text(
                DateFormat.getMediumDateFormat(context).format(Date(current.taken)),
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

// ---- Screen time ----

private val ScreenTimePurple = Color(0xFF5E5CE6)
private val UsageColors = listOf(Color(0xFF0A84FF), Color(0xFF5AC8FA), Color(0xFFFF9F0A), Color(0xFF8E8E93))

@Composable
private fun ScreenTimeCard(size: TodaySize, host: TodayHost, colors: TodayColors) {
    val context = LocalContext.current
    val granted = remember(host.refreshKey) { context.hasUsageAccess() }
    if (!granted) {
        Box(
            Modifier.fillMaxSize().clickableNoRipple {
                host.startIntent(
                    Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
                        .setData(android.net.Uri.parse("package:${context.packageName}")),
                )
            },
        ) {
            PermissionPrompt(Icons.Rounded.HourglassBottom, "Screen Time", "See how long you use your apps", ScreenTimePurple, colors)
        }
        return
    }
    val usage by produceState<ScreenTime?>(null, host.refreshKey) { value = loadScreenTime(context) }
    val current = usage
    val wellbeing = remember {
        context.packageManager.getLaunchIntentForPackage("com.google.android.apps.wellbeing")
            ?: Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
    }
    Column(
        Modifier
            .fillMaxSize()
            .clickableNoRipple { host.startIntent(wellbeing) }
            .padding(14.dp),
    ) {
        CardTitle(Icons.Rounded.HourglassBottom, "Screen Time", ScreenTimePurple, colors)
        Spacer(Modifier.height(4.dp))
        Text(
            current?.let { formatDuration(it.total) } ?: "–",
            color = colors.text,
            fontSize = if (size == TodaySize.Small) 28.sp else 30.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.weight(1f))
        val apps = current?.apps.orEmpty()
        if (apps.isEmpty()) {
            Text("No app use yet today", color = colors.secondary, fontSize = 13.sp)
            return@Column
        }
        // A stacked bar of the top apps.
        Row(
            Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(colors.track),
        ) {
            apps.forEachIndexed { i, app ->
                val fraction = app.millis.toFloat() / current!!.total
                if (fraction > 0.01f) {
                    val c = UsageColors[i % UsageColors.size]
                    Box(
                        Modifier
                            .weight(fraction)
                            .fillMaxHeight()
                            .background(Brush.verticalGradient(listOf(c.copy(alpha = 0.85f), c))),
                    )
                }
            }
            val rest = 1f - apps.sumOf { it.millis }.toFloat() / current!!.total
            if (rest > 0.01f) Spacer(Modifier.weight(rest))
        }
        Spacer(Modifier.height(10.dp))
        val shown = if (size == TodaySize.Small) apps.take(2) else apps.take(4)
        if (size == TodaySize.Small) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                shown.forEach { UsageRow(it, colors, 13.sp) }
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                shown.forEach { Box(Modifier.weight(1f)) { UsageRow(it, colors, 13.sp) } }
            }
        }
    }
}

@Composable
private fun UsageRow(app: AppUsage, colors: TodayColors, fontSize: TextUnit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (app.icon != null) {
            Image(rememberDrawablePainter(app.icon), contentDescription = app.label, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(5.dp))
        }
        Text(formatDuration(app.millis), color = colors.secondary, fontSize = fontSize, maxLines = 1)
    }
}

/** Adds a click handler without the Material ripple, iOS cards don't have one. */
internal fun Modifier.clickableNoRipple(onClick: () -> Unit): Modifier = composed {
    val currentOnClick by rememberUpdatedState(onClick)
    pointerInput(Unit) { detectTapGestures(onTap = { currentOnClick() }) }
}
