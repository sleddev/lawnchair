package app.lawnchair.today

import android.annotation.SuppressLint
import android.app.Activity
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Process
import android.util.Log
import android.view.MotionEvent
import android.view.ViewConfiguration
import app.lawnchair.LawnchairLauncher
import com.android.launcher3.Utilities
import kotlin.math.hypot

/**
 * Hosts the Android app widgets shown on the Today page, and runs the bind → configure flow
 * when one is added.
 */
class TodayWidgets(
    private val launcher: LawnchairLauncher,
    private val repository: TodayRepository,
) {

    private val manager = AppWidgetManager.getInstance(launcher)
    private val host = Host(launcher)
    private val views = mutableMapOf<Int, TodayWidgetHostView>()

    // The widget being added while the bind or configure activity is showing.
    private var pending: AppWidgetProviderInfo? = null
    private var pendingId = -1

    /** Called when a widget inside the page is long pressed. */
    var onLongPress: (() -> Unit)? = null

    init {
        removeOrphans()
    }

    fun startListening() {
        runCatching { host.startListening() }.onFailure { Log.w(TAG, "startListening failed", it) }
    }

    fun stopListening() {
        runCatching { host.stopListening() }
    }

    fun getInfo(appWidgetId: Int): AppWidgetProviderInfo? = manager.getAppWidgetInfo(appWidgetId)

    /** The view for a widget, created once and reused while the launcher lives. */
    fun getView(item: TodayItem.Widget): TodayWidgetHostView? {
        views[item.appWidgetId]?.let { return it }
        val info = getInfo(item.appWidgetId) ?: return null
        val view = host.createView(launcher, item.appWidgetId, info) as TodayWidgetHostView
        view.onLongPress = { onLongPress?.invoke() }
        views[item.appWidgetId] = view
        return view
    }

    fun installedProviders(): List<AppWidgetProviderInfo> =
        manager.getInstalledProvidersForProfile(Process.myUserHandle())
            .filter { it.widgetCategory and AppWidgetProviderInfo.WIDGET_CATEGORY_HOME_SCREEN != 0 }

    fun add(info: AppWidgetProviderInfo) {
        cancelPending()
        val id = host.allocateAppWidgetId()
        pending = info
        pendingId = id
        if (manager.bindAppWidgetIdIfAllowed(id, info.profile, info.provider, null)) {
            configureOrFinish()
        } else {
            val intent = Intent(AppWidgetManager.ACTION_APPWIDGET_BIND)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, info.provider)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER_PROFILE, info.profile)
            launcher.startActivityForResult(intent, REQUEST_BIND, null)
        }
    }

    /** Returns true when the result belonged to the Today page. */
    fun onActivityResult(requestCode: Int, resultCode: Int): Boolean {
        when (requestCode) {
            REQUEST_BIND -> if (resultCode == Activity.RESULT_OK) configureOrFinish() else cancelPending()
            REQUEST_CONFIGURE -> if (resultCode == Activity.RESULT_OK) finish() else cancelPending()
            else -> return false
        }
        return true
    }

    private fun configureOrFinish() {
        val info = pending ?: return
        val optional = Utilities.ATLEAST_P &&
            info.widgetFeatures and AppWidgetProviderInfo.WIDGET_FEATURE_CONFIGURATION_OPTIONAL != 0
        if (info.configure != null && !optional) {
            try {
                host.startAppWidgetConfigureActivityForResult(launcher, pendingId, 0, REQUEST_CONFIGURE, null)
                return
            } catch (e: Exception) {
                Log.w(TAG, "Couldn't configure ${info.provider}", e)
            }
        }
        finish()
    }

    private fun finish() {
        val info = pending ?: return
        repository.add(TodayItem.Widget(pendingId, info.provider, sizeFor(info)))
        pending = null
        pendingId = -1
    }

    private fun cancelPending() {
        if (pendingId != -1) host.deleteAppWidgetId(pendingId)
        pending = null
        pendingId = -1
    }

    fun remove(item: TodayItem.Widget) {
        repository.remove(item)
        views.remove(item.appWidgetId)
        host.deleteAppWidgetId(item.appWidgetId)
    }

    /** Picks the grid size that fits the widget's own minimum size best. */
    fun sizeFor(info: AppWidgetProviderInfo): TodaySize {
        val density = launcher.resources.displayMetrics.density
        val small = smallSideDp(launcher)
        fun dp(px: Int) = px / density
        val width = dp(if (info.minResizeWidth in 1 until info.minWidth) info.minResizeWidth else info.minWidth)
        val height = dp(if (info.minResizeHeight in 1 until info.minHeight) info.minResizeHeight else info.minHeight)
        return when {
            height > small * 1.25f -> TodaySize.Large
            width > small * 1.05f -> TodaySize.Medium
            else -> TodaySize.Small
        }
    }

    /** Sizes the widget can be shown in: anything at least as big as its own pick. */
    fun sizesFor(item: TodayItem.Widget): List<TodaySize> {
        val info = getInfo(item.appWidgetId) ?: return listOf(item.size)
        val min = sizeFor(info)
        val resizable = info.resizeMode != AppWidgetProviderInfo.RESIZE_NONE
        return if (resizable) TodaySize.entries.filter { it >= min } else listOf(item.size)
    }

    // Widget ids the host still holds but the page no longer shows, e.g. after a crash mid-add.
    private fun removeOrphans() {
        val used = repository.items.value.filterIsInstance<TodayItem.Widget>().map { it.appWidgetId }.toSet()
        runCatching {
            host.appWidgetIds.filter { it !in used }.forEach { host.deleteAppWidgetId(it) }
        }
        // And items whose widget went away (app uninstalled).
        repository.items.value.filterIsInstance<TodayItem.Widget>()
            .filter { manager.getAppWidgetInfo(it.appWidgetId) == null }
            .forEach { repository.remove(it) }
    }

    private class Host(context: Context) : AppWidgetHost(context, HOST_ID) {
        override fun onCreateView(context: Context, appWidgetId: Int, appWidget: AppWidgetProviderInfo?): AppWidgetHostView =
            TodayWidgetHostView(context)
    }

    companion object {
        private const val TAG = "TodayWidgets"
        private const val HOST_ID = 1029
        const val REQUEST_BIND = 0x7D01
        const val REQUEST_CONFIGURE = 0x7D02

        /** The side of a small grid item, in dp. */
        fun smallSideDp(context: Context): Float {
            val metrics = context.resources.displayMetrics
            val width = minOf(metrics.widthPixels, metrics.heightPixels) / metrics.density
            return (width - 2 * GRID_MARGIN_DP - GRID_GAP_DP) / 2f
        }

        const val GRID_MARGIN_DP = 20f
        const val GRID_GAP_DP = 16f
    }
}

