package com.orangestudio.deepapichat.ui.chat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.orangestudio.deepapichat.AppContainer
import com.orangestudio.deepapichat.ContainerViewModelFactory
import com.orangestudio.deepapichat.core.newId
import com.orangestudio.deepapichat.core.now
import com.orangestudio.deepapichat.core.titleFrom
import com.orangestudio.deepapichat.data.model.AppSettings
import com.orangestudio.deepapichat.data.model.Attachment
import com.orangestudio.deepapichat.data.model.Conversation
import com.orangestudio.deepapichat.data.model.Message
import com.orangestudio.deepapichat.data.model.Role
import com.orangestudio.deepapichat.data.model.ToolCall
import com.orangestudio.deepapichat.data.model.ToolCallStatus
import com.orangestudio.deepapichat.domain.PromptBuilder
import com.orangestudio.deepapichat.domain.Titles
import com.orangestudio.deepapichat.ui.components.Composer
import com.orangestudio.deepapichat.ui.components.ConfirmDialog
import com.orangestudio.deepapichat.ui.components.ImageViewerDialog
import com.orangestudio.deepapichat.ui.components.MessageBubble
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Owns one conversation: its messages, the composer, and the tool-approval loop.
 *
 * The approval loop is deliberately explicit. A turn that ends in `Pending` tool
 * calls stops; the user decides; the decision is written to the transcript as a
 * `tool` message; only then does the next turn run, so the model sees the result.
 * Sandboxed shell commands will plug into exactly this seam.
 */
