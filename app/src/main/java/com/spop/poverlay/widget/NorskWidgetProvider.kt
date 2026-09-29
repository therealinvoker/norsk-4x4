package com.spop.poverlay.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.os.Build
import android.widget.RemoteViews
import com.spop.poverlay.MainActivity
import com.spop.poverlay.R

/**
 * Home-screen widget that starts a Norwegian 4×4 ride with the last saved settings in one tap.
 * Placed from the launcher's widget picker (e.g. the bike's Kvaesitso launcher). Tapping routes
 * through [MainActivity] with [MainActivity.EXTRA_START_RIDE], which starts the overlay + session and
 * finishes without showing the config UI — going through an activity keeps the foreground-service
 * start in a foreground context (which Android permits) instead of a restricted background start.
 */
class NorskWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        val launchIntent = Intent(context, MainActivity::class.java).apply {
            action = "com.spop.poverlay.action.START_RIDE_FROM_WIDGET"
            putExtra(MainActivity.EXTRA_START_RIDE, true)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        var flags = PendingIntent.FLAG_UPDATE_CURRENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags = flags or PendingIntent.FLAG_IMMUTABLE
        }
        val pendingIntent = PendingIntent.getActivity(context, 0, launchIntent, flags)

        appWidgetIds.forEach { id ->
            val views = RemoteViews(context.packageName, R.layout.widget_norsk).apply {
                setOnClickPendingIntent(R.id.widget_root, pendingIntent)
            }
            appWidgetManager.updateAppWidget(id, views)
        }
    }
}
