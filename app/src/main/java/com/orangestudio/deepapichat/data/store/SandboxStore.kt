package com.orangestudio.deepapichat.data.store

import com.orangestudio.deepapichat.core.JsonStore
import java.io.File

/**
 * Layout of the sandbox inside the app's private directory:
 *
 * ```
 * files/sandbox/rootfs            the extracted Alpine rootfs, shared by every chat
 * files/sandbox/tmp               scratch space proot stages its loader in
 * files/sandbox/workspaces/<id>   one workspace per conversation, mounted as /workspace
 * ```
 *
 * Nothing here is a "sandbox" in the security sense: proot confines which files the
 * guest can see, not what it can reach on the network or how much it can consume.
 */
class SandboxStore(private val store: JsonStore) {

    val root: File get() = File(store.root, Storage.SANDBOX)
    val rootfs: File get() = File(root, "rootfs")
    val tmp: File get() = File(root, "tmp")
    private val workspaces: File get() = File(root, "workspaces")

    fun workspaceFor(conversationId: String): File = File(workspaces, safeId(conversationId))

    fun hasWorkspace(conversationId: String): Boolean = workspaceFor(conversationId).isDirectory

    /** Creates whatever a run needs and hands back the chat's workspace. */
    fun prepare(conversationId: String): File = workspaceFor(conversationId).apply {
        mkdirs()
        tmp.mkdirs()
    }

    fun deleteWorkspace(conversationId: String): Boolean =
        workspaceFor(conversationId).deleteRecursively()

    /** Ids come from our own generators, but never let one escape its folder. */
    private fun safeId(id: String): String =
        id.filter { it.isLetterOrDigit() || it == '-' || it == '_' }.ifBlank { "unknown" }
}