class ChatViewModel(
    private val container: AppContainer,
    private val conversationId: String,
) : ViewModel() {

    val conversation: StateFlow<Conversation?> = container.conversations.items
        .map { list -> list.firstOrNull { it.id == conversationId } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val settings: StateFlow<AppSettings> = container.settings.settings

    private val _draft = MutableStateFlow(container.conversations.byId(conversationId)?.draft.orEmpty())
    val draft: StateFlow<String> = _draft.asStateFlow()

    private val _pendingAttachments = MutableStateFlow<List<Attachment>>(emptyList())
    val pendingAttachments: StateFlow<List<Attachment>> = _pendingAttachments.asStateFlow()

    private val _streamingMessage = MutableStateFlow<Message?>(null)
    val streamingMessage: StateFlow<Message?> = _streamingMessage.asStateFlow()

    private val _generating = MutableStateFlow(false)
    val generating: StateFlow<Boolean> = _generating.asStateFlow()

    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice.asStateFlow()

    /** A name the model proposed for an already-named chat, waiting on the user. */
    private val _titleSuggestion = MutableStateFlow<String?>(null)
    val titleSuggestion: StateFlow<String?> = _titleSuggestion.asStateFlow()

    /** Conversation messages with the in-flight reply appended, so the UI is one list. */
    val messages: StateFlow<List<Message>> = combine(conversation, _streamingMessage) { current, streaming ->
        val base = current?.messages.orEmpty()
        if (streaming == null) base else base + streaming
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private var turnJob: Job? = null
    private var draftJob: Job? = null
    private var titleJob: Job? = null

    fun fileFor(attachment: Attachment): File = container.attachments.fileFor(attachment)

    fun clearNotice() {
        _notice.value = null
    }

    // ---------------------------------------------------------------- composer

    fun setDraft(text: String) {
        _draft.value = text
        draftJob?.cancel()
        draftJob = viewModelScope.launch {
            delay(DRAFT_DEBOUNCE_MS)
            val current = container.conversations.byId(conversationId) ?: return@launch
            if (current.draft == text) return@launch
            container.conversations.upsert(current.copy(draft = text), touch = false)
        }
    }

    /**
     * Puts a non-image file where the assistant's tools can reach it. The file is
     * copied rather than referenced, so it stays readable once the picker's grant
     * expires.
     */
    fun attachFile(appContext: Context, uri: Uri) {
        viewModelScope.launch {
            if (_pendingAttachments.value.size >= MAX_ATTACHMENTS) {
                _notice.value = "Up to $MAX_ATTACHMENTS files per message."
                return@launch
            }
            if (!container.settings.settings.value.sandboxEnabled) {
                _notice.value = "Turn on Sandbox in Settings so the assistant can open this file."
                return@launch
            }
            val workspace = container.sandbox.workspaceFor(conversationId)
            val attachment = container.attachments.importFile(appContext, uri, workspace, null)
            if (attachment == null) {
                _notice.value = "That file could not be read."
            } else {
                _pendingAttachments.value = _pendingAttachments.value + attachment
            }
        }
    }

    fun attachImage(appContext: Context, uri: Uri) {
        viewModelScope.launch {
            if (_pendingAttachments.value.size >= MAX_ATTACHMENTS) {
                _notice.value = "Up to $MAX_ATTACHMENTS images per message."
                return@launch
            }
            val attachment = container.attachments.importFromUri(appContext, uri, null)
            if (attachment == null) {
                _notice.value = "That image could not be read."
            } else {
                _pendingAttachments.value = _pendingAttachments.value + attachment
            }
        }
    }

    fun removeAttachment(attachment: Attachment) {
        _pendingAttachments.value = _pendingAttachments.value.filterNot { it.id == attachment.id }
        viewModelScope.launch { container.attachments.delete(attachment) }
    }

    // ------------------------------------------------------------------ turns

    fun send() {
        val text = _draft.value.trim()
        val attachments = _pendingAttachments.value
        if (text.isEmpty() && attachments.isEmpty()) return
        if (turnJob?.isActive == true) return

        val current = container.conversations.byId(conversationId) ?: return
        if (hasPendingToolCalls(current)) {
            _notice.value = "Answer the assistant's pending request first."
            return
        }
        val userMessage = Message(
            id = newId(),
            role = Role.User,
            content = text,
            createdAt = now(),
            attachments = attachments,
        )
        _draft.value = ""
        _pendingAttachments.value = emptyList()

        launchTurn {
            val latest = container.conversations.byId(conversationId) ?: return@launchTurn false
            container.conversations.upsert(
                latest.copy(messages = latest.messages + userMessage, draft = ""),
            )
            true
        }
    }

    /**
     * Re-runs the reply at [messageId], dropping it and everything after it. The
     * transcript is rewound to just before that reply, so it is produced again from
     * the same history - this time under whatever the prompt says now.
     *
     * Anything that followed the reply goes with it: the model must not see the
     * answer it is being asked to rewrite, and a reply that carried tool calls cannot
     * be kept without the tool results that followed.
     */
    /**
     * Set for the single turn a regenerate asks for: the prompt edit the user has
     * chosen to apply, repeated after the history for that one request only.
     */
    private var restatement: String? = null

    /**
     * Drops the reply and everything after it, and adopts any pending prompt edit
     * as part of the same action. Tapping regenerate is the user asking for this
     * answer again under the instructions they have written since, so that tap is
     * the moment the chat's prompt actually moves.
     */
    fun regenerate(messageId: String) {
        launchTurn {
            val current = container.conversations.byId(conversationId) ?: return@launchTurn false
            val index = current.messages.indexOfFirst { it.id == messageId }
            if (index < 0 || current.messages[index].role != Role.Assistant) return@launchTurn false
            val adopted = PromptBuilder.effectiveSystemPrompt(current, container.settings.settings.value)
            container.conversations.upsert(
                current.copy(
                    messages = current.messages.take(index),
                    systemPromptAtStart = adopted,
                    announcedSystemPrompt = adopted,
                    pendingPromptReminder = "",
                ),
                touch = false,
            )
            restatement = adopted
            true
        }
    }

    /**
     * True while any tool call in the transcript is still waiting on the user.
     * An assistant message carrying `tool_calls` must be followed by one `tool`
     * message per call id, so nothing may be sent onward while one is unanswered.
     */
    private fun hasPendingToolCalls(conversation: Conversation): Boolean =
        conversation.messages.any { message ->
            message.toolCalls.any { it.status == ToolCallStatus.Pending }
        }

    /** Records the user's decision, then lets the model continue with the result. */
    fun decideToolCall(call: ToolCall, approved: Boolean) {
        if (call.status != ToolCallStatus.Pending) return
        launchTurn {
            persistDecision(call, approved)
            // When the model asked for several calls at once, the remaining ones
            // still need the user, so do not call the model yet.
            container.conversations.byId(conversationId)?.let { !hasPendingToolCalls(it) } == true
        }
    }

    fun stop() {
        turnJob?.cancel()
    }

    private fun launchTurn(prepare: suspend () -> Boolean) {
        if (turnJob?.isActive == true) return
        _notice.value = null
        turnJob = viewModelScope.launch {
            _generating.value = true
            try {
                if (prepare()) runTurns()
            } catch (cancelled: CancellationException) {
                val partial = _streamingMessage.value
                _streamingMessage.value = null
                if (partial != null && (partial.content.isNotBlank() || partial.reasoning.isNotBlank())) {
                    // The coroutine is already cancelled, so persist outside that scope.
                    withContext(NonCancellable) { persist(partial.copy(interrupted = true)) }
                }
                throw cancelled
            } catch (failure: Throwable) {
                _streamingMessage.value = null
                _notice.value = "Request failed: ${failure.message ?: failure::class.java.simpleName}"
            } finally {
                _generating.value = false
            }
        }
    }

    /**
     * One or more model round trips. Each pass stops as soon as a tool call needs
     * the user; pre-approved calls are executed and the loop continues so the
     * model can see their results.
     */
    private suspend fun runTurns() {
        while (true) {
            val current = trackSystemPrompt() ?: return
            val currentSettings = container.settings.settings.value
            if (currentSettings.apiKey.isBlank()) {
                _notice.value = "Add your API key in Settings first."
                return
            }
            val memories = if (currentSettings.memoriesEnabled && current.memoryEnabled) {
                container.memories.enabledContents()
            } else {
                emptyList()
            }

            // The restatement belongs to the single request a regenerate asked for,
            // so it is taken once and never carried into a later pass of this loop.
            val restated = restatement
            restatement = null
            val message = container.engine.runTurn(current, currentSettings, memories, restated) { snapshot ->
                _streamingMessage.value = snapshot
            }
            _streamingMessage.value = null
            persist(message)

            val autoApprovable = if (currentSettings.autoApproveMemoryTool) {
                message.toolCalls.filter {
                    it.status == ToolCallStatus.Pending && container.tools.isAutoApprovable(it)
                }
            } else {
                emptyList()
            }
            if (autoApprovable.isEmpty()) {
                maybeOfferTitle()
                return
            }
            for (call in autoApprovable) persistDecision(call, approved = true)
            val afterApproving = container.conversations.byId(conversationId) ?: return
            if (hasPendingToolCalls(afterApproving)) {
                // The model asked for more than one thing in the same turn. The
                // rest are the user's call, so stop here and wait for them.
                maybeOfferTitle()
                return
            }
        }
    }

    /**
     * Remembers the system prompt this chat began under, and records any later edit
     * as a queued change. The chat keeps running on its starting prompt, so an edit
     * reaches the model only when a regenerate adopts it - this is what shows that
     * edit as pending in the meantime instead of applying it by stealth.
     */
    private suspend fun trackSystemPrompt(): Conversation? {
        val current = container.conversations.byId(conversationId) ?: return null
        val effective = PromptBuilder.effectiveSystemPrompt(current, container.settings.settings.value)
        if (current.systemPromptAtStart.isBlank()) {
            if (current.announcedSystemPrompt == effective) return current
            val updated = current.copy(systemPromptAtStart = effective, announcedSystemPrompt = effective)
            container.conversations.upsert(updated, touch = false)
            return updated
        }
        if (effective != current.announcedSystemPrompt) {
            val updated = current.copy(announcedSystemPrompt = effective, pendingPromptReminder = effective)
            container.conversations.upsert(updated, touch = false)
            return updated
        }
        return current
    }

    // ------------------------------------------------------------- chat titles

    /** Names the chat once the first reply lands, and offers a new name as it grows. */
    private fun maybeOfferTitle() {
        val settings = container.settings.settings.value
        if (!settings.autoTitleEnabled || settings.apiKey.isBlank()) return
        if (titleJob?.isActive == true) return
        val current = container.conversations.byId(conversationId) ?: return
        val firstUser = current.messages.firstOrNull { it.role == Role.User && it.content.isNotBlank() }
        // A title the user typed is left alone; only the placeholder or the one
        // derived from the first message may be replaced.
        val derived = firstUser?.let { titleFrom(it.content) }
        val first = !current.titleGenerated && (current.title == UNTITLED || current.title == derived)
        val stale = current.titleGenerated &&
            current.messages.size - current.titleSetAtCount >= Titles.REFRESH_AFTER
        if (!first && !stale) return
        titleJob = viewModelScope.launch {
            val suggestion = container.engine.suggestTitle(current, settings) ?: return@launch
            val latest = container.conversations.byId(conversationId) ?: return@launch
            // Something else named the chat while we waited; don't fight it.
            if (latest.titleSetAtCount != current.titleSetAtCount) return@launch
            if (first) applyTitle(latest, suggestion) else _titleSuggestion.value = suggestion
        }
    }

    /** Asks for a name on demand, whatever the automatic rules would say. */
    fun suggestTitleNow() {
        if (titleJob?.isActive == true) return
        _titleSuggestion.value = null
        titleJob = viewModelScope.launch {
            val current = container.conversations.byId(conversationId) ?: return@launch
            val suggestion = container.engine.suggestTitle(current, container.settings.settings.value)
            if (suggestion == null) {
                _notice.value = "Could not name this chat."
            } else {
                _titleSuggestion.value = suggestion
            }
        }
    }

    fun acceptTitleSuggestion() {
        val suggestion = _titleSuggestion.value ?: return
        _titleSuggestion.value = null
        viewModelScope.launch {
            val current = container.conversations.byId(conversationId) ?: return@launch
            applyTitle(current, suggestion)
        }
    }

    fun dismissTitleSuggestion() {
        _titleSuggestion.value = null
    }

    private suspend fun applyTitle(conversation: Conversation, title: String) {
        container.conversations.upsert(
            conversation.copy(
                title = title,
                titleGenerated = true,
                titleSetAtCount = conversation.messages.size,
            ),
        )
    }

    private suspend fun persist(message: Message) {
        val current = container.conversations.byId(conversationId) ?: return
        if (current.messages.any { it.id == message.id }) return
        val firstUser = current.messages.firstOrNull { it.role == Role.User && it.content.isNotBlank() }
        val title = if (current.title == UNTITLED && firstUser != null) titleFrom(firstUser.content) else current.title
        container.conversations.upsert(current.copy(title = title, messages = current.messages + message))
    }

    private suspend fun persistDecision(call: ToolCall, approved: Boolean) {
        val current = container.conversations.byId(conversationId) ?: return
        val outcome = if (approved) {
            container.tools.execute(call, current)
        } else {
            container.tools.denialOutcome()
        }
        val status = when {
            !approved -> ToolCallStatus.Denied
            outcome.succeeded -> ToolCallStatus.Approved
            else -> ToolCallStatus.Failed
        }
        val decidedAt = now()
        val updatedMessages = current.messages.map { message ->
            if (message.toolCalls.none { it.id == call.id }) {
                message
            } else {
                message.copy(
                    toolCalls = message.toolCalls.map { existing ->
                        if (existing.id == call.id) {
                            existing.copy(status = status, result = outcome.resultJson, decidedAt = decidedAt)
                        } else {
                            existing
                        }
                    },
                )
            }
        }
        val resultMessage = Message(
            id = newId(),
            role = Role.Tool,
            content = outcome.resultJson,
            createdAt = decidedAt,
            toolCallId = call.id,
            toolName = call.name,
        )
        container.conversations.upsert(current.copy(messages = updatedMessages + resultMessage))
        if (approved && !outcome.succeeded) _notice.value = outcome.summary
    }

    // ------------------------------------------------------- conversation edits

    fun setMemoryEnabled(enabled: Boolean) =
        viewModelScope.launch { patch { it.copy(memoryEnabled = enabled) } }

    fun setModelOverride(model: String) =
        viewModelScope.launch { patch { it.copy(modelOverride = model.trim()) } }

    fun setSystemPromptOverride(prompt: String) =
        viewModelScope.launch { patch { it.copy(systemPrompt = prompt.trim()) } }

    /** A hand-typed name. The app stops offering to replace it from then on. */
    fun setTitle(title: String) = viewModelScope.launch {
        patch { it.copy(title = title.trim().ifBlank { UNTITLED }, titleGenerated = false) }
    }

    fun deleteMessage(messageId: String) = viewModelScope.launch {
        patch { conversation -> conversation.copy(messages = conversation.messages.filterNot { it.id == messageId }) }
    }

    /**
     * Rewrites the text of one of the user's own messages, in place. Nothing is sent
     * and nothing below it is dropped: the model is given what you have written only
     * when you ask for the answer again, which is regenerate on the reply that
     * followed. That tap drops the reply and re-asks, so the edit is what it sees.
     */
    fun editMessage(messageId: String, text: String) = viewModelScope.launch {
        val updated = text.trim()
        if (updated.isEmpty()) return@launch
        patch { conversation ->
            conversation.copy(
                messages = conversation.messages.map { message ->
                    if (message.id == messageId && message.role == Role.User) {
                        message.copy(content = updated)
                    } else {
                        message
                    }
                },
            )
        }
    }

    fun deleteConversation(onDeleted: () -> Unit) = viewModelScope.launch {
        turnJob?.cancel()
        container.conversations.delete(conversationId)
        onDeleted()
    }

    private suspend fun patch(transform: (Conversation) -> Conversation) {
        val current = container.conversations.byId(conversationId) ?: return
        container.conversations.upsert(transform(current), touch = false)
    }

    private companion object {
        const val UNTITLED = "New chat"
        const val MAX_ATTACHMENTS = 4
        const val DRAFT_DEBOUNCE_MS = 600L
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    container: AppContainer,
    conversationId: String,
    onBack: () -> Unit,
) {
    val appContext = LocalContext.current
    val viewModel: ChatViewModel = viewModel<ChatViewModel>(
        key = conversationId,
        factory = ContainerViewModelFactory(container) { ChatViewModel(it, conversationId) },
    )

    val conversation by viewModel.conversation.collectAsState()
    val messages by viewModel.messages.collectAsState()
    val draft by viewModel.draft.collectAsState()
    val generating by viewModel.generating.collectAsState()
    val pending by viewModel.pendingAttachments.collectAsState()
    val notice by viewModel.notice.collectAsState()
    val settings by viewModel.settings.collectAsState()
    val titleSuggestion by viewModel.titleSuggestion.collectAsState()

    val snackbarHostState = remember { SnackbarHostState() }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    var menuOpen by remember { mutableStateOf(false) }
    var chatSettingsOpen by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var editingId by remember { mutableStateOf<String?>(null) }
    var editDraft by remember { mutableStateOf("") }
    var viewerTarget by remember { mutableStateOf<Attachment?>(null) }

    var captureUri by remember { mutableStateOf<Uri?>(null) }

    // Images go to the model so it can look at them. Anything else is copied into
    // this chat's workspace, for the assistant's own tools to open.
    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) viewModel.attachImage(appContext, uri)
    }
    // Always a file, never an image: a picture picked here goes into the workspace
    // too, so the assistant opens it there rather than the app sending it to the model.
    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.attachFile(appContext, uri)
    }
    val takePicture = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { saved ->
        val uri = captureUri
        captureUri = null
        if (saved && uri != null) viewModel.attachImage(appContext, uri)
    }

    LaunchedEffect(notice) {
        val text = notice
        if (text != null) {
            snackbarHostState.showSnackbar(text)
            viewModel.clearNotice()
        }
    }

    // Follow the tail of the transcript while it grows.
    val lastContentLength = messages.lastOrNull()?.content?.length ?: 0
    LaunchedEffect(messages.size, lastContentLength) {
        if (messages.isNotEmpty()) {
            runCatching { listState.animateScrollToItem(messages.lastIndex) }
        }
    }

    val current = conversation

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = current?.title ?: "Chat",
                        maxLines = 1,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(
                        onClick = { viewModel.setMemoryEnabled(current?.memoryEnabled != true) },
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Psychology,
                            contentDescription = if (current?.memoryEnabled == true) {
                                "Memories on for this chat"
                            } else {
                                "Memories off for this chat"
                            },
                            tint = if (current?.memoryEnabled == true) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                    Box {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = "Chat options")
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text("This chat's settings") },
                                leadingIcon = { Icon(Icons.Filled.Tune, contentDescription = null) },
                                onClick = {
                                    menuOpen = false
                                    chatSettingsOpen = true
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Suggest a name") },
                                leadingIcon = { Icon(Icons.Filled.AutoAwesome, contentDescription = null) },
                                onClick = {
                                    menuOpen = false
                                    viewModel.suggestTitleNow()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Clear messages") },
                                leadingIcon = { Icon(Icons.Filled.DeleteSweep, contentDescription = null) },
                                onClick = {
                                    menuOpen = false
                                    confirmClear = true
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Delete chat") },
                                leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null) },
                                onClick = {
                                    menuOpen = false
                                    confirmDelete = true
                                },
                            )
                        }
                    }
                },
            )
        },
        bottomBar = {
            Composer(
                value = draft,
                onValueChange = viewModel::setDraft,
                pending = pending,
                resolveFile = viewModel::fileFor,
                onRemoveAttachment = viewModel::removeAttachment,
                onOpenAttachment = { viewerTarget = it },
                onPickImage = { pickImage.launch("image/*") },
                onPickFile = { pickFile.launch(arrayOf("*/*")) },
                sandboxEnabled = settings.sandboxEnabled,
                onTakePhoto = {
                    val uri = newCaptureUri(appContext)
                    if (uri == null) {
                        scope.launch { snackbarHostState.showSnackbar("No camera app is available.") }
                    } else {
                        captureUri = uri
                        takePicture.launch(uri)
                    }
                },
                onSend = viewModel::send,
                onStop = viewModel::stop,
                isStreaming = generating,
                enabled = !generating,
                modifier = Modifier.imePadding(),
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            // Only the earliest pending call may be run: approving several at once
            // would send the model an assistant turn with unanswered tool calls.
            val approvableCallId = remember(messages) {
                messages.asSequence()
                    .flatMap { it.toolCalls.asSequence() }
                    .firstOrNull { it.status == ToolCallStatus.Pending }
                    ?.id
            }
            if (messages.isEmpty()) {
                Text(
                    text = if (settings.apiKey.isBlank()) {
                        "Add your DeepSeek API key in Settings, then say hello."
                    } else {
                        "Say something. This conversation is saved to this device."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.align(Alignment.Center).padding(32.dp),
                )
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(messages, key = { it.id }) { message ->
                        MessageBubble(
                            message = message,
                            resolveFile = viewModel::fileFor,
                            isStreaming = generating && message.id == messages.lastOrNull()?.id,
                            onCopy = {
                                copyToClipboard(appContext, message.content.ifBlank { message.error.orEmpty() })
                                scope.launch { snackbarHostState.showSnackbar("Copied.") }
                            },
                            onOpenImage = { viewerTarget = it },
                            onRegenerate = if (message.role == Role.Assistant && !generating) {
                                { viewModel.regenerate(message.id) }
                            } else {
                                null
                            },
                            onEdit = if (message.role == Role.User && !generating) {
                                {
                                    editDraft = message.content
                                    editingId = message.id
                                }
                            } else {
                                null
                            },
                            onDelete = { viewModel.deleteMessage(message.id) },
                            onDecideToolCall = if (generating) {
                                null
                            } else {
                                { call, approved -> viewModel.decideToolCall(call, approved) }
                            },
                            autoApproveTools = settings.autoApproveMemoryTool,
                            onAutoApproveChange = { value -> updateAutoApprove(container, value) },
                            approvableCallId = approvableCallId,
                        )
                    }
                }
            }
        }
    }

    editingId?.let { id ->
        AlertDialog(
            onDismissRequest = { editingId = null },
            title = { Text("Edit message") },
            text = {
                Column {
                    OutlinedTextField(
                        value = editDraft,
                        onValueChange = { editDraft = it },
                        label = { Text("Message") },
                        modifier = Modifier.fillMaxWidth(),
                        maxLines = 12,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "Saving rewrites this message and asks the assistant nothing. " +
                            "Tap regenerate on the reply below to get an answer to the new wording.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.editMessage(id, editDraft)
                        editingId = null
                    },
                    enabled = editDraft.isNotBlank(),
                ) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { editingId = null }) { Text("Cancel") }
            },
        )
    }

    viewerTarget?.let { attachment ->
        ImageViewerDialog(
            attachment = attachment,
            resolveFile = viewModel::fileFor,
            onDismiss = { viewerTarget = null },
        )
    }

    titleSuggestion?.let { suggestion ->
        AlertDialog(
            onDismissRequest = { viewModel.dismissTitleSuggestion() },
            title = { Text("Update chat name?") },
            text = {
                Column {
                    Text(
                        text = "A lot has been said since this chat was named. Use the new one?",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(text = suggestion, style = MaterialTheme.typography.titleMedium)
                }
            },
            confirmButton = {
                TextButton(onClick = { viewModel.acceptTitleSuggestion() }) { Text("Use it") }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.dismissTitleSuggestion() }) { Text("Keep current") }
            },
        )
    }

    if (chatSettingsOpen && current != null) {
        var modelText by remember(current.id) { mutableStateOf(current.modelOverride) }
        var promptText by remember(current.id) { mutableStateOf(current.systemPrompt) }
        AlertDialog(
            onDismissRequest = { chatSettingsOpen = false },
            title = { Text("This chat's settings") },
            text = {
                Column {
                    Text(
                        text = "Overrides apply to this conversation only.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = modelText,
                        onValueChange = { modelText = it },
                        singleLine = true,
                        label = { Text("Model (default ${settings.model})") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = promptText,
                        onValueChange = { promptText = it },
                        minLines = 4,
                        label = { Text("System prompt") },
                        placeholder = { Text(settings.systemPrompt) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(
                            checked = current.memoryEnabled,
                            onCheckedChange = { viewModel.setMemoryEnabled(it) },
                        )
                        Spacer(Modifier.width(12.dp))
                        Text("Use memories in this chat", style = MaterialTheme.typography.bodyMedium)
                    }
                    if (current.systemPromptAtStart.isNotBlank() &&
                        promptText.trim() != current.systemPromptAtStart
                    ) {
                        Spacer(Modifier.height(10.dp))
                        Text(
                            text = "Changed since this chat started. This chat keeps the prompt it " +
                                "began with, so your edit is applied only when you tap regenerate " +
                                "on a reply — that reply is what it applies to.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.setModelOverride(modelText)
                    viewModel.setSystemPromptOverride(promptText)
                    chatSettingsOpen = false
                }) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { chatSettingsOpen = false }) { Text("Cancel") }
            },
        )
    }

    if (confirmClear) {
        ConfirmDialog(
            title = "Clear this chat's messages?",
            message = "The conversation stays, but every message in it is removed.",
            confirmLabel = "Clear",
            destructive = true,
            onConfirm = {
                scope.launch {
                    val latest = container.conversations.byId(conversationId)
                    if (latest != null) {
                        container.conversations.upsert(latest.copy(messages = emptyList()), touch = false)
                    }
                }
                confirmClear = false
            },
            onDismiss = { confirmClear = false },
        )
    }

    if (confirmDelete) {
        ConfirmDialog(
            title = "Delete this chat?",
            message = "The conversation and its messages are removed from this device.",
            confirmLabel = "Delete",
            destructive = true,
            onConfirm = {
                confirmDelete = false
                viewModel.deleteConversation(onBack)
            },
            onDismiss = { confirmDelete = false },
        )
    }
}

private fun updateAutoApprove(container: AppContainer, enabled: Boolean) {
    container.settings.update { it.copy(autoApproveMemoryTool = enabled) }
}

private fun copyToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText("message", text))
}

private fun newCaptureUri(context: Context): Uri? = runCatching {
    val dir = File(context.cacheDir, "captures").apply { mkdirs() }
    val file = File(dir, "capture-${System.currentTimeMillis()}.jpg")
    FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
}.getOrNull()
