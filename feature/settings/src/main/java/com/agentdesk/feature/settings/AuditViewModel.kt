package com.agentdesk.feature.settings

import androidx.lifecycle.ViewModel
import com.agentdesk.core.persistence.dao.AuditDao
import com.agentdesk.core.persistence.dao.PolicyDecisionDao
import com.agentdesk.core.persistence.entity.AuditEventEntity
import com.agentdesk.core.persistence.entity.PolicyDecisionEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

/**
 * AuditViewModel — exposes the recent audit trail and the policy decisions
 * that were rejected by the PolicyEngine. Read-only: the audit log is
 * append-only and every agent decision is written to it.
 */
@HiltViewModel
class AuditViewModel @Inject constructor(
    auditDao: AuditDao,
    policyDecisionDao: PolicyDecisionDao
) : ViewModel() {

    /** Last 50 audit events, newest first. */
    val events: Flow<List<AuditEventEntity>> = auditDao.observeRecent(limit = 50)

    /** Policy decisions blocked by the engine (allowed = 0), newest first. */
    val rejected: Flow<List<PolicyDecisionEntity>> = policyDecisionDao.observeRejected()
}