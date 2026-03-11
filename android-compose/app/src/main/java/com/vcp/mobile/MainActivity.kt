package com.vcp.mobile

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.vcp.mobile.ui.chat.ChatViewModel
import com.vcp.mobile.ui.chat.ChatScreen
import com.vcp.mobile.ui.theme.VcpMobileTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private val chatViewModel: ChatViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AppRoot(viewModel = chatViewModel)
        }
    }
}

@Composable
private fun AppRoot(viewModel: ChatViewModel) {
    VcpMobileTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            ChatScreen(viewModel = viewModel)
        }
    }
}
