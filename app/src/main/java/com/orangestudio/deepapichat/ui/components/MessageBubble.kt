package com.orangestudio.deepapichat.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.orangestudio.deepapichat.data.model.Attachment
import com.orangestudio.deepapichat.data.model.Message
import com.orangestudio.deepapichat.data.model.Role
import com.orangestudio.deepapichat.data.model.ToolCall
import com.orangestudio.deepapichat.data.model.ToolCallStatus
import com.orangestudio.deepapichat.domain.ToolPresenter
import java.io.File

/**
 * One message in the transcript.
 *
 * User turns render as a right-aligned bubble; assistant turns render full width
 * so markdown, tool approvals and errors have room. [onDecideToolCall] is null in
 * read-only contexts (history browsing), which renders finished tool calls
 * without approval buttons.
 *
 * [onEdit] offers the pencil on a user turn. Editing rewrites the text in place
 * and asks the model nothing: the chat runs on what you have written when you ask
 * for the answer again, which is regenerate on the reply that followed it.
 */
@Composable
fun MessageBubble(
    message: Message,
    resolveFile: (Attachment) -> File,
    isStreaming: Boolean,
    onCopy: () -> Unit,
    onOpenImage: (Attachment) -> Unit,
    modifier: Modifier = Modifier,
    onRegenerate: (() -> Unit)? = null,
    onEdit: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
    onDecideToolCall: ((ToolCall, Boolean) -> Unit)? = null,
    autoApproveTools: Boolean = false,
    onAutoApproveChange: ((Boolean) -> Unit)? = null,
    /** The one pending call the user may run now; the rest wait their turn. */
    approvableCallId: String? = null,
    /** Save a file the assistant pinned somewhere in this chat's sandbox. */
    onSaveSandboxFile: ((String) -> Unit)? = null,
) {
    when (message.role) {
        Role.User -> UserTurn(message, resolveFile, onOpenImage, onCopy, onEdit, onDelete, modifier)
        Role.Assistant -> AssistantTurn(
            message = message,
            resolveFile = resolveFile,
            isStreaming = isStreaming,
            onCopy = onCopy,
            onOpenImage = onOpenImage,
            onRegenerate = onRegenerate,
            onDelete = onDelete,
            onDecideToolCall = onDecideToolCall,
            autoApproveTools = autoApproveTools,
            onAutoApproveChange = onAutoApproveChange,
            approvableCallId = approvableCallId,
            onSaveSandboxFile = onSaveSandboxFile,
            modifier = modifier,
        )
        Role.Tool -> ToolTurn(message, modifier)
        Role.System -> Unit
    }
}

@Composable
private fun PinnedFileCard(call: ToolCall, onSave: ((String) -> Unit)?) {
    val path = pinnedPath(call) ?: return
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.PushPin,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = path.substringAfterLast('/'),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                )
                Text(
                    text = "Pinned from the sandbox",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (onSave != null) {
                TextButton(onClick = { onSave(path) }) { Text("Save") }
            }
        }
    }
}

/** The path the model asked to pin, read from the call's own arguments. */
private fun pinnedPath(call: ToolCall): String? = runCatching {
    org.json.JSONObject(call.arguments).optString("path").ifBlank { null }
}.getOrNull()

