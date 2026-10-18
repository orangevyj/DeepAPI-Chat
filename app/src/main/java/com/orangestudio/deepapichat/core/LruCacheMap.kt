package com.orangestudio.deepapichat.core

/**
 * Bounded least-recently-used map.
 *
 * Used to keep encoded image payloads (base64 data URLs, a few hundred KB each)
 * from being rebuilt on every request while still capping memory use.
 */
class LruCacheMap<K : Any, V : Any>(private val maxEntries: Int) {

    private val map = object : LinkedHashMap<K, V>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>): Boolean =
            size > maxEntries
    }

    @Synchronized
    fun get(key: K): V? = map[key]

    @Synchronized
    fun put(key: K, value: V) {
        map[key] = value
    }

    @Synchronized
    fun remove(key: K) {
        map.remove(key)
    }

    @Synchronized
    fun clear() {
        map.clear()
    }

    @Synchronized
    fun size(): Int = map.size
}
