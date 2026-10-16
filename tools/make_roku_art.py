"""Draws the Roku receiver's artwork (backgrounds, cards, grid 9-patches, icons) in the app's palette.

Run from the repo root:  python3 tools/make_roku_art.py
"""
from PIL import Image, ImageDraw, ImageFilter

OUT = "roku/images"
NIGHT = (11, 10, 26)
INDIGO = (26, 18, 64)
VIOLET = (155, 123, 255)
CYAN = (63, 224, 208)
PINK = (255, 95, 162)


def lerp(a, b, t):
    return tuple(int(a[i] + (b[i] - a[i]) * t) for i in range(len(a)))


def gradient(w, h, top, bottom):
    img = Image.new("RGB", (w, h))
    px = img.load()
    for y in range(h):
        c = lerp(top, bottom, y / max(1, h - 1))
        for x in range(w):
            px[x, y] = c
    return img


def glow(img, center, radius, color, strength):
    layer = Image.new("RGBA", img.size, (0, 0, 0, 0))
    d = ImageDraw.Draw(layer)
    x, y = center
    d.ellipse((x - radius, y - radius, x + radius, y + radius), fill=color + (int(255 * strength),))
    layer = layer.filter(ImageFilter.GaussianBlur(radius * 0.6))
    base = img.convert("RGBA")
    base.alpha_composite(layer)
    return base.convert("RGB")


def neon_line(w, h):
    """Horizontal violet → cyan → pink gradient strip."""
    img = Image.new("RGBA", (w, h))
    px = img.load()
    for x in range(w):
        t = x / max(1, w - 1)
        c = lerp(VIOLET, CYAN, t * 2) if t < 0.5 else lerp(CYAN, PINK, (t - 0.5) * 2)
        for y in range(h):
            px[x, y] = c + (255,)
    return img


def background():
    img = gradient(960, 540, NIGHT, INDIGO)
    img = glow(img, (120, 60), 260, VIOLET, 0.35)
    img = glow(img, (880, 520), 240, CYAN, 0.18)
    img = glow(img, (700, 40), 160, PINK, 0.12)
    img.save(f"{OUT}/bg.png", optimize=True)


def header():
    img = gradient(960, 220, (38, 28, 92), (20, 16, 48))
    img = glow(img, (160, 40), 200, VIOLET, 0.45)
    img = glow(img, (820, 200), 160, PINK, 0.2)
    img = img.convert("RGBA")
    img.alpha_composite(neon_line(960, 2), (0, 218))
    img.save(f"{OUT}/header.png", optimize=True)


def banner():
    w, h = 960, 160
    img = Image.new("RGBA", (w, h))
    px = img.load()
    for y in range(h):
        a = int(235 * min(1, (y / h) * 1.6))
        for x in range(w):
            px[x, y] = NIGHT + (a,)
    img.alpha_composite(neon_line(w, 2).point(lambda v: v), (0, 22))
    img.save(f"{OUT}/banner.png", optimize=True)


def card(w, h, radius, name):
    """Rounded glass card with a gradient border (used for the timecard)."""
    scale = 4
    big = Image.new("RGBA", (w * scale, h * scale), (0, 0, 0, 0))
    border = neon_line(w * scale, h * scale)
    mask = Image.new("L", big.size, 0)
    ImageDraw.Draw(mask).rounded_rectangle((0, 0, w * scale - 1, h * scale - 1), radius * scale, fill=255)
    big.paste(border, (0, 0), mask)
    inner = Image.new("L", big.size, 0)
    b = 3 * scale
    ImageDraw.Draw(inner).rounded_rectangle((b, b, w * scale - 1 - b, h * scale - 1 - b), (radius - 3) * scale, fill=255)
    big.paste(Image.new("RGBA", big.size, (14, 12, 34, 238)), (0, 0), inner)
    big.resize((w, h), Image.LANCZOS).save(f"{OUT}/{name}.png", optimize=True)


def bar():
    neon_line(400, 8).save(f"{OUT}/bar.png", optimize=True)


