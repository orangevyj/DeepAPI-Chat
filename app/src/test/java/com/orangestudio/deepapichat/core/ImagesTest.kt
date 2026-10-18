package com.orangestudio.deepapichat.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImagesTest {

    @Test
    fun extensionLookupHandlesParametersAndCase() {
        assertEquals("png", Images.extensionFor("image/png"))
        assertEquals("jpg", Images.extensionFor("IMAGE/JPEG; charset=binary"))
        assertEquals("jpg", Images.extensionFor("application/octet-stream"))
    }

    @Test
    fun mimeLookupFromFileName() {
        assertEquals("image/png", Images.mimeForExtension("screenshot.PNG"))
        assertEquals("image/webp", Images.mimeForExtension("photo.webp"))
        assertEquals("image/jpeg", Images.mimeForExtension("no-extension"))
    }

    @Test
    fun onlyAStoredImageIsSentInline() {
        assertTrue(Images.isInlineImage("image/png", inWorkspace = false))
        assertTrue(Images.isInlineImage("image/jpeg", inWorkspace = false))
        // A file in the chat's workspace is opened with tools, never sent as a payload,
        // even when its type claims to be an image.
        assertFalse(Images.isInlineImage("image/png", inWorkspace = true))
        assertFalse(Images.isInlineImage("text/plain", inWorkspace = false))
        assertFalse(Images.isInlineImage("application/pdf", inWorkspace = false))
    }

    @Test
    fun dataUrlIsBase64Encoded() {
        val url = Images.dataUrl("image/png", byteArrayOf(1, 2, 3))
        assertEquals("data:image/png;base64,${java.util.Base64.getEncoder().encodeToString(byteArrayOf(1, 2, 3))}", url)
    }

    @Test
    fun supportedMimeTypes() {
        assertTrue(Images.isSupportedMime("image/jpeg"))
        assertTrue(Images.isSupportedMime("image/png"))
        assertFalse(Images.isSupportedMime("application/pdf"))
        assertFalse(Images.isSupportedMime(""))
    }
}
