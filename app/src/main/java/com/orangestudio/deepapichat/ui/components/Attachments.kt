package com.orangestudio.deepapichat.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.orangestudio.deepapichat.core.Bitmaps
import com.orangestudio.deepapichat.data.model.Attachment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Decodes a stored image off the main thread and keeps the result across
 * recompositions. Follows the file's path and timestamp, so replacing a file
 * re-decodes while unrelated recompositions do not.
 */
@Composable
fun rememberLocalBitmap(file: File?, maxEdge: Int = Bitmaps.MAX_STORED_EDGE): State<ImageBitmap?> {
    val path = file?.absolutePath
    val stamp = file?.lastModified()
    return produceState<ImageBitmap?>(initialValue = null, path, stamp, maxEdge) {
        value = withContext(Dispatchers.IO) {
            file?.takeIf { it.isFile }?.let { Bitmaps.decode(it, maxEdge)?.asImageBitmap() }
        }
    }
}

/** A single image thumbnail. [onRemove] non-null shows a delete badge. */
@Composable
fun AttachmentThumb(
    attachment: Attachment,
    resolveFile: (Attachment) -> File,
    modifier: Modifier = Modifier,
    size: Int = 72,
    onOpen: (() -> Unit)? = null,
    onRemove: (() -> Unit)? = null,
) {
    val file = resolveFile(attachment)
    // A file in the chat's workspace shows as a file even when its type is an image:
    // it was attached for the assistant to open, not for this row to draw.
    val isImage = !attachment.isWorkspaceFile && attachment.mimeType.startsWith("image/")
    val bitmap by rememberLocalBitmap(file, maxEdge = 512)
    Box(modifier = modifier.size(size.dp)) {
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.fillMaxSize().then(
                if (onOpen != null) Modifier.clickable { onOpen() } else Modifier,
            ),
        ) {
            val image = bitmap
            when {
                !isImage -> FileChipBody(attachment, file)
                image != null -> Image(
                    bitmap = image,
                    contentDescription = attachment.fileName,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
                else -> Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = if (file.isFile) Icons.Filled.Image else Icons.Filled.Warning,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        if (onRemove != null) {
            Box(modifier = Modifier.align(Alignment.TopEnd)) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.scrim,
                    modifier = Modifier.size(22.dp).clickable { onRemove() },
                ) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = "Remove image",
                        tint = Color.White,
                        modifier = Modifier.padding(3.dp),
                    )
                }
            }
        }
    }
}

/**
 * A file the model cannot look at is shown as what it is and how big it is. The
 * name shown here is the name the model is told to open from /workspace.
 */
@Composable
private fun FileChipBody(attachment: Attachment, file: File) {
    Column(
        modifier = Modifier.fillMaxSize().padding(6.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = Icons.Filled.Description,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = attachment.fileName,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 3,
        )
        Text(
            text = formatBytes(if (file.isFile) file.length() else attachment.sizeBytes),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes < 0 -> "?"
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    else -> "${bytes / (1024 * 1024)} MB"
}

/** Horizontal strip of attachment thumbnails. */
@Composable
fun AttachmentStrip(
    attachments: List<Attachment>,
    resolveFile: (Attachment) -> File,
    modifier: Modifier = Modifier,
    size: Int = 72,
    onOpen: ((Attachment) -> Unit)? = null,
    onRemove: ((Attachment) -> Unit)? = null,
) {
    if (attachments.isEmpty()) return
    LazyRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(attachments, key = { it.id }) { attachment ->
            AttachmentThumb(
                attachment = attachment,
                resolveFile = resolveFile,
                size = size,
                onOpen = onOpen?.let { open -> { open(attachment) } },
                onRemove = onRemove?.let { remove -> { remove(attachment) } },
            )
        }
    }
}

/** Full-screen image viewer. */
@Composable
fun ImageViewerDialog(
    attachment: Attachment,
    resolveFile: (Attachment) -> File,
    onDismiss: () -> Unit,
) {
    val bitmap by rememberLocalBitmap(resolveFile(attachment), maxEdge = 2048)
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.94f))
                .clickable { onDismiss() },
            contentAlignment = Alignment.Center,
        ) {
            val image = bitmap
            if (image != null) {
                Image(
                    bitmap = image,
                    contentDescription = attachment.fileName,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize().padding(16.dp),
                )
            } else {
                Text(
                    text = "This image is no longer on this device.",
                    color = Color.White,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Row(
                modifier = Modifier.align(Alignment.TopEnd).padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(text = attachment.fileName, color = Color.White.copy(alpha = 0.8f))
                Spacer(Modifier.width(8.dp))
                Surface(shape = CircleShape, color = Color.White.copy(alpha = 0.15f)) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = "Close",
                        tint = Color.White,
                        modifier = Modifier.size(40.dp).clickable { onDismiss() }.padding(8.dp),
                    )
                }
            }
            Spacer(Modifier.height(0.dp))
        }
    }
}
