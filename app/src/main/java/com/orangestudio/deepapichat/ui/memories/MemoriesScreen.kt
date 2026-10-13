package com.orangestudio.deepapichat.ui.memories

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.orangestudio.deepapichat.AppContainer
import com.orangestudio.deepapichat.ContainerViewModelFactory
import com.orangestudio.deepapichat.data.model.AppSettings
import com.orangestudio.deepapichat.data.model.Memory
import com.orangestudio.deepapichat.data.model.MemorySource
import com.orangestudio.deepapichat.ui.components.ConfirmDialog
import com.orangestudio.deepapichat.ui.components.SettingsGroup
import com.orangestudio.deepapichat.ui.components.SettingsRow
import com.orangestudio.deepapichat.ui.components.StatusPill
import com.orangestudio.deepapichat.ui.formatTimestamp
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class MemoriesViewModel(private val container: AppContainer) : ViewModel() {

    val memories: StateFlow<List<Memory>> = container.memories.items
        .map { list -> list.sortedByDescending { it.updatedAt } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val settings: StateFlow<AppSettings> = container.settings.settings

    /** Master switch. Off means nothing is saved, injected or proposed. */
    fun setMasterEnabled(enabled: Boolean) =
        container.settings.update { it.copy(memoriesEnabled = enabled) }

    /** Whether the assistant may offer a `save_memory` tool call. */
    fun setToolEnabled(enabled: Boolean) =
        container.settings.update { it.copy(memoryToolEnabled = enabled) }

    /** When on, approved-by-default memory writes skip the prompt. */
    fun setAutoApprove(enabled: Boolean) =
        container.settings.update { it.copy(autoApproveMemoryTool = enabled) }

    fun add(content: String) {
        viewModelScope.launch { container.memories.add(content, MemorySource.User) }
    }

    fun update(id: String, content: String) {
        viewModelScope.launch { container.memories.updateContent(id, content) }
    }

    fun setEnabled(id: String, enabled: Boolean) {
        viewModelScope.launch { container.memories.setEnabled(id, enabled) }
    }

    fun delete(id: String) {
        viewModelScope.launch { container.memories.delete(id) }
    }

    fun deleteAll() {
        viewModelScope.launch { container.memories.deleteAll() }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MemoriesScreen(container: AppContainer, onBack: () -> Unit) {
    val viewModel: MemoriesViewModel = viewModel<MemoriesViewModel>(
        factory = ContainerViewModelFactory(container) { MemoriesViewModel(it) },
    )
    val memories by viewModel.memories.collectAsState()
    val settings by viewModel.settings.collectAsState()

    var editorOpen by remember { mutableStateOf(false) }
    var editorId by remember { mutableStateOf<String?>(null) }
    var editorText by remember { mutableStateOf("") }
    var deleteTarget by remember { mutableStateOf<Memory?>(null) }
    var confirmDeleteAll by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Memories") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (memories.isNotEmpty()) {
                        IconButton(onClick = { confirmDeleteAll = true }) {
                            Icon(Icons.Filled.Delete, contentDescription = "Delete all memories")
                        }
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = {
                editorId = null
                editorText = ""
                editorOpen = true
            }) {
                Icon(Icons.Filled.Add, contentDescription = "Add memory")
            }
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState()),
        ) {
            SettingsGroup(title = "Memory settings", modifier = Modifier.padding(top = 8.dp)) {
                SettingsRow(
                    title = "Use memories",
                    subtitle = if (settings.memoriesEnabled) {
                        "Enabled memories are added to the system prompt of every chat that has them on."
                    } else {
                        "Off — memories are neither saved nor sent."
                    },
                    trailing = {
                        Switch(
                            checked = settings.memoriesEnabled,
                            onCheckedChange = { viewModel.setMasterEnabled(it) },
                        )
                    },
                )
                SettingsRow(
                    title = "Let the assistant propose memories",
                    subtitle = "It asks with a save_memory request you approve or deny.",
                    trailing = {
                        Switch(
                            checked = settings.memoryToolEnabled,
                            enabled = settings.memoriesEnabled,
                            onCheckedChange = { viewModel.setToolEnabled(it) },
                        )
                    },
                )
                SettingsRow(
                    title = "Skip the prompt for memory writes",
                    subtitle = "Saves without asking. Everything else still prompts.",
                    trailing = {
                        Switch(
                            checked = settings.autoApproveMemoryTool,
                            enabled = settings.memoriesEnabled && settings.memoryToolEnabled,
                            onCheckedChange = { viewModel.setAutoApprove(it) },
                        )
                    },
                )
            }

            ListHeader(count = memories.size)

            Card(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                shape = RoundedCornerShape(16.dp),
            ) {
                if (memories.isEmpty()) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(28.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Psychology,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(12.dp))
                        Text("No memories yet", style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = "Memories are short facts you want every chat to know. Add them " +
                                "yourself, or let the assistant propose them mid-conversation.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    memories.forEachIndexed { index, memory ->
                        MemoryRow(
                            memory = memory,
                            onToggle = { viewModel.setEnabled(memory.id, it) },
                            onEdit = {
                                editorId = memory.id
                                editorText = memory.content
                                editorOpen = true
                            },
                            onDelete = { deleteTarget = memory },
                        )
                        if (index != memories.lastIndex) {
                            HorizontalDivider(
                                modifier = Modifier.padding(start = 16.dp),
                                color = MaterialTheme.colorScheme.outlineVariant,
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(88.dp))
        }
    }

    if (editorOpen) {
        AlertDialog(
            onDismissRequest = { editorOpen = false },
            title = { Text(if (editorId == null) "New memory" else "Edit memory") },
            text = {
                OutlinedTextField(
                    value = editorText,
                    onValueChange = { editorText = it },
                    label = { Text("What should the assistant remember?") },
                    minLines = 3,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val text = editorText.trim()
                        val id = editorId
                        if (text.isNotBlank()) {
                            if (id == null) viewModel.add(text) else viewModel.update(id, text)
                        }
                        editorOpen = false
                    },
                ) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { editorOpen = false }) { Text("Cancel") }
            },
        )
    }

    deleteTarget?.let { target ->
        ConfirmDialog(
            title = "Delete this memory?",
            message = target.content,
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
            title = "Delete every memory?",
            message = "All ${memories.size} saved memories are removed. Conversations are unaffected.",
            confirmLabel = "Delete all",
            destructive = true,
            onConfirm = {
                viewModel.deleteAll()
                confirmDeleteAll = false
            },
            onDismiss = { confirmDeleteAll = false },
        )
    }
}

