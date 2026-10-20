package com.agentdesk.tools.impl

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.agentdesk.core.common.model.RiskLevel
import com.agentdesk.tools.contact.ContactResolver
import com.agentdesk.tools.contact.Resolution
import com.agentdesk.tools.executor.ToolRequest
import com.agentdesk.tools.executor.ToolResult
import com.agentdesk.tools.registry.Tool
import com.agentdesk.tools.registry.ToolCategory
import com.agentdesk.tools.registry.ToolDefinition
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Handles communication intents: Phone dialer, SMS drafting, and Email drafting.
 *
 * Security Invariants:
 * - Never transmits messages or places calls silently in the background (no SmsManager, no ACTION_CALL).
 * - Always opens the transparent system UI requiring user tap to confirm.
 * - Gated by RiskLevel.HIGH in PolicyEngine, requiring human-in-the-loop approval.
 */
@Singleton
class CommunicationTool private constructor(
    private val appContext: Context?,
    private val contactResolver: ContactResolver?,
    @Suppress("UNUSED_PARAMETER") marker: Any?
) : Tool {

    @Inject
    constructor(
        @ApplicationContext context: Context,
        contactResolver: ContactResolver
    ) : this(context, contactResolver, null)

    internal constructor() : this(null, null, null)

    override val definition = ToolDefinition(
        id = "communication",
        name = "Communication Tool",
        description = "Draft SMS, place calls, or draft emails with human confirmation.",
        category = ToolCategory.COMMUNICATION,
        riskLevel = RiskLevel.HIGH,
        requiresConfirmation = true
    )

    override suspend fun execute(request: ToolRequest): ToolResult {
        return when (request.toolId) {
            "draft_sms" -> draftSms(request.parameters)
            "open_dialer" -> openDialer(request.parameters)
            "draft_email", "send_email" -> draftEmail(request.parameters)
            else -> {
                when {
                    request.parameters.containsKey("email") || request.parameters.containsKey("subject") ->
                        draftEmail(request.parameters)
                    request.parameters.containsKey("body") || request.parameters.containsKey("message") ->
                        draftSms(request.parameters)
                    else ->
                        openDialer(request.parameters)
                }
            }
        }
    }

    private suspend fun draftSms(params: Map<String, String>): ToolResult {
        val contact = params["contact"] ?: params["recipient"] ?: params["phone_number"]
        if (contact.isNullOrBlank()) {
            return ToolResult.Error("draft_sms", "No contact or phone number specified.")
        }
        val body = params["body"] ?: params["message"] ?: ""

        val resolution = contactResolver?.resolve(contact) ?: run {
            if (contact.any { it.isDigit() }) Resolution.IsNumber(contact)
            else Resolution.Single(contact, contact)
        }
        val number = when (resolution) {
            is Resolution.IsNumber -> resolution.number
            is Resolution.Single -> resolution.number
            is Resolution.Multiple -> return ToolResult.Error(
                "draft_sms",
                "Found ${resolution.labels.size} matches for \"$contact\": " +
                        "${resolution.labels.joinToString(", ")}. Please specify which one."
            )
            Resolution.None -> {
                val reason = if (contactResolver?.hasContactsPermission() == true) {
                    "No contact found for \"$contact\"."
                } else {
                    "Contacts permission is needed to look up \"$contact\". " +
                            "Enable it in Settings -> Apps -> AgentDesk -> Permissions."
                }
                return ToolResult.Error("draft_sms", reason)
            }
        }

        val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$number")).apply {
            putExtra("sms_body", body)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        appContext?.startActivity(intent)
        return ToolResult.Success("draft_sms", "SMS draft opened for $contact ($number). Tap Send to deliver.")
    }

    private suspend fun openDialer(params: Map<String, String>): ToolResult {
        val contact = params["contact"] ?: params["phone_number"] ?: params["number"]
        if (contact.isNullOrBlank()) {
            return ToolResult.Error("open_dialer", "No contact or phone number specified.")
        }

        val resolution = contactResolver?.resolve(contact) ?: run {
            if (contact.any { it.isDigit() }) Resolution.IsNumber(contact)
            else Resolution.Single(contact, contact)
        }
        val number = when (resolution) {
            is Resolution.IsNumber -> resolution.number
            is Resolution.Single -> resolution.number
            is Resolution.Multiple -> return ToolResult.Error(
                "open_dialer",
                "Found ${resolution.labels.size} matches for \"$contact\": " +
                        "${resolution.labels.joinToString(", ")}. Please specify which one."
            )
            Resolution.None -> {
                val reason = if (contactResolver?.hasContactsPermission() == true) {
                    "No contact found for \"$contact\"."
                } else {
                    "Contacts permission is needed to look up \"$contact\". " +
                            "Enable it in Settings -> Apps -> AgentDesk -> Permissions."
                }
                return ToolResult.Error("open_dialer", reason)
            }
        }

        val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$number")).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        appContext?.startActivity(intent)
        return ToolResult.Success("open_dialer", "Dialer opened for $contact ($number). Tap Call to connect.")
    }

    private fun draftEmail(params: Map<String, String>): ToolResult {
        val recipient = params["recipient"] ?: params["email"] ?: params["contact"]
        if (recipient.isNullOrBlank()) {
            return ToolResult.Error("draft_email", "No recipient email address specified.")
        }
        val subject = params["subject"]?.trim().orEmpty()
        val body = params["body"] ?: params["text"] ?: params["message"] ?: ""

        val emailUri = if (recipient.contains("@")) {
            Uri.parse("mailto:$recipient")
        } else {
            Uri.parse("mailto:")
        }

        val intent = Intent(Intent.ACTION_SENDTO, emailUri).apply {
            if (subject.isNotBlank()) {
                putExtra(Intent.EXTRA_SUBJECT, subject)
            }
            if (body.isNotBlank()) {
                putExtra(Intent.EXTRA_TEXT, body)
            }
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        appContext?.startActivity(intent)
        return ToolResult.Success("draft_email", "Email draft opened for $recipient.")
    }
}
