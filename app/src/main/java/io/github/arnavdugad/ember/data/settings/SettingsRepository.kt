package io.github.arnavdugad.ember.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.arnavdugad.ember.data.model.DiscoveryFilters
import io.github.arnavdugad.ember.data.model.LookingFor
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

enum class ThemeMode { System, Dark, Light }

data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.System,
    val afterDark: Boolean = false,
    val filters: DiscoveryFilters = DiscoveryFilters(),
    val ageGateBlocked: Boolean = false,
    val notificationsPrompted: Boolean = false,
)

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "ember_settings")

/** Device-local preferences. Nothing here is sensitive; it is excluded from backup anyway. */
@Singleton
class SettingsRepository @Inject constructor(@ApplicationContext context: Context) {
    private val store = context.dataStore

    val settings: Flow<AppSettings> = store.data.map { p ->
        AppSettings(
            themeMode = p[THEME]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: ThemeMode.System,
            afterDark = p[AFTER_DARK] ?: false,
            filters = DiscoveryFilters(
                maxDistanceKm = p[MAX_DISTANCE] ?: 80,
                minAge = p[MIN_AGE] ?: 18,
                maxAge = p[MAX_AGE] ?: 45,
                lookingFor = p[LOOKING_FOR].orEmpty().mapNotNull { key -> LookingFor.entries.firstOrNull { it.key == key } }.toSet(),
            ),
            ageGateBlocked = p[AGE_GATE_BLOCKED] ?: false,
            notificationsPrompted = p[NOTIFICATIONS_PROMPTED] ?: false,
        )
    }.distinctUntilChanged()

    suspend fun current(): AppSettings = settings.first()

    suspend fun setThemeMode(mode: ThemeMode) = store.edit { it[THEME] = mode.name }

    suspend fun setAfterDark(enabled: Boolean) = store.edit { it[AFTER_DARK] = enabled }

    suspend fun setFilters(filters: DiscoveryFilters) = store.edit {
        it[MAX_DISTANCE] = filters.maxDistanceKm
        it[MIN_AGE] = filters.minAge
        it[MAX_AGE] = filters.maxAge
        it[LOOKING_FOR] = filters.lookingFor.map(LookingFor::key).toSet()
    }

    /** Set when the age gate rejects someone, so the device can't simply retry with another date. */
    suspend fun setAgeGateBlocked() = store.edit { it[AGE_GATE_BLOCKED] = true }

    suspend fun setNotificationsPrompted() = store.edit { it[NOTIFICATIONS_PROMPTED] = true }

    // --- Updater bookkeeping ---

    suspend fun updateSnoozedUntil(version: String): Long {
        val p = store.data.first()
        return if (p[SNOOZED_VERSION] == version) p[SNOOZED_UNTIL] ?: 0L else 0L
    }

    suspend fun snoozeUpdate(version: String, untilMillis: Long) = store.edit {
        it[SNOOZED_VERSION] = version
        it[SNOOZED_UNTIL] = untilMillis
    }

    suspend fun lastNotifiedUpdate(): String? = store.data.first()[NOTIFIED_VERSION]

    suspend fun setLastNotifiedUpdate(version: String) = store.edit { it[NOTIFIED_VERSION] = version }

    private companion object {
        val THEME = stringPreferencesKey("theme_mode")
        val AFTER_DARK = booleanPreferencesKey("after_dark")
        val MAX_DISTANCE = intPreferencesKey("filter_max_distance")
        val MIN_AGE = intPreferencesKey("filter_min_age")
        val MAX_AGE = intPreferencesKey("filter_max_age")
        val LOOKING_FOR = stringSetPreferencesKey("filter_looking_for")
        val AGE_GATE_BLOCKED = booleanPreferencesKey("age_gate_blocked")
        val NOTIFICATIONS_PROMPTED = booleanPreferencesKey("notifications_prompted")
        val SNOOZED_VERSION = stringPreferencesKey("update_snoozed_version")
        val SNOOZED_UNTIL = longPreferencesKey("update_snoozed_until")
        val NOTIFIED_VERSION = stringPreferencesKey("update_notified_version")
    }
}
