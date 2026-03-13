package com.vcp.mobile.data.network

import com.vcp.mobile.domain.model.ast.MarkdownAstNode
import com.vcp.mobile.testing.RichContentSmokeFixtures
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AstStreamParserTest {

    @Test
    fun `parser maps rich markdown floor nodes into document ast`() {
        val document = AstStreamParser.parseJsonToDocument(
            """
            [
              {"type":"quote","text":"quoted context"},
              {"type":"task_list_item","text":"ship android parity","checked":true},
              {"type":"link","text":"spec","url":"https://example.com/spec"},
              {"type":"inline_code","text":"println(1)"},
              {"type":"math_block","text":"x^2 + y^2 = z^2"},
              {
                "type":"table",
                "headers":["Name","Value"],
                "rows":[["alpha","1"],["beta","2"]]
              }
            ]
            """.trimIndent()
        )

        assertEquals(
            listOf(
                MarkdownAstNode.Quote(content = "quoted context"),
                MarkdownAstNode.ListItem(
                    content = "ship android parity",
                    index = null,
                    checked = true,
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
                MarkdownAstNode.Table(
                    headers = listOf("Name", "Value"),
                    rows = listOf(
                        listOf("alpha", "1"),
                        listOf("beta", "2"),
                    ),
                ),
            ),
            document.nodes,
        )
    }

    @Test
    fun `parser keeps ordered lists and markdown table text readable`() {
        val document = AstStreamParser.toMarkdownDocument(
            listOf(
                AstStreamNode(type = "list_item", text = "first", ordered = true),
                AstStreamNode(type = "list_item", text = "second", ordered = true),
                AstStreamNode(
                    type = "table",
                    text = """
                        | Metric | Value |
                        | --- | --- |
                        | Pass | yes |
                    """.trimIndent(),
                ),
            )
        )

        assertEquals(
            listOf(
                MarkdownAstNode.ListItem(content = "first", index = 1, checked = null),
                MarkdownAstNode.ListItem(content = "second", index = 2, checked = null),
                MarkdownAstNode.Table(
                    headers = listOf("Metric", "Value"),
                    rows = listOf(listOf("Pass", "yes")),
                ),
            ),
            document.nodes,
        )
    }

    @Test
    fun `parser smoke fixture covers markdown parity floor constructs`() {
        val document = AstStreamParser.parseJsonToDocument(
            RichContentSmokeFixtures.markdownAstJson
        )

        assertEquals(
            RichContentSmokeFixtures.markdownAstExpectedNodes(),
            document.nodes,
        )
    }
}
