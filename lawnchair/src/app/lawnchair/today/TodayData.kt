package app.lawnchair.today

import android.Manifest
import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.provider.CalendarContract
import android.provider.MediaStore
import android.util.Log
import android.util.Size
import androidx.core.content.ContextCompat
import com.android.launcher3.Utilities
import java.util.Calendar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext

private const val TAG = "TodayData"

internal fun Context.hasPermission(permission: String) =
    ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

internal fun startOfToday(): Long = Calendar.getInstance().apply {
    set(Calendar.HOUR_OF_DAY, 0)
    set(Calendar.MINUTE, 0)
    set(Calendar.SECOND, 0)
    set(Calendar.MILLISECOND, 0)
}.timeInMillis

// ---- Calendar ----

data class TodayEvent(
    val id: Long,
    val title: String,
    val begin: Long,
    val end: Long,
    val allDay: Boolean,
    val color: Int,
    val location: String?,
)

const val CALENDAR_PERMISSION = Manifest.permission.READ_CALENDAR

/** Events that haven't ended yet, from now until the end of tomorrow, refreshed on changes. */
fun calendarEvents(context: Context): Flow<List<TodayEvent>> = callbackFlow {
    fun load() = trySend(queryEvents(context))
    val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            load()
        }
    }
    context.contentResolver.registerContentObserver(CalendarContract.CONTENT_URI, true, observer)
    load()
    awaitClose { context.contentResolver.unregisterContentObserver(observer) }
}.flowOn(Dispatchers.IO)

private fun queryEvents(context: Context): List<TodayEvent> {
    if (!context.hasPermission(CALENDAR_PERMISSION)) return emptyList()
    val now = System.currentTimeMillis()
    val dayEnd = startOfToday() + 2 * DAY_MS
    val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
        ContentUris.appendId(it, startOfToday())
        ContentUris.appendId(it, dayEnd)
    }.build()
    val projection = arrayOf(
        CalendarContract.Instances.EVENT_ID,
        CalendarContract.Instances.TITLE,
        CalendarContract.Instances.BEGIN,
        CalendarContract.Instances.END,
        CalendarContract.Instances.ALL_DAY,
        CalendarContract.Instances.DISPLAY_COLOR,
        CalendarContract.Instances.EVENT_LOCATION,
    )
    return try {
        context.contentResolver.query(
            uri,
            projection,
            "${CalendarContract.Instances.VISIBLE} = 1",
            null,
            "${CalendarContract.Instances.BEGIN} ASC",
        )?.use { c ->
            buildList {
                while (c.moveToNext()) {
                    val allDay = c.getInt(4) != 0
                    var begin = c.getLong(2)
                    var end = c.getLong(3)
                    if (allDay) {
                        // All-day events are stored in UTC midnight; move them to local days.
                        val offset = java.util.TimeZone.getDefault().getOffset(begin)
                        begin -= offset
                        end -= offset
                    }
                    if (end <= now) continue
                    add(
                        TodayEvent(
                            id = c.getLong(0),
                            title = c.getString(1)?.takeIf { it.isNotBlank() } ?: "(No title)",
                            begin = begin,
                            end = end,
                            allDay = allDay,
                            color = c.getInt(5),
                            location = c.getString(6)?.takeIf { it.isNotBlank() },
                        ),
                    )
                }
            }
        }.orEmpty()
    } catch (e: Exception) {
        Log.w(TAG, "Couldn't read calendar", e)
        emptyList()
    }
}

fun eventIntent(event: TodayEvent): Intent = Intent(Intent.ACTION_VIEW)
    .setData(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, event.id))
    .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, event.begin)
    .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, event.end)

const val DAY_MS = 24 * 60 * 60 * 1000L

// ---- Battery ----

enum class BatteryDeviceType { Phone, Headphones, Watch, Other }

data class BatteryEntry(
    val name: String,
    val level: Int,
    val charging: Boolean,
    val type: BatteryDeviceType,
)

const val BLUETOOTH_PERMISSION = Manifest.permission.BLUETOOTH_CONNECT

