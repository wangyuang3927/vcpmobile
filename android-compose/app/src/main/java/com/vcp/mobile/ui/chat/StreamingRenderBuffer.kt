package com.vcp.mobile.ui.chat

import com.vcp.mobile.data.network.AstStreamNode
import com.vcp.mobile.data.network.AstStreamParser
import com.vcp.mobile.domain.model.ast.MarkdownDocument

/**
 * generation-local 渲染缓冲：
 * - 归拢 AST 流节点
 * - 把 AST ownership 从 ChatViewModel 的裸局部变量中抽出来
 * - 不进入持久 state，先保持最小回归面
 */
class StreamingRenderBuffer {
    private var activeMessageKey: String? = null
    private val astNodes = mutableListOf<AstStreamNode>()

    fun onMessageKeyChanged(messageKey: String?) {
        if (messageKey == activeMessageKey) return
        activeMessageKey = messageKey
        astNodes.clear()
    }

    fun appendAst(messageKey: String, incoming: List<AstStreamNode>): MarkdownDocument? {
        onMessageKeyChanged(messageKey)
        if (incoming.isEmpty()) return currentDocumentOrNull()
        astNodes += incoming
        return currentDocumentOrNull()
    }

    fun currentDocumentFor(messageKey: String?): MarkdownDocument? {
        if (messageKey == null) return null
        if (messageKey != activeMessageKey) return null
        return currentDocumentOrNull()
    }

    fun clear() {
        activeMessageKey = null
        astNodes.clear()
    }

    private fun currentDocumentOrNull(): MarkdownDocument? {
        if (astNodes.isEmpty()) return null
        val document = AstStreamParser.toMarkdownDocument(astNodes)
        return document.takeIf { it.nodes.isNotEmpty() }
    }
}
