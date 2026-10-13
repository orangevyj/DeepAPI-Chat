package com.orangestudio.deepapichat

import android.app.Application
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.orangestudio.deepapichat.core.JsonStore
import com.orangestudio.deepapichat.data.api.HttpClientFactory
import com.orangestudio.deepapichat.data.api.OpenAiClient
import com.orangestudio.deepapichat.data.store.AttachmentStore
import com.orangestudio.deepapichat.data.store.ConversationStore
import com.orangestudio.deepapichat.data.store.MemoryStore
import com.orangestudio.deepapichat.data.store.SandboxStore
import com.orangestudio.deepapichat.data.store.SettingsStore
import com.orangestudio.deepapichat.data.transfer.TransferService
import com.orangestudio.deepapichat.domain.ChatEngine
import com.orangestudio.deepapichat.domain.ToolRegistry
import com.orangestudio.deepapichat.domain.sandbox.RootfsInstaller
import com.orangestudio.deepapichat.domain.sandbox.SandboxTools
import com.orangestudio.deepapichat.domain.sandbox.ShellRunner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

class DeepApiChatApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.warmUp()
    }
}

/**
 * Hand-rolled dependency graph.
 *
 * Everything is a lazily created singleton built from one [JsonStore] rooted at
 * `filesDir/data`, which keeps the wiring visible and avoids an annotation
 * processor. [appScope] outlives any screen so that debounced settings writes and
 * the initial load are not tied to an Activity.
 */
class AppContainer(context: Context) {

    /** Exposed so screens can hand a `ContentResolver` to image import and export. */
    val appContext: Context = context.applicationContext
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val json = JsonStore(File(appContext.filesDir, "data"))

    val conversations = ConversationStore(json)
    val memories = MemoryStore(json)
    val settings = SettingsStore(json, appScope)
    val attachments = AttachmentStore(json)

    private val httpClient = HttpClientFactory.create()
    val client = OpenAiClient(httpClient)
    val sandbox = SandboxStore(json)
    private val rootfs = RootfsInstaller(appContext, sandbox)
    val tools = ToolRegistry(
        memories,
        SandboxTools(sandbox, rootfs, ShellRunner(), appContext.applicationInfo.nativeLibraryDir),
    )
    val engine = ChatEngine(client, attachments, tools)
    val transfer = TransferService(conversations, memories, attachments, appVersion(appContext))

    private val _ready = MutableStateFlow(false)

    /** True once the on-disk state has been read into the stores. */
    val ready: StateFlow<Boolean> = _ready.asStateFlow()

    fun warmUp() {
        appScope.launch { loadAll() }
    }

    suspend fun loadAll() {
        conversations.load()
        memories.load()
        settings.load()
        _ready.value = true
    }

    /** Releases the OkHttp pools. Called when the app is torn down. */
    fun shutdown() {
        appScope.cancel()
        runCatching { httpClient.connectionPool.evictAll() }
    }
}

/**
 * Builds one ViewModel from [AppContainer]. Manual because the whole graph comes
 * from a single object, so there is nothing for a DI framework to resolve.
 */
class ContainerViewModelFactory(
    private val container: AppContainer,
    private val create: (AppContainer) -> ViewModel,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = create(container) as T
}

private fun appVersion(context: Context): String = runCatching {
    @Suppress("DEPRECATION")
    context.packageManager.getPackageInfo(context.packageName, 0).versionName
}.getOrNull().orEmpty().ifBlank { "dev" }
