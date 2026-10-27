package app.lawnchair.finder

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.net.Uri
import android.provider.ContactsContract
import android.provider.MediaStore
import android.util.Log
import androidx.core.net.toUri
import app.lawnchair.LawnchairLauncher
import app.lawnchair.preferences2.PreferenceManager2
import app.lawnchair.preferences2.firstCached
import app.lawnchair.search.adapter.ContactsTarget
import app.lawnchair.search.adapter.FilesTarget
import app.lawnchair.search.adapter.SearchTargetFactory
import app.lawnchair.search.algorithms.data.FileInfo
import app.lawnchair.search.algorithms.data.calculator.Expressions
import app.lawnchair.search.algorithms.engine.provider.apps.AppMatcher
import app.lawnchair.search.algorithms.engine.provider.apps.MatchType
import app.lawnchair.search.algorithms.engine.provider.web.CustomWebSearchProvider
import app.lawnchair.search.algorithms.engine.provider.web.WebSearchProvider
import app.lawnchair.search.algorithms.filterHiddenApps
import com.android.launcher3.appprediction.PredictionRowView
import com.android.launcher3.model.data.AppInfo
import java.math.BigDecimal
import java.math.MathContext
import java.text.Normalizer
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

sealed interface FinderItem {
    val key: String

    data class App(val app: AppInfo) : FinderItem {
        override val key = "app:${app.toComponentKey()}"
    }

    data class Setting(val entry: SettingEntry) : FinderItem {
        override val key = "setting:${entry.title}"
    }

    data class Contact(val id: String, val name: String, val detail: String?, val icon: Icon, val intent: Intent) : FinderItem {
        override val key = "contact:$id"
    }

    data class File(val name: String, val detail: String, val icon: Icon, val intent: Intent) : FinderItem {
        override val key = "file:$detail/$name"
    }

    data class Calculation(val expression: String, val result: String) : FinderItem {
        override val key = "calc"
    }

    data class Converted(val conversion: Conversion) : FinderItem {
        override val key = "conversion"
    }

    data class Web(val text: String, val url: String, val isSuggestion: Boolean) : FinderItem {
        override val key = "web:$isSuggestion:$text"
    }
}

data class FinderResults(
    val query: String = "",
    val topHit: FinderItem? = null,
    val apps: List<AppInfo> = emptyList(),
    val settings: List<FinderItem.Setting> = emptyList(),
    val contacts: List<FinderItem.Contact> = emptyList(),
    val files: List<FinderItem.File> = emptyList(),
    val web: List<FinderItem.Web> = emptyList(),
    val webProviderName: String = "",
) {
    /** What pressing enter opens: the top hit, else the first result, else a web search. */
    val primary: FinderItem?
        get() = topHit ?: apps.firstOrNull()?.let { FinderItem.App(it) }
            ?: settings.firstOrNull() ?: contacts.firstOrNull() ?: files.firstOrNull() ?: web.firstOrNull()
}

/**
 * Searches apps, calculations, conversions, settings, contacts, files and the web for the
 * finder, and picks a top hit like Spotlight does.
 */
class FinderSearch(private val launcher: LawnchairLauncher) {

    private val context: Context = launcher
    private val scope = CoroutineScope(Dispatchers.Main.immediate + Job())
    private var searchJob: Job? = null

    private val _results = MutableStateFlow(FinderResults())
    val results: StateFlow<FinderResults> = _results

    private val _suggestions = MutableStateFlow<List<AppInfo>>(emptyList())

    /** Apps for the empty query ("App Suggestions"). */
    val suggestions: StateFlow<List<AppInfo>> = _suggestions

    /** The system Settings app, whose icon is shown next to settings results. */
    val settingsApp: AppInfo? by lazy {
        val pkg = Intent(android.provider.Settings.ACTION_SETTINGS).resolveActivity(context.packageManager)?.packageName
        findApp(pkg, null)
    }

    fun loadSuggestions(count: Int) {
        val predicted = runCatching {
            launcher.appsView.floatingHeaderView
                ?.findFixedRowByType(PredictionRowView::class.java)
                ?.predictedApps
                ?.mapNotNull { info -> findApp(info.targetComponent?.packageName, info.targetComponent?.className) }
        }.getOrNull().orEmpty()
        val apps = visibleApps("")
        _suggestions.value = (predicted + apps).distinctBy { it.toComponentKey() }.take(count)
    }

    private fun findApp(pkg: String?, cls: String?): AppInfo? {
        if (pkg == null) return null
        return launcher.appsView.appsStore.apps.firstOrNull {
            it.componentName?.packageName == pkg && (cls == null || it.componentName?.className == cls)
        }
    }

    private fun visibleApps(query: String): List<AppInfo> {
        val prefs = PreferenceManager2.getInstance(context)
        val hiddenApps = prefs.hiddenApps.firstCached()
        val hiddenAppsInSearch = prefs.hiddenAppsInSearch.firstCached()
        return launcher.appsView.appsStore.apps.asSequence()
            .filterHiddenApps(query, hiddenApps, hiddenAppsInSearch)
            .sortedBy { it.title?.toString()?.lowercase(Locale.getDefault()) }
            .toList()
    }

