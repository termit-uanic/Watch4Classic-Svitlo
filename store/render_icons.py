#!/usr/bin/env python3
"""Renders the launcher icon (same geometry as core/src/main/res/drawable/ic_launcher_foreground.xml and
ic_launcher_monochrome.xml) to PNG: the 512x512 Google Play icon and, with --previews DIR, a look at the masks
and at a themed (monochrome) icon. Needs Pillow. Run again after changing the vector drawables."""
import math, sys
from PIL import Image, ImageDraw

BG = (0x10, 0x12, 0x16)
G, R, A = (0x4A, 0xDE, 0x80), (0xFF, 0x5A, 0x5F), (0xFF, 0xC1, 0x4D)
N, SWEEP, STROKE = 12, 8.5, 6
COLORS = [G, G, G, G, G, R, R, G, G, A, G, G]
SS = 4  # supersampling


def tf(p):  # the group of the drawable: scale 0.85 about (54, 51), then down by 2.5
    return (54 + 0.85 * (p[0] - 54), 51 + 0.85 * (p[1] - 51) + 2.5)


def stroke(d, pts, w, col, k):
    """A polyline with round caps and joins: circles stamped along it (a smooth edge at any angle)."""
    pts = [(x * k, y * k) for x, y in pts]
    w *= k
    step = max(0.5, w / 10)
    for (x0, y0), (x1, y1) in zip(pts, pts[1:] or pts):
        n = max(1, int(math.hypot(x1 - x0, y1 - y0) / step))
        for i in range(n + 1):
            x, y = x0 + (x1 - x0) * i / n, y0 + (y1 - y0) * i / n
            d.ellipse((x - w / 2, y - w / 2, x + w / 2, y + w / 2), fill=col)


def arc_pts(cx, cy, r, a0, a1, step=1.0):
    n = max(2, int(abs(a1 - a0) / step) + 1)
    return [(cx + r * math.cos(math.radians(a0 + (a1 - a0) * i / (n - 1))),
             cy + r * math.sin(math.radians(a0 + (a1 - a0) * i / (n - 1)))) for i in range(n)]


def pill(d, x0, y0, x1, y1, col, k):
    pts = [tf((x0, y0)), tf((x1, y1))]
    d.rounded_rectangle((pts[0][0] * k, pts[0][1] * k, pts[1][0] * k, pts[1][1] * k),
                        radius=(pts[1][1] - pts[0][1]) * k / 2, fill=col)


def colour_icon(size, bg=BG):
    k = size * SS / 108
    im = Image.new('RGB', (size * SS, size * SS), bg)
    d = ImageDraw.Draw(im)
    for i, c in enumerate(COLORS):
        mid = i / N * 360 - 90
        stroke(d, [tf(p) for p in arc_pts(54, 44, 25, mid - SWEEP / 2, mid + SWEEP / 2)], STROKE * 0.85, c, k)
    stroke(d, [tf(p) for p in [(44, 38), (49, 52), (54, 41), (59, 52), (64, 38)]], 4.2 * 0.85, A, k)
    pill(d, 44, 76, 64, 82, (0xC9, 0xCD, 0xD3), k)
    pill(d, 48, 85, 60, 90, (0x7D, 0x83, 0x8C), k)
    return im.resize((size, size), Image.LANCZOS)


def mono_icon(size, bg, fg):
    k = size * SS / 108
    im = Image.new('RGB', (size * SS, size * SS), bg)
    d = ImageDraw.Draw(im)
    glass = arc_pts(54, 44, 25, -90, 54.3) + [(66.5, 74), (41.5, 74)] + arc_pts(54, 44, 25, 125.7, 270)
    stroke(d, [tf(p) for p in glass] + [tf(glass[0])], 6 * 0.85, fg, k)
    stroke(d, [tf(p) for p in [(44, 38), (49, 52), (54, 41), (59, 52), (64, 38)]], 5 * 0.85, fg, k)
    pill(d, 44.75, 79.5, 63.25, 84, fg, k)  # the two bases, clear of the glass edge (y=77)
    pill(d, 48.5, 86, 59.5, 90, fg, k)
    return im.resize((size, size), Image.LANCZOS)


def masked(im, shape):
    s = im.width
    m = Image.new('L', (s * 4, s * 4), 0)
    dm = ImageDraw.Draw(m)
    box = (0, 0, s * 4, s * 4)
    dm.ellipse(box, fill=255) if shape == 'circle' else dm.rounded_rectangle(box, radius=s * 4 * 0.28, fill=255)
    out = Image.new('RGBA', (s, s), (0, 0, 0, 0))
    out.paste(im, (0, 0), m.resize((s, s), Image.LANCZOS))
    return out


if __name__ == '__main__':
    colour_icon(512).save('store/ic_launcher_512.png')  # Google Play wants a square 512x512 without a mask
    print('store/ic_launcher_512.png')
    masked(colour_icon(256), 'circle').save('store/icon_round.png')  # transparent corners, for the README
    print('store/icon_round.png')
    if len(sys.argv) == 3 and sys.argv[1] == '--previews':
        out = sys.argv[2]
        row = Image.new('RGB', (4 * 232, 232), (236, 237, 240))
        items = [masked(colour_icon(216), 'circle'), masked(colour_icon(216), 'squircle'),
                 masked(mono_icon(216, (0x2B, 0x3A, 0x55), (0xD6, 0xE3, 0xFF)), 'circle'),
                 masked(mono_icon(216, (0x1B, 0x1B, 0x1F), (0xE3, 0xE2, 0xE6)), 'circle')]
        for i, it in enumerate(items):
            row.paste(it, (8 + i * 232, 8), it)
        row.save(out + '/icon_preview.png')
        small = Image.new('RGB', (4 * 64, 64), (236, 237, 240))
        for i, it in enumerate(items):
            t = it.resize((48, 48), Image.LANCZOS)
            small.paste(t, (8 + i * 64, 8), t)
        small.resize((small.width * 3, small.height * 3), Image.NEAREST).save(out + '/icon_preview_small.png')