@Composable
private fun ListHeader(count: Int) {
    Text(
        text = if (count == 1) "1 saved memory" else "$count saved memories",
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 6.dp),
    )
}

/** One row of the framed list: the fact, where it came from, and its controls. */
@Composable
private fun MemoryRow(
    memory: Memory,
    onToggle: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f).padding(vertical = 12.dp)) {
            Text(
                text = memory.content,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(
                    alpha = if (memory.enabled) 1f else 0.5f,
                ),
            )
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusPill(text = sourceLabel(memory.source), positive = memory.enabled)
                Spacer(Modifier.width(8.dp))
                Text(
                    text = if (memory.enabled) {
                        "Used in chats · updated ${formatTimestamp(memory.updatedAt)}"
                    } else {
                        "Paused · updated ${formatTimestamp(memory.updatedAt)}"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Switch(checked = memory.enabled, onCheckedChange = onToggle)
        Box {
            IconButton(onClick = { menuOpen = true }) {
                Icon(Icons.Filled.MoreVert, contentDescription = "Memory actions")
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text("Edit") },
                    leadingIcon = { Icon(Icons.Filled.Edit, contentDescription = null) },
                    onClick = {
                        menuOpen = false
                        onEdit()
                    },
                )
                DropdownMenuItem(
                    text = { Text("Delete") },
                    leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null) },
                    onClick = {
                        menuOpen = false
                        onDelete()
                    },
                )
            }
        }
    }
}

private fun sourceLabel(source: MemorySource): String = when (source) {
    MemorySource.User -> "You"
    MemorySource.Assistant -> "Assistant"
    MemorySource.Imported -> "Imported"
}
