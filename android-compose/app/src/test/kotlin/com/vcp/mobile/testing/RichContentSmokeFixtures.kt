package com.vcp.mobile.testing

import com.vcp.mobile.domain.model.ast.MarkdownAstNode
import com.vcp.mobile.domain.model.ast.MarkdownDocument
import com.vcp.mobile.ui.chat.ChatMessage
import com.vcp.mobile.ui.chat.MessageSender
import com.vcp.mobile.ui.chat.UiMessagePart

object RichContentSmokeFixtures {

    private const val MARKDOWN_AST_FIXTURE = "fixtures/chat-rich-markdown-ast.json"
    private const val DOCUMENT_INGESTION_FIXTURE = "fixtures/chat-document-ingestion-parts.json"

    val markdownAstJson: String
        get() = loadTextFixture(MARKDOWN_AST_FIXTURE)

    val documentIngestionJson: String
        get() = loadTextFixture(DOCUMENT_INGESTION_FIXTURE)

    fun markdownAstExpectedNodes(): List<MarkdownAstNode> = listOf(
        MarkdownAstNode.Heading(level = 2, content = "Parity floor"),
        MarkdownAstNode.Text(content = "Markdown fixtures stay observable."),
        MarkdownAstNode.Quote(content = "quoted context"),
        MarkdownAstNode.ListItem(
            content = "ship android parity",
            index = null,
            checked = true,
        ),
        MarkdownAstNode.ListItem(
            content = "document prompt smoke",
            index = 1,
            checked = null,
        ),
        MarkdownAstNode.ListItem(
            content = "render smoke",
            index = 2,
            checked = null,
        ),
        MarkdownAstNode.Link(
            label = "spec",
            destination = "https://example.com/spec",
        ),
        MarkdownAstNode.InlineCode(content = "println(1)"),
        MarkdownAstNode.Math(
            expression = "x^2 + y^2 = z^2",
            isBlock = true,
        ),
        MarkdownAstNode.Code(
            content = "println(\"fixture\")",
            language = "kotlin",
        ),
        MarkdownAstNode.Table(
            headers = listOf("Type", "Observable"),
            rows = listOf(
                listOf("markdown", "yes"),
                listOf("document", "yes"),
            ),
        ),
    )

    fun supportedDocumentParts(): List<UiMessagePart> = listOf(
        UiMessagePart(
            type = "document",
            title = "notes.txt",
            url = "file:///fixtures/notes.txt",
            mime = "text/plain",
        ),
        UiMessagePart(
            type = "document",
            title = "guide.md",
            url = "file:///fixtures/guide.md",
            mime = "text/markdown",
        ),
        UiMessagePart(
            type = "document",
            title = "spec.pdf",
            url = "file:///fixtures/spec.pdf",
            mime = "application/pdf",
        ),
        UiMessagePart(
            type = "document",
            title = "brief.docx",
            url = "file:///fixtures/brief.docx",
            mime = "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        ),
        UiMessagePart(
            type = "document",
            title = "deck.pptx",
            url = "file:///fixtures/deck.pptx",
            mime = "application/vnd.openxmlformats-officedocument.presentationml.presentation",
        ),
    )

    fun compatibilitySmokeParts(): List<UiMessagePart> = listOf(
        UiMessagePart(type = "reasoning", text = "thinking through fixtures"),
        UiMessagePart(type = "markdown_block", text = "## Fixture\n- markdown\n- code"),
        UiMessagePart(
            type = "code_block",
            text = "println(\"fixture\")",
            language = "kotlin",
        ),
    ) + supportedDocumentParts()

    fun markdownRenderMessage(): ChatMessage = ChatMessage(
        id = "fixture-markdown",
        sender = MessageSender.AGENT,
        content = "## Fixture\n- markdown\n- code",
        ast = MarkdownDocument(markdownAstExpectedNodes()),
        nodeId = "node-fixture-markdown",
        variantId = "variant-fixture-markdown",
        parts = listOf(UiMessagePart(type = "markdown_block", text = "## Fixture\n- markdown\n- code")),
    )

    fun codeRenderMessage(): ChatMessage = ChatMessage(
        id = "fixture-code",
        sender = MessageSender.AGENT,
        content = "println(\"fixture\")",
        nodeId = "node-fixture-code",
        variantId = "variant-fixture-code",
        parts = listOf(
            UiMessagePart(
                type = "code_block",
                text = "println(\"fixture\")",
                language = "kotlin",
            ),
        ),
    )

    fun documentRenderMessage(): ChatMessage = ChatMessage(
        id = "fixture-document",
        sender = MessageSender.AGENT,
        content = "document ingestion fixture",
        nodeId = "node-fixture-document",
        variantId = "variant-fixture-document",
        parts = supportedDocumentParts(),
    )

    fun expectedCompatibilityContent(): String = buildString {
        append("## Fixture\n- markdown\n- code")
        append("```kotlin\nprintln(\"fixture\")\n```")
        supportedDocumentParts().forEach { part ->
            append(part.title)
            append('\n')
            append(part.url)
            append('\n')
            append(part.mime)
        }
    }

    private fun loadTextFixture(path: String): String {
        val url = checkNotNull(javaClass.classLoader?.getResource(path)) {
            "Missing test fixture: $path"
        }
        return url.readText()
    }
}
