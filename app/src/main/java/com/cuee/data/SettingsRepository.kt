package com.cuee.data

import kotlinx.coroutines.flow.Flow

data class UserSettings(
    val onboardingCompleted: Boolean = false,
    val accessibilityGuideCompleted: Boolean = false,
    val consentVersion: String = CURRENT_CONSENT_VERSION,
    val consentAcceptedAt: Long = 0L,
    val bubbleEnabled: Boolean = true,
    val bubbleEdge: BubbleEdge = BubbleEdge.RIGHT,
    val bubbleYRatio: Float = 0.55f,
    val voiceEnabled: Boolean = true
)

const val CURRENT_CONSENT_VERSION = "2026-08-29"

fun UserSettings.hasCurrentConsent(): Boolean =
    onboardingCompleted && consentAcceptedAt > 0L && consentVersion == CURRENT_CONSENT_VERSION

enum class BubbleEdge { LEFT, RIGHT }

interface SettingsRepository {
    val settings: Flow<UserSettings>
    suspend fun setOnboardingCompleted(value: Boolean)
    suspend fun withdrawConsent()
    suspend fun setAccessibilityGuideCompleted(value: Boolean)
    suspend fun setBubbleEnabled(value: Boolean)
    suspend fun setBubblePosition(edge: BubbleEdge, yRatio: Float)
    suspend fun setVoiceEnabled(value: Boolean)
}
