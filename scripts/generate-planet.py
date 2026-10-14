#!/usr/bin/env python3
"""Bake seamless sphere fields once; runtime only samples and lights them.

Dev-only dependencies: numpy, Pillow. No imagery or network sources are used.
RGB stores terrain height, surface detail, and clouds (opaque data texture).
"""
from pathlib import Path
import numpy as np
from PIL import Image


def fract(x):
    return x - np.floor(x)


def noise(p):
    def h(q):
        q = fract(q * 0.1031)
        q += np.sum(q * (q[..., [1, 2, 0]] + 33.33), axis=-1)[..., None]
        return fract((q[..., 0] + q[..., 1]) * q[..., 2])

    i, f = np.floor(p), fract(p)
    f = f * f * (3 - 2 * f)
    result = np.zeros(p.shape[:-1])
    for x in (0, 1):
        for y in (0, 1):
            for z in (0, 1):
                weight = np.prod(np.where(np.array([x, y, z]), f, 1 - f), axis=-1)
                result += h(i + [x, y, z]) * weight
    return result


def fbm(p):
    result, weight = 0, 0.5
    for _ in range(5):
        result += weight * noise(p)
        p = p * 2.03 + [11.2, 7.1, 3.4]
        weight *= 0.5
    return result


if __name__ == "__main__":
    width, height = 1024, 512
    lon, lat = np.meshgrid(
        ((np.arange(width) + 0.5) / width - 0.5) * 2 * np.pi,
        ((np.arange(height) + 0.5) / height - 0.5) * np.pi,
    )
    world = np.stack((np.sin(lon) * np.cos(lat), np.sin(lat), np.cos(lon) * np.cos(lat)), -1)
    terrain = fbm(world * 3.1 + [7.8, 1.3, 5.1])
    detail = fbm(world * 14)
    warp = fbm(world * 2.4 + 8)
    clouds = fbm(world * 8 + np.stack((warp * 2.3, warp, np.zeros_like(warp)), -1))
    fields = np.clip(np.stack((terrain, detail, clouds), -1) * 255, 0, 255).round().astype('uint8')
    target = Path(__file__).resolve().parents[1] / 'android/app/src/main/assets/planet-fields.png'
    target.parent.mkdir(parents=True, exist_ok=True)
    Image.fromarray(fields).save(target, optimize=True)
    print(f'{target.name}: {target.stat().st_size:,} bytes')
