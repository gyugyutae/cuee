package com.cuee.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.cuee.domain.safety.StopReason
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.utMetricDataStore by preferencesDataStore("ut_metrics")

class LocalUtMetricRepository(
    private val context: Context,
    private val maxStoredMetrics: Int = MAX_STORED_METRICS
) : UtMetricRepository {
    private object Keys {
        val encodedMetrics = stringPreferencesKey("encoded_metrics")
    }

    override val metrics: Flow<List<UtMetric>> = context.utMetricDataStore.data.map { prefs ->
        prefs[Keys.encodedMetrics]
            .orEmpty()
            .lineSequence()
            .mapNotNull { line -> line.decodeMetric() }
            .sortedBy { it.startedAt }
            .toList()
    }

    override suspend fun append(metric: UtMetric) {
        context.utMetricDataStore.edit { prefs ->
            val current = prefs[Keys.encodedMetrics]
                .orEmpty()
                .lineSequence()
                .mapNotNull { line -> line.decodeMetric() }
                .toMutableList()
            current += metric.withDerivedElapsed()
            prefs[Keys.encodedMetrics] = current
                .sortedBy { it.startedAt }
                .takeLast(maxStoredMetrics.coerceAtLeast(1))
                .joinToString(separator = "\n") { it.encode() }
        }
    }

    override suspend fun clear() {
        context.utMetricDataStore.edit { prefs ->
            prefs.remove(Keys.encodedMetrics)
        }
    }

    private fun UtMetric.withDerivedElapsed(): UtMetric {
        val safeElapsed = elapsedMs ?: finishedAt?.let { (it - startedAt).coerceAtLeast(0L) }
        return copy(elapsedMs = safeElapsed)
    }

    private fun UtMetric.encode(): String {
        return listOf(
            sessionId.toLongOrNull()?.coerceAtLeast(0L) ?: sessionId.hashCode().toLong().coerceAtLeast(0L),
            taskType.ordinal,
            startedAt.coerceAtLeast(0L),
            finishedAt ?: NO_VALUE,
            elapsedMs ?: NO_VALUE,
            result.ordinal,
            stopReason?.ordinal ?: NO_VALUE,
            stepCount.coerceAtLeast(0)
        ).joinToString(separator = FIELD_SEPARATOR)
    }

    private fun String.decodeMetric(): UtMetric? {
        val parts = split(FIELD_SEPARATOR)
        if (parts.size != FIELD_COUNT) return null

        val sessionNumber = parts[0].toLongOrNull() ?: return null
        val taskType = enumByOrdinal<UtTaskType>(parts[1].toIntOrNull() ?: return null) ?: return null
        val startedAt = parts[2].toLongOrNull() ?: return null
        val finishedAtValue = parts[3].toLongOrNull() ?: return null
        val elapsedMsValue = parts[4].toLongOrNull() ?: return null
        val finishedAt = finishedAtValue.takeUnless { it == NO_VALUE }
        val elapsedMs = elapsedMsValue.takeUnless { it == NO_VALUE }
        val result = enumByOrdinal<UtResult>(parts[5].toIntOrNull() ?: return null) ?: return null
        val stopReasonOrdinal = parts[6].toIntOrNull() ?: return null
        val stopReason = if (stopReasonOrdinal == NO_VALUE.toInt()) null else enumByOrdinal<StopReason>(stopReasonOrdinal) ?: return null
        val stepCount = parts[7].toIntOrNull() ?: return null

        return UtMetric(
            sessionId = sessionNumber.toString(),
            taskType = taskType,
            startedAt = startedAt,
            finishedAt = finishedAt,
            elapsedMs = elapsedMs,
            result = result,
            stopReason = stopReason,
            stepCount = stepCount
        )
    }

    private inline fun <reified T : Enum<T>> enumByOrdinal(ordinal: Int): T? {
        return enumValues<T>().getOrNull(ordinal)
    }

    private companion object {
        const val FIELD_SEPARATOR = "|"
        const val FIELD_COUNT = 8
        const val NO_VALUE = -1L
        const val MAX_STORED_METRICS = 200
    }
}
