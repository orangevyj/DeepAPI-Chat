package com.orangestudio.deepapichat.ui.chatlist

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.orangestudio.deepapichat.AppContainer
import com.orangestudio.deepapichat.ui.components.LicensesDialog
import com.orangestudio.deepapichat.ContainerViewModelFactory
import com.orangestudio.deepapichat.data.model.Conversation
import com.orangestudio.deepapichat.data.store.sortedForDisplay
import com.orangestudio.deepapichat.data.transfer.BundleSummary
import com.orangestudio.deepapichat.data.transfer.ImportOptions
import com.orangestudio.deepapichat.data.transfer.ImportReport
import com.orangestudio.deepapichat.ui.components.ConfirmDialog
import com.orangestudio.deepapichat.ui.components.EmptyState
import com.orangestudio.deepapichat.ui.formatTimestamp
import com.orangestudio.deepapichat.ui.messagePreview
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ChatListViewModel(private val container: AppContainer) : ViewModel() {

    val conversations: StateFlow<List<Conversation>> = container.conversations.items
        .map { list -> list.sortedForDisplay() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun newConversation(onCreated: (String) -> Unit) {
        viewModelScope.launch { onCreated(container.conversations.create().id) }
    }

    fun rename(id: String, title: String) {
        viewModelScope.launch {
            val existing = container.conversations.byId(id) ?: return@launch
            container.conversations.upsert(
                existing.copy(title = title.trim().ifBlank { "New chat" }),
                touch = false,
            )
        }
    }

    fun togglePinned(conversation: Conversation) {
        viewModelScope.launch {
            container.conversations.upsert(conversation.copy(pinned = !conversation.pinned), touch = false)
        }
    }

    fun duplicate(id: String) {
        viewModelScope.launch { container.conversations.duplicate(id) }
    }

    fun delete(id: String) {
        viewModelScope.launch { container.conversations.delete(id) }
    }

    fun deleteAll() {
        viewModelScope.launch { container.conversations.deleteAll() }
    }

    fun suggestedFileName(id: String?): String =
        container.transfer.suggestedFileName(id?.let { container.conversations.byId(it) })

    /** Writes the bundle to a SAF document. [ids] null means "everything". */
    fun exportTo(
        appContext: Context,
        uri: Uri,
        ids: Set<String>?,
        includeMemories: Boolean,
        onDone: (String) -> Unit,
    ) {
        viewModelScope.launch {
            val message = runCatching {
                val text = container.transfer.export(ids, includeMemories, true)
                withContext(Dispatchers.IO) {
                    val stream = appContext.contentResolver.openOutputStream(uri)
                        ?: error("Could not open the file you picked.")
                    stream.use { it.write(text.toByteArray()) }
                }
                "Exported ${text.toByteArray().size / 1024 + 1} KB."
            }.getOrElse { "Export failed: ${it.message ?: it::class.java.simpleName}" }
            onDone(message)
        }
    }

    /** Reads and validates the picked document without touching any store. */
    fun readSummary(appContext: Context, uri: Uri, onDone: (BundleSummary?) -> Unit) {
        viewModelScope.launch {
            val summary = runCatching {
                val text = withContext(Dispatchers.IO) {
                    appContext.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() }
                }
                text?.let { container.transfer.readSummary(it) }
            }.getOrNull()
            onDone(summary)
        }
    }

    fun importFrom(
        appContext: Context,
        uri: Uri,
        options: ImportOptions,
        onDone: (ImportReport) -> Unit,
    ) {
        viewModelScope.launch {
            val report = runCatching {
                val text = withContext(Dispatchers.IO) {
                    appContext.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() }
                        ?: error("Could not read the file you picked.")
                }
                container.transfer.import(text, options)
            }.getOrElse { ImportReport(error = "Import failed: ${it.message ?: it::class.java.simpleName}") }
            onDone(report)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatListScreen(
    container: AppContainer,
    onOpenChat: (String) -> Unit,
    onOpenMemories: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val appContext = LocalContext.current
    val viewModel: ChatListViewModel = viewModel<ChatListViewModel>(
        factory = ContainerViewModelFactory(container) { ChatListViewModel(it) },
    )
    val conversations by viewModel.conversations.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    var menuOpen by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<Conversation?>(null) }
    var renameText by remember { mutableStateOf("") }
    var deleteTarget by remember { mutableStateOf<Conversation?>(null) }
    var confirmDeleteAll by remember { mutableStateOf(false) }
    var licensesOpen by remember { mutableStateOf(false) }

    // Kept across the SAF round trip: the file dialog result arrives long after
    // the item that started it was composed.
    var exportIds by remember { mutableStateOf<Set<String>?>(null) }
    var pendingImport by remember { mutableStateOf<Pair<BundleSummary, Uri>?>(null) }

    val toast: (String) -> Unit = { text -> scope.launch { snackbarHostState.showSnackbar(text) } }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        val ids = exportIds
        exportIds = null
        if (uri != null) viewModel.exportTo(appContext, uri, ids, true, toast)
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            viewModel.readSummary(appContext, uri) { summary ->
                if (summary == null) toast("That file is not a DeepAPI Chat export.") else pendingImport = summary to uri
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Chats") },
                actions = {
                    IconButton(onClick = onOpenMemories) {
                        Icon(Icons.Filled.Psychology, contentDescription = "Memories")
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "Settings")
                    }
                    Box {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = "More options")
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text("Export all conversations") },
                                leadingIcon = { Icon(Icons.Filled.FileUpload, contentDescription = null) },
                                onClick = {
                                    menuOpen = false
                                    exportIds = null
                                    exportLauncher.launch(viewModel.suggestedFileName(null))
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Import a backup") },
                                leadingIcon = { Icon(Icons.Filled.FileDownload, contentDescription = null) },
                                onClick = {
                                    menuOpen = false
                                    importLauncher.launch(arrayOf("*/*"))
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Delete all conversations") },
                                leadingIcon = { Icon(Icons.Filled.DeleteSweep, contentDescription = null) },
                                onClick = {
                                    menuOpen = false
                                    confirmDeleteAll = true
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Licenses") },
                                leadingIcon = { Icon(Icons.Filled.Description, contentDescription = null) },
                                onClick = {
                                    menuOpen = false
                                    licensesOpen = true
                                },
                            )
                        }
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { viewModel.newConversation(onOpenChat) }) {
                Icon(Icons.Filled.Add, contentDescription = "New chat")
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            if (conversations.isEmpty()) {
                EmptyState(
                    icon = Icons.Filled.ChatBubbleOutline,
                    title = "No conversations yet",
                    subtitle = "Start a chat, and it is saved to this device as you go.",
                    modifier = Modifier.align(Alignment.Center),
                )
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(conversations, key = { it.id }) { conversation ->
                        ConversationCard(
                            conversation = conversation,
                            onOpen = { onOpenChat(conversation.id) },
                            onRename = {
                                renameTarget = conversation
                                renameText = conversation.title
                            },
                            onTogglePin = { viewModel.togglePinned(conversation) },
                            onDuplicate = { viewModel.duplicate(conversation.id) },
                            onExport = {
                                exportIds = setOf(conversation.id)
                                exportLauncher.launch(viewModel.suggestedFileName(conversation.id))
                            },
                            onDelete = { deleteTarget = conversation },
                        )
                    }
                }
            }
        }
    }

    renameTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("Rename chat") },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    singleLine = true,
                    label = { Text("Title") },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.rename(target.id, renameText)
                    renameTarget = null
                }) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { renameTarget = null }) { Text("Cancel") }
            },
        )
    }

    deleteTarget?.let { target ->
        ConfirmDialog(
            title = "Delete this chat?",
            message = "\"${target.title}\" and its messages are removed from this device. Exported copies are unaffected.",
            confirmLabel = "Delete",
            destructive = true,
            onConfirm = {
                viewModel.delete(target.id)
                deleteTarget = null
            },
            onDismiss = { deleteTarget = null },
        )
    }

    if (confirmDeleteAll) {
        ConfirmDialog(
            title = "Delete every chat?",
            message = "All conversations on this device are removed. Memories and settings stay.",
            confirmLabel = "Delete all",
            destructive = true,
            onConfirm = {
                viewModel.deleteAll()
                confirmDeleteAll = false
            },
            onDismiss = { confirmDeleteAll = false },
        )
    }

    if (licensesOpen) {
        LicensesDialog(onDismiss = { licensesOpen = false })
    }

    pendingImport?.let { (summary, uri) ->
        var importMemories by remember(summary) { mutableStateOf(true) }
        var skipDuplicates by remember(summary) { mutableStateOf(true) }
        AlertDialog(
            onDismissRequest = { pendingImport = null },
            title = { Text("Import backup") },
            text = {
                Column {
                    Text(
                        text = "${count(summary.conversations, "conversation")}, " +
                            "${count(summary.memories, "memory")}, " +
                            "${count(summary.images, "image")}.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "Exported ${formatTimestamp(summary.exportedAt)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = importMemories, onCheckedChange = { importMemories = it })
                        Text("Also import memories", style = MaterialTheme.typography.bodyMedium)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = skipDuplicates, onCheckedChange = { skipDuplicates = it })
                        Text("Skip memories I already have", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.importFrom(
                        appContext = appContext,
                        uri = uri,
                        options = ImportOptions(
                            mergeConversations = true,
                            importMemories = importMemories,
                            skipDuplicateMemories = skipDuplicates,
                        ),
                    ) { report ->
                        pendingImport = null
                        toast(report.summary())
                    }
                }) { Text("Import") }
            },
            dismissButton = {
                TextButton(onClick = { pendingImport = null }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun ConversationCard(
    conversation: Conversation,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onTogglePin: () -> Unit,
    onDuplicate: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .clickable { onOpen() },
        shape = RoundedCornerShape(14.dp),
    ) {
        Row(modifier = Modifier.padding(start = 14.dp, top = 12.dp, bottom = 12.dp, end = 4.dp)) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (conversation.pinned) {
                        Icon(
                            imageVector = Icons.Filled.PushPin,
                            contentDescription = "Pinned",
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.width(6.dp))
                    }
                    Text(
                        text = conversation.title,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.height(3.dp))
                Text(
                    text = messagePreview(conversation.messages.lastOrNull()),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "${formatTimestamp(conversation.updatedAt)} · " +
                        count(conversation.messages.size, "message"),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Box {
                IconButton(onClick = { menu = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = "Chat options")
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text(if (conversation.pinned) "Unpin" else "Pin to top") },
                        leadingIcon = {
                            Icon(
                                imageVector = if (conversation.pinned) Icons.Filled.Star else Icons.Filled.StarBorder,
                                contentDescription = null,
                            )
                        },
                        onClick = {
                            menu = false
                            onTogglePin()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Rename") },
                        leadingIcon = { Icon(Icons.Filled.Create, contentDescription = null) },
                        onClick = {
                            menu = false
                            onRename()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Duplicate") },
                        leadingIcon = { Icon(Icons.Filled.ContentCopy, contentDescription = null) },
                        onClick = {
                            menu = false
                            onDuplicate()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Export") },
                        leadingIcon = { Icon(Icons.Filled.FileUpload, contentDescription = null) },
                        onClick = {
                            menu = false
                            onExport()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Delete") },
                        leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null) },
                        onClick = {
                            menu = false
                            onDelete()
                        },
                    )
                }
            }
        }
    }
}

private fun count(n: Int, noun: String): String = "$n $noun" + if (n == 1) "" else "s"
