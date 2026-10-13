package com.orangestudio.deepapichat.core

import com.orangestudio.deepapichat.data.model.AppSettings
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class JsonStoreTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun store() = JsonStore(File(temp.root, "data"))

    @Test
    fun roundTripsSerializableValues() {
        val store = store()
        val file = store.file("settings.json")
        assertTrue(store.write(file, AppSettings.serializer(), AppSettings(apiKey = "sk-test", model = "deepseek-reasoner")))

        val back = store.read(file, AppSettings.serializer())
        assertNotNull(back)
        assertEquals("sk-test", back!!.apiKey)
        assertEquals("deepseek-reasoner", back.model)
    }

    @Test
    fun missingFileReadsAsNull() {
        assertNull(store().read(store().file("nope.json"), AppSettings.serializer()))
    }

    @Test
    fun corruptFileReadsAsNullAndIsQuarantined() {
        val store = store()
        val file = store.file("settings.json")
        store.ensureRoot()
        file.writeText("{ this is not json")

        assertNull(store.read(file, AppSettings.serializer()))

        val quarantined = store.root.walkTopDown().filter { it.isFile && it.name.contains("corrupt") }.toList()
        assertTrue("expected the broken file to be moved aside", quarantined.isNotEmpty())
        assertFalse(file.exists())
    }

    @Test
    fun deleteRemovesTheFile() {
        val store = store()
        val file = store.file("settings.json")
        store.write(file, AppSettings.serializer(), AppSettings())
        assertTrue(store.delete(file))
        assertFalse(file.exists())
    }

    @Test
    fun writesAreAtomicAndLeaveNoTemporaryFiles() {
        val store = store()
        val file = store.file("settings.json")
        repeat(5) { store.write(file, AppSettings.serializer(), AppSettings(model = "m$it")) }
        val leftovers = (file.parentFile?.listFiles() ?: emptyArray()).filter { it.name != file.name }
        assertTrue("unexpected temporary files: $leftovers", leftovers.isEmpty())
        assertEquals("m4", store.read(file, AppSettings.serializer())!!.model)
    }
}
