package net.paxtech.workspace

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import kotlin.math.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** One GPU draw for the sphere; older Android releases use a cached, shaded still. */
internal interface PlanetPainter {
    fun draw(canvas: Canvas, x: Float, y: Float, radius: Float, time: Float, alpha: Float)
}

@Composable
internal fun rememberPlanetPainter(forceSoftware: Boolean = false): PlanetPainter? {
    val context = LocalContext.current.applicationContext
    val painter by
        produceState<PlanetPainter?>(null, forceSoftware) {
            val fields =
                withContext(Dispatchers.IO) {
                    context.assets.open("planet-fields.png").use {
                        BitmapFactory.decodeStream(it)!!
                    }
                }
            value =
                if (Build.VERSION.SDK_INT >= 33 && !forceSoftware) {
                    ShaderPlanetPainter(fields)
                } else
                    withContext(Dispatchers.Default) {
                        BitmapPlanetPainter(bakePlanet(fields, 512))
                    }
        }
    return painter
}

@RequiresApi(33)
internal class ShaderPlanetPainter(fields: Bitmap) : PlanetPainter {
    private val shader =
        RuntimeShader(PLANET_SHADER).apply {
            setInputBuffer(
                "geography",
                BitmapShader(fields, Shader.TileMode.REPEAT, Shader.TileMode.CLAMP).apply {
                    setFilterMode(BitmapShader.FILTER_MODE_LINEAR)
                },
            )
            setFloatUniform("mapSize", fields.width.toFloat(), fields.height.toFloat())
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
    ) {
        shader.setFloatUniform("origin", x, y)
        shader.setFloatUniform("radius", radius)
        shader.setFloatUniform("time", time)
        shader.setFloatUniform("opacity", alpha)
        canvas.drawRect(
            x - radius * 1.28f,
            y - radius * 1.28f,
            x + radius * 1.28f,
            y + radius * 1.28f,
            paint,
        )
    }
}

private class BitmapPlanetPainter(private val bitmap: Bitmap) : PlanetPainter {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val destination = RectF()

    override fun draw(
        canvas: Canvas,
        x: Float,
        y: Float,
        radius: Float,
        time: Float,
        alpha: Float,
    ) {
        paint.alpha = (alpha * 255).toInt().coerceIn(0, 255)
        destination.set(
            x - radius * 1.28f,
            y - radius * 1.28f,
            x + radius * 1.28f,
            y + radius * 1.28f,
        )
        canvas.drawBitmap(bitmap, null, destination, paint)
    }
}

// Baked seamless fields keep per-frame work bounded: sphere projection, two samples and lighting.
private const val PLANET_SHADER =
    """
uniform float2 origin;
uniform float radius;
uniform float time;
uniform float opacity;
uniform shader geography;
uniform float2 mapSize;
float hash(float3 p) {
    p = fract(p * 0.1031);
    p += dot(p, p.yzx + 33.33);
    return fract((p.x + p.y) * p.z);
}
float noise(float3 p) {
    float3 i = floor(p), f = fract(p);
    f = f*f*(3.0-2.0*f);
    return mix(mix(mix(hash(i),hash(i+float3(1,0,0)),f.x),
                   mix(hash(i+float3(0,1,0)),hash(i+float3(1,1,0)),f.x),f.y),
               mix(mix(hash(i+float3(0,0,1)),hash(i+float3(1,0,1)),f.x),
                   mix(hash(i+float3(0,1,1)),hash(i+float3(1,1,1)),f.x),f.y),f.z);
}
float3 fields(float3 world) {
    float2 uv=float2(atan(world.x,world.z)/6.2831853+0.5,asin(clamp(world.y,-1.0,1.0))/3.1415927+0.5);
    return geography.eval(uv*mapSize).rgb;
}
float3 turn(float3 n, float a) {
    return float3(n.x*cos(a)+n.z*sin(a), n.y, -n.x*sin(a)+n.z*cos(a));
}
half4 main(float2 position) {
    float2 p=(position-origin)/radius;
    float rr=dot(p,p), distance=sqrt(rr);
    float3 light=normalize(float3(-0.72,-0.47,0.48));
    if(distance>1.0) {
        float glow=exp(-(distance-1.0)*38.0)*0.40+exp(-(distance-1.0)*13.0)*0.045;
        glow*=0.25+0.75*max(0.0,dot(normalize(float3(p,0.15)),light));
        float a=glow*opacity*(1.0-smoothstep(1.15,1.28,distance));
        return half4(float3(0.25,0.62,1.0)*a,a);
    }
    float3 n=float3(p,sqrt(max(0.0,1.0-rr)));
    float3 world=turn(n,time);
    float3 data=fields(world);
    float terrain=data.r;
    float land=smoothstep(0.47,0.52,terrain);
    float mountain=data.g;
    float coast=smoothstep(0.43,0.48,terrain)*(1.0-land);
    float3 ocean=mix(float3(0.018,0.075,0.14),float3(0.035,0.19,0.25),coast);
    float3 ground=mix(float3(0.10,0.20,0.18),float3(0.32,0.31,0.24),smoothstep(0.4,0.68,mountain));
    float snow=smoothstep(0.76,0.92,abs(world.y)+mountain*0.10);
    float3 albedo=mix(ocean,ground,land);
    albedo=mix(albedo,float3(0.7,0.79,0.83),snow);
    float sunlight=dot(n,light);
    float daylight=smoothstep(-0.17,0.35,sunlight);
    float3 color=albedo*(0.06+1.35*max(0.0,sunlight));
    float3 cloudWorld=turn(n,time+0.04);
    float clouds=fields(cloudWorld).b;
    float veil=smoothstep(0.50,0.69,clouds)*0.90;
    color=mix(color,float3(0.86,0.91,0.98)*(0.07+0.95*max(0.0,sunlight)),veil);
    float spec=pow(max(0.0,dot(reflect(-light,n),float3(0,0,1))),48.0);
    color+=float3(0.54,0.70,0.8)*spec*0.48*(1.0-land)*(1.0-veil);
    float cities=smoothstep(0.72,0.86,noise(world*150.0))*smoothstep(0.48,0.63,mountain);
    color+=float3(1.0,0.55,0.20)*cities*land*(1.0-daylight)*0.45;
    float rim=pow(1.0-n.z,3.5);
    color+=float3(0.12,0.37,0.78)*rim*(0.12+0.72*daylight);
    float edge=1.0-smoothstep(0.995,1.0,distance);
    float a=edge*opacity;
    return half4(clamp(color,0.0,1.0)*a,a);
}
"""

