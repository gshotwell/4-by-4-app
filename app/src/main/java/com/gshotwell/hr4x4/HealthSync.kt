package com.gshotwell.hr4x4

import android.content.Context
import android.content.Intent
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.time.Instant
import java.time.ZoneId

/** Writes a finished session to Health Connect (exercise session + heart-rate samples). */
object HealthSync {
    /** Outlives the activity so a write isn't cancelled by a rotation or back press. */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    val PERMISSIONS = setOf(
        HealthPermission.getWritePermission(ExerciseSessionRecord::class),
        HealthPermission.getWritePermission(HeartRateRecord::class),
    )

    fun available(ctx: Context) =
        HealthConnectClient.getSdkStatus(ctx) == HealthConnectClient.SDK_AVAILABLE

    suspend fun hasPermissions(ctx: Context): Boolean {
        if (!available(ctx)) return false
        val granted = HealthConnectClient.getOrCreate(ctx).permissionController.getGrantedPermissions()
        return granted.containsAll(PERMISSIONS)
    }

    private val contract = PermissionController.createRequestPermissionResultContract()
    fun permissionIntent(ctx: Context): Intent = contract.createIntent(ctx, PERMISSIONS)
    fun parsePermissionResult(resultCode: Int, data: Intent?): Boolean =
        contract.parseResult(resultCode, data).containsAll(PERMISSIONS)

    /** Returns a short human-readable result. */
    suspend fun write(ctx: Context, startWall: Long, samples: List<Sample>, reps: Int): String {
        if (samples.size < 10) return "Not saved to Health Connect: too little data"
        if (!available(ctx)) return "Health Connect not available on this phone"
        if (!hasPermissions(ctx)) return "Not saved: Health Connect permission not granted"
        val client = HealthConnectClient.getOrCreate(ctx)
        val start = Instant.ofEpochMilli(startWall)
        val end = start.plusMillis((samples.last().t * 1000).toLong() + 1000)
        val offset = ZoneId.systemDefault().rules.getOffset(start)

        val session = ExerciseSessionRecord(
            startTime = start, startZoneOffset = offset,
            endTime = end, endZoneOffset = offset,
            exerciseType = ExerciseSessionRecord.EXERCISE_TYPE_HIGH_INTENSITY_INTERVAL_TRAINING,
            title = "Norwegian ${reps}x4",
        )
        // One heart-rate record per ~5 minutes keeps each record small.
        val hrRecords = samples.chunked(300).map { chunk ->
            val cs = start.plusMillis((chunk.first().t * 1000).toLong())
            val ce = start.plusMillis((chunk.last().t * 1000).toLong() + 1000)
            HeartRateRecord(
                startTime = cs, startZoneOffset = offset,
                endTime = ce, endZoneOffset = offset,
                samples = chunk.map {
                    HeartRateRecord.Sample(start.plusMillis((it.t * 1000).toLong()), it.hr.toLong())
                },
            )
        }
        return try {
            client.insertRecords(listOf(session) + hrRecords)
            "Saved to Health Connect"
        } catch (e: Exception) {
            "Health Connect error: ${e.message}"
        }
    }
}
