package com.agentdesk.core.settings.repository

import com.agentdesk.core.common.model.ConsentState
import com.agentdesk.core.persistence.dao.ConsentDao
import com.agentdesk.core.persistence.entity.ConsentRecordEntity
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Single source of truth for all consent state.
 * All feature-gate checks that require user consent go through this repository.
 */
@Singleton
class ConsentRepository @Inject constructor(
    private val consentDao: ConsentDao
) {

    fun observeAll(): Flow<List<ConsentRecordEntity>> = consentDao.observeAll()

    suspend fun getState(key: String): ConsentState {
        return consentDao.getState(key) ?: ConsentState.UNKNOWN
    }

    suspend fun isGranted(key: String): Boolean {
        return getState(key) == ConsentState.GRANTED
    }

    suspend fun grant(key: String) {
        val now = System.currentTimeMillis()
        consentDao.upsert(
            ConsentRecordEntity(
                consentKey = key,
                state = ConsentState.GRANTED,
                grantedAt = now,
                updatedAt = now
            )
        )
    }

    suspend fun deny(key: String) {
        val now = System.currentTimeMillis()
        consentDao.upsert(
            ConsentRecordEntity(
                consentKey = key,
                state = ConsentState.DENIED,
                updatedAt = now
            )
        )
    }

    suspend fun revoke(key: String) {
        val now = System.currentTimeMillis()
        consentDao.upsert(
            ConsentRecordEntity(
                consentKey = key,
                state = ConsentState.REVOKED,
                revokedAt = now,
                updatedAt = now
            )
        )
    }
}
