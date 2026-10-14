# Earth imagery

Version 0.1.10 replaces the procedural fantasy planet and random light noise
with three bundled NASA equirectangular maps. JPEG source bytes are preserved;
Android downsamples them to at most 2048 x 1024 off the UI thread.

| Asset | Source | Meaning |
| --- | --- | --- |
| `earth-day.jpg` | [Blue Marble Next Generation](https://science.nasa.gov/earth/earth-observatory/blue-marble-next-generation/base-topography/), July 2004, topography | Actual continent shapes, vegetation, deserts, ice and coastlines |
| `earth-night.jpg` | [Black Marble 2016 grayscale](https://science.nasa.gov/earth/earth-observatory/earth-at-night/maps/) | VIIRS observed night-light distribution |
| `earth-clouds.jpg` | [Blue Marble clouds](https://visibleearth.nasa.gov/images/57747/blue-marble-clouds/77558l), 2002 | Observed cloud composite |

Credits travel with the APK in `earth-sources.txt`. The maps are historical
composites from different dates, not current weather or a single contemporaneous
view. Lighting exposure, warm city-light tint, ocean highlights and the subtle
atmosphere are rendering choices. No NASA endorsement is implied. See the
[NASA media guidelines](https://www.nasa.gov/nasa-brand-center/images-and-media/).

Verify original bytes with `python3 scripts/fetch-earth-assets.py`. Restore the
same sources using `--download`; a changed upstream hash fails before writing.
The three source files total about 2.8 MB. No runtime downloads are required.

On API 33+, the shader samples aligned day/night UV coordinates on a rotating
sphere. City emission fades in only on the night hemisphere and clouds attenuate
it. Unlit terrain and clouds have very little fill, so city networks dominate
instead of the previous gray, fully visible night surface. Sun direction comes
from the existing orbital scene; a Sun outside the viewport can still illuminate
Earth. The geographic north remains above south; the initial view faces Asia.

API 26–32 uses two preprojected 512-pixel maps. A moving, curved terminator reveals
the night texture as the Sun moves, avoiding CPU pixel work every frame. This
fallback has static geography, simpler surface shading and an antialiased but
sharper twilight boundary than the GPU version. Both use the same real maps.
