package app.lawnchair.today

import android.content.ComponentName
import org.json.JSONArray
import org.json.JSONObject

/** How much room an item takes in the Today grid, like iOS widget sizes. */
enum class TodaySize {
    /** Half the width, square. */
    Small,

    /** Full width, as tall as a small item. */
    Medium,

    /** Full width, twice as tall as a small item. */
    Large,
}

/** The built-in cards the Today page can show. */
enum class TodayCard(val title: String, val sizes: List<TodaySize>) {
    Weather("Weather", listOf(TodaySize.Small, TodaySize.Medium)),
    Battery("Batteries", listOf(TodaySize.Small, TodaySize.Medium)),
    Calendar("Up Next", listOf(TodaySize.Small, TodaySize.Medium, TodaySize.Large)),
    Photo("Photos", listOf(TodaySize.Small, TodaySize.Medium, TodaySize.Large)),
    ScreenTime("Screen Time", listOf(TodaySize.Small, TodaySize.Medium)),
}

/** An item on the Today page: either a built-in card or an Android app widget. */
sealed class TodayItem {
    abstract val id: String
    abstract val size: TodaySize

    data class Card(val card: TodayCard, override val size: TodaySize) : TodayItem() {
        override val id get() = "card:${card.name}"
    }

    data class Widget(
        val appWidgetId: Int,
        val provider: ComponentName,
        override val size: TodaySize,
    ) : TodayItem() {
        override val id get() = "widget:$appWidgetId"
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("size", size.name)
        when (this@TodayItem) {
            is Card -> put("card", card.name)
            is Widget -> {
                put("widgetId", appWidgetId)
                put("provider", provider.flattenToString())
            }
        }
    }

    companion object {
        val defaults = listOf(
            Card(TodayCard.Weather, TodaySize.Small),
            Card(TodayCard.Battery, TodaySize.Small),
            Card(TodayCard.Calendar, TodaySize.Medium),
            Card(TodayCard.Photo, TodaySize.Small),
            Card(TodayCard.ScreenTime, TodaySize.Small),
        )

        fun fromJson(json: JSONObject): TodayItem? = runCatching {
            val size = TodaySize.valueOf(json.getString("size"))
            if (json.has("card")) {
                Card(TodayCard.valueOf(json.getString("card")), size)
            } else {
                val provider = ComponentName.unflattenFromString(json.getString("provider"))
                    ?: return null
                Widget(json.getInt("widgetId"), provider, size)
            }
        }.getOrNull()

        fun listToJson(items: List<TodayItem>): String = JSONArray(items.map { it.toJson() }).toString()

        fun listFromJson(json: String): List<TodayItem> = runCatching {
            val array = JSONArray(json)
            (0 until array.length()).mapNotNull { fromJson(array.getJSONObject(it)) }
        }.getOrDefault(defaults)
    }
}
