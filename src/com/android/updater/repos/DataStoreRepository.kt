package com.android.updater.repos

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.android.updater.dataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

val PAGE_ID = stringPreferencesKey("page_id")
val UPDATING = booleanPreferencesKey("updating")
val EARLY_UPDATES = booleanPreferencesKey("early_updates")
val CHANGELOG = stringPreferencesKey("changelog")
val PROG_PERCENT = intPreferencesKey("prog_percent")
val PROG_STEP = stringPreferencesKey("prog_step")

private const val TAG = "DataStoreRepository"

class DataStoreRepository(private val applicationContext: Context) {

    private val scope = CoroutineScope(Dispatchers.IO)

    val pageIdFlow: Flow<String> = applicationContext.dataStore.data
        .map { preferences ->
            preferences[PAGE_ID] ?: ""
        }
    val wasUpdatingFlow: Flow<Boolean> = applicationContext.dataStore.data
        .map { preferences ->
            preferences[UPDATING] ?: false
        }
    val earlyUpdatesFlow: Flow<Boolean> = applicationContext.dataStore.data
        .map { preferences ->
            preferences[EARLY_UPDATES] ?: false
        }
    val changelogFlow: Flow<String> = applicationContext.dataStore.data
        .map { preferences ->
            preferences[CHANGELOG] ?: ""
        }
    val progStepFlow: Flow<String> = applicationContext.dataStore.data
        .map { preferences ->
            preferences[PROG_STEP] ?: ""
        }
    val progPercentFlow: Flow<Int> = applicationContext.dataStore.data
        .map { preferences ->
            preferences[PROG_PERCENT] ?: 0
        }

    fun putString(key: Preferences.Key<String>, value: String) {
        scope.launch {
            applicationContext.dataStore.edit { preferences ->
                preferences[key] = value
            }
        }
    }

    fun putInt(key: Preferences.Key<Int>, value: Int) {
        scope.launch {
            applicationContext.dataStore.edit { preferences ->
                preferences[key] = value
            }
        }
    }

    fun putBoolean(key: Preferences.Key<Boolean>, value: Boolean) {
        scope.launch {
            applicationContext.dataStore.edit { preferences ->
                preferences[key] = value
            }
        }
    }

    suspend fun getString(key: Preferences.Key<String>, defValue: String): String {
        val preferences = applicationContext.dataStore.data.first()
        Log.d(TAG, "${key.name}: ${preferences[key]}")
        return preferences[key] ?: defValue
    }

    suspend fun getInt(key: Preferences.Key<Int>, defValue: Int): Int {
        val preferences = applicationContext.dataStore.data.first()
        Log.d(TAG, "${key.name}: ${preferences[key]}")
        return preferences[key] ?: defValue
    }

    suspend fun getBoolean(key: Preferences.Key<Boolean>, defValue: Boolean): Boolean {
        val preferences = applicationContext.dataStore.data.first()
        Log.d(TAG, "${key.name}: ${preferences[key]}")
        return preferences[key] ?: defValue
    }

    fun clear() {
        scope.launch {
            applicationContext.dataStore.edit {
                it.clear()
            }
        }
    }
}