package com.example.floatick.data

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Relation
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "schemes")
data class SchemeEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val name: String,
    val type: String,
    val loopCount: Int,
    val intervalMs: Long,
    val enabled: Boolean
)

@Entity(
    tableName = "steps",
    foreignKeys = [
        ForeignKey(
            entity = SchemeEntity::class,
            parentColumns = ["id"],
            childColumns = ["schemeId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("schemeId")]
)
data class StepEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val schemeId: Long,
    val orderIndex: Int,
    val xRatio: Float,
    val yRatio: Float,
    val endXRatio: Float,
    val endYRatio: Float,
    val action: String,
    val durationMs: Long,
    val delayAfterMs: Long
)

@Entity(tableName = "schedules")
data class ScheduleEntity(
    @PrimaryKey val schemeId: Long,
    val triggerUtcMillis: Long,
    val enabled: Boolean
)

@Entity(tableName = "run_logs")
data class RunLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val schemeId: Long,
    val targetUtcMillis: Long,
    val offsetMs: Long,
    val rttMs: Long,
    val deviationMs: Long,
    val executedCount: Int,
    val stopReason: String,
    val synced: Boolean,
    val startedAtUtcMillis: Long
)

data class SchemeWithSteps(
    @Embedded val scheme: SchemeEntity,
    @Relation(parentColumn = "id", entityColumn = "schemeId")
    val steps: List<StepEntity>
)

@Dao
interface AppDao {
    @Transaction
    @Query("SELECT * FROM schemes ORDER BY id DESC")
    fun observeSchemes(): Flow<List<SchemeWithSteps>>

    @Transaction
    @Query("SELECT * FROM schemes WHERE id = :id")
    suspend fun getScheme(id: Long): SchemeWithSteps?

    @Insert
    suspend fun insertScheme(scheme: SchemeEntity): Long

    @Update
    suspend fun updateScheme(scheme: SchemeEntity)

    @Query("DELETE FROM schemes WHERE id = :id")
    suspend fun deleteSchemeById(id: Long)

    @Query("DELETE FROM steps WHERE schemeId = :schemeId")
    suspend fun deleteSteps(schemeId: Long)

    @Insert
    suspend fun insertSteps(steps: List<StepEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putSchedule(schedule: ScheduleEntity)

    @Query("SELECT * FROM schedules WHERE schemeId = :schemeId")
    suspend fun scheduleFor(schemeId: Long): ScheduleEntity?

    @Query("SELECT * FROM schedules WHERE enabled = 1")
    suspend fun allSchedules(): List<ScheduleEntity>

    @Query("DELETE FROM schedules WHERE schemeId = :schemeId")
    suspend fun deleteSchedule(schemeId: Long)

    @Insert
    suspend fun insertLog(log: RunLogEntity)

    @Query("SELECT * FROM run_logs ORDER BY id DESC LIMIT :limit")
    fun observeLogs(limit: Int): Flow<List<RunLogEntity>>

    @Query("SELECT COUNT(*) FROM schedules WHERE enabled = 1")
    suspend fun activeScheduleCount(): Int
}

@Database(
    entities = [
        SchemeEntity::class,
        StepEntity::class,
        ScheduleEntity::class,
        RunLogEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun dao(): AppDao
}
