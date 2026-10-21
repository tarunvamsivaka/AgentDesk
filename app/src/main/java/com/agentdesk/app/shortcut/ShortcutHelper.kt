package com.agentdesk.app.shortcut

import android.content.Context
import android.content.Intent
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.agentdesk.app.MainActivity
import com.agentdesk.app.R
import com.agentdesk.app.overlay.AssistantOverlayActivity

/**
 * Manages native dynamic launcher shortcuts for AgentDesk.
 *
 * Exposes 1-tap fast actions:
 * - "Quick Note" -> Directly opens note creation
 * - "Voice Command" -> Launches the assistant overlay ready for voice input
 * - "Search Library" -> Navigates to document and library search
 */
object ShortcutHelper {

    const val ACTION_QUICK_NOTE = "com.agentdesk.action.QUICK_NOTE"
    const val ACTION_VOICE_COMMAND = "com.agentdesk.action.VOICE_COMMAND"
    const val ACTION_SEARCH_LIBRARY = "com.agentdesk.action.SEARCH_LIBRARY"

    const val SHORTCUT_ID_NOTE = "shortcut_quick_note"
    const val SHORTCUT_ID_VOICE = "shortcut_voice_command"
    const val SHORTCUT_ID_SEARCH = "shortcut_search_library"

    /**
     * Publishes dynamic shortcuts on Android 7.1+ (API 25+).
     */
    fun publishDynamicShortcuts(context: Context) {
        try {
            val noteIntent = Intent(context, MainActivity::class.java).apply {
                action = ACTION_QUICK_NOTE
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }

            val noteShortcut = ShortcutInfoCompat.Builder(context, SHORTCUT_ID_NOTE)
                .setShortLabel("Quick Note")
                .setLongLabel("Create Quick Note")
                .setIcon(IconCompat.createWithResource(context, R.mipmap.ic_launcher))
                .setIntent(noteIntent)
                .build()

            val voiceIntent = Intent(context, AssistantOverlayActivity::class.java).apply {
                action = ACTION_VOICE_COMMAND
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }

            val voiceShortcut = ShortcutInfoCompat.Builder(context, SHORTCUT_ID_VOICE)
                .setShortLabel("Voice Command")
                .setLongLabel("Dictate Voice Command")
                .setIcon(IconCompat.createWithResource(context, R.mipmap.ic_launcher))
                .setIntent(voiceIntent)
                .build()

            val searchIntent = Intent(context, MainActivity::class.java).apply {
                action = ACTION_SEARCH_LIBRARY
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }

            val searchShortcut = ShortcutInfoCompat.Builder(context, SHORTCUT_ID_SEARCH)
                .setShortLabel("Search Library")
                .setLongLabel("Search Local Knowledge")
                .setIcon(IconCompat.createWithResource(context, R.mipmap.ic_launcher))
                .setIntent(searchIntent)
                .build()

            ShortcutManagerCompat.setDynamicShortcuts(
                context,
                listOf(noteShortcut, voiceShortcut, searchShortcut)
            )
        } catch (_: Exception) {
            // Graceful fallback if shortcuts cannot be registered
        }
    }
}
