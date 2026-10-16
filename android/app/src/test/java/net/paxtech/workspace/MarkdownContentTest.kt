package net.paxtech.workspace

import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.parser.MarkdownParser
import org.junit.Assert.*
import org.junit.Test

class MarkdownContentTest {
    @Test
    fun codeInsideQuotesAndListsRemovesContainerMarkers() {
        listOf("> ```js\n>   run()\n> ```", "- Item\n\n  ```js\n    run()\n  ```").forEach { source
            ->
            assertEquals("  run()", code(source).code)
        }
    }

    private fun code(text: String): MarkdownCodeContent {
        val root = MarkdownParser(GFMFlavourDescriptor()).buildMarkdownTreeFromString(text)
        return markdownCode(
            text,
            markdownNodes(root).first {
                it.type == MarkdownElementTypes.CODE_FENCE ||
                    it.type == MarkdownElementTypes.CODE_BLOCK
            },
        )
    }

    @Test
    fun codePreservesIndentationUnicodeAndEmbeddedBackticks() {
        val content = "    print(\"你好 👋 ```\")\n        return 1"
        assertEquals(MarkdownCodeContent(content, "python"), code("````python\n$content\n````"))
        assertEquals(
            MarkdownCodeContent(content, "unknown-language"),
            code("~~~unknown-language\n$content\n~~~"),
        )
    }

    @Test
    fun everyStreamingFencePrefixPreservesItsLastCharacter() {
        val content = "    console.log('你好 👋');\n    done()"
        for (end in 1..content.length) {
            val chunk = content.take(end)
            assertEquals("Chunk $end", chunk.removeSuffix("\n"), code("```js\n$chunk").code)
        }
        assertEquals("", code("```js").code)
    }

    @Test
    fun indentedCodeStripsOnlyMarkdownIndentation() {
        assertEquals(
            "if (ready) {\n    run()\n}",
            code("    if (ready) {\n        run()\n    }").code,
        )
    }

    @Test
    fun linksRejectExecutableAndLocalSchemes() {
        listOf(
                "javascript:alert(1)",
                "intent://launch",
                "file:///private/data",
                "data:text/html,x",
                "content://secret",
                "//host/path",
                "https://user:password@example.com",
                "https://",
                "https://exa mple.com",
            )
            .forEach { assertNull(it, safeMarkdownLink(it)) }
        listOf(
                "https://example.com/a?q=1#b",
                "http://example.com",
                "mailto:hello@example.com",
                "HTTPS://example.com",
            )
            .forEach { assertEquals(it, safeMarkdownLink(it)) }
    }

    @Test
    fun imagesResolveInlineReferenceAndCollapsedReferenceForms() {
        listOf(
                "![Picture](https://example.com/image.png)",
                "![Picture][asset]",
                "![Picture][]",
                "![Picture]",
            )
            .forEach { source ->
                val root =
                    MarkdownParser(GFMFlavourDescriptor()).buildMarkdownTreeFromString(source)
                val image = markdownNodes(root).first { it.type == MarkdownElementTypes.IMAGE }
                assertEquals("Picture", markdownImageAlt(source, image))
                assertEquals(
                    "https://example.com/image.png",
                    markdownImageUrl(source, image) { label ->
                        assertTrue(label in listOf("[asset]", "[Picture]"))
                        "https://example.com/image.png"
                    },
                )
            }
    }
}