def tv_glyph(img, cx, cy, w):
    """Line-art TV with antennas, stroked with the neon gradient and a soft glow."""
    h = w * 0.62
    stroke = max(3, int(w * 0.045))
    mask = Image.new("L", img.size, 0)
    d = ImageDraw.Draw(mask)
    box = (cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2)
    d.rounded_rectangle(box, radius=w * 0.09, outline=255, width=stroke)
    top = cy - h / 2
    d.line((cx - w * 0.16, top - w * 0.2, cx, top - 2), fill=255, width=stroke)
    d.line((cx + w * 0.16, top - w * 0.2, cx, top - 2), fill=255, width=stroke)
    # Play triangle inside the screen.
    t = w * 0.13
    d.polygon([(cx - t * 0.7, cy - t), (cx - t * 0.7, cy + t), (cx + t, cy)], fill=255)
    halo = Image.new("RGBA", img.size, VIOLET + (0,))
    halo.putalpha(mask.filter(ImageFilter.GaussianBlur(w * 0.06)).point(lambda v: int(v * 0.8)))
    img.alpha_composite(halo)
    fill = Image.new("RGBA", img.size, (0, 0, 0, 0))
    fill.paste(neon_line(int(w) + 4, img.height), (int(cx - w / 2) - 2, 0))
    img.paste(fill, (0, 0), mask)


def icon(w, h, name):
    img = glow(gradient(w, h, (30, 20, 76), NIGHT), (w * 0.2, h * 0.1), w * 0.5, VIOLET, 0.4).convert("RGBA")
    tv_glyph(img, w / 2, h * 0.54, w * 0.36)
    img.convert("RGB").save(f"{OUT}/{name}.png", optimize=True)


def idle():
    """Splash and idle screen: the night background with the TV glyph, no text."""
    img = Image.open(f"{OUT}/bg.png").resize((1920, 1080), Image.LANCZOS).convert("RGBA")
    tv_glyph(img, 960, 400, 300)
    img.convert("RGB").save(f"{OUT}/splash_fhd.jpg", quality=88)


def nine_patch(w, h, radius, fill, name, border=None, stretch=None, bottom_line=None):
    """Writes a Roku 9-patch: a 1px frame whose black pixels mark the stretchable middle."""
    scale = 4
    big = Image.new("RGBA", (w * scale, h * scale), (0, 0, 0, 0))
    d = ImageDraw.Draw(big)
    if border:
        d.rounded_rectangle((0, 0, w * scale - 1, h * scale - 1), radius * scale, fill=border)
        b = scale
        d.rounded_rectangle((b, b, w * scale - 1 - b, h * scale - 1 - b), (radius - 1) * scale, fill=fill)
    else:
        d.rounded_rectangle((0, 0, w * scale - 1, h * scale - 1), radius * scale, fill=fill)
    if bottom_line:
        d.rectangle((0, (h - 3) * scale, w * scale, h * scale), fill=bottom_line)
    art = big.resize((w, h), Image.LANCZOS)
    out = Image.new("RGBA", (w + 2, h + 2), (0, 0, 0, 0))
    out.paste(art, (1, 1))
    px = out.load()
    s0, s1 = stretch or (radius + 2, w - radius - 2)
    v1 = h - 6 if bottom_line else h - radius - 2
    for x in range(s0, s1):
        px[x + 1, 0] = (0, 0, 0, 255)
    for y in range(radius + 2 if not bottom_line else 2, v1):
        px[0, y + 1] = (0, 0, 0, 255)
    out.save(f"{OUT}/{name}.9.png", optimize=True)


def grid_art():
    nine_patch(48, 48, 10, (63, 224, 208, 255), "grid_focus")
    nine_patch(48, 48, 10, (28, 25, 54, 255), "grid_program", border=(46, 42, 88, 255))
    nine_patch(48, 48, 10, (21, 19, 43, 255), "grid_channel")
    nine_patch(48, 48, 1, (11, 10, 26, 255), "grid_timebar", bottom_line=VIOLET + (255,))


if __name__ == "__main__":
    background()
    header()
    banner()
    card(640, 196, 26, "timecard")
    bar()
    icon(336, 210, "icon_hd")
    icon(540, 405, "icon_fhd")
    idle()
    grid_art()