/** A widget view that reports long presses, even when its contents handle clicks. */
@SuppressLint("ViewConstructor")
class TodayWidgetHostView(context: Context) : AppWidgetHostView(context) {

    var onLongPress: (() -> Unit)? = null

    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var longPressed = false
    private val longPressRunnable = Runnable {
        longPressed = true
        performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
        onLongPress?.invoke()
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                downY = ev.y
                longPressed = false
                postDelayed(longPressRunnable, ViewConfiguration.getLongPressTimeout().toLong())
            }

            MotionEvent.ACTION_MOVE -> if (hypot(ev.x - downX, ev.y - downY) > slop) {
                removeCallbacks(longPressRunnable)
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> removeCallbacks(longPressRunnable)
        }
        // Once long pressed, take the rest of the gesture so the widget doesn't also get a click.
        return longPressed
    }

    // Only reached when no child took the touch: keep tracking it for the long press.
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> if (hypot(event.x - downX, event.y - downY) > slop) {
                removeCallbacks(longPressRunnable)
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> removeCallbacks(longPressRunnable)
        }
        super.onTouchEvent(event)
        return true
    }

    // The page's card is the widget's frame, so drop the default padding around the widget, which
    // left a second, inset frame inside the card and cost the widget space.
    override fun setAppWidget(appWidgetId: Int, info: AppWidgetProviderInfo?) {
        super.setAppWidget(appWidgetId, info)
        setPadding(0, 0, 0, 0)
    }

    fun updateSize(widthDp: Float, heightDp: Float) {
        @Suppress("DEPRECATION")
        updateAppWidgetSize(Bundle(), widthDp.toInt(), heightDp.toInt(), widthDp.toInt(), heightDp.toInt())
    }
}
