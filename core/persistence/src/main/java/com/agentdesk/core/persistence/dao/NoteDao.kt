package com.agentdesk.core.persistence.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.agentdesk.core.persistence.entity.NoteEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface NoteDao {

    @Insert
    suspend fun insert(note: NoteEntity)

    @Query("SELECT * FROM notes ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<NoteEntity>>

    @Query("SELECT * FROM notes WHERE body LIKE '%' || :query || '%' LIMIT 10")
    suspend fun search(query: String): List<NoteEntity>

    @Query("DELETE FROM notes")
    suspend fun deleteAll()
}