package com.agentdesk.app.service

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.agentdesk.app.overlay.AssistantOverlayActivity

/**
 * AgentDeskTileService provides a native Quick Settings Tile in the Android notification shade.
 *
 * Tapping the tile unlocks the screen (if locked) and brings up [AssistantOverlayActivity]
 * without forcing a jarring full-screen context switch over running applications.
 */
class AgentDeskTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        qsTile?.let { tile ->
            tile.state = Tile.STATE_INACTIVE
            tile.label = "AgentDesk"
            tile.contentDescription = "Open AgentDesk Assistant"
            tile.updateTile()
        }
    }

    override fun onClick() {
        super.onClick()
        val intent = Intent(this, AssistantOverlayActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }

        if (isLocked) {
            unlockAndRun {
                launchOverlay(intent)
            }
        } else {
            launchOverlay(intent)
        }
    }

    private fun launchOverlay(intent: Intent) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                val pendingIntent = PendingIntent.getActivity(
                    this,
                    0,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                startActivityAndCollapse(pendingIntent)
            } else {
                @Suppress("DEPRECATION")
                startActivityAndCollapse(intent)
            }
        } catch (_: Exception) {
            // Graceful fallback to standard startActivity if collapse fails
            startActivity(intent)
        }
    }
}
