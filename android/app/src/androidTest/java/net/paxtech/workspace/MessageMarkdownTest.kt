package net.paxtech.workspace

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import net.paxtech.workspace.session.ChatItem
import org.junit.*

class MessageMarkdownTest {
    @get:Rule val compose = createComposeRule()

    @Before
    fun isolatedDeviceOnly() {
        Assume.assumeTrue(
            Build.HARDWARE in listOf("ranchu", "goldfish") &&
                InstrumentationRegistry.getArguments().getString("paxFixtureTests") == "true"
        )
    }

    private fun awaitText(text: String) {
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun richReplyHasOnlyCopyIconAndCopiesExactMarkdown() {
        val source =
            "# Release notes\n\n**Ready** with *emphasis*, ~~removed~~ and `inline code`.\n\n- [x] Complete\n- [ ] Pending\n\n> Quoted response\n\n[Documentation](https://example.com)\n\n---"
        compose.setContent { WorkspaceTheme { Surface { MessageMarkdown(source) } } }
        awaitText("Release notes")
        compose.onNodeWithText("Ready with emphasis", substring = true).assertExists()
        compose.onNodeWithText("Release notes").assertExists()
        compose.onNodeWithText("Quoted response", substring = true).assertExists()
        compose.onNodeWithText("Documentation").assertExists()
        compose.onNodeWithText("-").assertDoesNotExist()
        compose.onNodeWithText("[x]").assertDoesNotExist()
        compose.onNodeWithText(">").assertDoesNotExist()
        compose.onNodeWithText("Source").assertDoesNotExist()
        compose.onNodeWithText("Copy message").assertDoesNotExist()
        compose.onNodeWithContentDescription("Copy message").performClick()
        compose.onNodeWithContentDescription("Message copied").assertExists()
        assertClipboard(source)
    }

    @Test
    fun streamingCodeAndReplacementNeverKeepStaleContent() {
        val text = mutableStateOf("```kotlin\nval answer = 1")
        compose.setContent { WorkspaceTheme { Surface { MessageMarkdown(text.value) } } }
        awaitText("val answer = 1")
        compose.runOnIdle { text.value = "```kotlin\nval answer = 12\nprintln(\"你好 👋\")" }
        awaitText("val answer = 12\nprintln(\"你好 👋\")")
        compose.onNodeWithText("Copy code").performClick()
        compose.onNodeWithText("Copied").assertExists()
        assertClipboard("val answer = 12\nprintln(\"你好 👋\")")
        compose.runOnIdle { text.value = "## Replacement\n\nFinal answer" }
        awaitText("Replacement")
        compose.onNodeWithText("Final answer").assertExists()
        compose.onNodeWithText("val answer", substring = true).assertDoesNotExist()
    }

    @Test
    fun extensionsRemainReadableAndImagesRequireATap() {
        val source =
            "Math: ${'$'}x^2 + y^2${'$'}\n\n${'$'}${'$'}\nE = mc^2\n${'$'}${'$'}\n\n<span>Raw HTML</span>\n\n![Preview][asset]\n\n[asset]: https://example.invalid/image.png\n\n```mermaid\ngraph LR; A-->B\n```"
        compose.setContent {
            WorkspaceTheme {
                Surface {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        MessageMarkdown(source)
                    }
                }
            }
        }
        awaitText("Load image")
        compose.onNodeWithText("Preview").assertExists()
        compose
            .onNodeWithText("Math:", substring = true)
            .assertTextContains("x^2 + y^2", substring = true)
        compose.onNodeWithText("E = mc^2", substring = true).assertExists()
        compose.onNodeWithText("Raw HTML", substring = true).assertExists()
        compose.onNodeWithContentDescription("Preview").assertDoesNotExist()
        compose.onNodeWithText("graph LR; A-->B").assertExists()
    }

    @Test fun phoneGallery() = gallery(380, 1f, "markdown-phone")

    @Test fun foldableGallery() = gallery(760, 1f, "markdown-foldable")

    @Test fun largeTextGallery() = gallery(380, 1.5f, "markdown-large-text")

    @Test fun numberedPhoneGallery() = numberedGallery(380, 1f, "numbered-phone")