    fun search(rawQuery: String) {
        searchJob?.cancel()
        val query = rawQuery.trim()
        if (query.isEmpty()) {
            _results.value = FinderResults()
            return
        }
        searchJob = scope.launch {
            val webProvider = PreferenceManager2.getInstance(context).webSuggestionProvider.firstCached()
                .configure(context)
            val webName = if (webProvider is CustomWebSearchProvider) {
                webProvider.getDisplayName()
            } else {
                context.getString(webProvider.label)
            }
            val webSearch = FinderItem.Web(query, webProvider.getSearchUrl(query), isSuggestion = false)

            // Instant results first, so typing feels immediate.
            val apps = matchApps(query)
            val settings = withContext(Dispatchers.Default) { SettingsCatalog.search(context, query, MAX_SETTINGS) }
            val calculation = calculate(query)
            var conversion = Conversions.convert(query, CurrencyRates.peek(context))

            var results = rank(query, apps, settings, calculation, conversion, emptyList(), emptyList())
                .copy(web = listOf(webSearch), webProviderName = webName)
            _results.value = results

            // Then everything that has to hit a database or the network. Debounced a little so
            // fast typing doesn't spawn a query per key.
            delay(SLOW_QUERY_DEBOUNCE_MS)
            val contacts = async(Dispatchers.IO) { searchContacts(query) }
            val files = async(Dispatchers.IO) { searchFiles(query) }
            val rates = if (conversion == null && Conversions.isCurrencyQuery(query)) {
                async { CurrencyRates.get(context) }
            } else {
                null
            }

            rates?.await()?.let { conversion = Conversions.convert(query, it) }
            results = rank(query, apps, settings, calculation, conversion, contacts.await(), files.await())
                .copy(web = listOf(webSearch), webProviderName = webName)
            _results.value = results

            val suggestions = withTimeoutOrNull(WEB_TIMEOUT_MS) {
                webSuggestions(webProvider, query)
            }.orEmpty()
            if (suggestions.isNotEmpty()) {
                _results.value = results.copy(web = listOf(webSearch) + suggestions)
            }
        }
    }

    private suspend fun webSuggestions(provider: WebSearchProvider, query: String): List<FinderItem.Web> {
        return provider.getSuggestions(query)
            .catch { emit(emptyList()) }
            .firstOrNull()
            .orEmpty()
            .filter { !it.equals(query, ignoreCase = true) }
            .take(MAX_WEB_SUGGESTIONS)
            .map { FinderItem.Web(it, provider.getSearchUrl(it), isSuggestion = true) }
    }

    private fun matchApps(query: String): List<Pair<AppInfo, MatchType>> {
        val normalized = normalize(query)
        return visibleApps(normalized).mapNotNull { app ->
            val result = AppMatcher.match(normalize(app.title?.toString().orEmpty()), normalized)
            if (result.type == MatchType.NO_MATCH) null else Triple(app, result.type, result.score)
        }.sortedWith(compareBy({ it.second.priority }, { -it.third }))
            .map { it.first to it.second }
            .take(MAX_APPS)
    }

    private fun rank(
        query: String,
        apps: List<Pair<AppInfo, MatchType>>,
        settings: List<Pair<SettingEntry, Int>>,
        calculation: FinderItem.Calculation?,
        conversion: Conversion?,
        contacts: List<FinderItem.Contact>,
        files: List<FinderItem.File>,
    ): FinderResults {
        var appList = apps.map { it.first }
        var settingList = settings.map { FinderItem.Setting(it.first) }
        var contactList = contacts
        var fileList = files

        val topHit: FinderItem? = when {
            conversion != null -> FinderItem.Converted(conversion)
            calculation != null -> FinderItem.Calculation(calculation.expression, calculation.result)
            else -> {
                // Best app match type vs best setting score, mapped onto one scale.
                val appScore = apps.firstOrNull()?.second?.let {
                    when (it) {
                        MatchType.EXACT_MATCH -> 110
                        MatchType.DIRECT_PREFIX -> 90
                        MatchType.INITIALS, MatchType.TOKEN_PREFIX_ORDERED -> 80
                        MatchType.SUBSTRING, MatchType.ALL_TOKENS_PRESENT -> 60
                        else -> 30
                    }
                } ?: -1
                val settingScore = settings.firstOrNull()?.second ?: -1
                val contactScore = contacts.firstOrNull()?.let {
                    if (it.name.startsWith(query, ignoreCase = true)) 75 else 50
                } ?: -1
                val best = maxOf(appScore, settingScore, contactScore)
                when {
                    best < 0 -> fileList.firstOrNull()?.also { fileList = fileList.drop(1) }
                    best == appScore -> FinderItem.App(appList.first()).also { appList = appList.drop(1) }
                    best == settingScore -> settingList.first().also { settingList = settingList.drop(1) }
                    else -> contactList.first().also { contactList = contactList.drop(1) }
                }
            }
        }
        return FinderResults(
            query = query,
            topHit = topHit,
            apps = appList,
            settings = settingList,
            contacts = contactList,
            files = fileList,
        )
    }

