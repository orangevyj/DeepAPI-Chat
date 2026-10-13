package com.orangestudio.deepapichat

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.orangestudio.deepapichat.ui.nav.AppRoot
import com.orangestudio.deepapichat.ui.theme.DeepAPIChatTheme
import com.orangestudio.deepapichat.ui.theme.SystemBarAppearance
import com.orangestudio.deepapichat.ui.theme.isDarkTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as DeepApiChatApp).container
        setContent {
            val settings by container.settings.settings.collectAsState()
            val dark = isDarkTheme(settings.themeMode)
            DeepAPIChatTheme(
                themeMode = settings.themeMode,
                amoledBlack = settings.amoledBlack,
                accent = settings.accent,
            ) {
                SystemBarAppearance(dark = dark)
                AppRoot(container = container)
            }
        }
    }
}