    @Test fun numberedFoldableGallery() = numberedGallery(760, 1f, "numbered-foldable")

    @Test fun numberedLargeTextGallery() = numberedGallery(380, 1.5f, "numbered-large-text")

    private fun numberedGallery(width: Int, fontScale: Float, name: String) {
        val first = "这是一条会自动换行的编号内容，用来确认第二行与正文左边缘对齐。"
        val source =
            "## 编号列表\n\n9. $first\n10. ### 第二项标题\n    正文可以包含不同字号和多段内容。\n\n    1. 嵌套第一项\n    2. 嵌套第二项\n11. 最后一项\n"
        compose.setContent {
            WorkspaceTheme {
                CompositionLocalProvider(
                    LocalDensity provides Density(LocalDensity.current.density, fontScale)
                ) {
                    Surface(Modifier.width(width.dp).fillMaxHeight()) {
                        Column(Modifier.verticalScroll(rememberScrollState()).padding(12.dp)) {
                            MessageCard(ChatItem("numbered", "turn", "agent", source))
                        }
                    }
                }
            }
        }
        awaitText("编号列表")
        fun textNode(text: String) = compose.onNodeWithText(text, useUnmergedTree = true)
        fun baseline(text: String): Float {
            val node = textNode(text)
            val layouts = mutableListOf<TextLayoutResult>()
            node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            return node.fetchSemanticsNode().boundsInRoot.top + layouts.single().firstBaseline
        }
        Assert.assertEquals(baseline("9."), baseline(first), 1.5f)
        Assert.assertEquals(baseline("10."), baseline("第二项标题"), 1.5f)
        Assert.assertEquals(baseline("1."), baseline("嵌套第一项"), 1.5f)
        Assert.assertEquals(
            textNode(first).fetchSemanticsNode().boundsInRoot.left,
            textNode("第二项标题").fetchSemanticsNode().boundsInRoot.left,
            1.5f,
        )
        Assert.assertEquals(
            textNode(first).fetchSemanticsNode().boundsInRoot.left,
            textNode("最后一项").fetchSemanticsNode().boundsInRoot.left,
            1.5f,
        )
        capture(name)
    }

    private fun gallery(width: Int, fontScale: Float, name: String) {
        val source =
            "## Markdown 回复\n\n**重点清楚**，支持 *强调*、~~删除线~~ 与 `inline code`。\n\n- 普通列表\n- [x] 已完成的任务\n\n> 引用内容保留层次。\n\n| Feature | Status |\n| --- | --- |\n| Long table cells wrap without losing their final words | Supported |\n\n```kotlin\nfun greet(name: String) {\n    println(\"Hello, ${'$'}name 👋\")\n}\n```\n\n[Read documentation](https://example.com)"
        compose.setContent {
            WorkspaceTheme {
                CompositionLocalProvider(
                    LocalDensity provides Density(LocalDensity.current.density, fontScale)
                ) {
                    Surface(Modifier.width(width.dp).fillMaxHeight()) {
                        Column(Modifier.verticalScroll(rememberScrollState()).padding(12.dp)) {
                            MessageCard(
                                ChatItem("user", "turn", "user", "请把结果整理成 Markdown，包含表格和代码。")
                            )
                            Spacer(Modifier.height(12.dp))
                            MessageCard(ChatItem("agent", "turn", "agent", source))
                        }
                    }
                }
            }
        }
        awaitText("Copy code")
        compose
            .onNodeWithText(
                "Long table cells wrap without losing their final words",
                substring = true,
            )
            .assertExists()
        capture(name)
        compose.onNodeWithText("Read documentation").performScrollTo()
        capture("$name-code")
    }

    private fun capture(name: String) {
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val folder =
            File(context.externalMediaDirs.first(), "additional_test_output").apply { mkdirs() }
        File(folder, "$name.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    private fun assertClipboard(expected: String) {
        compose.runOnIdle {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val clipboard =
                context.getSystemService(Context.CLIPBOARD_SERVICE)
                    as android.content.ClipboardManager
            Assert.assertEquals(expected, clipboard.primaryClip?.getItemAt(0)?.text?.toString())
        }
    }
}
