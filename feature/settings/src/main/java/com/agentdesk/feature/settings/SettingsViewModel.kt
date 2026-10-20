package com.agentdesk.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.withTransaction
import com.agentdesk.agent.rule.RuleEngine
import com.agentdesk.core.common.model.ConsentState
import com.agentdesk.core.persistence.dao.AuditDao
import com.agentdesk.core.persistence.dao.ConsentDao
import com.agentdesk.core.persistence.dao.KnowledgeDao
import com.agentdesk.core.persistence.dao.NoteDao
import com.agentdesk.core.persistence.db.AgentDeskDatabase
import com.agentdesk.core.persistence.entity.ConsentRecordEntity
import com.agentdesk.core.persistence.entity.NoteEntity
import com.agentdesk.core.security.AppLockManager
import com.agentdesk.core.security.VaultBackupManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.io.OutputStream
import javax.inject.Inject

/**
 * SettingsViewModel — wires real controls:
 * - App lock enable/disable via [AppLockManager]
 * - Consent records via [ConsentDao] (one record per ConsentKeys entry)
 * - "Delete all local data" clears notes, documents, chunks (FTS4 is
 *   content-synced), knowledge sources, shared items, and the audit log
 *   atomically inside one database transaction.
 * - Authenticated AES-256-GCM encrypted vault export & restore via [VaultBackupManager]
 * - Custom keyword-triggered user rules via [RuleEngine]
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val appLockManager: AppLockManager,
    private val agentDeskDatabase: AgentDeskDatabase,
    private val consentDao: ConsentDao,
    private val noteDao: NoteDao,
    private val knowledgeDao: KnowledgeDao,
    private val auditDao: AuditDao,
    private val vaultBackupManager: VaultBackupManager,
    private val ruleEngine: RuleEngine
) : ViewModel() {

    private val _appLockEnabled = MutableStateFlow(appLockManager.isLockEnabled())
    val appLockEnabled: StateFlow<Boolean> = _appLockEnabled.asStateFlow()

    private val _customRules = MutableStateFlow(ruleEngine.getCustomRules())
    val customRules: StateFlow<List<RuleEngine.CustomRule>> = _customRules.asStateFlow()

    private val _vaultStatusMessage = MutableStateFlow<String?>(null)
    val vaultStatusMessage: StateFlow<String?> = _vaultStatusMessage.asStateFlow()

    /** All consent records, newest update first. */
    val consents: Flow<List<ConsentRecordEntity>> = consentDao.observeAll()

    fun enableAppLock(pin: String) {
        viewModelScope.launch {
            appLockManager.enableLock(pin)
            _appLockEnabled.value = true
        }
    }

    fun disableAppLock() {
        viewModelScope.launch {
            appLockManager.disableLock()
            _appLockEnabled.value = false
        }
    }

    /** Upserts a consent record for [key] as GRANTED or REVOKED. */
    fun setConsent(key: String, granted: Boolean) {
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            consentDao.upsert(
                ConsentRecordEntity(
                    consentKey = key,
                    state = if (granted) ConsentState.GRANTED else ConsentState.REVOKED,
                    grantedAt = if (granted) now else null,
                    revokedAt = if (!granted) now else null,
                    updatedAt = now
                )
            )
        }
    }

    /** Destructive: clears all local knowledge, notes, and audit data. Requires prior user confirmation. */
    fun deleteAllLocalData() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                agentDeskDatabase.withTransaction {
                    knowledgeDao.deleteAllChunks()
                    knowledgeDao.deleteAllDocuments()
                    knowledgeDao.deleteAllSources()
                    knowledgeDao.deleteAllSharedItems()
                    noteDao.deleteAll()
                    auditDao.deleteAll()
                }
            }
        }
    }

    // ─── Custom Rules Builder ──────────────────────────────────────────────────

    fun addCustomRule(triggerPhrase: String, intentName: String, targetAppOrPackage: String? = null) {
        val rule = RuleEngine.CustomRule(
            triggerPhrase = triggerPhrase.trim(),
            intentName = intentName.trim(),
            targetAppOrPackage = targetAppOrPackage?.trim()?.ifBlank { null }
        )
        ruleEngine.registerCustomRule(rule)
        _customRules.value = ruleEngine.getCustomRules()
    }

    fun removeCustomRule(ruleId: String) {
        ruleEngine.removeCustomRule(ruleId)
        _customRules.value = ruleEngine.getCustomRules()
    }

    // ─── Encrypted Vault Backup & Restore ──────────────────────────────────────

    fun exportVault(password: String, outputStream: OutputStream, onComplete: (Boolean, String) -> Unit) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val notes = noteDao.observeAll().first()
                    val rules = ruleEngine.getCustomRules()

                    val json = JSONObject()
                    json.put("format", "agentdesk_vault")
                    json.put("version", 1)
                    json.put("exportedAt", System.currentTimeMillis())

                    val notesArray = JSONArray()
                    for (note in notes) {
                        val n = JSONObject()
                        n.put("id", note.id)
                        n.put("title", note.title)
                        n.put("body", note.body)
                        n.put("createdAt", note.createdAt)
                        notesArray.put(n)
                    }
                    json.put("notes", notesArray)

                    val rulesArray = JSONArray()
                    for (rule in rules) {
                        val r = JSONObject()
                        r.put("id", rule.id)
                        r.put("triggerPhrase", rule.triggerPhrase)
                        r.put("intentName", rule.intentName)
                        r.put("targetApp", rule.targetAppOrPackage ?: "")
                        rulesArray.put(r)
                    }
                    json.put("customRules", rulesArray)

                    val payloadBytes = json.toString().toByteArray(Charsets.UTF_8)
                    val encryptedBytes = vaultBackupManager.encryptVault(payloadBytes, password.toCharArray())
                    outputStream.use { it.write(encryptedBytes) }
                }
                _vaultStatusMessage.value = "Vault exported successfully."
                onComplete(true, "Vault exported successfully.")
            } catch (e: Exception) {
                _vaultStatusMessage.value = "Export failed: ${e.message}"
                onComplete(false, e.message ?: "Export failed")
            }
        }
    }

    fun importVault(password: String, inputStream: InputStream, onComplete: (Boolean, String) -> Unit) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val vaultBytes = inputStream.use { it.readBytes() }
                    val decryptedBytes = vaultBackupManager.decryptVault(vaultBytes, password.toCharArray())
                    val json = JSONObject(String(decryptedBytes, Charsets.UTF_8))

                    val notesArray = json.optJSONArray("notes")
                    if (notesArray != null) {
                        for (i in 0 until notesArray.length()) {
                            val n = notesArray.getJSONObject(i)
                            noteDao.insert(
                                NoteEntity(
                                    id = n.getString("id"),
                                    title = n.getString("title"),
                                    body = n.getString("body"),
                                    createdAt = n.getLong("createdAt")
                                )
                            )
                        }
                    }

                    val rulesArray = json.optJSONArray("customRules")
                    if (rulesArray != null) {
                        for (i in 0 until rulesArray.length()) {
                            val r = rulesArray.getJSONObject(i)
                            val targetApp = r.optString("targetApp").ifBlank { null }
                            ruleEngine.registerCustomRule(
                                RuleEngine.CustomRule(
                                    id = r.getString("id"),
                                    triggerPhrase = r.getString("triggerPhrase"),
                                    intentName = r.getString("intentName"),
                                    targetAppOrPackage = targetApp
                                )
                            )
                        }
                    }
                }
                _customRules.value = ruleEngine.getCustomRules()
                _vaultStatusMessage.value = "Vault restored successfully."
                onComplete(true, "Vault restored successfully.")
            } catch (e: SecurityException) {
                _vaultStatusMessage.value = "Restore failed: Invalid password or corrupted vault"
                onComplete(false, "Invalid password or corrupted vault")
            } catch (e: Exception) {
                _vaultStatusMessage.value = "Restore failed: ${e.message}"
                onComplete(false, e.message ?: "Restore failed")
            }
        }
    }

    fun clearVaultStatusMessage() {
        _vaultStatusMessage.value = null
    }
}
