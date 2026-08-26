package com.luckycatpaw.luckyfilestv.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.luckycatpaw.luckyfilestv.data.common.model.FileManagerSettings
import com.luckycatpaw.luckyfilestv.data.common.model.FileSortMode
import com.luckycatpaw.luckyfilestv.util.AppLocaleManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "filemanager_settings")

class SettingsRepository(private val context: Context) {

    private object Keys {
        val languageTag = stringPreferencesKey("language_tag")
        val hideFolderJpg = booleanPreferencesKey("hide_folder_jpg")
        val useFolderJpgAsIcon = booleanPreferencesKey("use_folder_jpg_as_icon")
        val optimizeFileNames = booleanPreferencesKey("optimize_file_names")
        val sortMode = stringPreferencesKey("sort_mode")
        val sortAscending = booleanPreferencesKey("sort_ascending")
        val foldersFirst = booleanPreferencesKey("folders_first")
    }

    val settings: Flow<FileManagerSettings> = context.settingsDataStore.data.map { preferences ->
        FileManagerSettings(
            languageTag = preferences[Keys.languageTag]?.takeIf { it == "en" || it == "de" },
            hideFolderJpg = preferences[Keys.hideFolderJpg] ?: true,
            useFolderJpgAsIcon = preferences[Keys.useFolderJpgAsIcon] ?: true,
            optimizeFileNames = preferences[Keys.optimizeFileNames] ?: true,
            sortMode = FileSortMode.entries.firstOrNull {
                it.name == preferences[Keys.sortMode]
            } ?: FileSortMode.NAME,
            sortAscending = preferences[Keys.sortAscending] ?: true,
            foldersFirst = preferences[Keys.foldersFirst] ?: true
        )
    }

    suspend fun setLanguageTag(languageTag: String?) {
        val normalizedTag = languageTag.takeIf {
            it == AppLocaleManager.ENGLISH || it == AppLocaleManager.GERMAN
        }

        context.settingsDataStore.edit { preferences ->
            if (normalizedTag == null) {
                preferences.remove(Keys.languageTag)
            } else {
                preferences[Keys.languageTag] = normalizedTag
            }
        }

        // setApplicationLocales recreates the running activities, which the appcompat backport
        // does synchronously, so it must not be invoked from the DataStore's IO dispatcher.
        withContext(Dispatchers.Main) {
            AppLocaleManager.apply(normalizedTag)
        }
    }

    suspend fun setHideFolderJpg(enabled: Boolean) {
        context.settingsDataStore.edit { it[Keys.hideFolderJpg] = enabled }
    }

    suspend fun setUseFolderJpgAsIcon(enabled: Boolean) {
        context.settingsDataStore.edit { it[Keys.useFolderJpgAsIcon] = enabled }
    }

    suspend fun setOptimizeFileNames(enabled: Boolean) {
        context.settingsDataStore.edit { it[Keys.optimizeFileNames] = enabled }
    }

    suspend fun setSortMode(mode: FileSortMode) {
        context.settingsDataStore.edit { it[Keys.sortMode] = mode.name }
    }

    suspend fun setSortAscending(enabled: Boolean) {
        context.settingsDataStore.edit { it[Keys.sortAscending] = enabled }
    }

    suspend fun setFoldersFirst(enabled: Boolean) {
        context.settingsDataStore.edit { it[Keys.foldersFirst] = enabled }
    }
}
