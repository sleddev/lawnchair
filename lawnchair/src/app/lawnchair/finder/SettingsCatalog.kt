package app.lawnchair.finder

import android.content.Context
import android.content.Intent
import android.provider.Settings
import java.util.Locale

data class SettingEntry(
    val title: String,
    // Where the setting lives, shown as the subtitle, like "Settings › Connections".
    val section: String,
    val keywords: List<String>,
    val intent: Intent,
)

/**
 * The system settings the finder knows about, with readable names and search keywords, instead
 * of matching raw [Settings] action names.
 */
object SettingsCatalog {

    private class Raw(val title: String, val section: String, val action: String, vararg val keywords: String)

    private val raw = listOf(
        Raw("Wi-Fi", "Network & internet", Settings.ACTION_WIFI_SETTINGS, "wifi", "wlan", "wireless", "internet", "network"),
        Raw("Bluetooth", "Connected devices", Settings.ACTION_BLUETOOTH_SETTINGS, "bt", "pair", "headphones", "earbuds"),
        Raw("Network & internet", "Settings", Settings.ACTION_WIRELESS_SETTINGS, "network", "internet", "connections", "hotspot", "tethering"),
        Raw("Mobile network", "Network & internet", Settings.ACTION_NETWORK_OPERATOR_SETTINGS, "cellular", "mobile data", "sim", "carrier", "roaming"),
        Raw("Data usage", "Network & internet", Settings.ACTION_DATA_USAGE_SETTINGS, "data saver", "mobile data"),
        Raw("Airplane mode", "Network & internet", Settings.ACTION_AIRPLANE_MODE_SETTINGS, "flight mode", "aeroplane"),
        Raw("VPN", "Network & internet", Settings.ACTION_VPN_SETTINGS, "proxy"),
        Raw("NFC", "Connected devices", Settings.ACTION_NFC_SETTINGS, "contactless", "tap to pay"),
        Raw("Cast", "Connected devices", Settings.ACTION_CAST_SETTINGS, "screen mirroring", "chromecast"),
        Raw("Display", "Settings", Settings.ACTION_DISPLAY_SETTINGS, "screen", "brightness", "font size", "screen timeout", "resolution"),
        Raw("Dark theme", "Display", "android.settings.DARK_THEME_SETTINGS", "dark mode", "night mode", "light mode"),
        Raw("Night Light", "Display", Settings.ACTION_NIGHT_DISPLAY_SETTINGS, "blue light", "eye comfort", "night shift"),
        Raw("Wallpaper & style", "Settings", Intent.ACTION_SET_WALLPAPER, "wallpaper", "background", "theme", "colors"),
        Raw("Sound & vibration", "Settings", Settings.ACTION_SOUND_SETTINGS, "sound", "volume", "ringtone", "vibration", "haptics"),
        Raw("Notifications", "Settings", Settings.ACTION_ALL_APPS_NOTIFICATION_SETTINGS, "alerts", "badges"),
        Raw("Do Not Disturb", "Notifications", Settings.ACTION_ZEN_MODE_PRIORITY_SETTINGS, "dnd", "focus", "silence", "modes"),
        Raw("Battery", "Settings", Intent.ACTION_POWER_USAGE_SUMMARY, "battery usage", "power", "charging"),
        Raw("Battery Saver", "Battery", Settings.ACTION_BATTERY_SAVER_SETTINGS, "low power mode", "power saving"),
        Raw("Storage", "Settings", Settings.ACTION_INTERNAL_STORAGE_SETTINGS, "space", "free up", "memory", "disk"),
        Raw("Apps", "Settings", Settings.ACTION_APPLICATION_SETTINGS, "applications", "app info", "uninstall", "permissions"),
        Raw("Default apps", "Apps", Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS, "default browser", "default home", "open by default"),
        Raw("Home app", "Apps", Settings.ACTION_HOME_SETTINGS, "launcher", "default launcher"),
        Raw("Location", "Settings", Settings.ACTION_LOCATION_SOURCE_SETTINGS, "gps", "location services"),
        Raw("Security", "Settings", Settings.ACTION_SECURITY_SETTINGS, "screen lock", "fingerprint", "face unlock", "password", "pin"),
        Raw("Privacy", "Settings", Settings.ACTION_PRIVACY_SETTINGS, "permissions", "permission manager"),
        Raw("Accessibility", "Settings", Settings.ACTION_ACCESSIBILITY_SETTINGS, "talkback", "magnification", "captions", "color correction"),
        Raw("Languages", "System", Settings.ACTION_LOCALE_SETTINGS, "language", "locale", "region"),
        Raw("Keyboard", "System", Settings.ACTION_INPUT_METHOD_SETTINGS, "input", "gboard", "on-screen keyboard"),
        Raw("Date & time", "System", Settings.ACTION_DATE_SETTINGS, "clock", "time zone", "timezone"),
        Raw("Accounts", "Settings", Settings.ACTION_SYNC_SETTINGS, "passwords", "sync", "google account"),
        Raw("About phone", "Settings", Settings.ACTION_DEVICE_INFO_SETTINGS, "device info", "model", "android version", "build number", "imei"),
        Raw("Developer options", "System", Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS, "developer", "usb debugging", "adb"),
        Raw("Settings", "Settings", Settings.ACTION_SETTINGS, "preferences", "system settings"),
    )

    @Volatile
    private var resolved: List<SettingEntry>? = null

    /** The entries this device can open. Resolving them hits the package manager once. */
    fun entries(context: Context): List<SettingEntry> {
        resolved?.let { return it }
        val pm = context.packageManager
        return raw.mapNotNull { entry ->
            val intent = Intent(entry.action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (intent.resolveActivity(pm) == null) return@mapNotNull null
            SettingEntry(entry.title, entry.section, entry.keywords.toList(), intent)
        }.also { resolved = it }
    }

    /** Matches [query] against titles and keywords. Higher scores are better matches. */
    fun search(context: Context, query: String, max: Int): List<Pair<SettingEntry, Int>> {
        val q = query.trim().lowercase(Locale.getDefault())
        if (q.isEmpty()) return emptyList()
        return entries(context).mapNotNull { entry ->
            val title = entry.title.lowercase(Locale.getDefault())
            val score = when {
                title == q -> 100
                title.startsWith(q) -> 85
                title.split(' ', '-', '&').any { it.startsWith(q) } -> 70
                entry.keywords.any { it == q } -> 65
                q.length >= 2 && entry.keywords.any { kw -> kw.startsWith(q) || kw.split(' ').any { it.startsWith(q) } } -> 55
                q.length >= 3 && title.contains(q) -> 45
                else -> return@mapNotNull null
            }
            entry to score
        }.sortedByDescending { it.second }.take(max)
    }
}
