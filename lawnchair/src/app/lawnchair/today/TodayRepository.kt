package app.lawnchair.today

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Keeps the ordered list of Today page items, persisted as JSON. */
class TodayRepository(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val _items = MutableStateFlow(load())
    val items: StateFlow<List<TodayItem>> = _items.asStateFlow()

    private fun load(): List<TodayItem> {
        val json = prefs.getString(KEY_ITEMS, null) ?: return TodayItem.defaults
        return TodayItem.listFromJson(json)
    }

    fun set(items: List<TodayItem>) {
        _items.value = items
        prefs.edit { putString(KEY_ITEMS, TodayItem.listToJson(items)) }
    }

    fun add(item: TodayItem) = set(listOf(item) + _items.value.filterNot { it.id == item.id })

    fun remove(item: TodayItem) = set(_items.value.filterNot { it.id == item.id })

    fun replace(old: TodayItem, new: TodayItem) = set(_items.value.map { if (it.id == old.id) new else it })

    companion object {
        private const val PREFS_NAME = "today_page"
        private const val KEY_ITEMS = "items"
    }
}
