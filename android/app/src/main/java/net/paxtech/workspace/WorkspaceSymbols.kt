package net.paxtech.workspace

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/** Small, consistent outline symbols for workspace concepts. */
internal object WorkspaceSymbols {
    private fun symbol(name: String, draw: PathBuilder.() -> Unit) =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
            .apply {
                path(
                    fill = null,
                    stroke = SolidColor(Color.Black),
                    strokeLineWidth = 1.7f,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                    pathBuilder = draw,
                )
            }
            .build()

    val Computer =
        symbol("Computer") {
            moveTo(4f, 4f)
            lineTo(20f, 4f)
            lineTo(20f, 16f)
            lineTo(4f, 16f)
            close()
            moveTo(12f, 16f)
            lineTo(12f, 20f)
            moveTo(8f, 20f)
            lineTo(16f, 20f)
        }
    val Folder =
        symbol("Folder") {
            moveTo(3f, 7f)
            lineTo(3f, 19f)
            lineTo(21f, 19f)
            lineTo(21f, 7f)
            lineTo(12f, 7f)
            lineTo(10f, 4f)
            lineTo(3f, 4f)
            close()
        }
    val Agent =
        symbol("Agent") {
            moveTo(8f, 4f)
            lineTo(4f, 4f)
            lineTo(4f, 8f)
            moveTo(16f, 4f)
            lineTo(20f, 4f)
            lineTo(20f, 8f)
            moveTo(4f, 16f)
            lineTo(4f, 20f)
            lineTo(8f, 20f)
            moveTo(20f, 16f)
            lineTo(20f, 20f)
            lineTo(16f, 20f)
            moveTo(8f, 12f)
            lineTo(16f, 12f)
            moveTo(12f, 8f)
            lineTo(12f, 16f)
        }
    val Send =
        symbol("Send") {
            moveTo(12f, 19f)
            lineTo(12f, 5f)
            moveTo(6f, 11f)
            lineTo(12f, 5f)
            lineTo(18f, 11f)
        }
    val SignOut =
        symbol("SignOut") {
            moveTo(9f, 4f)
            lineTo(4f, 4f)
            lineTo(4f, 20f)
            lineTo(9f, 20f)
            moveTo(10f, 12f)
            lineTo(21f, 12f)
            moveTo(17f, 8f)
            lineTo(21f, 12f)
            lineTo(17f, 16f)
        }
}