    private fun calculate(query: String): FinderItem.Calculation? {
        val expression = query.replace('×', '*').replace('÷', '/').replace('−', '-')
            .replace(Regex("(?<=\\d)\\s*x\\s*(?=\\d)"), "*")
            .removeSuffix("=").trim()
        // A bare number isn't a calculation.
        if (!expression.any { it in "+-*/^%(!" } && !expression.any { it.isLetter() }) return null
        if (!expression.any { it.isDigit() }) return null
        return try {
            val value = Expressions().eval(expression).round(MathContext.DECIMAL64).stripTrailingZeros()
            val result = if (value.abs() > BigDecimal("9999999999999999")) value.toString() else value.toPlainString()
            if (expression.trimStart('+') == result) null else FinderItem.Calculation(expression, result)
        } catch (_: Exception) {
            null
        } catch (_: StackOverflowError) {
            null
        }
    }

    private fun searchContacts(query: String): List<FinderItem.Contact> {
        if (context.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            return emptyList()
        }
        return try {
            val uri = Uri.withAppendedPath(ContactsContract.Contacts.CONTENT_FILTER_URI, Uri.encode(query))
            val projection = arrayOf(
                ContactsContract.Contacts._ID,
                ContactsContract.Contacts.LOOKUP_KEY,
                ContactsContract.Contacts.DISPLAY_NAME_PRIMARY,
                ContactsContract.Contacts.PHOTO_THUMBNAIL_URI,
            )
            context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                buildList {
                    while (cursor.moveToNext() && size < MAX_CONTACTS) {
                        val id = cursor.getLong(0)
                        val lookup = cursor.getString(1)
                        val name = cursor.getString(2) ?: continue
                        val photo = cursor.getString(3)
                        val contactUri = ContactsContract.Contacts.getLookupUri(id, lookup)
                        val icon = if (photo != null) {
                            ContactsTarget.displayContactPhoto(context, name, photo.toUri())
                        } else {
                            ContactsTarget.displayContactPhoto(context, name, Uri.EMPTY)
                        }
                        val intent = Intent(Intent.ACTION_VIEW, contactUri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        add(FinderItem.Contact(id.toString(), name, null, icon, intent))
                    }
                }
            }.orEmpty()
        } catch (e: Exception) {
            Log.w(TAG, "Contact search failed", e)
            emptyList()
        }
    }

    private fun searchFiles(query: String): List<FinderItem.File> {
        if (query.length < 2) return emptyList()
        return try {
            val projection = arrayOf(
                MediaStore.MediaColumns._ID,
                MediaStore.MediaColumns.DATA,
                MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.SIZE,
                MediaStore.MediaColumns.DATE_MODIFIED,
                MediaStore.MediaColumns.MIME_TYPE,
                MediaStore.MediaColumns.RELATIVE_PATH,
            )
            val selection = "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ? AND ${MediaStore.MediaColumns.MIME_TYPE} IS NOT NULL"
            val factory = SearchTargetFactory(context)
            context.contentResolver.query(
                MediaStore.Files.getContentUri("external"),
                projection,
                selection,
                arrayOf("%$query%"),
                "${MediaStore.MediaColumns.DATE_MODIFIED} DESC",
            )?.use { cursor ->
                buildList {
                    while (cursor.moveToNext() && size < MAX_FILES) {
                        val path = cursor.getString(1) ?: continue
                        val name = cursor.getString(2) ?: continue
                        if (name.startsWith(".")) continue
                        val info = FileInfo(
                            fileId = cursor.getString(0),
                            path = path,
                            name = name,
                            size = cursor.getLong(3),
                            dateModified = cursor.getLong(4) * 1000,
                            mimeType = cursor.getString(5),
                        )
                        val intent = factory.createFilesTarget(info).searchAction?.intent ?: continue
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        val folder = cursor.getString(6)?.trimEnd('/') ?: path.substringBeforeLast('/')
                        add(FinderItem.File(name, folder, FilesTarget.getPreviewIcon(context, info), intent))
                    }
                }
            }.orEmpty()
        } catch (e: Exception) {
            Log.w(TAG, "File search failed", e)
            emptyList()
        }
    }

    fun destroy() {
        scope.cancel()
    }

    companion object {
        private const val TAG = "FinderSearch"
        private const val MAX_APPS = 8
        private const val MAX_SETTINGS = 4
        private const val MAX_CONTACTS = 3
        private const val MAX_FILES = 4
        private const val MAX_WEB_SUGGESTIONS = 4
        private const val SLOW_QUERY_DEBOUNCE_MS = 120L
        private const val WEB_TIMEOUT_MS = 2500L

        private val DIACRITICS = "\\p{M}+".toRegex()

        private fun normalize(input: String) = Normalizer.normalize(input, Normalizer.Form.NFKD)
            .replace(DIACRITICS, "")
            .lowercase(Locale.getDefault())
    }
}