/** The phone's battery plus any connected Bluetooth devices that report theirs. */
fun batteryLevels(context: Context): Flow<List<BatteryEntry>> = callbackFlow {
    var phone: BatteryEntry? = null
    fun publish() {
        trySend(listOfNotNull(phone) + bluetoothBatteries(context))
    }
    val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_BATTERY_CHANGED) {
                val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
                val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                phone = BatteryEntry(
                    name = "Phone",
                    level = if (level >= 0 && scale > 0) level * 100 / scale else 0,
                    charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                        status == BatteryManager.BATTERY_STATUS_FULL,
                    type = BatteryDeviceType.Phone,
                )
            }
            publish()
        }
    }
    val filter = IntentFilter().apply {
        addAction(Intent.ACTION_BATTERY_CHANGED)
        addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
        addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
        addAction(ACTION_BATTERY_LEVEL_CHANGED)
    }
    ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
    awaitClose { context.unregisterReceiver(receiver) }
}

// BluetoothDevice.ACTION_BATTERY_LEVEL_CHANGED is hidden.
private const val ACTION_BATTERY_LEVEL_CHANGED = "android.bluetooth.device.action.BATTERY_LEVEL_CHANGED"

private fun bluetoothBatteries(context: Context): List<BatteryEntry> {
    if (Utilities.ATLEAST_S && !context.hasPermission(BLUETOOTH_PERMISSION)) return emptyList()
    return try {
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter ?: return emptyList()
        if (!adapter.isEnabled) return emptyList()
        val isConnected = BluetoothDevice::class.java.getMethod("isConnected")
        val getBatteryLevel = BluetoothDevice::class.java.getMethod("getBatteryLevel")
        adapter.bondedDevices.orEmpty().mapNotNull { device ->
            if (isConnected.invoke(device) != true) return@mapNotNull null
            val level = getBatteryLevel.invoke(device) as? Int ?: -1
            if (level !in 0..100) return@mapNotNull null
            val type = when (device.bluetoothClass?.majorDeviceClass) {
                android.bluetooth.BluetoothClass.Device.Major.AUDIO_VIDEO -> BatteryDeviceType.Headphones
                android.bluetooth.BluetoothClass.Device.Major.WEARABLE -> BatteryDeviceType.Watch
                else -> BatteryDeviceType.Other
            }
            val name = (if (Utilities.ATLEAST_R) device.alias else null) ?: device.name ?: "Device"
            BatteryEntry(name, level, false, type)
        }
    } catch (e: Exception) {
        Log.w(TAG, "Couldn't read Bluetooth batteries", e)
        emptyList()
    }
}

// ---- Photos ----

data class TodayPhoto(val uri: Uri, val bitmap: Bitmap, val taken: Long)

val PHOTO_PERMISSIONS: Array<String> = when {
    Utilities.ATLEAST_U -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
    Utilities.ATLEAST_T -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES)
    else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
}

fun Context.hasPhotoPermission() = PHOTO_PERMISSIONS.any { hasPermission(it) }

/**
 * A random photo from the last month (or any time, if there are none that recent). The same
 * [seed] gives the same photo, so the page only changes it when asked to.
 */
suspend fun loadPhoto(context: Context, seed: Long, sizePx: Int): TodayPhoto? = withContext(Dispatchers.IO) {
    if (!context.hasPhotoPermission()) return@withContext null
    try {
        val since = (System.currentTimeMillis() - 30 * DAY_MS) / 1000
        val photos = queryPhotos(context, since).ifEmpty { queryPhotos(context, 0) }
        if (photos.isEmpty()) return@withContext null
        val (id, taken) = photos[java.util.Random(seed).nextInt(photos.size)]
        val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
        val bitmap = if (Utilities.ATLEAST_Q) {
            context.contentResolver.loadThumbnail(uri, Size(sizePx, sizePx), null)
        } else {
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = 4 })
            }
        } ?: return@withContext null
        TodayPhoto(uri, bitmap, taken)
    } catch (e: Exception) {
        Log.w(TAG, "Couldn't load photo", e)
        null
    }
}

