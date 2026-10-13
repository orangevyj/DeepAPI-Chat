package com.orangestudio.deepapichat.data.store

import com.orangestudio.deepapichat.core.JsonStore
import com.orangestudio.deepapichat.core.newId
import com.orangestudio.deepapichat.core.now
import com.orangestudio.deepapichat.data.model.Memory
import com.orangestudio.deepapichat.data.model.MemorySource
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.File

/**
 * Wrapper object so the memories file has room to grow (a bare JSON array would
 * leave nowhere to put a schema version).
 */
@Serializable
data class MemoryFile(
    val version: Int = 1,
    val memories: List<Memory> = emptyList(),
)

/** Everything the assistant is allowed to remember, newest first. */
class MemoryStore(
    private val store: JsonStore,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val file: File get() = store.file(Storage.MEMORIES_FILE)

    private val _items = MutableStateFlow<List<Memory>>(emptyList())
    val items: StateFlow<List<Memory>> = _items.asStateFlow()

    suspend fun load() {
        val loaded = withContext(io) {
            store.read(file, MemoryFile.serializer())?.memories.orEmpty()
        }
        _items.value = loaded.sortedByDescending { it.createdAt }
    }

    /** Contents of memories the model is allowed to see. */
    fun enabledContents(): List<String> =
        _items.value.filter { it.enabled }.map { it.content.trim() }.filter { it.isNotEmpty() }

    suspend fun add(content: String, source: MemorySource = MemorySource.User): Memory {
        val stamp = now()
        val memory = Memory(
            id = newId(),
            content = content.trim(),
            createdAt = stamp,
            updatedAt = stamp,
            source = source,
        )
        return upsert(memory, touch = false)
    }

    suspend fun upsert(memory: Memory, touch: Boolean = true): Memory {
        val stored = if (touch) memory.copy(updatedAt = now()) else memory
        val list = _items.value.toMutableList()
        val index = list.indexOfFirst { it.id == stored.id }
        if (index >= 0) list[index] = stored else list.add(stored)
        _items.value = list.sortedByDescending { it.createdAt }
        persist()
        return stored
    }

    suspend fun updateContent(id: String, content: String) {
        val existing = _items.value.firstOrNull { it.id == id } ?: return
        upsert(existing.copy(content = content.trim()))
    }

    suspend fun setEnabled(id: String, enabled: Boolean) {
        val existing = _items.value.firstOrNull { it.id == id } ?: return
        upsert(existing.copy(enabled = enabled))
    }

    suspend fun delete(id: String) {
        _items.value = _items.value.filterNot { it.id == id }
        persist()
    }

    suspend fun deleteAll() {
        _items.value = emptyList()
        persist()
    }

    suspend fun replaceAll(memories: List<Memory>) {
        _items.value = memories.sortedByDescending { it.createdAt }
        persist()
    }

    private suspend fun persist() {
        val snapshot = MemoryFile(memories = _items.value)
        withContext(io) { store.write(file, MemoryFile.serializer(), snapshot) }
    }
}
