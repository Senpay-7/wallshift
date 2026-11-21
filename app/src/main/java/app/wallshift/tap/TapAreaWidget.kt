package app.wallshift.tap

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.widget.RemoteViews
import app.wallshift.R

/**
 * Retired: the old invisible "double-tap area" widget.
 *
 * It could never cover the whole screen (widgets can't lie over icons), and on HyperOS adding
 * it put an *invisible* widget on a new Home screen page each time. The live wallpaper already
 * catches double taps on all empty space, on every page.
 *
 * The provider stays only so widgets placed earlier become visible ("remove me") and can be
 * found and removed; it is hidden from the widget picker and does nothing on tap.
 */
class TapAreaWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        showRemovalNotice(context, manager, ids)
    }

    companion object {
        private fun component(context: Context) = ComponentName(context, TapAreaWidget::class.java)

        /** Number of old tap areas still placed on the Home screen. */
        fun leftoverCount(context: Context): Int = runCatching {
            context.getSystemService(AppWidgetManager::class.java)
                ?.getAppWidgetIds(component(context))?.size ?: 0
        }.getOrDefault(0)

        /** Makes every leftover instance visible with instructions to remove it. */
        fun refreshLeftovers(context: Context) {
            runCatching {
                val mgr = context.getSystemService(AppWidgetManager::class.java) ?: return
                val ids = mgr.getAppWidgetIds(component(context))
                if (ids.isNotEmpty()) showRemovalNotice(context, mgr, ids)
            }
        }

        private fun showRemovalNotice(context: Context, manager: AppWidgetManager, ids: IntArray) {
            manager.updateAppWidget(ids, RemoteViews(context.packageName, R.layout.widget_tap_area))
        }
    }
}