/** API 26–32 fallback: generated once off the main thread, never each frame. */
internal fun bakePlanet(fields: Bitmap, size: Int): Bitmap {
    val map = IntArray(fields.width * fields.height)
    fields.getPixels(map, 0, fields.width, 0, 0, fields.width, fields.height)
    fun sample(x: Float, y: Float, z: Float): Int {
        val u = ((atan2(x, z) / (2 * PI) + .5) * fields.width).toInt().mod(fields.width)
        val v =
            ((asin(y.coerceIn(-1f, 1f)) / PI + .5) * fields.height)
                .toInt()
                .coerceIn(0, fields.height - 1)
        return map[v * fields.width + u]
    }
    fun smooth(a: Float, b: Float, x: Float): Float {
        val t = ((x - a) / (b - a)).coerceIn(0f, 1f)
        return t * t * (3 - 2 * t)
    }
    val pixels = IntArray(size * size)
    for (y in 0 until size) for (x in 0 until size) {
        val px = (x + 0.5f - size / 2f) / (size / 2.56f)
        val py = (y + 0.5f - size / 2f) / (size / 2.56f)
        val rr = px * px + py * py
        val r = sqrt(rr)
        var a = 1f
        var red: Float
        var green: Float
        var blue: Float
        if (r > 1f) {
            a =
                (exp(-(r - 1) * 38) * .40f + exp(-(r - 1) * 13) * .045f) *
                    (0.25f + 0.75f * max(0f, (-px * .72f - py * .47f) / r))
            red = .25f
            green = .62f
            blue = 1f
        } else {
            val z = sqrt(1 - rr)
            val daylight = max(0f, -px * .72f - py * .47f + z * .48f)
            val data = sample(px, py, z)
            val terrain = android.graphics.Color.red(data) / 255f
            val land = smooth(.47f, .52f, terrain)
            val light = .06f + 1.35f * daylight
            red = (.018f + land * .14f) * light
            green = (.075f + land * .15f) * light
            blue = (.14f + land * .06f) * light
            val clouds = smooth(.50f, .69f, android.graphics.Color.blue(data) / 255f) * .9f
            red = red * (1 - clouds) + .86f * (.07f + .95f * daylight) * clouds
            green = green * (1 - clouds) + .91f * (.07f + .95f * daylight) * clouds
            blue = blue * (1 - clouds) + .98f * (.07f + .95f * daylight) * clouds
            val rim = (1 - z).pow(3.5f) * (.12f + .72f * smooth(-.17f, .35f, daylight))
            red += .12f * rim
            green += .37f * rim
            blue += .78f * rim
        }
        fun channel(v: Float) = (v.coerceIn(0f, 1f) * 255).roundToInt()
        pixels[y * size + x] =
            android.graphics.Color.argb(channel(a), channel(red), channel(green), channel(blue))
    }
    return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
}
