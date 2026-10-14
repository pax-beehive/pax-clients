package net.paxtech.workspace

import androidx.compose.ui.graphics.Color
import kotlin.math.*

internal data class Vector3(val x: Float, val y: Float, val z: Float)

internal data class SatelliteFace(val points: List<Vector3>, val color: Color, val normal: Vector3)

internal fun satelliteMesh(kind: Int = 0): List<SatelliteFace> = buildList {
    fun face(color: Color, normal: Vector3, vararg points: Vector3) {
        add(SatelliteFace(points.toList(), color, normal))
    }
    fun box(
        x0: Float,
        x1: Float,
        y0: Float,
        y1: Float,
        z0: Float,
        z1: Float,
        color: Color,
        openFront: Boolean = false,
        openBack: Boolean = false,
    ) {
        if (!openFront)
            face(
                color,
                Vector3(0f, 0f, 1f),
                Vector3(x0, y0, z1),
                Vector3(x1, y0, z1),
                Vector3(x1, y1, z1),
                Vector3(x0, y1, z1),
            )
        if (!openBack)
            face(
                color,
                Vector3(0f, 0f, -1f),
                Vector3(x0, y0, z0),
                Vector3(x1, y0, z0),
                Vector3(x1, y1, z0),
                Vector3(x0, y1, z0),
            )
        face(
            color,
            Vector3(0f, 1f, 0f),
            Vector3(x0, y1, z0),
            Vector3(x1, y1, z0),
            Vector3(x1, y1, z1),
            Vector3(x0, y1, z1),
        )
        face(
            color,
            Vector3(0f, -1f, 0f),
            Vector3(x0, y0, z0),
            Vector3(x1, y0, z0),
            Vector3(x1, y0, z1),
            Vector3(x0, y0, z1),
        )
        face(
            color,
            Vector3(1f, 0f, 0f),
            Vector3(x1, y0, z0),
            Vector3(x1, y1, z0),
            Vector3(x1, y1, z1),
            Vector3(x1, y0, z1),
        )
        face(
            color,
            Vector3(-1f, 0f, 0f),
            Vector3(x0, y0, z0),
            Vector3(x0, y1, z0),
            Vector3(x0, y1, z1),
            Vector3(x0, y0, z1),
        )
    }
    val metal = Color(0xFFD1DCE9)
    val gold = if (kind == 2) Color(0xFFBDCADA) else Color(0xFFC7B991)
    box(-0.35f, 0.35f, -0.32f, 0.32f, -0.25f, 0.25f, gold)
    box(-0.36f, 0.36f, 0.27f, 0.34f, -0.26f, 0.27f, metal)
    box(-0.36f, 0.36f, -0.34f, -0.27f, -0.26f, 0.27f, metal)
    // Raised instrument housing stays visibly separate from the thermal shell.
    box(-0.19f, 0.19f, -0.19f, 0.22f, 0.26f, 0.36f, Color(0xFF344761))
    box(-0.55f, 0.55f, -0.035f, 0.035f, -0.035f, 0.035f, metal)
    if (kind != 1)
        for (side in listOf(-1, 1)) {
            val left = if (side == -1) -1.42f else 0.52f
            val right = left + 0.9f
            // Cells replace the front surface, preventing coplanar faces from sorting over them.
            box(
                left,
                right,
                -0.4f,
                0.4f,
                -0.035f,
                0.035f,
                Color(0xFF708197),
                openFront = true,
                openBack = true,
            )
            repeat(5) { column ->
                repeat(4) { row ->
                    val x = left + column * 0.18f
                    val y = -0.4f + row * 0.2f
                    val color = if (row % 2 == 0) Color(0xFF29445D) else Color(0xFF223D56)
                    face(
                        color,
                        Vector3(0f, 0f, 1f),
                        Vector3(x + 0.012f, y + 0.008f, 0.037f),
                        Vector3(x + 0.168f, y + 0.008f, 0.037f),
                        Vector3(x + 0.168f, y + 0.192f, 0.037f),
                        Vector3(x + 0.012f, y + 0.192f, 0.037f),
                    )
                    face(
                        Color(0xFF718495),
                        Vector3(0f, 0f, -1f),
                        Vector3(x + 0.008f, y + 0.006f, -0.037f),
                        Vector3(x + 0.172f, y + 0.006f, -0.037f),
                        Vector3(x + 0.172f, y + 0.194f, -0.037f),
                        Vector3(x + 0.008f, y + 0.194f, -0.037f),
                    )
                }
            }
        }
    if (kind == 1) {
        // CubeSat: compact bus, dark front face and four long antenna booms.
        for (x in listOf(-0.3f, 0.3f)) for (y in listOf(-1, 1)) {
            box(
                x - 0.015f,
                x + 0.015f,
                if (y < 0) -0.9f else 0.3f,
                if (y < 0) -0.3f else 0.9f,
                0f,
                0.03f,
                metal,
            )
        }
    }
    if (kind == 2) {
        // Observation satellite: raised optical instrument rather than a dish.
        box(-0.23f, 0.23f, -0.23f, 0.23f, 0.35f, 0.62f, metal)
        box(-0.18f, 0.18f, -0.18f, 0.18f, 0.62f, 0.64f, Color(0xFF132940))
    }
    if (kind == 0) {
        // Dish with a recessed center, raised rim and central feed.
        box(-0.045f, 0.045f, 0.05f, 0.37f, 0.25f, 0.49f, metal)
        repeat(32) { index ->
            val a = index * 2f * PI.toFloat() / 32f
            val b = (index + 1) * 2f * PI.toFloat() / 32f
            val center = Vector3(0f, 0.29f, 0.49f)
            val p = Vector3(cos(a) * 0.28f, 0.29f + sin(a) * 0.28f, 0.67f)
            val q = Vector3(cos(b) * 0.28f, 0.29f + sin(b) * 0.28f, 0.67f)
            face(Color(0xFFD2E3F5), Vector3(-cos(a) * 0.4f, -sin(a) * 0.4f, 0.8f), center, p, q)
        }
        box(-0.018f, 0.018f, 0.27f, 0.31f, 0.5f, 0.81f, gold)
        box(-0.05f, 0.05f, 0.24f, 0.34f, 0.80f, 0.84f, metal)
    }
    box(0.2f, 0.22f, 0.34f, 0.77f, -0.17f, -0.15f, metal)
    box(0.16f, 0.26f, 0.76f, 0.80f, -0.2f, -0.12f, gold)
}
