#!/usr/bin/env python3
"""Verify bundled NASA originals, or fetch exact source files with --download. No image synthesis."""
import argparse
import hashlib
from pathlib import Path
from urllib.request import urlopen

ASSETS = {
    'earth-day.jpg': ('https://assets.science.nasa.gov/content/dam/science/esd/eo/images/bmng/bmng-topography/july/world.topo.200407.3x5400x2700.jpg', '85a24885616097b5e06532b7d8e30fa5d749a782e70ea55971dd8539a0899206'),
    'earth-night.jpg': ('https://assets.science.nasa.gov/content/dam/science/esd/eo/images/imagerecords/144000/144897/BlackMarble_2016_01deg_gray.jpg', '4d2158f59123dadf0696a1cf8909c45018a1de8d0daab40da04122a5aa7f27c6'),
    'earth-clouds.jpg': ('https://eoimages.gsfc.nasa.gov/images/imagerecords/57000/57747/cloud_combined_2048.jpg', 'daddaad84d7a33bbbc86cdda3f591099f57cee8607b7bcf3b67eb7e4f7a1c793'),
}
if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--download', action='store_true')
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1] / 'android/app/src/main/assets'
    for name, (url, expected) in ASSETS.items():
        data = urlopen(url, timeout=60).read() if args.download else (root/name).read_bytes()
        if hashlib.sha256(data).hexdigest() != expected:
            raise SystemExit(f'Unexpected source hash: {name}')
        if args.download:
            (root/name).write_bytes(data)
        print(f'{name}: verified {len(data):,} bytes')
