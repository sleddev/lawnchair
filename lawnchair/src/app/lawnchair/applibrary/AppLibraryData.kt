package app.lawnchair.applibrary

import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.LauncherApps
import android.os.Process
import app.lawnchair.LawnchairLauncher
import app.lawnchair.preferences2.PreferenceManager2
import app.lawnchair.preferences2.firstCached
import app.lawnchair.today.hasUsageAccess
import com.android.launcher3.allapps.AllAppsStore
import com.android.launcher3.appprediction.PredictionRowView
import com.android.launcher3.model.data.AppInfo
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * A group of apps in the App Library. [Suggestions][LibraryCategory.SUGGESTIONS] always shows four
 * large icons; the others show three, and the rest as a small grid that opens the whole category.
 */
data class LibraryCategory(val key: String, val title: String, val apps: List<AppInfo>) {
    val allLarge: Boolean get() = key == SUGGESTIONS || apps.size <= 4

    companion object {
        const val SUGGESTIONS = "suggestions"
        const val RECENTLY_ADDED = "recent"
    }
}

data class AppLibraryState(
    val categories: List<LibraryCategory> = emptyList(),
    /** Every app, alphabetically, for the list view. */
    val allApps: List<AppInfo> = emptyList(),
)

/**
 * Sorts the apps into categories like the iOS App Library: Suggestions and Recently Added first,
 * then categories from the app's declared category (or a guess from its package name), with the
 * most used categories, and apps, first.
 */
class AppLibraryRepository(private val launcher: LawnchairLauncher) {

