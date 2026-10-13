package com.orangestudio.deepapichat.data.store

import com.orangestudio.deepapichat.core.JsonStore
import com.orangestudio.deepapichat.core.newId
import com.orangestudio.deepapichat.core.now
import com.orangestudio.deepapichat.data.model.Conversation
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File

/** Pinned chats float to the top, then most recently updated first. */
fun List<Conversation>.sortedForDisplay(): List<Conversation> =
    sortedWith(compareByDescending<Conversation> { it.pinned }.thenByDescending { it.updatedAt })

/**
 * One JSON file per conversation, plus an in-memory copy of all of them.
 *
 * Holding every conversation in memory is deliberate: the documents are small
 * (text only, images live in the attachments folder by reference) and it makes
 * the list, search and cross-chat lookups instant. Disk is the source of truth
 * at startup and after that every mutation is written through.
 */
class ConversationStore(
    private val store: JsonStore,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val dir: File get() = store.file(Storage.CONVERSATIONS)

    private val _items = MutableStateFlow<List<Conversation>>(emptyList())
    val items: StateFlow<List<Conversation>> = _items.asStateFlow()

    suspend fun load() {
        val loaded = withContext(io) {
            store.jsonFilesIn(dir)
                .mapNotNull { store.read(it, Conversation.serializer()) }
                .filter { it.id.isNotBlank() }
        }
        _items.value = loaded.sortedForDisplay()
    }

    fun byId(id: String): Conversation? = _items.value.firstOrNull { it.id == id }

    suspend fun create(title: String = "New chat"): Conversation {
        val stamp = now()
        return upsert(Conversation(id = newId(), title = title, createdAt = stamp, updatedAt = stamp))
    }

    /** Writes [conversation] and updates the cache. Bumps `updatedAt` by default. */
    suspend fun upsert(conversation: Conversation, touch: Boolean = true): Conversation {
        val stored = if (touch) conversation.copy(updatedAt = now()) else conversation
        persist(stored)
        val index = _items.value.indexOfFirst { it.id == stored.id }
        _items.value = if (index >= 0) {
            _items.value.toMutableList().also { it[index] = stored }.sortedForDisplay()
        } else {
            (_items.value + stored).sortedForDisplay()
        }
        return stored
    }

    suspend fun delete(id: String) {
        _items.value = _items.value.filterNot { it.id == id }
        withContext(io) { store.delete(fileFor(id)) }
    }

    suspend fun deleteAll() {
        _items.value = emptyList()
        withContext(io) { store.jsonFilesIn(dir).forEach { store.delete(it) } }
    }

    suspend fun duplicate(id: String): Conversation? {
        val source = byId(id) ?: return null
        val stamp = now()
        val copy = source.copy(
            id = newId(),
            title = "${source.title} (copy)",
            createdAt = stamp,
            updatedAt = stamp,
            pinned = false,
            draft = "",
            messages = source.messages.map { it.copy(id = newId()) },
        )
        return upsert(copy)
    }

    /**
     * Replaces everything on disk. Used by "import" once the incoming list has
     * been merged with what is already here.
     */
    suspend fun replaceAll(conversations: List<Conversation>) {
        withContext(io) { store.jsonFilesIn(dir).forEach { store.delete(it) } }
        conversations.forEach { persist(it) }
        _items.value = conversations.sortedForDisplay()
    }

    private suspend fun persist(conversation: Conversation) = withContext(io) {
        store.write(fileFor(conversation.id), Conversation.serializer(), conversation)
    }

    private fun fileFor(id: String): File = File(dir, "$id.json")
}
