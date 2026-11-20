#!/usr/bin/env python3
"""Draws the Google Play feature graphic (1024x500, PNG without transparency) in Ukrainian, Russian and English.

Uses the real screenshots in store/screenshots (the phone's "Now" screen and the watch tile) and the icon.
  python3 store/render_feature_graphic.py        ->  store/feature-graphic-{uk,ru,en}.png"""
import os
from PIL import Image, ImageChops, ImageDraw, ImageFilter, ImageFont

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
FONT = '/usr/share/fonts/truetype/noto/NotoSans-%s.ttf'
W, H = 1024, 500
TEXT = {
    'uk': ('Графік відключень світла\nна телефоні та годиннику', 'Wear OS · Android'),
    'ru': ('График отключений света\nна телефоне и часах', 'Wear OS · Android'),
    'en': ('Power outage schedule\non your phone and watch', 'Wear OS · Android'),
}


def font(style, size):
    return ImageFont.truetype(FONT % style, size)


def background():
    bg = Image.new('RGB', (W, H), (11, 14, 18))
    # a soft green glow behind the icon and a red one behind the devices, like the app's own states
    glow = Image.new('RGB', (W, H), (0, 0, 0))
    d = ImageDraw.Draw(glow)
    d.ellipse((-160, -120, 420, 380), fill=(18, 74, 44))
    d.ellipse((560, 150, 1180, 700), fill=(70, 24, 28))
    glow = glow.filter(ImageFilter.GaussianBlur(110))
    return ImageChops.add(bg, glow)


def rounded(im, radius):
    mask = Image.new('L', im.size, 0)
    ImageDraw.Draw(mask).rounded_rectangle((0, 0, im.width - 1, im.height - 1), radius, fill=255)
    out = im.convert('RGBA')
    out.putalpha(mask)
    return out


def shadow(layer, offset=(0, 14), blur=22, alpha=170):
    sh = Image.new('RGBA', layer.size, (0, 0, 0, 0))
    sh.putalpha(layer.getchannel('A').point(lambda v: v * alpha // 255))
    pad = blur * 3
    big = Image.new('RGBA', (layer.width + 2 * pad, layer.height + 2 * pad), (0, 0, 0, 0))
    big.paste(sh, (pad, pad))
    return big.filter(ImageFilter.GaussianBlur(blur)), (offset[0] - pad, offset[1] - pad)


def paste_with_shadow(canvas, layer, xy):
    sh, (dx, dy) = shadow(layer)
    canvas.alpha_composite(sh, (xy[0] + dx, xy[1] + dy))
    canvas.alpha_composite(layer, xy)


def draw(lang):
    canvas = background().convert('RGBA')

    # the phone: the top of the "Now" screen, bleeding off the bottom edge
    shot = Image.open(os.path.join(ROOT, 'store/screenshots/phone/01-now.png')).convert('RGB')
    pw = 236
    phone = shot.resize((pw, round(shot.height * pw / shot.width)), Image.LANCZOS)
    frame = Image.new('RGBA', (pw + 12, phone.height + 12), (0, 0, 0, 0))
    ImageDraw.Draw(frame).rounded_rectangle((0, 0, frame.width - 1, frame.height - 1), 34, fill=(36, 40, 47, 255))
    frame.alpha_composite(rounded(phone, 28), (6, 6))
    paste_with_shadow(canvas, frame, (592, 52))

    # the watch: the tile, round
    tile = Image.open(os.path.join(ROOT, 'store/screenshots/wear/03-tile.png')).convert('RGB')
    d = 250
    tile = tile.resize((d, d), Image.LANCZOS).convert('RGBA')
    mask = Image.new('L', (d, d), 0)
    ImageDraw.Draw(mask).ellipse((0, 0, d - 1, d - 1), fill=255)
    tile.putalpha(mask)
    ring = Image.new('RGBA', (d + 16, d + 16), (0, 0, 0, 0))
    ImageDraw.Draw(ring).ellipse((0, 0, ring.width - 1, ring.height - 1), fill=(52, 57, 66, 255))
    ring.alpha_composite(tile, (8, 8))
    paste_with_shadow(canvas, ring, (738, 208))

    # the words
    icon = Image.open(os.path.join(ROOT, 'store/icon_round.png')).convert('RGBA').resize((112, 112), Image.LANCZOS)
    canvas.alpha_composite(icon, (60, 58))
    draw_ = ImageDraw.Draw(canvas)
    draw_.text((60, 188), 'Wattlo', font=font('Bold', 92), fill=(255, 255, 255))
    title, sub = TEXT[lang]
    draw_.multiline_text((62, 312), title, font=font('Regular', 32), fill=(196, 203, 212), spacing=8)
    draw_.text((62, 418), sub, font=font('Bold', 24), fill=(74, 222, 128))
    return canvas.convert('RGB')


if __name__ == '__main__':
    for lang in TEXT:
        out = os.path.join(ROOT, f'store/feature-graphic-{lang}.png')
        draw(lang).save(out, optimize=True)
        print(out)