    private val context: Context = launcher
    private val scope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())
    private var loadJob: Job? = null
    private var listening = false

    private val _state = MutableStateFlow(AppLibraryState())
    val state: StateFlow<AppLibraryState> = _state

    private val appsStore: AllAppsStore<*> get() = launcher.appsView.appsStore
    private val updateListener = AllAppsStore.OnUpdateListener { reload() }

    fun start() {
        if (!listening) {
            appsStore.addUpdateListener(updateListener)
            listening = true
        }
        reload()
    }

    fun stop() {
        if (listening) appsStore.removeUpdateListener(updateListener)
        listening = false
    }

    fun destroy() {
        stop()
        scope.cancel()
    }

    fun reload() {
        val apps = visibleApps()
        val predicted = predictedApps(apps)
        loadJob?.cancel()
        loadJob = scope.launch {
            _state.value = withContext(Dispatchers.Default) { build(apps, predicted) }
        }
    }

    private fun visibleApps(): List<AppInfo> {
        val prefs = PreferenceManager2.getInstance(context)
        val hidden = prefs.hiddenApps.firstCached()
        return appsStore.apps.asSequence()
            .filter { it.toComponentKey().toString() !in hidden }
            .sortedBy { it.title?.toString()?.lowercase(Locale.getDefault()) }
            .toList()
    }

    private fun predictedApps(apps: List<AppInfo>): List<AppInfo> = runCatching {
        launcher.appsView.floatingHeaderView
            ?.findFixedRowByType(PredictionRowView::class.java)
            ?.predictedApps
            ?.mapNotNull { info ->
                val component = info.targetComponent ?: return@mapNotNull null
                apps.firstOrNull { it.componentName == component && it.user == info.user }
            }
    }.getOrNull().orEmpty()

    private fun build(apps: List<AppInfo>, predicted: List<AppInfo>): AppLibraryState {
        val launcherApps = context.getSystemService(LauncherApps::class.java)
        val usage = loadUsage()
        val infos = apps.associateWith { app ->
            runCatching { launcherApps.getApplicationInfo(app.targetPackage!!, 0, app.user) }.getOrNull()
        }
        val used = { app: AppInfo -> usage[app.targetPackage]?.totalTime ?: 0L }
        val byUse = compareByDescending<AppInfo>(used)

        val suggestions = (
            predicted +
                apps.filter { usage.containsKey(it.targetPackage) }
                    .sortedByDescending { usage[it.targetPackage]?.lastUsed ?: 0L } +
                apps
            )
            .distinctBy { it.toComponentKey() }
            .take(4)

        val recentlyAdded = apps
            .map { it to installTime(it) }
            .filter { it.second > 0 }
            .sortedByDescending { it.second }
            .take(RECENT_COUNT)
            .map { it.first }

        val grouped = apps.groupBy { categorize(it, infos[it]) }
        val categories = CATEGORIES
            .mapNotNull { category ->
                val members = grouped[category]?.sortedWith(byUse) ?: return@mapNotNull null
                LibraryCategory(category.key, category.title, members)
            }
            // Most used categories first; the stable sort keeps the default order for ties.
            .sortedByDescending { category -> category.apps.sumOf(used) }

        val fixed = listOfNotNull(
            LibraryCategory(LibraryCategory.SUGGESTIONS, "Suggestions", suggestions).takeIf { suggestions.isNotEmpty() },
            LibraryCategory(LibraryCategory.RECENTLY_ADDED, "Recently Added", recentlyAdded).takeIf { recentlyAdded.isNotEmpty() },
        )
        return AppLibraryState(fixed + categories, apps)
    }

    private fun installTime(app: AppInfo): Long {
        // Only for the main user: other profiles' packages aren't visible to this package manager.
        if (app.user != Process.myUserHandle()) return 0L
        return runCatching { context.packageManager.getPackageInfo(app.targetPackage!!, 0).firstInstallTime }
            .getOrDefault(0L)
    }

    private class Usage(var totalTime: Long, var lastUsed: Long)

    private fun loadUsage(): Map<String, Usage> {
        if (!context.hasUsageAccess()) return emptyMap()
        val usm = context.getSystemService(UsageStatsManager::class.java) ?: return emptyMap()
        val now = System.currentTimeMillis()
        val stats = runCatching { usm.queryAndAggregateUsageStats(now - USAGE_WINDOW_MS, now) }.getOrNull()
            ?: return emptyMap()
        return stats.mapValues { (_, s) -> Usage(s.totalTimeInForeground, s.lastTimeUsed) }
            .filterValues { it.totalTime > 0 }
    }

    private class Category(val key: String, val title: String)

    private fun categorize(app: AppInfo, info: ApplicationInfo?): Category {
        val pkg = app.targetPackage.orEmpty().lowercase(Locale.ROOT)
        val title = app.title?.toString().orEmpty().lowercase(Locale.getDefault())
        // Names first: they catch apps whose declared category is too broad, like a camera
        // that says it's a productivity app.
        KEYWORDS.firstOrNull { (_, words) -> words.any { it in pkg || it in title } }?.let { return it.first }
        when (info?.category) {
            ApplicationInfo.CATEGORY_GAME -> return GAMES
            ApplicationInfo.CATEGORY_AUDIO, ApplicationInfo.CATEGORY_VIDEO -> return ENTERTAINMENT
            ApplicationInfo.CATEGORY_IMAGE -> return CREATIVITY
            ApplicationInfo.CATEGORY_SOCIAL -> return SOCIAL
            ApplicationInfo.CATEGORY_NEWS -> return READING
            ApplicationInfo.CATEGORY_MAPS -> return TRAVEL
            ApplicationInfo.CATEGORY_PRODUCTIVITY -> return PRODUCTIVITY
            ApplicationInfo.CATEGORY_ACCESSIBILITY -> return UTILITIES
        }
        if (info != null && info.flags and ApplicationInfo.FLAG_SYSTEM != 0) return UTILITIES
        return OTHER
    }

    private companion object {
        const val RECENT_COUNT = 8
        const val USAGE_WINDOW_MS = 14L * 24 * 60 * 60 * 1000

        val SOCIAL = Category("social", "Social")
        val UTILITIES = Category("utilities", "Utilities")
        val PRODUCTIVITY = Category("productivity", "Productivity & Finance")
        val CREATIVITY = Category("creativity", "Creativity")
        val ENTERTAINMENT = Category("entertainment", "Entertainment")
        val READING = Category("reading", "Information & Reading")
        val TRAVEL = Category("travel", "Travel")
        val SHOPPING = Category("shopping", "Shopping & Food")
        val HEALTH = Category("health", "Health & Fitness")
        val EDUCATION = Category("education", "Education")
        val GAMES = Category("games", "Games")
        val OTHER = Category("other", "Other")

        // The order categories are shown in when there's no usage to sort them by.
        val CATEGORIES = listOf(
            SOCIAL, UTILITIES, PRODUCTIVITY, CREATIVITY, ENTERTAINMENT, READING,
            TRAVEL, SHOPPING, HEALTH, EDUCATION, GAMES, OTHER,
        )

        // Matched against the package name and the label, in this order.
        val KEYWORDS = listOf(
            SOCIAL to listOf(
                "whatsapp", "telegram", "messenger", "instagram", "facebook", "twitter", "discord",
                "signal", "snapchat", "reddit", "tiktok", "musically", "mastodon", "threads", "linkedin",
                "bluesky", "messaging", "dialer", "contacts", ".mms", "android.apps.messaging", "phone",
                "messages", "slack", "teams", "zoom", "skype", "viber", "wechat", "line.android",
            ),
            TRAVEL to listOf(
                "maps", "uber", "lyft", "bolt", "airbnb", "booking", "transit", "waze", "trip",
                "flight", "airline", "citymapper", "navigation", "taxi",
            ),
            SHOPPING to listOf(
                "amazon", "shopping", "ebay", "aliexpress", "temu", "doordash", "ubereats", "eats",
                "deliveroo", "glovo", "wolt", "grubhub", "instacart", "food", "restaurant", "shopee",
                "etsy", "wish", "ikea",
            ),
            HEALTH to listOf("fitness", "health", "strava", "workout", "fitbit", "sleep", "running", "yoga", "meditat"),
            EDUCATION to listOf("duolingo", "classroom", "khan", "learn", "anki", "education", "school"),
            ENTERTAINMENT to listOf(
                "youtube", "netflix", "spotify", "music", "twitch", "primevideo", "disney", "hulu",
                "podcast", "player", "hbo", "soundcloud", "deezer", "tidal", "plex", "crunchyroll", "vlc",
                "radio",
            ),
            READING to listOf(
                "news", "weather", "kindle", "books", "reader", "wikipedia", "medium", "feedly",
                "magazine", "rss",
            ),
            CREATIVITY to listOf(
                "camera", "photos", "gallery", "lightroom", "snapseed", "canva", "picsart", "capcut",
                "editor", "sketch", "draw", "paint", "recorder", "studio",
            ),
            PRODUCTIVITY to listOf(
                "docs", "office", "sheets", "slides", "drive", "gmail", "mail", "calendar", "keep",
                "notes", "notion", "obsidian", "bank", "wallet", "finance", "paypal", "revolut",
                "outlook", "todo", "tasks", "trello", "dropbox", "excel", "powerpoint", "pdf",
                "money", "invest", "crypto",
            ),
            UTILITIES to listOf(
                "settings", "calculator", "clock", "deskclock", "files", "documentsui", "chrome",
                "firefox", "browser", "vending", "store", "authenticator", "vpn", "launcher", "flashlight",
                "compass", "translate", "search", "googlequicksearchbox", "assistant", "backup",
                "password", "bitwarden", "keyboard", "inputmethod", "scanner", "lens",
            ),
        )
    }
}
