package com.orangestudio.deepapichat.data.store

import com.orangestudio.deepapichat.core.JsonStore
import com.orangestudio.deepapichat.core.newId
import com.orangestudio.deepapichat.core.now
import com.orangestudio.deepapichat.data.model.AppSettings
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Holds [AppSettings] and writes them to disk.
 *
 * Note this file contains the API key in plaintext. It lives in the app's
 * private storage and is excluded from cloud backup (see `backup_rules.xml`).
 */
class SettingsStore(
    private val store: JsonStore,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val file: File get() = store.file(Storage.SETTINGS_FILE)

    private val _settings = MutableStateFlow(AppSettings())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    private var saveJob: Job? = null

    suspend fun load() {
        _settings.value = withContext(io) { store.read(file, AppSettings.serializer()) } ?: AppSettings()
    }

    /**
     * Applies [transform] and schedules a write. Writes are debounced because
     * every keystroke in the Settings text fields lands here.
     */
    fun update(transform: (AppSettings) -> AppSettings) {
        val updated = transform(_settings.value)
        if (updated == _settings.value) return
        _settings.value = updated
        saveJob?.cancel()
        saveJob = scope.launch {
            delay(SAVE_DEBOUNCE_MS)
            withContext(io) { store.write(file, AppSettings.serializer(), updated) }
        }
    }

    fun set(settings: AppSettings) {
        update { settings }
    }

    /** Writes immediately. Called when the Settings screen is left. */
    suspend fun flush() {
        saveJob?.cancel()
        saveJob = null
        val current = _settings.value
        withContext(io) { store.write(file, AppSettings.serializer(), current) }
    }

    private companion object {
        const val SAVE_DEBOUNCE_MS = 400L
    }
}