@Composable
private fun UserTurn(
    message: Message,
    resolveFile: (Attachment) -> File,
    onOpenImage: (Attachment) -> Unit,
    onCopy: () -> Unit,
    onEdit: (() -> Unit)?,
    onDelete: (() -> Unit)?,
    modifier: Modifier,
) {
    Row(modifier = modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Column(horizontalAlignment = Alignment.End) {
            if (message.attachments.isNotEmpty()) {
                AttachmentStrip(
                    attachments = message.attachments,
                    resolveFile = resolveFile,
                    modifier = Modifier.padding(bottom = 6.dp),
                    onOpen = onOpenImage,
                )
            }
            if (message.content.isNotBlank()) {
                androidx.compose.foundation.text.selection.SelectionContainer {
                    Surface(
                        shape = RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomEnd = 6.dp, bottomStart = 18.dp),
                        color = MaterialTheme.colorScheme.primaryContainer,
                        modifier = Modifier.widthIn(max = 320.dp),
                    ) {
                        Text(
                            text = message.content,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        )
                    }
                }
            }
            if (!message.error.isNullOrBlank()) ErrorSurface(message.error)
            Row {
                if (onEdit != null) {
                    IconButton(onClick = onEdit, modifier = Modifier.size(32.dp)) {
                        Icon(
                            imageVector = Icons.Filled.Edit,
                            contentDescription = "Edit message",
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
                IconButton(onClick = onCopy, modifier = Modifier.size(32.dp)) {
                    Icon(
                        imageVector = Icons.Filled.ContentCopy,
                        contentDescription = "Copy message",
                        modifier = Modifier.size(16.dp),
                    )
                }
                if (onDelete != null) {
                    IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                        Icon(
                            imageVector = Icons.Filled.Delete,
                            contentDescription = "Delete message",
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AssistantTurn(
    message: Message,
    resolveFile: (Attachment) -> File,
    isStreaming: Boolean,
    onCopy: () -> Unit,
    onOpenImage: (Attachment) -> Unit,
    onRegenerate: (() -> Unit)?,
    onDelete: (() -> Unit)?,
    onDecideToolCall: ((ToolCall, Boolean) -> Unit)?,
    autoApproveTools: Boolean,
    onAutoApproveChange: ((Boolean) -> Unit)?,
    approvableCallId: String?,
    onSaveSandboxFile: ((String) -> Unit)?,
    modifier: Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        if (message.reasoning.isNotBlank()) ReasoningSection(message.reasoning)

        if (message.attachments.isNotEmpty()) {
            AttachmentStrip(
                attachments = message.attachments,
                resolveFile = resolveFile,
                modifier = Modifier.padding(bottom = 6.dp),
                onOpen = onOpenImage,
            )
        }

        if (message.content.isNotBlank()) {
            androidx.compose.foundation.text.selection.SelectionContainer {
                MarkdownText(text = message.content, modifier = Modifier.fillMaxWidth())
            }
        }

        val waiting = message.content.isBlank() &&
            message.reasoning.isBlank() &&
            message.error.isNullOrBlank() &&
            message.toolCalls.none { it.status == ToolCallStatus.Pending }
        if (waiting && isStreaming) ThinkingIndicator()

        if (!message.error.isNullOrBlank()) ErrorSurface(message.error)

        for (call in message.toolCalls) {
            if (call.name == com.orangestudio.deepapichat.domain.ToolSpecs.PIN_FILE &&
                call.status != ToolCallStatus.Pending
            ) {
                PinnedFileCard(call, onSaveSandboxFile)
            } else if (call.status != ToolCallStatus.Pending || onDecideToolCall == null) {
                DecidedToolCall(call)
            } else if (call.id == approvableCallId) {
                ApprovalCard(
                    call = call,
                    autoApprove = autoApproveTools,
                    onDecide = { approved -> onDecideToolCall(call, approved) },
                    onAutoApproveChange = { onAutoApproveChange?.invoke(it) },
                )
            } else {
                QueuedToolCall(call)
            }
        }

        if (!isStreaming) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
                val meta = ArrayList<String>()
                message.model?.takeIf { it.isNotBlank() }?.let { meta.add(it) }
                message.usage?.total?.takeIf { it > 0 }?.let { meta.add("$it tokens") }
                if (meta.isNotEmpty()) {
                    Text(
                        text = meta.joinToString(" · "),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onCopy, modifier = Modifier.size(32.dp)) {
                    Icon(
                        imageVector = Icons.Filled.ContentCopy,
                        contentDescription = "Copy message",
                        modifier = Modifier.size(16.dp),
                    )
                }
                if (onRegenerate != null) {
                    IconButton(onClick = onRegenerate, modifier = Modifier.size(32.dp)) {
                        Icon(
                            imageVector = Icons.Filled.Refresh,
                            contentDescription = "Regenerate reply",
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
                if (onDelete != null) {
                    IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                        Icon(
                            imageVector = Icons.Filled.Delete,
                            contentDescription = "Delete message",
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ReasoningSection(text: String) {
    var expanded by remember { mutableStateOf(false) }
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
    ) {
        Column(modifier = Modifier.clickable { expanded = !expanded }.padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Filled.Psychology,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = if (expanded) "Hide reasoning" else "Show reasoning",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (expanded) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ThinkingIndicator() {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(vertical = 6.dp),
    ) {
        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
        Spacer(Modifier.width(10.dp))
        Text(
            text = "Thinking…",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ErrorSurface(text: String) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.errorContainer,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    ) {
        Row(modifier = Modifier.padding(10.dp)) {
            Icon(
                imageVector = Icons.Filled.ErrorOutline,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onErrorContainer,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
        }
    }
}

/**
 * The confirmation prompt for a pending tool call.
 *
 * This is the single approve/deny surface every side-effecting capability goes
 * through — memories today, sandboxed shell commands later.
 */
@Composable
fun ApprovalCard(
    call: ToolCall,
    autoApprove: Boolean,
    onDecide: (Boolean) -> Unit,
    onAutoApproveChange: (Boolean) -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.tertiaryContainer,
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
    ) {
        val sandbox = ToolPresenter.alwaysAsks(call)
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Filled.Build,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onTertiaryContainer,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = ToolPresenter.title(call),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = if (sandbox) {
                    "The assistant wants to run this in this chat's sandbox:"
                } else {
                    "The assistant is asking to do this:"
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
            )
            Spacer(Modifier.height(6.dp))
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surface,
            ) {
                Text(
                    text = ToolPresenter.detail(call),
                    style = if (sandbox) {
                        MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace)
                    } else {
                        MaterialTheme.typography.bodyMedium
                    },
                    modifier = Modifier.padding(10.dp),
                )
            }
            if (sandbox) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Sandbox calls are confirmed every time; there is no way to switch that off.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                )
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = autoApprove, onCheckedChange = onAutoApproveChange)
                    Text(text = "Always allow this without asking", style = MaterialTheme.typography.bodySmall)
                }
            }
            Row(
                horizontalArrangement = Arrangement.End,
                modifier = Modifier.fillMaxWidth(),
            ) {
                TextButton(onClick = { onDecide(false) }) { Text("Deny") }
                Spacer(Modifier.width(8.dp))
                Button(onClick = { onDecide(true) }) { Text(if (sandbox) "Run" else "Allow") }
            }
        }
    }
}

@Composable
private fun DecidedToolCall(call: ToolCall) {
    val approved = call.status == ToolCallStatus.Approved
    val sandbox = ToolPresenter.alwaysAsks(call)
    val result = call.result?.let { ToolPresenter.resultSummary(call.name, it) }.orEmpty()
    var expanded by remember { mutableStateOf(false) }
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = ToolPresenter.title(call),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(8.dp))
                StatusPill(text = statusLabel(call.status), positive = approved)
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = ToolPresenter.detail(call),
                style = if (sandbox) {
                    MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
                } else {
                    MaterialTheme.typography.bodySmall
                },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            // What the command actually printed, kept in the transcript rather than
            // living only in the model's context. Tap to see the rest of it.
            if (sandbox && result.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = result,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = if (expanded) Int.MAX_VALUE else RESULT_PREVIEW_LINES,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { expanded = !expanded },
                )
            }
        }
    }
}

private const val RESULT_PREVIEW_LINES = 4

/**
 * A call the model asked for in the same turn as one that is still unanswered.
 * It becomes the real approval card once the earlier call is decided, so the user
 * never faces two prompts at once.
 */
@Composable
private fun QueuedToolCall(call: ToolCall) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(10.dp)) {
            Icon(
                imageVector = Icons.Filled.HourglassEmpty,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = ToolPresenter.title(call),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = "waiting for the request above",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ToolTurn(message: Message, modifier: Modifier) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = modifier.fillMaxWidth().padding(vertical = 2.dp),
    ) {
        Row(modifier = Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(8.dp))
            Box {
                Text(
                    text = ToolPresenter.resultSummary(message.toolName, message.content),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private fun statusLabel(status: ToolCallStatus): String = when (status) {
    ToolCallStatus.Pending -> "Waiting for you"
    ToolCallStatus.Approved -> "Allowed"
    ToolCallStatus.Denied -> "Denied"
    ToolCallStatus.Failed -> "Failed"
}
