package net.paxtech.workspace

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import net.paxtech.workspace.data.ConversationPresence
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ConversationBylineTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun namesAlignAtLargeFontAndEachStatusUpdatesIndependently() {
        var presence by mutableStateOf(ConversationPresence(true, false))
        var narrow by mutableStateOf(false)
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.5f)) {
                WorkspaceTheme {
                    Box(Modifier.width(if (narrow) 240.dp else 600.dp)) {
                        ConversationByline(ConversationIdentity("Codex", "工作站", presence))
                    }
                }
            }
        }
        fun baseline(text: String): Float {
            val node = compose.onNodeWithText(text, useUnmergedTree = true)
            val layouts = mutableListOf<TextLayoutResult>()
            node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            return node.fetchSemanticsNode().boundsInRoot.top + layouts.single().firstBaseline
        }
        assertEquals(baseline("Codex"), baseline("工作站"), 1f)
        compose
            .onNode(
                SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Agent Online")
            )
            .assertIsDisplayed()
        compose
            .onNode(
                SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Device Offline")
            )
            .assertIsDisplayed()
        compose.runOnIdle {
            presence = ConversationPresence(null, true)
            narrow = true
        }
        compose
            .onNode(
                SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Agent Unknown")
            )
            .assertIsDisplayed()
        compose
            .onNode(
                SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Device Online")
            )
            .assertIsDisplayed()
        compose.onNodeWithText("工作站").assertIsDisplayed()
    }
}
