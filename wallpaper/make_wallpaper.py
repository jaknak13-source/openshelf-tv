"""Generates the home-screen wallpaper at the TV's UI resolution (1920x1080).

Dark graphite base with two soft light pools, a gentle vignette, and
triangular dither noise so 8-bit gradients do not band on the panel.
Usage: python3 make_wallpaper.py <out.png>
"""
import sys

import numpy as np
from PIL import Image

W, H = 1920, 1080
rng = np.random.default_rng(7)
y, x = np.mgrid[0:H, 0:W].astype(np.float64)
xn, yn = x / W, y / H

base = np.array([11.0, 14.0, 19.0])  # graphite with a slight blue bias


def pool(cx: float, cy: float, rx: float, ry: float) -> np.ndarray:
    d = ((xn - cx) / rx) ** 2 + ((yn - cy) / ry) ** 2
    return np.exp(-d)


cool = pool(0.82, 0.05, 0.55, 0.75)[..., None] * np.array([22.0, 42.0, 58.0])
warm = pool(0.08, 1.05, 0.50, 0.55)[..., None] * np.array([26.0, 17.0, 12.0])
img = base + cool + warm

vignette = 1.0 - 0.35 * (((xn - 0.5) / 0.75) ** 2 + ((yn - 0.45) / 0.85) ** 2)
img *= np.clip(vignette, 0.55, 1.0)[..., None]

# Triangular-PDF dither of +-1 LSB breaks up banding in the dark gradient.
img += rng.random((H, W, 1)) - rng.random((H, W, 1))
Image.fromarray(np.clip(np.round(img), 0, 255).astype(np.uint8), "RGB").save(sys.argv[1], optimize=True)
print("saved", sys.argv[1])
