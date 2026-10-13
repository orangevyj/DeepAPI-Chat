package com.orangestudio.deepapichat.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.orangestudio.deepapichat.AppContainer
import com.orangestudio.deepapichat.ContainerViewModelFactory
import com.orangestudio.deepapichat.data.api.ModelsResult
import com.orangestudio.deepapichat.data.model.AccentColor
import com.orangestudio.deepapichat.data.model.AppSettings
import com.orangestudio.deepapichat.data.model.Defaults
import com.orangestudio.deepapichat.data.model.ThemeMode
import com.orangestudio.deepapichat.ui.components.SettingsGroup
import com.orangestudio.deepapichat.ui.components.SettingsRow
import com.orangestudio.deepapichat.ui.theme.accentSwatch
import com.orangestudio.deepapichat.ui.theme.isDarkTheme
import com.orangestudio.deepapichat.ui.theme.onAccentSwatch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class SettingsViewModel(private val container: AppContainer) : ViewModel() {
    val settings: StateFlow<AppSettings> = container.settings.settings

    /** Applies immediately; [com.orangestudio.deepapichat.data.store.SettingsStore] debounces the write. */
    fun update(transform: (AppSettings) -> AppSettings) = container.settings.update(transform)

    private val _catalogue = MutableStateFlow<ModelCatalogue>(ModelCatalogue.Idle)

    /** What the endpoint has said about the models it offers, if it has been asked. */
    val catalogue: StateFlow<ModelCatalogue> = _catalogue

    /**
     * Asks the endpoint which models it offers.
     *
     * Takes the values currently in the form rather than the ones on disk, so a key
     * just typed in works before it is saved, and an edited base URL is the one
     * actually asked instead of the stale saved one.
     */
    fun loadModels(baseUrl: String, apiKey: String) {
        if (_catalogue.value is ModelCatalogue.Loading) return
        _catalogue.value = ModelCatalogue.Loading
        viewModelScope.launch {
            _catalogue.value = when (val result = container.client.listModels(baseUrl, apiKey)) {
                is ModelsResult.Success -> ModelCatalogue.Loaded(result.ids.sorted())
                is ModelsResult.Failure -> ModelCatalogue.Failed(result.message)
            }
        }
    }
}

/**
 * What the endpoint has said about the models it offers.
 *
 * [Failed] is a normal outcome, not an error state for the screen: `/models` is not
 * part of every OpenAI-compatible server, so the field keeps its built-in suggestions
 * and shows why the list could not be fetched.
 */
sealed interface ModelCatalogue {
    data object Idle : ModelCatalogue
    data object Loading : ModelCatalogue
    data class Loaded(val ids: List<String>) : ModelCatalogue
    data class Failed(val message: String) : ModelCatalogue
}

/**
 * The model field's chips: whatever the endpoint listed once it has been asked, and the
 * built-in suggestions otherwise, so the field is never a dead end.
 */