private fun queryPhotos(context: Context, sinceSeconds: Long): List<Pair<Long, Long>> =
    context.contentResolver.query(
        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
        arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DATE_ADDED),
        "${MediaStore.Images.Media.DATE_ADDED} >= ?",
        arrayOf(sinceSeconds.toString()),
        "${MediaStore.Images.Media.DATE_ADDED} DESC",
    )?.use { c ->
        buildList {
            while (c.moveToNext() && size < 500) add(c.getLong(0) to c.getLong(1) * 1000)
        }
    }.orEmpty()

// ---- Screen time ----

data class AppUsage(val packageName: String, val label: String, val icon: Drawable?, val millis: Long)

data class ScreenTime(val total: Long, val apps: List<AppUsage>)

fun Context.hasUsageAccess(): Boolean {
    val appOps = getSystemService(AppOpsManager::class.java) ?: return false
    @Suppress("DEPRECATION")
    val mode = if (Utilities.ATLEAST_Q) {
        appOps.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), packageName)
    } else {
        appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), packageName)
    }
    return mode == AppOpsManager.MODE_ALLOWED
}

/** Foreground time per app since midnight, worked out from resume/pause events. */
suspend fun loadScreenTime(context: Context): ScreenTime? = withContext(Dispatchers.IO) {
    if (!context.hasUsageAccess()) return@withContext null
    val usm = context.getSystemService(UsageStatsManager::class.java) ?: return@withContext null
    val start = startOfToday()
    val now = System.currentTimeMillis()
    val events = usm.queryEvents(start, now)
    val event = UsageEvents.Event()
    // Keyed by package and activity: another activity of the same app pausing in the background
    // doesn't end the one in front.
    val resumedAt = mutableMapOf<Pair<String, String?>, Long>()
    val totals = mutableMapOf<String, Long>()
    // Launchers are where you are between apps, not screen time.
    val launchers = context.packageManager.queryIntentActivities(
        Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
        0,
    )
        // Settings has a fallback home screen for while the device boots.
        .filterNot { it.activityInfo.name.endsWith("FallbackHome") }
        .map { it.activityInfo.packageName }.toSet() + context.packageName
    while (events.hasNextEvent()) {
        events.getNextEvent(event)
        val key = event.packageName to event.className
        when (event.eventType) {
            UsageEvents.Event.ACTIVITY_RESUMED -> {
                // Only one app is in front: a resume ends anything still open, which also covers
                // activities that never reported a pause.
                resumedAt.forEach { (k, from) ->
                    if (k != key) totals[k.first] = (totals[k.first] ?: 0) + (event.timeStamp - from)
                }
                resumedAt.keys.retainAll { it == key }
                resumedAt.putIfAbsent(key, event.timeStamp)
            }
            UsageEvents.Event.ACTIVITY_PAUSED -> {
                val from = resumedAt.remove(key) ?: continue
                totals[key.first] = (totals[key.first] ?: 0) + (event.timeStamp - from)
            }

            UsageEvents.Event.SCREEN_NON_INTERACTIVE -> {
                resumedAt.forEach { (k, from) -> totals[k.first] = (totals[k.first] ?: 0) + (event.timeStamp - from) }
                resumedAt.clear()
            }
        }
    }
    resumedAt.forEach { (k, from) -> totals[k.first] = (totals[k.first] ?: 0) + (now - from) }
    val pm = context.packageManager
    val apps = totals
        .filterKeys { it !in launchers }
        .filterValues { it >= 60_000 }
        .entries
        .sortedByDescending { it.value }
        .map { (pkg, millis) ->
            val info = runCatching { pm.getApplicationInfo(pkg, 0) }.getOrNull()
            AppUsage(
                packageName = pkg,
                label = info?.loadLabel(pm)?.toString() ?: pkg,
                icon = info?.loadIcon(pm),
                millis = millis,
            )
        }
    ScreenTime(total = apps.sumOf { it.millis }, apps = apps.take(4))
}

fun formatDuration(millis: Long): String {
    val minutes = millis / 60_000
    val hours = minutes / 60
    return when {
        hours > 0 && minutes % 60 > 0 -> "${hours}h ${minutes % 60}m"
        hours > 0 -> "${hours}h"
        else -> "${minutes}m"
    }
}
