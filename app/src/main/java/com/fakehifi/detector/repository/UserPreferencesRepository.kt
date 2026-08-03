package com.fakehifi.detector.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "user_preferences")

class UserPreferencesRepository(private val context: Context) {

    private object PreferencesKeys {
        val HAS_COMPLETED_ONBOARDING = booleanPreferencesKey("has_completed_onboarding")
        val CONTRIBUTE_TO_COMMUNITY = booleanPreferencesKey("contribute_to_community")
        val PICKED_FOLDER_URI = androidx.datastore.preferences.core.stringPreferencesKey("picked_folder_uri")
        val LAST_SEEN_VERSION_CODE = androidx.datastore.preferences.core.intPreferencesKey("last_seen_version_code")
    }

    val hasCompletedOnboarding: Flow<Boolean> = context.dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { preferences ->
            preferences[PreferencesKeys.HAS_COMPLETED_ONBOARDING] ?: false
        }

    val contributeToCommunity: Flow<Boolean> = context.dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { preferences ->
            preferences[PreferencesKeys.CONTRIBUTE_TO_COMMUNITY] ?: false
        }

    val pickedFolderUri: Flow<String?> = context.dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { preferences ->
            preferences[PreferencesKeys.PICKED_FOLDER_URI]
        }

    val lastSeenVersionCode: Flow<Int> = context.dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { preferences ->
            preferences[PreferencesKeys.LAST_SEEN_VERSION_CODE] ?: 0
        }

    suspend fun setCompletedOnboarding(completed: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.HAS_COMPLETED_ONBOARDING] = completed
        }
    }

    suspend fun setContributeToCommunity(contribute: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.CONTRIBUTE_TO_COMMUNITY] = contribute
        }
    }

    suspend fun setPickedFolderUri(uri: String?) {
        context.dataStore.edit { preferences ->
            if (uri == null) {
                preferences.remove(PreferencesKeys.PICKED_FOLDER_URI)
            } else {
                preferences[PreferencesKeys.PICKED_FOLDER_URI] = uri
            }
        }
    }

    suspend fun setLastSeenVersionCode(versionCode: Int) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.LAST_SEEN_VERSION_CODE] = versionCode
        }
    }
}