@Composable
private fun ModelChoices(
    catalogue: ModelCatalogue,
    selected: String,
    onPick: (String) -> Unit,
    onRefresh: () -> Unit,
) {
    val loading = catalogue is ModelCatalogue.Loading
    val ids = when (catalogue) {
        is ModelCatalogue.Loaded -> catalogue.ids
        else -> Defaults.MODEL_SUGGESTIONS
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            for (id in ids) {
                AssistChip(
                    onClick = { onPick(id) },
                    label = { Text(id) },
                    leadingIcon = if (id == selected) {
                        {
                            Icon(
                                imageVector = Icons.Filled.Check,
                                contentDescription = "The model in use",
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    } else {
                        null
                    },
                )
            }
            TextButton(onClick = onRefresh, enabled = !loading) {
                Text(if (loading) "Asking…" else "Refresh")
            }
        }
        Text(
            text = when (catalogue) {
                is ModelCatalogue.Idle -> "Suggested names. Refresh asks the server what it offers."
                is ModelCatalogue.Loading -> "Asking the server…"
                is ModelCatalogue.Loaded -> "${catalogue.ids.size} models listed by the server."
                is ModelCatalogue.Failed -> catalogue.message
            },
            style = MaterialTheme.typography.bodySmall,
            color = if (catalogue is ModelCatalogue.Failed) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(container: AppContainer, onBack: () -> Unit) {
    val appContext = LocalContext.current
    val viewModel: SettingsViewModel = viewModel<SettingsViewModel>(
        factory = ContainerViewModelFactory(container) { SettingsViewModel(it) },
    )
    val settings by viewModel.settings.collectAsState()
    val darkTheme = isDarkTheme(settings.themeMode)
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    // Text fields keep local state and commit on Save, so a half-typed URL is
    // never written to disk.
    var baseUrl by remember(settings.baseUrl) { mutableStateOf(settings.baseUrl) }
    var apiKey by remember(settings.apiKey) { mutableStateOf(settings.apiKey) }
    var model by remember(settings.model) { mutableStateOf(settings.model) }
    var systemPrompt by remember(settings.systemPrompt) { mutableStateOf(settings.systemPrompt) }
    var maxTokens by remember(settings.maxTokens) { mutableStateOf(settings.maxTokens.toString()) }
    var keyVisible by remember { mutableStateOf(false) }
    val catalogue by viewModel.catalogue.collectAsState()

    // Ask the server once its key is known, so the real list is there without a tap.
    // Keyed on the saved key and base URL: they arrive from disk a moment after the
    // screen first composes, and change again on Save. Without a key the request would
    // only come back unauthorized, so it is skipped and the suggestions stand.
    LaunchedEffect(settings.apiKey, settings.baseUrl) {
        if (settings.apiKey.isNotBlank()) viewModel.loadModels(settings.baseUrl, settings.apiKey)
    }

    fun save() {
        val tokens = maxTokens.trim().toIntOrNull()?.coerceAtLeast(0) ?: 0
        viewModel.update {
            it.copy(
                baseUrl = baseUrl.trim().ifBlank { Defaults.BASE_URL },
                apiKey = apiKey.trim(),
                model = model.trim().ifBlank { Defaults.MODEL },
                systemPrompt = systemPrompt.trim().ifBlank { Defaults.SYSTEM_PROMPT },
                maxTokens = tokens,
            )
        }
        maxTokens = tokens.toString()
        scope.launch { snackbarHostState.showSnackbar("Saved.") }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = { TextButton(onClick = { save() }) { Text("Save") } },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Spacer(Modifier.height(4.dp))

            SettingsGroup(title = "Connection") {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    OutlinedTextField(
                        value = baseUrl,
                        onValueChange = { baseUrl = it },
                        singleLine = true,
                        label = { Text("Base URL") },
                        supportingText = { Text("Requests go to <base>/chat/completions") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = apiKey,
                        onValueChange = { apiKey = it },
                        singleLine = true,
                        label = { Text("API key") },
                        visualTransformation = if (keyVisible) {
                            VisualTransformation.None
                        } else {
                            PasswordVisualTransformation()
                        },
                        trailingIcon = {
                            IconButton(onClick = { keyVisible = !keyVisible }) {
                                Icon(
                                    imageVector = if (keyVisible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                    contentDescription = if (keyVisible) "Hide the key" else "Show the key",
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = model,
                        onValueChange = { model = it },
                        singleLine = true,
                        label = { Text("Model") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    ModelChoices(
                        catalogue = catalogue,
                        selected = model,
                        onPick = { model = it },
                        onRefresh = { viewModel.loadModels(baseUrl, apiKey) },
                    )
                }
            }

            SettingsGroup(title = "Generation") {
                SettingsRow(
                    title = "Stream replies",
                    subtitle = "Show tokens as they arrive instead of waiting for the whole reply.",
                    trailing = {
                        Switch(
                            checked = settings.streaming,
                            onCheckedChange = { enabled -> viewModel.update { it.copy(streaming = enabled) } },
                        )
                    },
                )
                SettingsRow(
                    title = "Name chats with AI",
                    subtitle = "After the first reply the model writes a short title, and offers a " +
                        "fresh one as the chat grows.",
                    trailing = {
                        Switch(
                            checked = settings.autoTitleEnabled,
                            onCheckedChange = { enabled -> viewModel.update { it.copy(autoTitleEnabled = enabled) } },
                        )
                    },
                )
                SettingsRow(
                    title = "Temperature",
                    subtitle = if (settings.temperature == Defaults.TEMPERATURE) {
                        "${settings.temperature} — left out of the request, so the server default applies."
                    } else {
                        "${settings.temperature} — sent with every request."
                    },
                )
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                    Slider(
                        value = settings.temperature.toFloat(),
                        onValueChange = { value ->
                            viewModel.update { it.copy(temperature = value.toDouble().roundToTenths()) }
                        },
                        valueRange = 0f..2f,
                        steps = STEPS,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    TextButton(
                        onClick = { viewModel.update { it.copy(temperature = Defaults.TEMPERATURE) } },
                    ) {
                        Text("Reset to the server default")
                    }
                }
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    OutlinedTextField(
                        value = maxTokens,
                        onValueChange = { maxTokens = it.filter { ch -> ch.isDigit() }.take(6) },
                        singleLine = true,
                        label = { Text("Max reply tokens") },
                        supportingText = { Text("0 leaves the limit to the server.") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            SettingsGroup(title = "Appearance") {
                SettingsRow(
                    title = "Theme",
                    subtitle = "Follow the system, or force light or dark.",
                )
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    for (mode in ThemeMode.entries) {
                        val selected = settings.themeMode == mode
                        val tick: (@Composable () -> Unit)? = if (selected) {
                            { Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }
                        } else {
                            null
                        }
                        FilterChip(
                            selected = selected,
                            onClick = { viewModel.update { it.copy(themeMode = mode) } },
                            label = { Text(mode.name) },
                            leadingIcon = tick,
                        )
                    }
                }
                SettingsRow(
                    title = "AMOLED black",
                    subtitle = if (darkTheme) {
                        "Draw true black surfaces in the dark theme, to save power on OLED screens."
                    } else {
                        "Needs the dark theme — set Theme to Dark, or use it on a dark device."
                    },
                    trailing = {
                        Switch(
                            checked = settings.amoledBlack,
                            enabled = darkTheme,
                            onCheckedChange = { enabled -> viewModel.update { it.copy(amoledBlack = enabled) } },
                        )
                    },
                )
                SettingsRow(
                    title = "Accent colour",
                    subtitle = settings.accent.label,
                )
                Row(
                    modifier = Modifier
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp)
                        .padding(bottom = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    for (accent in AccentColor.entries) {
                        val selected = settings.accent == accent
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(accentSwatch(accent, darkTheme))
                                .border(
                                    width = if (selected) 3.dp else 1.dp,
                                    color = if (selected) {
                                        MaterialTheme.colorScheme.onSurface
                                    } else {
                                        MaterialTheme.colorScheme.outlineVariant
                                    },
                                    shape = CircleShape,
                                )
                                .clickable { viewModel.update { it.copy(accent = accent) } },
                            contentAlignment = Alignment.Center,
                        ) {
                            if (selected) {
                                Icon(
                                    imageVector = Icons.Filled.Check,
                                    contentDescription = accent.label,
                                    tint = onAccentSwatch(accent, darkTheme),
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                        }
                    }
                }
            }

            SettingsGroup(title = "System prompt") {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    OutlinedTextField(
                        value = systemPrompt,
                        onValueChange = { systemPrompt = it },
                        minLines = 5,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(4.dp))
                    TextButton(onClick = { systemPrompt = Defaults.SYSTEM_PROMPT }) {
                        Text("Restore the default prompt")
                    }
                }
            }

            SettingsGroup(title = "Memories") {
                SettingsRow(
                    title = "Use memories",
                    subtitle = "Off means nothing is saved, injected or proposed.",
                    trailing = {
                        Switch(
                            checked = settings.memoriesEnabled,
                            onCheckedChange = { enabled -> viewModel.update { it.copy(memoriesEnabled = enabled) } },
                        )
                    },
                )
                SettingsRow(
                    title = "Let the assistant propose memories",
                    subtitle = "Sent as a save_memory tool the model can call.",
                    trailing = {
                        Switch(
                            checked = settings.memoryToolEnabled,
                            enabled = settings.memoriesEnabled,
                            onCheckedChange = { enabled -> viewModel.update { it.copy(memoryToolEnabled = enabled) } },
                        )
                    },
                )
                SettingsRow(
                    title = "Skip the prompt for memory writes",
                    subtitle = "Every other capability still asks first.",
                    trailing = {
                        Switch(
                            checked = settings.autoApproveMemoryTool,
                            enabled = settings.memoriesEnabled && settings.memoryToolEnabled,
                            onCheckedChange = { enabled -> viewModel.update { it.copy(autoApproveMemoryTool = enabled) } },
                        )
                    },
                )
            }

            SettingsGroup(title = "Sandbox") {
                SettingsRow(
                    title = "Let the assistant use a terminal",
                    subtitle = "Offers run_command, read_file, write_file and list_files, working inside " +
                        "this chat's own folder. Every call is confirmed by you before it runs.",
                    trailing = {
                        Switch(
                            checked = settings.sandboxEnabled,
                            onCheckedChange = { enabled -> viewModel.update { it.copy(sandboxEnabled = enabled) } },
                        )
                    },
                )
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text(
                        text = "Commands run in an Alpine Linux rootfs bundled inside the app, entered " +
                            "through proot. A command inside sees that rootfs and this chat's own " +
                            "folder, and nothing else of the device: no host process list, no system " +
                            "information, and none of Android's own device nodes.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "That is a file boundary, not a security boundary. proot rewrites paths " +
                            "rather than enforcing anything at the kernel level, so this narrows what a " +
                            "command can see without caging the process: it runs as the app itself, it " +
                            "can reach the network, and a heavy one can slow the device down. The app " +
                            "only caps how long a command runs and how much output is kept.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "It is a complete Alpine system, so the assistant can also install extra " +
                            "tools with `apk add` when it needs them. Those downloads count against " +
                            "whatever connection you are on, and installing one can take a while.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            SettingsGroup(title = "Where your data lives") {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = appContext.filesDir.absolutePath + "/data",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "One JSON file per conversation, plus memories.json and settings.json. " +
                            "Conversations and memories can be exported from the chat list.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            SettingsGroup(title = "Things worth knowing") {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "• Your API key is stored unencrypted inside this app's private storage. " +
                            "It is excluded from cloud backups, but anyone with access to the device's app data can read it.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "• Plain HTTP is allowed so you can point this at a server on your own network " +
                            "(Ollama, LM Studio, vLLM). Only do that on a network you trust.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "• Data stays on this device until you send it to the URL above. " +
                            "Conversations are only written to that server when a turn runs.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

private fun Double.roundToTenths(): Double = kotlin.math.round(this * 100.0) / 100.0

private const val STEPS = 39
