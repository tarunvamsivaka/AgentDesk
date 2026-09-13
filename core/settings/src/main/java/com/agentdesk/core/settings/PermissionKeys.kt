package com.agentdesk.core.settings

import android.Manifest

/**
 * Maps internal permission keys to Android manifest permission strings.
 * Used by PermissionDao and the Permission Center UI.
 */
object PermissionKeys {
    const val READ_MEDIA_IMAGES = "permission.read_media_images"
    const val READ_MEDIA_VIDEO = "permission.read_media_video"
    const val READ_MEDIA_AUDIO = "permission.read_media_audio"
    const val POST_NOTIFICATIONS = "permission.post_notifications"
    const val READ_CALENDAR = "permission.read_calendar"
    const val WRITE_CALENDAR = "permission.write_calendar"
    const val READ_CONTACTS = "permission.read_contacts"
    const val USE_BIOMETRIC = "permission.use_biometric"

    // Maps key -> Android system permission string
    val androidPermissionMap: Map<String, String> = mapOf(
        READ_MEDIA_IMAGES to Manifest.permission.READ_MEDIA_IMAGES,
        READ_MEDIA_VIDEO to Manifest.permission.READ_MEDIA_VIDEO,
        READ_MEDIA_AUDIO to Manifest.permission.READ_MEDIA_AUDIO,
        POST_NOTIFICATIONS to Manifest.permission.POST_NOTIFICATIONS,
        READ_CALENDAR to Manifest.permission.READ_CALENDAR,
        WRITE_CALENDAR to Manifest.permission.WRITE_CALENDAR,
        READ_CONTACTS to Manifest.permission.READ_CONTACTS,
        USE_BIOMETRIC to Manifest.permission.USE_BIOMETRIC,
    )
}
