package com.example.core

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.studioDataStore by preferencesDataStore(name = "sarvcut_studio_prefs")

data class LocalStudioSettings(
    val burnSubtitlesInMp4: Boolean = true,
    val addAmbientMusicBed: Boolean = true,
    val autoSaveToDownloads: Boolean = false
)

class StudioPreferences(private val context: Context) {
    private object Keys {
        val BURN_SUBTITLES = booleanPreferencesKey("burn_subtitles")
        val AMBIENT_MUSIC = booleanPreferencesKey("ambient_music")
        val AUTO_SAVE_DOWNLOADS = booleanPreferencesKey("auto_save_downloads")
    }

    val settings: Flow<LocalStudioSettings> = context.studioDataStore.data.map { prefs ->
        LocalStudioSettings(
            burnSubtitlesInMp4 = prefs[Keys.BURN_SUBTITLES] ?: true,
            addAmbientMusicBed = prefs[Keys.AMBIENT_MUSIC] ?: true,
            autoSaveToDownloads = prefs[Keys.AUTO_SAVE_DOWNLOADS] ?: false
        )
    }

    suspend fun setBurnSubtitles(enabled: Boolean) {
        context.studioDataStore.edit { it[Keys.BURN_SUBTITLES] = enabled }
    }

    suspend fun setAmbientMusic(enabled: Boolean) {
        context.studioDataStore.edit { it[Keys.AMBIENT_MUSIC] = enabled }
    }

    suspend fun setAutoSaveDownloads(enabled: Boolean) {
        context.studioDataStore.edit { it[Keys.AUTO_SAVE_DOWNLOADS] = enabled }
    }
}
