package net.paxtech.workspace

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.*
import kotlin.math.*
import kotlin.random.Random

internal fun Vector3.dot(other: Vector3) = x * other.x + y * other.y + z * other.z

internal fun Vector3.unit(): Vector3 {
    val length = sqrt(dot(this)).coerceAtLeast(.000001f)
    return Vector3(x / length, y / length, z / length)
}

internal fun Vector3.cross(v: Vector3) =
    Vector3(y * v.z - z * v.y, z * v.x - x * v.z, x * v.y - y * v.x)

internal data class MoonPose(
    val position: Vector3,
    val right: Vector3,
    val north: Vector3,
    val nearSide: Vector3,
) {
    fun surface(normal: Vector3) =
        Vector3(
            right.x * normal.x + north.x * normal.y + nearSide.x * normal.z,
            right.y * normal.x + north.y * normal.y + nearSide.y * normal.z,
            right.z * normal.x + north.z * normal.y + nearSide.z * normal.z,
        )
}

/** Compressed circular orbit for the welcome composition; the near-side axis always faces Earth. */
internal fun moonPose(seconds: Float): MoonPose {
    val angle = .45f + seconds / 80f * (2 * PI).toFloat()
    val inclination = 1.05f
    val heading = -.32f
    val u = Vector3(cos(heading), sin(heading), 0f)
    val v =
        Vector3(-sin(heading) * cos(inclination), cos(heading) * cos(inclination), sin(inclination))
    val radial =
        Vector3(
            u.x * cos(angle) + v.x * sin(angle),
            u.y * cos(angle) + v.y * sin(angle),
            v.z * sin(angle),
        )
    val right =
        Vector3(
            u.x * sin(angle) - v.x * cos(angle),
            u.y * sin(angle) - v.y * cos(angle),
            -v.z * cos(angle),
        )
    return MoonPose(
        Vector3(radial.x * 3.1f, radial.y * 3.1f, radial.z * 3.1f),
        right,
        u.cross(v),
        Vector3(-radial.x, -radial.y, -radial.z),
    )
}

private data class MoonMark(val normal: Vector3, val radius: Float, val mare: Boolean)

// Stable, illustrative lunar terrain. This is not a geographic Moon map.
private val moonMarks =
    Random(1969).let { random ->
        List(74) { index ->
            val y = random.nextFloat() * 1.8f - .9f
            val longitude = random.nextFloat() * (2 * PI).toFloat()
            val r = sqrt(1f - y * y)
            MoonMark(
                Vector3(r * sin(longitude), y, r * cos(longitude)),
                if (index < 8) .16f + random.nextFloat() * .16f
                else .025f + random.nextFloat() * .065f,
                index < 8,
            )
        }
    }

internal fun DrawScope.drawMoon(
    pose: MoonPose,
    center: Offset,
    radius: Float,
    sunlight: Vector3,
    alpha: Float,
) {
    val view = Vector3(-pose.position.x, -pose.position.y, 10f - pose.position.z).unit()
    val right = Vector3(0f, 1f, 0f).cross(view).unit()
    val up = view.cross(right)
    // SolarFrame's light uses screen-down Y; local orbital geometry uses Y up.
    val light = Vector3(sunlight.x, -sunlight.y, sunlight.z).unit()
    val lx = light.dot(right)
    val ly = -light.dot(up)
    val phase = light.dot(view).coerceIn(-1f, 1f)
    val angle = atan2(ly, lx)
    val disc =
        Path().apply {
            addOval(Rect(center - Offset(radius, radius), Size(radius * 2, radius * 2)))
        }
    clipPath(disc) {
        drawCircle(Color(0xFFBCBDB9).copy(alpha = alpha), radius, center)
        moonMarks.forEach { mark ->
            val normal = pose.surface(mark.normal)
            val facing = normal.dot(view)
            if (facing <= .03f) return@forEach
            val p = center + Offset(normal.dot(right), -normal.dot(up)) * radius
            val r = mark.radius * radius
            val rotation = atan2(p.y - center.y, p.x - center.x) * 180f / PI.toFloat()
            rotate(rotation, p) {
                drawOval(
                    Color(0xFF656B70).copy(alpha = alpha * if (mark.mare) .35f else .30f),
                    p - Offset(r * facing, r),
                    Size(r * facing * 2, r * 2),
                )
                if (!mark.mare)
                    drawOval(
                        Color(0xFFE8E6DE).copy(alpha = alpha * .32f),
                        p - Offset(r * facing, r),
                        Size(r * facing * 2, r * 2),
                        style = Stroke((radius * .009f).coerceAtLeast(.35f)),
                    )
            }
        }
        // The shadow follows the same Sun as Earth, independently of the tidally locked texture.
        fun point(x: Float, y: Float) =
            center +
                Offset(x * cos(angle) - y * sin(angle), x * sin(angle) + y * cos(angle)) * radius
        val night =
            Path().apply {
                for (i in 0..64) {
                    val y = -1f + i / 32f
                    val p = point(-sqrt((1f - y * y).coerceAtLeast(0f)), y)
                    if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y)
                }
                for (i in 64 downTo 0) {
                    val y = -1f + i / 32f
                    val p = point(-phase * sqrt((1f - y * y).coerceAtLeast(0f)), y)
                    lineTo(p.x, p.y)
                }
                close()
            }
        drawPath(night, Color(0xFF03070D).copy(alpha = alpha * .95f))
        drawCircle(
            Brush.radialGradient(
                listOf(Color.Transparent, Color.Black.copy(alpha = .42f * alpha)),
                center,
                radius,
            ),
            radius,
            center,
        )
    }
}
