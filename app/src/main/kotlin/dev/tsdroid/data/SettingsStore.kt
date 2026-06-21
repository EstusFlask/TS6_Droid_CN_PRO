package dev.tsdroid.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore by preferencesDataStore(name = "settings")

private val KEY_AUDIO_GAIN = floatPreferencesKey("audio_gain")
private val KEY_SHOW_LINK_THUMBNAILS = booleanPreferencesKey("show_link_thumbnails")
private val KEY_AUTO_LOAD_IMAGES = booleanPreferencesKey("auto_load_images")
private val KEY_LANGUAGE = stringPreferencesKey("language")
private val KEY_ENABLE_FLOATING_WINDOW = booleanPreferencesKey("enable_floating_window")
private val KEY_VOICE_ACTIVITY_DETECTION_ENABLED = booleanPreferencesKey("voice_activity_detection_enabled")
private val KEY_VOICE_ACTIVITY_THRESHOLD_DB = floatPreferencesKey("voice_activity_threshold_db")

class SettingsStore(private val context: Context) {
    companion object {
        const val MIN_VOICE_ACTIVITY_THRESHOLD_DB = -80.0f
        const val MAX_VOICE_ACTIVITY_THRESHOLD_DB = 0.0f
        const val DEFAULT_VOICE_ACTIVITY_THRESHOLD_DB = -40.0f
    }

    val audioGain: Flow<Float> = context.settingsDataStore.data
        .map { it[KEY_AUDIO_GAIN] ?: 1.0f }

    val showLinkThumbnails: Flow<Boolean> = context.settingsDataStore.data
        .map { it[KEY_SHOW_LINK_THUMBNAILS] ?: false }

    val autoLoadImages: Flow<Boolean> = context.settingsDataStore.data
        .map { it[KEY_AUTO_LOAD_IMAGES] ?: true }

    val language: Flow<String> = context.settingsDataStore.data
        .map { it[KEY_LANGUAGE] ?: "zh" }

    val enableFloatingWindow: Flow<Boolean> = context.settingsDataStore.data
        .map { it[KEY_ENABLE_FLOATING_WINDOW] ?: false }

    val voiceActivityDetectionEnabled: Flow<Boolean> = context.settingsDataStore.data
        .map { it[KEY_VOICE_ACTIVITY_DETECTION_ENABLED] ?: false }

    val voiceActivityThresholdDb: Flow<Float> = context.settingsDataStore.data
        .map {
            it[KEY_VOICE_ACTIVITY_THRESHOLD_DB]?.coerceIn(
                MIN_VOICE_ACTIVITY_THRESHOLD_DB,
                MAX_VOICE_ACTIVITY_THRESHOLD_DB,
            ) ?: DEFAULT_VOICE_ACTIVITY_THRESHOLD_DB
        }

    suspend fun setAudioGain(gain: Float) {
        context.settingsDataStore.edit { it[KEY_AUDIO_GAIN] = gain }
    }

    suspend fun setShowLinkThumbnails(enabled: Boolean) {
        context.settingsDataStore.edit { it[KEY_SHOW_LINK_THUMBNAILS] = enabled }
    }

    suspend fun setAutoLoadImages(enabled: Boolean) {
        context.settingsDataStore.edit { it[KEY_AUTO_LOAD_IMAGES] = enabled }
    }

    suspend fun setLanguage(language: String) {
        context.settingsDataStore.edit { it[KEY_LANGUAGE] = language }
    }

    suspend fun setEnableFloatingWindow(enabled: Boolean) {
        context.settingsDataStore.edit { it[KEY_ENABLE_FLOATING_WINDOW] = enabled }
    }

    suspend fun setVoiceActivityDetectionEnabled(enabled: Boolean) {
        context.settingsDataStore.edit { it[KEY_VOICE_ACTIVITY_DETECTION_ENABLED] = enabled }
    }

    suspend fun setVoiceActivityThresholdDb(thresholdDb: Float) {
        context.settingsDataStore.edit {
            it[KEY_VOICE_ACTIVITY_THRESHOLD_DB] = thresholdDb.coerceIn(
                MIN_VOICE_ACTIVITY_THRESHOLD_DB,
                MAX_VOICE_ACTIVITY_THRESHOLD_DB,
            )
        }
    }
}
