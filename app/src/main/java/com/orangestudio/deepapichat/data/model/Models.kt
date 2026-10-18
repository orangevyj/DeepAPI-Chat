package com.orangestudio.deepapichat.data.model

import com.orangestudio.deepapichat.data.store.Storage
import kotlinx.serialization.Serializable

/** Defaults the user can override in Settings. */
object Defaults {
    const val BASE_URL = "https://api.deepseek.com"
    const val MODEL = "deepseek-chat"
    /** Temperature is omitted from the request when it equals this, so the server default applies. */
    const val TEMPERATURE = 1.0
    const val SYSTEM_PROMPT =
        "You are a helpful, knowledgeable assistant. " +
            "Reply in the language the user writes in. " +
            "Use Markdown for structure and code blocks for code. " +
            "Be concise unless the user asks for depth."
    val MODEL_SUGGESTIONS = listOf("deepseek-chat", "deepseek-reasoner")

    /** Shown as the title of a chat that has not been named yet. */
    const val UNTITLED_CHAT = "New chat"
}

@Serializable
enum class Role { System, User, Assistant, Tool }

/** Which colour scheme the app should use, independent of the device setting. */
@Serializable
enum class ThemeMode { System, Light, Dark }

/**
 * Preset accent colours. Kept as a plain enum here so the model layer has no
 * Compose dependency; [com.orangestudio.deepapichat.ui.theme] maps each one to
 * actual tones.
 */
@Serializable
enum class AccentColor(val label: String) {
    Default("Purple"),
    Blue("Blue"),
    Teal("Teal"),
    Green("Green"),
    Amber("Amber"),
    Orange("Orange"),
    Red("Red"),
    Violet("Violet"),
}

@Serializable
enum class MemorySource { User, Assistant, Imported }

@Serializable
enum class ToolCallStatus {
    /** Waiting for the user to approve or deny. */
    Pending,
    Approved,
    Denied,
    /** Approved, but the tool itself failed. */
    Failed,
}

/**
 * A file hanging off a message, kept in one of two places.
 *
 * An image the user picked from the gallery is normalized and stored in the app's
 * attachments directory, and is shown to the model inline. A file attached for the
 * assistant to work on is copied into this chat's sandbox workspace instead, and is
 * never sent inline: the model is told it exists and opens it with its own tools.
 */
@Serializable
data class Attachment(
    val id: String = "",
    val fileName: String = "",
    val mimeType: String = "image/jpeg",
    /** Path relative to the root data dir, e.g. `attachments/<id>.jpg`. */
    val relativePath: String = "",
    val width: Int = 0,
    val height: Int = 0,
    val sizeBytes: Long = 0L,
    /**
     * True when [relativePath] is inside this chat's sandbox workspace rather than the
     * attachments directory. Only an attachment in the attachments directory is
     * eligible to be sent to the model as an image.
     */
    val inWorkspace: Boolean = false,
) {
    /**
     * True when this file lives in the chat's sandbox workspace rather than the
     * attachments directory - the question that decides whether it may travel to the
     * model as an image. [inWorkspace] records it for new attachments; the path is the
     * fallback, because an attachment written before that field existed still lives
     * under the workspace directory and deserializes with the flag false.
     */
    val isWorkspaceFile: Boolean
        get() = inWorkspace || !relativePath.startsWith("${Storage.ATTACHMENTS}/")
}

@Serializable
data class Usage(
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
    val totalTokens: Int = 0,
) {
    val total: Int get() = if (totalTokens > 0) totalTokens else promptTokens + completionTokens
}

/**
 * A tool call requested by the model.
 *
 * Nothing is executed until [status] leaves [ToolCallStatus.Pending]. This exists
 * as a general mechanism: `save_memory` is the only tool today, but the future
 * sandboxed-terminal tools plug into the same approval flow.
 */
@Serializable
data class ToolCall(
    val id: String = "",
    val name: String = "",
    /** Raw JSON arguments exactly as the model streamed them. */
    val arguments: String = "{}",
    val status: ToolCallStatus = ToolCallStatus.Pending,
    /** JSON result fed back to the model, once decided. */
    val result: String? = null,
    val createdAt: Long = 0L,
    val decidedAt: Long? = null,
)

