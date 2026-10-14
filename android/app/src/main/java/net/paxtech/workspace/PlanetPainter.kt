package net.paxtech.workspace

import android.content.Context
import android.graphics.*
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import kotlin.math.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A single GPU sphere, with a cached-texture, dynamic day/night fallback before API 33. */
internal interface PlanetPainter {
    fun draw(
        canvas: Canvas,
        x: Float,
        y: Float,
        radius: Float,
        time: Float,
        alpha: Float,
        sunlight: Vector3 = Vector3(-.72f, -.47f, .48f),
    )
}

internal data class EarthTextures(val day: Bitmap, val night: Bitmap, val clouds: Bitmap)

internal fun loadEarthTextures(context: Context): EarthTextures {
    fun load(name: String): Bitmap {
        val options =
            BitmapFactory.Options().apply {
                inJustDecodeBounds = true
                inSampleSize = 1
            }
        context.assets.open(name).use { BitmapFactory.decodeStream(it, null, options) }
        options.inJustDecodeBounds = false
        // Bound transient decode memory, then keep at most a 2048 x 1024 texture on the GPU.
        while (options.outWidth / options.inSampleSize > 4096) options.inSampleSize *= 2
        val decoded =
            context.assets.open(name).use { BitmapFactory.decodeStream(it, null, options)!! }
        if (decoded.width <= 2048) return decoded
        return Bitmap.createScaledBitmap(decoded, 2048, 1024, true).also { decoded.recycle() }
    }
    return EarthTextures(load("earth-day.jpg"), load("earth-night.jpg"), load("earth-clouds.jpg"))
}

@Composable
internal fun rememberPlanetPainter(forceSoftware: Boolean = false): PlanetPainter? {
    val context = LocalContext.current.applicationContext
    val painter by
        produceState<PlanetPainter?>(null, forceSoftware) {
            val textures = withContext(Dispatchers.IO) { loadEarthTextures(context) }
            value =
                if (Build.VERSION.SDK_INT >= 33 && !forceSoftware) ShaderPlanetPainter(textures)
                else
                    withContext(Dispatchers.Default) {
                        val result =
                            BitmapPlanetPainter(
                                bakeEarth(textures, 512, false),
                                bakeEarth(textures, 512, true),
                            )
                        textures.day.recycle()
                        textures.night.recycle()
                        textures.clouds.recycle()
                        result
                    }
        }
    return painter
}

@RequiresApi(33)
internal class ShaderPlanetPainter(textures: EarthTextures) : PlanetPainter {
    private val shader =
        RuntimeShader(PLANET_SHADER).apply {
            fun bind(name: String, bitmap: Bitmap) {
                setInputBuffer(
                    name,
                    BitmapShader(bitmap, Shader.TileMode.REPEAT, Shader.TileMode.CLAMP).apply {
                        setFilterMode(BitmapShader.FILTER_MODE_LINEAR)
                    },
                )
                setFloatUniform(name + "Size", bitmap.width.toFloat(), bitmap.height.toFloat())
            }
            bind("dayMap", textures.day)
            bind("nightMap", textures.night)
            bind("cloudMap", textures.clouds)
        }
    private val paint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply { this.shader = this@ShaderPlanetPainter.shader }

    override fun draw(
        canvas: Canvas,
        x: Float,
        y: Float,
        radius: Float,
        time: Float,
        alpha: Float,
        sunlight: Vector3,
    ) {
        shader.setFloatUniform("origin", x, y)
        shader.setFloatUniform("radius", radius)
        shader.setFloatUniform("time", time)
        shader.setFloatUniform("opacity", alpha)
        shader.setFloatUniform("lightDirection", sunlight.x, sunlight.y, sunlight.z)
        canvas.drawRect(
            x - radius * 1.15f,
            y - radius * 1.15f,
            x + radius * 1.15f,
            y + radius * 1.15f,
            paint,
        )
    }
}

private const val PLANET_SHADER =
    """
uniform float2 origin;
uniform float radius;
uniform float time;
uniform float opacity;
uniform float3 lightDirection;
uniform shader dayMap;
uniform shader nightMap;
uniform shader cloudMap;
uniform float2 dayMapSize;
uniform float2 nightMapSize;
uniform float2 cloudMapSize;
float2 coordinates(float3 n, float rotation) {
    float3 tilted=float3(n.x,n.y*cos(0.28)-n.z*sin(0.28),n.y*sin(0.28)+n.z*cos(0.28));
    float a=1.6-rotation; // Prograde spin: surface features move eastward.
    float3 w=float3(tilted.x*cos(a)+tilted.z*sin(a),tilted.y,-tilted.x*sin(a)+tilted.z*cos(a));
    return float2(atan(w.x,w.z)/6.2831853+0.5,asin(clamp(w.y,-1.0,1.0))/3.1415927+0.5);
}
half4 main(float2 position) {
    float2 p=(position-origin)/radius;
    float rr=dot(p,p), distance=sqrt(rr);
    float3 light=normalize(lightDirection);
    if(distance>1.0) {
        float sun=max(0.0,dot(normalize(float3(p,0.08)),light));
        float glow=(exp(-(distance-1.0)*65.0)*0.26+exp(-(distance-1.0)*22.0)*0.025)*(0.035+sun);
        float a=glow*opacity*(1.0-smoothstep(1.08,1.15,distance));
        return half4(float3(0.22,0.52,1.0)*a,a);
    }
    float3 n=float3(p,sqrt(max(0.0,1.0-rr)));
    float2 uv=coordinates(n,time);
    float3 albedo=dayMap.eval(uv*dayMapSize).rgb;
    float incidence=dot(n,light);
    float day=max(0.0,incidence);
    float night=1.0-smoothstep(-0.12,0.06,incidence);
    float clouds=cloudMap.eval(coordinates(n,time+0.008)*cloudMapSize).r;
    float veil=pow(clouds,1.35)*0.86;
    float3 color=albedo*(0.006+1.12*day);
    color=mix(color,float3(0.89,0.94,1.0)*(0.003+1.04*day),veil);
    float lamp=nightMap.eval(uv*nightMapSize).r;
    float cities=pow(max(0.0,lamp-0.012),0.65)*1.45;
    color+=float3(1.0,0.69,0.34)*cities*night*(1.0-veil*0.65);
    // Ocean glints only where the blue albedo indicates water, never on the unlit hemisphere.
    float water=smoothstep(0.015,0.045,albedo.b-max(albedo.r,albedo.g));
    float spec=pow(max(0.0,dot(reflect(-light,n),float3(0,0,1))),60.0);
    color+=float3(0.45,0.61,0.8)*spec*water*(1.0-veil)*day*0.35;
    float rim=pow(1.0-n.z,4.0);
    color+=float3(0.10,0.30,0.68)*rim*(0.012+0.65*smoothstep(-0.10,0.35,incidence));
    float a=(1.0-smoothstep(0.996,1.0,distance))*opacity;
    return half4(clamp(color,0.0,1.0)*a,a);
}
"""

