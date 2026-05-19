package com.cuee.data

import com.cuee.domain.safety.StopReason
import kotlinx.coroutines.flow.Flow

data class UtMetric(
    val sessionId: String,
    val taskType: UtTaskType,
    val startedAt: Long,
    val finishedAt: Long?,
    val elapsedMs: Long?,
    val result: UtResult,
    val stopReason: StopReason?,
    val stepCount: Int
)

enum class UtTaskType {
    SHOW_MY_TICKET,
    FIND_RESERVATION_START
}

enum class UtResult {
    SUCCESS,
    FAILED,
    SENSITIVE_PAUSE,
    CANCELLED
}

interface UtMetricRepository {
    val metrics: Flow<List<UtMetric>>

    suspend fun append(metric: UtMetric)
    suspend fun clear()
}
