package com.agentdesk.core.persistence.dao

import androidx.room.*
import com.agentdesk.core.persistence.entity.DeviceCapabilitySnapshotEntity
import com.agentdesk.core.persistence.entity.DeviceHealthSnapshotEntity
import com.agentdesk.core.persistence.entity.ResourceEventEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface DeviceDao {

    @Insert
    suspend fun insertCapabilitySnapshot(snapshot: DeviceCapabilitySnapshotEntity): Long

    /** Alias used by OnboardingViewModel during device scan step. */
    suspend fun insertSnapshot(snapshot: DeviceCapabilitySnapshotEntity) =
        insertCapabilitySnapshot(snapshot)

    @Query("SELECT * FROM device_capability_snapshot ORDER BY captured_at DESC LIMIT 1")
    suspend fun latestCapabilitySnapshot(): DeviceCapabilitySnapshotEntity?

    @Insert
    suspend fun insertHealthSnapshot(snapshot: DeviceHealthSnapshotEntity): Long

    @Query("SELECT * FROM device_health_snapshot ORDER BY captured_at DESC LIMIT :limit")
    fun observeHealthSnapshots(limit: Int = 24): Flow<List<DeviceHealthSnapshotEntity>>

    @Query("SELECT * FROM device_health_snapshot ORDER BY captured_at DESC LIMIT 1")
    suspend fun latestHealthSnapshot(): DeviceHealthSnapshotEntity?

    @Insert
    suspend fun insertResourceEvent(event: ResourceEventEntity): Long

    @Query("SELECT * FROM resource_event ORDER BY event_at DESC LIMIT :limit")
    fun observeResourceEvents(limit: Int = 50): Flow<List<ResourceEventEntity>>

    @Query("DELETE FROM device_health_snapshot WHERE captured_at < :beforeEpoch")
    suspend fun pruneHealthSnapshots(beforeEpoch: Long)
}