@Serializable
data class Message(
    val id: String = "",
    val role: Role = Role.User,
    val content: String = "",
    val createdAt: Long = 0L,
    val attachments: List<Attachment> = emptyList(),
    val toolCalls: List<ToolCall> = emptyList(),
    /** Set on [Role.Tool] messages: the call this message answers. */
    val toolCallId: String? = null,
    val toolName: String? = null,
    /** Reasoning trace for models that expose one (e.g. deepseek-reasoner). */
    val reasoning: String = "",
    val model: String? = null,
    val usage: Usage? = null,
    /** Set when the request failed. [content] may hold a partial response. */
    val error: String? = null,
    /** True when the user stopped generation mid-stream. */
    val interrupted: Boolean = false,
) {
    val hasPendingToolCalls: Boolean
        get() = toolCalls.any { it.status == ToolCallStatus.Pending }
}

@Serializable
data class Conversation(
    val id: String = "",
    val title: String = "New chat",
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
    val messages: List<Message> = emptyList(),
    /** Overrides [AppSettings.systemPrompt] when non-blank. */
    val systemPrompt: String = "",
    /** Overrides [AppSettings.model] when non-blank. */
    val modelOverride: String = "",
    val temperatureOverride: Double? = null,
    /** Per-conversation thinking switch; null follows [AppSettings.thinkingEnabled]. */
    val thinkingOverride: Boolean? = null,
    /** "low", "medium" or "high"; blank follows [AppSettings.thinkingEffort]. */
    val thinkingEffortOverride: String = "",
    /** Per-conversation switch for injecting saved memories. */
    val memoryEnabled: Boolean = true,
    val pinned: Boolean = false,
    /** Unsent composer text, persisted so switching chats doesn't lose it. */
    val draft: String = "",
    /** True while [title] is one the model wrote, false for hand-typed titles. */
    val titleGenerated: Boolean = false,
    /** Message count when the title was last written, so growth can be measured. */
    val titleSetAtCount: Int = 0,
    /** Effective system prompt when this chat began; blank until the first turn. */
    val systemPromptAtStart: String = "",
    /**
     * The system prompt the transcript is currently running under. Diverging from
     * the effective prompt means the user changed it and the model needs to be
     * told again mid-conversation.
     */
    val announcedSystemPrompt: String = "",
    /** Prompt restatement queued for the next request, then cleared. */
    val pendingPromptReminder: String = "",
)

@Serializable
data class Memory(
    val id: String = "",
    val content: String = "",
    val enabled: Boolean = true,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
    val source: MemorySource = MemorySource.User,
)

@Serializable
data class AppSettings(
    val baseUrl: String = Defaults.BASE_URL,
    val apiKey: String = "",
    val model: String = Defaults.MODEL,
    val temperature: Double = 1.0,
    /** 0 means "don't send max_tokens at all". */
    val maxTokens: Int = 0,
    /** Ask the model to reason before answering. Off keeps replies faster and cheaper. */
    val thinkingEnabled: Boolean = false,
    /** "low", "medium" or "high" — sent as reasoning_effort when thinking is on. */
    val thinkingEffort: String = "medium",
    val streaming: Boolean = true,
    val systemPrompt: String = Defaults.SYSTEM_PROMPT,
    /** Master switch. When off, memories are neither stored nor injected. */
    val memoriesEnabled: Boolean = true,
    /** When on, the `save_memory` tool is offered to the model. */
    val memoryToolEnabled: Boolean = true,
    /** When on, `save_memory` calls run without an approval prompt. */
    val autoApproveMemoryTool: Boolean = false,
    /** When on, the app names new chats with the model and offers refreshes. */
    val autoTitleEnabled: Boolean = true,
    /** Follow the system, or force light/dark. */
    val themeMode: ThemeMode = ThemeMode.System,
    /** In the dark scheme, draw pure-black surfaces (OLED panels). */
    val amoledBlack: Boolean = false,
    /** Accent colour seed for the colour schemes. */
    val accent: AccentColor = AccentColor.Default,
    /**
     * When on, the model gets the sandbox tools and may run commands and touch
     * files in this chat's own workspace. Off by default: it is code execution.
     */
    val sandboxEnabled: Boolean = false,
)
