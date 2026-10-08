package com.erol.allreader.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.erol.allreader.engine.reflow.ReaderTheme
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

enum class PageMode(val label: String) {
    SCROLL("Kaydırma"),
    PAGED("Sayfa sayfa"),
}

private val Context.settingsStore by preferencesDataStore(name = "settings")

class SettingsRepository(private val context: Context) {
    private val themeKey = stringPreferencesKey("reader_theme")
    private val fontKey = intPreferencesKey("reader_font_percent")
    private val nightKey = booleanPreferencesKey("page_night_mode")
    private val pageModeKey = stringPreferencesKey("page_mode")

    /** PDF/CBZ/görsel için renkleri ters çeviren gece modu. */
    val pageNightMode: Flow<Boolean> = context.settingsStore.data.map { it[nightKey] ?: false }

    val pageMode: Flow<PageMode> = context.settingsStore.data.map { prefs ->
        prefs[pageModeKey]?.let { name -> PageMode.entries.firstOrNull { it.name == name } } ?: PageMode.SCROLL
    }

    suspend fun setPageNightMode(on: Boolean) {
        context.settingsStore.edit { it[nightKey] = on }
    }

    suspend fun setPageMode(mode: PageMode) {
        context.settingsStore.edit { it[pageModeKey] = mode.name }
    }

    val readerTheme: Flow<ReaderTheme> = context.settingsStore.data.map { prefs ->
        prefs[themeKey]?.let { name -> ReaderTheme.entries.firstOrNull { it.name == name } } ?: ReaderTheme.SYSTEM
    }

    val fontPercent: Flow<Int> = context.settingsStore.data.map { prefs ->
        (prefs[fontKey] ?: DEFAULT_FONT_PERCENT).coerceIn(MIN_FONT_PERCENT, MAX_FONT_PERCENT)
    }

    suspend fun setReaderTheme(theme: ReaderTheme) {
        context.settingsStore.edit { it[themeKey] = theme.name }
    }

    suspend fun setFontPercent(percent: Int) {
        context.settingsStore.edit { it[fontKey] = percent.coerceIn(MIN_FONT_PERCENT, MAX_FONT_PERCENT) }
    }

    companion object {
        const val DEFAULT_FONT_PERCENT = 100
        const val MIN_FONT_PERCENT = 70
        const val MAX_FONT_PERCENT = 220
    }
}