/** Static geography on older GPUs, with a moving solar terminator and real night-light texture. */
private class BitmapPlanetPainter(private val day: Bitmap, private val night: Bitmap) :
    PlanetPainter {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val destination = RectF()
    private val terminator = Path()

    override fun draw(
        canvas: Canvas,
        x: Float,
        y: Float,
        radius: Float,
        time: Float,
        alpha: Float,
        sunlight: Vector3,
    ) {
        destination.set(x - radius, y - radius, x + radius, y + radius)
        paint.alpha = (alpha * 255).toInt().coerceIn(0, 255)
        canvas.drawBitmap(day, null, destination, paint)
        val length =
            sqrt(sunlight.x * sunlight.x + sunlight.y * sunlight.y + sunlight.z * sunlight.z)
        val phase = (sunlight.z / length).coerceIn(-1f, 1f)
        val angle = atan2(sunlight.y, sunlight.x)
        terminator.reset()
        fun point(px: Float, py: Float, start: Boolean = false) {
            val sx = x + radius * (px * cos(angle) - py * sin(angle))
            val sy = y + radius * (px * sin(angle) + py * cos(angle))
            if (start) terminator.moveTo(sx, sy) else terminator.lineTo(sx, sy)
        }
        for (step in 0..96) {
            val py = -1f + step / 48f
            point(-sqrt((1 - py * py).coerceAtLeast(0f)), py, step == 0)
        }
        for (step in 96 downTo 0) {
            val py = -1f + step / 48f
            point(-phase * sqrt((1 - py * py).coerceAtLeast(0f)), py)
        }
        terminator.close()
        canvas.save()
        canvas.clipPath(terminator)
        canvas.drawBitmap(night, null, destination, paint)
        canvas.restore()
    }
}

/** Project the real maps once off the main thread; never do per-pixel work during a Canvas draw. */
internal fun bakeEarth(textures: EarthTextures, size: Int, night: Boolean): Bitmap {
    class Map(val bitmap: Bitmap) {
        val pixels =
            IntArray(bitmap.width * bitmap.height).also {
                bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            }

        fun sample(u: Double, v: Double): Int =
            pixels[
                (v * bitmap.height).toInt().coerceIn(0, bitmap.height - 1) * bitmap.width +
                    (u * bitmap.width).toInt().mod(bitmap.width)]
    }
    val dayMap = Map(textures.day)
    val nightMap = Map(textures.night)
    val cloudMap = Map(textures.clouds)
    val pixels = IntArray(size * size)
    fun channel(v: Double) = (v.coerceIn(0.0, 1.0) * 255).roundToInt()
    for (y in 0 until size) for (x in 0 until size) {
        val px = (x + .5 - size / 2.0) / (size / 2.0)
        val py = (y + .5 - size / 2.0) / (size / 2.0)
        val rr = px * px + py * py
        if (rr >= 1) continue
        val z = sqrt(1 - rr)
        val wy = py * cos(.28) - z * sin(.28)
        val wz = py * sin(.28) + z * cos(.28)
        val u = atan2(px * cos(1.6) + wz * sin(1.6), -px * sin(1.6) + wz * cos(1.6)) / (2 * PI) + .5
        val v = asin(wy) / PI + .5
        val data = dayMap.sample(u, v)
        val cloud = (Color.red(cloudMap.sample(u - .008 / (2 * PI), v)) / 255.0).pow(1.35) * .86
        val lamp =
            (max(0.0, Color.red(nightMap.sample(u, v)) / 255.0 - .012)).pow(.65) *
                1.45 *
                (1 - cloud * .65)
        val shade = .45 + .55 * z
        fun color(base: Int, tint: Double): Int =
            channel(
                if (night) base / 255.0 * .006 + lamp * tint
                else (base / 255.0 * (1 - cloud) + cloud * .95) * shade
            )
        pixels[y * size + x] =
            Color.argb(
                255,
                color(Color.red(data), 1.0),
                color(Color.green(data), .69),
                color(Color.blue(data), .34),
            )
    }
    return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
}
