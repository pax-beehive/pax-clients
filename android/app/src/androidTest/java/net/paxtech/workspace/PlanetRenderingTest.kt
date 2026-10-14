package net.paxtech.workspace

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.*
import org.junit.Assert.*

/** Exercises the actual hardware Canvas and the pre-33 fallback, without account state. */
class PlanetRenderingTest {
    @get:Rule val compose = createComposeRule()

    @Before
    fun isolatedDeviceOnly() {
        Assume.assumeTrue(
            Build.HARDWARE in listOf("ranchu", "goldfish") &&
                InstrumentationRegistry.getArguments().getString("paxFixtureTests") == "true"
        )
    }

    @Test
    fun shaderHasLitSurfaceAndRotates() {
        Assume.assumeTrue(Build.VERSION.SDK_INT >= 33)
        val time = mutableFloatStateOf(0f)
        showPlanet(false, time)
        val first = capture("planet-shader")
        assertLitSphere(first)
        compose.runOnIdle { time.floatValue = 1.2f }
        val second = capture("planet-rotated")
        var changed = 0
        for (y in first.height / 3 until first.height * 2 / 3 step 4) {
            for (x in first.width / 3 until first.width * 2 / 3 step 4) {
                if (first.getPixel(x, y) != second.getPixel(x, y)) changed++
            }
        }
        assertTrue("Rotation must change the visible terrain", changed > 100)
    }

    @Test
    fun fallbackHasLitSurfaceWithoutRuntimeShader() {
        showPlanet(true, mutableFloatStateOf(0f))
        assertLitSphere(capture("planet-fallback"))
    }

    private fun showPlanet(software: Boolean, time: State<Float>) {
        var ready = false
        compose.setContent {
            val painter = rememberPlanetPainter(forceSoftware = software)
            SideEffect { ready = painter != null }
            Canvas(Modifier.size(300.dp).testTag("planet")) {
                drawRect(Color.Black)
                painter?.let { p ->
                    drawIntoCanvas {
                        p.draw(
                            it.nativeCanvas,
                            center.x,
                            center.y,
                            size.minDimension * .35f,
                            time.value,
                            1f,
                        )
                    }
                }
            }
        }
        compose.waitUntil(15_000) { ready }
        compose.waitForIdle()
    }

    private fun capture(name: String): Bitmap {
        val bitmap = compose.onNodeWithTag("planet").captureToImage().asAndroidBitmap()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val folder = File(context.externalMediaDirs.first(), "additional_test_output").apply { mkdirs() }
        File(folder, "$name.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        return bitmap
    }

    private fun assertLitSphere(bitmap: Bitmap) {
        fun brightness(x: Int, y: Int): Int {
            val c = bitmap.getPixel(x, y)
            return android.graphics.Color.red(c) +
                android.graphics.Color.green(c) +
                android.graphics.Color.blue(c)
        }
        var lit = 0L
        var dark = 0L
        for (y in bitmap.height / 3 until bitmap.height / 2 step 3) {
            for (x in bitmap.width / 3 until bitmap.width / 2 step 3) {
                lit += brightness(x, y)
                dark += brightness(bitmap.width - x - 1, bitmap.height - y - 1)
            }
        }
        assertTrue("The lit hemisphere must have visible detail", lit > 10000)
        assertTrue("The sphere must retain day/night depth", lit > dark * 1.3)
        assertEquals("No rectangular texture border", 0, brightness(2, 2))
    }
}
