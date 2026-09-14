package com.agentdesk.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.agentdesk.core.common.model.ConsentState
import com.agentdesk.core.persistence.dao.AuditDao
import com.agentdesk.core.persistence.dao.ConsentDao
import com.agentdesk.core.persistence.dao.KnowledgeDao
import com.agentdesk.core.persistence.dao.NoteDao
import com.agentdesk.core.persistence.entity.ConsentRecordEntity
import com.agentdesk.core.security.AppLockManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * SettingsViewModel — wires real controls:
 * - App lock enable/disable via [AppLockManager]
 * - Consent records via [ConsentDao] (one record per ConsentKeys entry)
 * - "Delete all local data" clears notes, documents, chunks (FTS4 is
 *   content-synced), knowledge sources, shared items, and the audit log.
 *   The UI MUST show a confirmation dialog before calling this.
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val appLockManager: AppLockManager,
    private val consentDao: ConsentDao,
    private val noteDao: NoteDao,
    private val knowledgeDao: KnowledgeDao,
    private val auditDao: AuditDao
) : ViewModel() {

    private val _appLockEnabled = MutableStateFlow(appLockManager.isLockEnabled())
    val appLockEnabled: StateFlow<Boolean> = _appLockEnabled.asStateFlow()

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
            knowledgeDao.deleteAllChunks()
            knowledgeDao.deleteAllDocuments()
            knowledgeDao.deleteAllSources()
            knowledgeDao.deleteAllSharedItems()
            noteDao.deleteAll()
            auditDao.deleteAll()
        }
    }
}