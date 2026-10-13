package com.orangestudio.deepapichat.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.orangestudio.deepapichat.data.model.Attachment
import java.io.File

/**
 * The message input: pending attachments, the text field, and the send/stop action.
 *
 * While a reply is streaming the send button becomes a stop button, because the
 * request is still holding its connection open.
 */
@Composable
fun Composer(
    value: String,
    onValueChange: (String) -> Unit,
    pending: List<Attachment>,
    resolveFile: (Attachment) -> File,
    onRemoveAttachment: (Attachment) -> Unit,
    onOpenAttachment: (Attachment) -> Unit,
    onPickImage: () -> Unit,
    onPickFile: () -> Unit,
    onTakePhoto: () -> Unit,
    sandboxEnabled: Boolean,
    onSend: () -> Unit,
    onStop: () -> Unit,
    isStreaming: Boolean,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    var menuOpen by remember { mutableStateOf(false) }

    Surface(
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 3.dp,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            if (pending.isNotEmpty()) {
                AttachmentStrip(
                    attachments = pending,
                    resolveFile = resolveFile,
                    modifier = Modifier.padding(bottom = 8.dp),
                    onOpen = onOpenAttachment,
                    onRemove = onRemoveAttachment,
                )
            }
            Row(verticalAlignment = Alignment.Bottom) {
                Box {
                    IconButton(
                        onClick = { menuOpen = true },
                        enabled = enabled && !isStreaming,
                    ) {
                        Icon(
                            imageVector = Icons.Filled.AttachFile,
                            contentDescription = "Attach",
                        )
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("Photo from gallery") },
                            onClick = {
                                menuOpen = false
                                onPickImage()
                            },
                            leadingIcon = { Icon(Icons.Filled.Image, contentDescription = null) },
                        )
                        DropdownMenuItem(
                            text = { Text("Take a photo") },
                            onClick = {
                                menuOpen = false
                                onTakePhoto()
                            },
                            leadingIcon = { Icon(Icons.Filled.PhotoCamera, contentDescription = null) },
                        )
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text("Attach a file")
                                    if (!sandboxEnabled) {
                                        Text(
                                            text = "Needs Sandbox turned on, so the assistant can open it",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            },
                            onClick = {
                                menuOpen = false
                                onPickFile()
                            },
                            enabled = sandboxEnabled,
                            leadingIcon = { Icon(Icons.Filled.Description, contentDescription = null) },
                        )
                    }
                }
                OutlinedTextField(
                    value = value,
                    onValueChange = onValueChange,
                    modifier = Modifier.weight(1f).heightIn(min = 52.dp),
                    placeholder = { Text("Message") },
                    maxLines = 6,
                    enabled = enabled,
                )
                Spacer(Modifier.width(6.dp))
                if (isStreaming) {
                    IconButton(onClick = onStop) {
                        Icon(
                            imageVector = Icons.Filled.Stop,
                            contentDescription = "Stop generating",
                            tint = MaterialTheme.colorScheme.error,
                        )
                    }
                } else {
                    IconButton(
                        onClick = onSend,
                        enabled = enabled && (value.isNotBlank() || pending.isNotEmpty()),
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Send,
                            contentDescription = "Send",
                        )
                    }
                }
            }
        }
    }
}
