package com.jemcik.jemrec.crm.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface WorkCallDao {

    /** IGNORE: a row already taken (same call log id) stays as it is. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(call: WorkCallEntry): Long

    @Update
    suspend fun update(call: WorkCallEntry)

    @Query("SELECT * FROM work_call WHERE id = :id")
    suspend fun byId(id: Long): WorkCallEntry?

    @Query("SELECT * FROM work_call WHERE callLogId = :callLogId")
    suspend fun byCallLogId(callLogId: Long): WorkCallEntry?

    @Query("SELECT * FROM work_call WHERE recording = :recording")
    suspend fun byRecording(recording: String): WorkCallEntry?

    @Query("SELECT * FROM work_call ORDER BY startedAtMs")
    suspend fun all(): List<WorkCallEntry>

    @Query("SELECT * FROM work_call ORDER BY startedAtMs DESC")
    fun watch(): Flow<List<WorkCallEntry>>

    /** Calls a recording could belong to: started in this window, no recording yet. */
    @Query("SELECT * FROM work_call WHERE recording IS NULL AND startedAtMs BETWEEN :from AND :to")
    suspend fun unmatchedBetween(from: Long, to: Long): List<WorkCallEntry>

    @Query("DELETE FROM work_call WHERE userId != :userId")
    suspend fun deleteOtherUsers(userId: Int): Int

    @Query("DELETE FROM work_call")
    suspend fun deleteAll()
}
