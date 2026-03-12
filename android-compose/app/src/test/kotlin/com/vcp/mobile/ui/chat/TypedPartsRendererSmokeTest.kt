package com.vcp.mobile.ui.chat

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TypedPartsRendererSmokeTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `renders reasoning and plain text parts without flattening them`() {
        render(
            ChatMessage(
                sender = MessageSender.AGENT,
                content = "",
                parts = listOf(
                    UiMessagePart(type = "reasoning", text = "thinking through the plan"),
                    UiMessagePart(type = "text", text = "Ship the parity smoke floor."),
                ),
            )
        )

        composeRule.onNodeWithText("thinking through the plan").assertExists()
        composeRule.onNodeWithText("Ship the parity smoke floor.").assertExists()
    }

    @Test
    fun `renders image document tool and error samples as typed cards`() {
        render(
            ChatMessage(
                sender = MessageSender.AGENT,
                content = "",
                parts = listOf(
                    UiMessagePart(
                        type = "image",
                        title = "cat preview",
                        url = "https://cdn.example.com/cat.png",
                        mime = "image/png",
                    ),
                    UiMessagePart(
                        type = "document",
                        title = "spec.pdf",
                        url = "file:///spec.pdf",
                        mime = "application/pdf",
                    ),
                    UiMessagePart(
                        type = "tool",
                        title = "search_web",
                        state = "completed",
                        text = "{\"items\":1}",
                    ),
                    UiMessagePart(type = "error", text = "upstream exploded"),
                ),
            )
        )

        composeRule.onNodeWithText("cat preview").assertExists()
        composeRule.onNodeWithText("image/png").assertExists()
        composeRule.onNodeWithText("spec.pdf").assertExists()
        composeRule.onNodeWithText("application/pdf").assertExists()
        composeRule.onNodeWithText("工具: search_web").assertExists()
        composeRule.onNodeWithText("completed").assertExists()
        composeRule.onNodeWithText("{\"items\":1}").assertExists()
        composeRule.onNodeWithText("upstream exploded").assertExists()
    }

    @Test
    fun `renders tool call and tool result samples with distinct labels`() {
        render(
            ChatMessage(
                sender = MessageSender.AGENT,
                content = "",
                parts = listOf(
                    UiMessagePart(
                        type = "tool_call",
                        language = "search_web",
                        text = "{\"q\":\"weather\"}",
                    ),
                    UiMessagePart(
                        type = "tool_result",
                        language = "search_web",
                        text = "{\"items\":1}",
                    ),
                ),
            )
        )

        composeRule.onNodeWithText("工具调用: search_web").assertExists()
        composeRule.onNodeWithText("调用参数").assertExists()
        composeRule.onNodeWithText("{\"q\":\"weather\"}").assertExists()
        composeRule.onNodeWithText("工具结果: search_web").assertExists()
        composeRule.onNodeWithText("执行结果").assertExists()
        composeRule.onNodeWithText("{\"items\":1}").assertExists()
    }

    private fun render(message: ChatMessage) {
        composeRule.setContent {
            MaterialTheme {
                TypedPartsRenderer(
                    message = message,
                    textColor = Color.Black,
                )
            }
        }
    }
}
