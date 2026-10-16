# Pax launcher mark

Android now uses the existing Pax network mark from
`pax-console/public/pax-icon.svg`, copied unchanged to
`assets/branding/pax-icon.svg`. Source SHA-256:
`c05514446d896b96e5478f7ac9c15942fe78d6f2d5def63d1b774a5e3a3d913b`.
All three local Console checkouts contain the same asset.

Run `python3 scripts/sync-android-brand.py` to regenerate Android vectors. The
nine original paths are preserved without rasterization or redrawing. The desktop
icon uses a white background and black mark; the system splash uses a white mark
on the existing dark background. The manifest now references mipmap resources for
both standard and round icons. Android 26+ uses adaptive layers; Android 33+ adds
the monochrome layer for themed launchers. No baked-in corner shape or shadow is
added to adaptive artwork.

The 108 dp foreground centers the source mark at (54, 54). Sampled curve geometry
stays within a 30.70 dp radius, inside the 33 dp safe radius from the
[Android adaptive icon guidance](https://developer.android.com/develop/ui/compose/system/icon_design_adaptive).

Validation on 2026-10-14: debug build and lint passed. The installed icon was
visually reviewed in the isolated emulator's actual app drawer. Evidence is in
`artifacts/pax-logo-2026-10-14/launcher.png`. No application behavior changed;
no new behavioral tests were added.

Installed over Wi-Fi on Pixel 11 Pro Fold with `adb install -r` (success, device
update time 2026-10-14 22:12:04). Existing data was retained. APK SHA-256:
`dda8ad0bfb413624c202af8dc5c84848fd8a0546a89f3708148eb02293c8648a`.
Version remains unpublished 0.1.13 (14). The icon update includes the previous
session-creation functionality and does not require another gateway deployment.
