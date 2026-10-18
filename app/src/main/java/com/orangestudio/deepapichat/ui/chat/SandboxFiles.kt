package com.orangestudio.deepapichat.ui.chat

import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.io.File

/**
 * Resolve a path the model wrote into a real file inside this chat's workspace.
 * Anything that escapes the workspace canonicalises outside it and is refused, so a
 * pinned path can never reach the app's own storage.
 */
fun workspaceFile(workspace: File, raw: String): File? {
    val guest = "/workspace"
    val trimmed = raw.trim()
    val relative = when {
        trimmed == guest -> ""
        trimmed.startsWith("$guest/") -> trimmed.removePrefix("$guest/")
        else -> trimmed.trimStart('/', '\\')
    }
    val root = runCatching { workspace.canonicalFile }.getOrNull() ?: return null
    val target = runCatching { File(root, relative).canonicalFile }.getOrNull() ?: return null
    return target.takeIf {
        it.isFile && (it.path == root.path || it.path.startsWith(root.path + File.separator))
    }
}

/** Copy a workspace file to a destination the user picked with the system picker. */
fun saveWorkspaceFile(context: Context, file: File, uri: Uri): Boolean = runCatching {
    context.contentResolver.openOutputStream(uri)?.use { out ->
        file.inputStream().use { it.copyTo(out) }
    } != null
}.getOrDefault(false)

/**
 * Browses this chat's sandbox workspace. The files are ordinary files on the app's
 * private storage, so listing them is direct; nothing outside this workspace is ever
 * shown, and saving hands a copy to the system picker.
 */
@Composable
fun SandboxFilesDialog(workspace: File, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var dir by remember { mutableStateOf("") }
    var refresh by remember { mutableStateOf(0) }
    var pending by remember { mutableStateOf<File?>(null) }

    val saver = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri ->
        val file = pending
        pending = null
        if (uri != null && file != null) {
            val ok = saveWorkspaceFile(context, file, uri)
            Toast.makeText(
                context,
                if (ok) "Saved ${file.name}" else "Could not save ${file.name}",
                Toast.LENGTH_SHORT,
            ).show()
        }
    }

    val root = remember(workspace) { runCatching { workspace.canonicalFile }.getOrNull() }
    val here = remember(root, dir) { root?.let { runCatching { File(it, dir).canonicalFile }.getOrNull() } }
    val entries = remember(here, refresh) {
        here?.listFiles()
            ?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
            .orEmpty()
    }

    fun save(file: File) {
        pending = file
        saver.launch(file.name)
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxSize().padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = {
                            val parent = File(dir).parent.orEmpty()
                            dir = if (parent == ".") "" else parent
                        },
                        enabled = dir.isNotEmpty(),
                    ) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Up one folder")
                    }
                    Column(Modifier.weight(1f)) {
                        Text("Sandbox files", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "/workspace" + if (dir.isBlank()) "" else "/$dir",
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    IconButton(onClick = { refresh++ }) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Refresh")
                    }
                    TextButton(onClick = onDismiss) { Text("Close") }
                }
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                if (entries.isEmpty()) {
                    Text(
                        "Nothing here yet. Files the assistant writes in /workspace show up here.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    LazyColumn(Modifier.fillMaxSize()) {
                        items(entries, key = { it.absolutePath }) { entry ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        if (entry.isDirectory) {
                                            dir = listOf(dir, entry.name)
                                                .filter { it.isNotEmpty() }
                                                .joinToString("/")
                                        } else {
                                            save(entry)
                                        }
                                    }
                                    .padding(vertical = 10.dp),
                            ) {
                                Icon(
                                    imageVector = if (entry.isDirectory) {
                                        Icons.Filled.Folder
                                    } else {
                                        Icons.Filled.InsertDriveFile
                                    },
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp),
                                )
                                Spacer(Modifier.width(10.dp))
                                Text(
                                    entry.name,
                                    Modifier.weight(1f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                if (!entry.isDirectory) {
                                    Text(
                                        "${entry.length()} B",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    TextButton(onClick = { save(entry) }) { Text("Save") }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
