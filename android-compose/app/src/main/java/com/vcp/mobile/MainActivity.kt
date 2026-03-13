package com.vcp.mobile

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Link
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import com.vcp.mobile.ui.agent.AgentScreen
import com.vcp.mobile.ui.agent.AgentViewModel
import com.vcp.mobile.ui.chat.ChatViewModel
import com.vcp.mobile.ui.chat.ChatScreen
import com.vcp.mobile.ui.pairing.PairingScreen
import com.vcp.mobile.ui.pairing.PairingViewModel
import com.vcp.mobile.ui.theme.VcpMobileTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private val chatViewModel: ChatViewModel by viewModels()
    private val pairingViewModel: PairingViewModel by viewModels()
    private val agentViewModel: AgentViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AppRoot(
                chatViewModel = chatViewModel,
                pairingViewModel = pairingViewModel,
                agentViewModel = agentViewModel,
            )
        }
    }
}

private enum class AppSection {
    CHAT,
    PAIRING,
    AGENTS,
}

@Composable
private fun AppRoot(
    chatViewModel: ChatViewModel,
    pairingViewModel: PairingViewModel,
    agentViewModel: AgentViewModel,
) {
    var selectedSection by rememberSaveable { mutableStateOf(AppSection.CHAT) }

    VcpMobileTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            Scaffold(
                bottomBar = {
                    NavigationBar {
                        NavigationBarItem(
                            selected = selectedSection == AppSection.CHAT,
                            onClick = { selectedSection = AppSection.CHAT },
                            icon = {
                                Icon(
                                    imageVector = Icons.Filled.ChatBubble,
                                    contentDescription = "聊天",
                                )
                            },
                            label = { androidx.compose.material3.Text(text = "聊天") },
                        )
                        NavigationBarItem(
                            selected = selectedSection == AppSection.PAIRING,
                            onClick = { selectedSection = AppSection.PAIRING },
                            icon = {
                                Icon(
                                    imageVector = Icons.Filled.Link,
                                    contentDescription = "配对",
                                )
                            },
                            label = { androidx.compose.material3.Text(text = "配对") },
                        )
                        NavigationBarItem(
                            selected = selectedSection == AppSection.AGENTS,
                            onClick = { selectedSection = AppSection.AGENTS },
                            icon = {
                                Icon(
                                    imageVector = Icons.Filled.Face,
                                    contentDescription = "Agents",
                                )
                            },
                            label = { androidx.compose.material3.Text(text = "Agents") },
                        )
                    }
                }
            ) { innerPadding ->
                when (selectedSection) {
                    AppSection.CHAT -> ChatScreen(
                        viewModel = chatViewModel,
                        modifier = Modifier.padding(innerPadding),
                    )

                    AppSection.PAIRING -> PairingScreen(
                        viewModel = pairingViewModel,
                        modifier = Modifier.padding(innerPadding),
                        onOpenChat = { selectedSection = AppSection.CHAT },
                    )

                    AppSection.AGENTS -> AgentScreen(
                        viewModel = agentViewModel,
                        modifier = Modifier.padding(innerPadding),
                    )
                }
            }
        }
    }
}
