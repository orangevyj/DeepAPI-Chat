package com.orangestudio.deepapichat.ui.nav

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.orangestudio.deepapichat.AppContainer
import com.orangestudio.deepapichat.ui.chat.ChatScreen
import com.orangestudio.deepapichat.ui.chatlist.ChatListScreen
import com.orangestudio.deepapichat.ui.memories.MemoriesScreen
import com.orangestudio.deepapichat.ui.settings.SettingsScreen

object Routes {
    const val CHATS = "chats"
    const val CHAT = "chat/{conversationId}"
    const val MEMORIES = "memories"
    const val SETTINGS = "settings"

    const val ARG_CONVERSATION_ID = "conversationId"

    fun chat(conversationId: String): String = "chat/$conversationId"
}

@Composable
fun AppRoot(container: AppContainer) {
    val ready by container.ready.collectAsState()
    if (!ready) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
        }
        return
    }

    val navController = rememberNavController()

    NavHost(navController = navController, startDestination = Routes.CHATS) {
        composable(Routes.CHATS) {
            ChatListScreen(
                container = container,
                onOpenChat = { id -> navController.navigate(Routes.chat(id)) },
                onOpenMemories = { navController.navigate(Routes.MEMORIES) },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
            )
        }

        composable(
            route = Routes.CHAT,
            arguments = listOf(navArgument(Routes.ARG_CONVERSATION_ID) { type = NavType.StringType }),
        ) { entry ->
            ChatScreen(
                container = container,
                conversationId = entry.arguments?.getString(Routes.ARG_CONVERSATION_ID).orEmpty(),
                onBack = { navController.popBackStack() },
            )
        }

        composable(Routes.MEMORIES) {
            MemoriesScreen(container = container, onBack = { navController.popBackStack() })
        }

        composable(Routes.SETTINGS) {
            SettingsScreen(container = container, onBack = { navController.popBackStack() })
        }
    }
}
