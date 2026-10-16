# Earth, Moon and starfield welcome scene

The camera follows Earth's position with a fixed orientation. Earth turns beneath
it; the other seven planets retain their heliocentric orbits and now have moving
surface detail. Uranus and Neptune use the same J2000 element source as the
existing planets: [JPL Table 1](https://ssd.jpl.nasa.gov/planets/approx_pos.html).

The Moon follows an inclined circular orbit outside the three local spacecraft
orbits. Its surface frame always points the same near-side axis toward Earth,
implementing [synchronous rotation](https://science.nasa.gov/moon/facts/), rather
than facing the camera. Moon, Earth and spacecraft share local depth ordering.
The lunar phase uses the same Sun direction as Earth's shader. Craters and dark
patches are deterministic procedural illustration, not a geographic lunar map.

The foreground fits a wider composition so the lunar orbit has room. Satellite
trails and orbiting dust are removed; guide lines are barely visible. The fixed
background has 180–520 seeded stars according to screen area, varied sizes,
mostly cool whites with occasional warm stars, a few soft halos and only 3.5%
exposure variation. Stars are dimmed behind the lower text/control area. This
exposure variation is an artistic effect, not atmospheric scintillation in space.

All motion is derived from the saved, lifecycle-aware orbital clock. It pauses
in the background, restores without resetting and respects system motion scale.
There is no independent 42-second loop that can reset the local scene.

## Display scale and timing

This is an art-directed scene, not an astronomical simulation. The Moon's orbital
distance is compressed to 3.1 scene units (Earth radius 1.34), while its radius
is 0.366 units. Its orbit and synchronous rotation both take 80 display seconds.
Earth's display rotation takes 64 seconds; the heliocentric year remains 120
seconds. Other planet spins use readable display periods, retaining retrograde
signs for Venus and Uranus. These are deliberately not true day/month/year ratios.
Planet disc size exaggeration, approximate orbital elements and older-API static
Earth geography remain as documented in android-welcome.md. Eclipses and lunar
libration are not simulated. Planets outside the camera view stay outside it.

## Verification

MoonSceneTest checks a full two-cycle near-side lock, orthonormal orientation,
orbit radius, loop continuity, complete planet count and spin directions.
PlanetRenderingTest covers moving lunar terrain and Sun-driven phase in addition
to the Earth shader/fallback checks. WelcomeTransitionTest samples the complete
80-second lunar cycle, confirms usable login controls, and checks entry guards,
camera approach, lifecycle pause and saved state.

Artifacts: `artifacts/welcome-moon-stars-2026-10-14/`.

Validated 2026-10-14: debug build, lint and all 60 JVM tests passed. All ten
rendering/welcome UI tests passed on the expanded emulator; all five welcome
tests also passed at 1080 x 2400. Reviewed initial and later-cycle expanded
frames and the compact opening frame. This validates sampled renders and
behavior, not human playback or physical-device frame timing.

Wi-Fi update succeeded on Pixel 11 Pro Fold with account data retained. Installed
APK SHA-256 matched `19db90c96e3bca6c346448790b2d5e8607957795b4da92c5fad9f218e1d3c661`.
