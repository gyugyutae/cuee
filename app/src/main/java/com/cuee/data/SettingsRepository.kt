package com.cuee.data

import kotlinx.coroutines.flow.Flow

data class UserSettings(
    val onboardingCompleted: Boolean = false,
    val accessibilityGuideCompleted: Boolean = false,
    val consentVersion: String = "2026-05-20",
    val consentAcceptedAt: Long = 0L,
    val bubbleEnabled: Boolean = true,
    val bubbleEdge: BubbleEdge = BubbleEdge.RIGHT,
    val bubbleYRatio: Float = 0.55f,
    val voiceEnabled: Boolean = true
)

enum class BubbleEdge { LEFT, RIGHT }

interface SettingsRepository {
    val settings: Flow<UserSettings>
    suspend fun setOnboardingCompleted(value: Boolean)
    suspend fun setAccessibilityGuideCompleted(value: Boolean)
    suspend fun setBubbleEnabled(value: Boolean)
    suspend fun setBubblePosition(edge: BubbleEdge, yRatio: Float)
    suspend fun setVoiceEnabled(value: Boolean)
}
