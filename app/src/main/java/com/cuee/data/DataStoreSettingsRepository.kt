package com.cuee.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore by preferencesDataStore("settings")

class DataStoreSettingsRepository(
    private val context: Context
) : SettingsRepository {
    private object Keys {
        val onboardingCompleted = booleanPreferencesKey("onboarding_completed")
        val accessibilityGuideCompleted = booleanPreferencesKey("accessibility_guide_completed")
        val consentVersion = stringPreferencesKey("consent_version")
        val consentAcceptedAt = longPreferencesKey("consent_accepted_at")
        val bubbleEnabled = booleanPreferencesKey("bubble_enabled")
        val bubbleEdge = stringPreferencesKey("bubble_edge")
        val bubbleYRatio = floatPreferencesKey("bubble_y_ratio")
        val voiceEnabled = booleanPreferencesKey("voice_enabled")
    }

    override val settings: Flow<UserSettings> = context.settingsDataStore.data.map { prefs ->
        UserSettings(
            onboardingCompleted = prefs[Keys.onboardingCompleted] ?: false,
            accessibilityGuideCompleted = prefs[Keys.accessibilityGuideCompleted] ?: false,
            consentVersion = prefs[Keys.consentVersion] ?: CURRENT_CONSENT_VERSION,
            consentAcceptedAt = prefs[Keys.consentAcceptedAt] ?: 0L,
            bubbleEnabled = prefs[Keys.bubbleEnabled] ?: true,
            bubbleEdge = prefs[Keys.bubbleEdge]?.let { runCatching { BubbleEdge.valueOf(it) }.getOrNull() }
                ?: BubbleEdge.RIGHT,
            bubbleYRatio = prefs[Keys.bubbleYRatio] ?: 0.55f,
            voiceEnabled = prefs[Keys.voiceEnabled] ?: true
        )
    }

    override suspend fun setOnboardingCompleted(value: Boolean) {
        context.settingsDataStore.edit { prefs ->
            prefs[Keys.onboardingCompleted] = value
            if (value) {
                prefs[Keys.consentVersion] = CURRENT_CONSENT_VERSION
                prefs[Keys.consentAcceptedAt] = System.currentTimeMillis()
            }
        }
    }

    override suspend fun withdrawConsent() {
        context.settingsDataStore.edit { prefs ->
            prefs[Keys.onboardingCompleted] = false
            prefs[Keys.accessibilityGuideCompleted] = false
            prefs[Keys.consentVersion] = CURRENT_CONSENT_VERSION
            prefs[Keys.consentAcceptedAt] = 0L
            prefs[Keys.bubbleEnabled] = false
        }
    }

    override suspend fun setAccessibilityGuideCompleted(value: Boolean) {
        context.settingsDataStore.edit { prefs ->
            prefs[Keys.accessibilityGuideCompleted] = value
        }
    }

    override suspend fun setBubbleEnabled(value: Boolean) {
        context.settingsDataStore.edit { prefs ->
            prefs[Keys.bubbleEnabled] = value
        }
    }

    override suspend fun setBubblePosition(edge: BubbleEdge, yRatio: Float) {
        context.settingsDataStore.edit { prefs ->
            prefs[Keys.bubbleEdge] = edge.name
            prefs[Keys.bubbleYRatio] = yRatio.coerceIn(0.08f, 0.92f)
        }
    }

    override suspend fun setVoiceEnabled(value: Boolean) {
        context.settingsDataStore.edit { prefs ->
            prefs[Keys.voiceEnabled] = value
        }
    }

}
