package com.agentdesk.app

import com.agentdesk.app.shortcut.ShortcutHelper
import com.agentdesk.app.voice.OfflineVoiceController
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeIntegrationUnitTest {

    @Test
    fun testShortcutActionConstants() {
        assertEquals("com.agentdesk.action.QUICK_NOTE", ShortcutHelper.ACTION_QUICK_NOTE)
        assertEquals("com.agentdesk.action.VOICE_COMMAND", ShortcutHelper.ACTION_VOICE_COMMAND)
        assertEquals("com.agentdesk.action.SEARCH_LIBRARY", ShortcutHelper.ACTION_SEARCH_LIBRARY)

        assertEquals("shortcut_quick_note", ShortcutHelper.SHORTCUT_ID_NOTE)
        assertEquals("shortcut_voice_command", ShortcutHelper.SHORTCUT_ID_VOICE)
        assertEquals("shortcut_search_library", ShortcutHelper.SHORTCUT_ID_SEARCH)
    }

    @Test
    fun testOfflineVoiceStateTransitions() {
        val idle = OfflineVoiceController.VoiceState.Idle
        val listening = OfflineVoiceController.VoiceState.Listening
        val partial = OfflineVoiceController.VoiceState.Partial("open set")
        val result = OfflineVoiceController.VoiceState.Result("open settings")
        val error = OfflineVoiceController.VoiceState.Error("No speech detected")

        assertNotNull(idle)
        assertNotNull(listening)
        assertEquals("open set", partial.text)
        assertEquals("open settings", result.text)
        assertEquals("No speech detected", error.message)
    }
}
