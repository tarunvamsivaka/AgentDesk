package com.agentdesk.app.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.agentdesk.app.MainActivity
import com.agentdesk.app.R
import com.agentdesk.app.overlay.AssistantOverlayActivity
import com.agentdesk.app.shortcut.ShortcutHelper
import com.agentdesk.core.persistence.dao.ConfirmationDao
import com.agentdesk.core.persistence.dao.NoteDao
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Native Android Home Screen AppWidget for AgentDesk.
 *
 * Displays:
 * 1. Quick-prompt bar: 1-tap fast access to AssistantOverlayActivity.
 * 2. Voice command button: Launches offline voice dictation overlay.
 * 3. Latest pinned note count: Queried asynchronously from [NoteDao].
 * 4. Pending task confirmation alerts: Queried from [ConfirmationDao].
 */
class AgentDeskAppWidgetProvider : AppWidgetProvider() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface WidgetEntryPoint {
        fun noteDao(): NoteDao
        fun confirmationDao(): ConfirmationDao
    }

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        val entryPoint = try {
            EntryPointAccessors.fromApplication(context.applicationContext, WidgetEntryPoint::class.java)
        } catch (_: Exception) {
            null
        }

        for (appWidgetId in appWidgetIds) {
            updateWidget(context, appWidgetManager, appWidgetId, entryPoint)
        }
    }

    private fun updateWidget(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        entryPoint: WidgetEntryPoint?
    ) {
        val views = RemoteViews(context.packageName, R.layout.widget_agentdesk)

        // 1. Tapping prompt bar launches the assistant overlay
        val overlayIntent = Intent(context, AssistantOverlayActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val overlayPendingIntent = PendingIntent.getActivity(
            context,
            101,
            overlayIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        views.setOnClickPendingIntent(R.id.widget_prompt_bar, overlayPendingIntent)

        // 2. Tapping voice button launches assistant with voice intent
        val voiceIntent = Intent(context, AssistantOverlayActivity::class.java).apply {
            action = ShortcutHelper.ACTION_VOICE_COMMAND
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val voicePendingIntent = PendingIntent.getActivity(
            context,
            102,
            voiceIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        views.setOnClickPendingIntent(R.id.widget_voice_btn, voicePendingIntent)

        // 3. Tapping note count launches MainActivity with quick note shortcut
        val noteIntent = Intent(context, MainActivity::class.java).apply {
            action = ShortcutHelper.ACTION_QUICK_NOTE
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val notePendingIntent = PendingIntent.getActivity(
            context,
            103,
            noteIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        views.setOnClickPendingIntent(R.id.widget_note_count, notePendingIntent)

        // 4. Tapping pending alerts opens MainActivity chat
        val chatIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val chatPendingIntent = PendingIntent.getActivity(
            context,
            104,
            chatIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        views.setOnClickPendingIntent(R.id.widget_pending_alert, chatPendingIntent)

        // Apply initial default view state
        appWidgetManager.updateAppWidget(appWidgetId, views)

        // Async query note count and pending confirmation alerts
        if (entryPoint != null) {
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val noteCount = entryPoint.noteDao().observeAll().first().size
                    val pendingCount = entryPoint.confirmationDao().observeRecent(50).first().count { it.userConfirmed == null }

                    views.setTextViewText(R.id.widget_note_count, "Notes: $noteCount")
                    views.setTextViewText(
                        R.id.widget_pending_alert,
                        if (pendingCount > 0) "Pending Actions: $pendingCount" else "All Actions Clear"
                    )
                    appWidgetManager.updateAppWidget(appWidgetId, views)
                } catch (_: Exception) {
                    // Retain default view values on query failure
                }
            }
        }
    }

    companion object {
        /**
         * Triggers an explicit update broadcast for all active AgentDesk home screen widgets.
         */
        fun updateAllWidgets(context: Context) {
            val intent = Intent(context, AgentDeskAppWidgetProvider::class.java).apply {
                action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
                val appWidgetManager = AppWidgetManager.getInstance(context)
                val ids = appWidgetManager.getAppWidgetIds(
                    ComponentName(context, AgentDeskAppWidgetProvider::class.java)
                )
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
            }
            context.sendBroadcast(intent)
        }
    }
}
