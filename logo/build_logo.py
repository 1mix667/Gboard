"""Gboard+ logo generator.

Design philosophy
-----------------
One idea per icon, two voices:
  1. The Gboard mark (familiar, trusted) stays the hero.
  2. A single quiet "+" in the brand colour rides the *top-right of the G*, fully
     inside the white mark, so it reads "G, plus more" rather than a second logo.
Flat, two colours (accent #F03971 + white), generous negative space. The accent is the
tile background **and** the "+"; the "+" is clipped-in to stay on the white badge so it
never touches the background.

Derived from the installed Gboard icon (glyph `aQv.webp`): transparent margins trimmed so
the mark fills the tile as large as the platform safe zone allows, background recoloured
to #F03971, the source ghost square removed, and the "+" placed inside the measured white
area of the mark's top-right.

Run:  python build_logo.py
"""
import base64
import io
import os

from PIL import Image, ImageDraw

HERE = os.path.dirname(os.path.abspath(__file__))
SRC = os.path.join(HERE, "source", "res")
GEN = os.path.join(HERE, "generated")
RES = os.path.join(HERE, "..", "app", "src", "main", "res")
os.makedirs(GEN, exist_ok=True)

BG = (240, 57, 113, 255)          # #F03971
WHITE = (255, 255, 255, 255)
MASTER = 432
DENS = {"mdpi": 1, "hdpi": 1.5, "xhdpi": 2, "xxhdpi": 3, "xxxhdpi": 4}

FILL_SQUARE = 0.62
FILL_ADAPTIVE = 0.586

# Final '+' geometry (design-final). Calibrated to the Figma frame
# "Gboard+ icon 512 (final)": plus centre (325,125) @512, arm 27, thickness 16.
# Stored in MASTER (432) coordinates of the square layer and scaled per layer.
PLUS_CENTER0 = (274.0, 105.0)
PLUS_ARM0 = 23.0
PLUS_THICK0 = 14.0


def plus_geom(fill):
    """Centre, arm and thickness of the '+' scaled for a given glyph fill ratio."""
    r = fill / FILL_SQUARE
    cx = MASTER / 2 + (PLUS_CENTER0[0] - MASTER / 2) * r
    cy = MASTER / 2 + (PLUS_CENTER0[1] - MASTER / 2) * r
    return cx, cy, PLUS_ARM0 * r, PLUS_THICK0 * r


def trimmed_glyph():
    im = Image.open(os.path.join(SRC, "aQv.webp")).convert("RGBA")
    r, g, b, a = im.split()
    a = a.point(lambda v: 0 if v < 40 else v)          # drop the source ghost square
    im = Image.merge("RGBA", (r, g, b, a))
    return im.crop(im.getbbox())                        # trim transparent margins


def place(canvas, fill, dy=0.0):
    g = trimmed_glyph()
    s = (MASTER * fill) / max(g.size)
    g = g.resize((max(1, int(g.size[0] * s)), max(1, int(g.size[1] * s))), Image.LANCZOS)
    x = (MASTER - g.size[0]) // 2
    y = (MASTER - g.size[1]) // 2 + int(MASTER * dy)
    canvas.alpha_composite(g, (x, y))
    return g, x, y


def draw_plus(canvas, fill, color):
    cx, cy, arm, thick = plus_geom(fill)
    layer = Image.new("RGBA", canvas.size, (0, 0, 0, 0))
    d = ImageDraw.Draw(layer)
    half = thick / 2
    d.rounded_rectangle([cx - arm, cy - half, cx + arm, cy + half], radius=half, fill=color)
    d.rounded_rectangle([cx - half, cy - arm, cx + half, cy + arm], radius=half, fill=color)
    return Image.alpha_composite(canvas, layer)


def square_icon():
    canvas = Image.new("RGBA", (MASTER, MASTER), BG)
    place(canvas, FILL_SQUARE, dy=-0.01)
    return draw_plus(canvas, FILL_SQUARE, BG)


def foreground_layer():
    layer = Image.new("RGBA", (MASTER, MASTER), (0, 0, 0, 0))
    place(layer, FILL_ADAPTIVE, dy=-0.01)
    return draw_plus(layer, FILL_ADAPTIVE, BG)


def monochrome_layer():
    layer = Image.new("RGBA", (MASTER, MASTER), (0, 0, 0, 0))
    g = trimmed_glyph()
    s = (MASTER * FILL_ADAPTIVE) / max(g.size)
    g = g.resize((int(g.size[0] * s), int(g.size[1] * s)), Image.LANCZOS)
    white = Image.merge("RGBA", (Image.new("L", g.size, 255),) * 3 + (g.split()[3],))
    x = (MASTER - g.size[0]) // 2
    y = (MASTER - g.size[1]) // 2 - int(MASTER * 0.01)
    layer.alpha_composite(white, (x, y))
    return draw_plus(layer, FILL_ADAPTIVE, WHITE)   # monochrome is an alpha mask -> white


def glyph_only():
    layer = Image.new("RGBA", (MASTER, MASTER), (0, 0, 0, 0))
    place(layer, FILL_SQUARE, dy=-0.01)
    return layer


def round_mask(img):
    m = Image.new("L", img.size, 0)
    ImageDraw.Draw(m).ellipse([0, 0, img.size[0] - 1, img.size[1] - 1], fill=255)
    out = img.copy()
    out.putalpha(m)
    return out


def save(img, path, size):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    img.resize((size, size), Image.LANCZOS).save(path)


def write_svg(path):
    buf = io.BytesIO(); glyph_only().save(buf, format="PNG")
    b64 = base64.b64encode(buf.getvalue()).decode("ascii")
    tmp = Image.new("RGBA", (MASTER, MASTER), (0, 0, 0, 0))
    place(tmp, FILL_SQUARE, dy=-0.01)
    cx, cy, arm, thick = plus_geom(FILL_SQUARE)
    half = thick / 2
    svg = f'''<svg xmlns="http://www.w3.org/2000/svg" width="512" height="512" viewBox="0 0 432 432">
  <rect width="432" height="432" rx="96" fill="#F03971"/>
  <image x="0" y="0" width="432" height="432" href="data:image/png;base64,{b64}"/>
  <rect x="{cx-arm:.1f}" y="{cy-half:.1f}" width="{arm*2:.1f}" height="{thick:.1f}" rx="{half:.1f}" fill="#F03971"/>
  <rect x="{cx-half:.1f}" y="{cy-arm:.1f}" width="{thick:.1f}" height="{arm*2:.1f}" rx="{half:.1f}" fill="#F03971"/>
</svg>
'''
    with open(path, "w", encoding="utf-8") as f:
        f.write(svg)


def main():
    square = square_icon()
    rnd = round_mask(square)
    fg = foreground_layer()
    mono = monochrome_layer()

    for dens, mul in DENS.items():
        d = os.path.join(RES, f"mipmap-{dens}")
        save(square, os.path.join(d, "ic_launcher.png"), int(48 * mul))
        save(rnd,    os.path.join(d, "ic_launcher_round.png"), int(48 * mul))
        save(fg,     os.path.join(d, "ic_launcher_foreground.png"), int(108 * mul))
        save(mono,   os.path.join(d, "ic_launcher_monochrome.png"), int(108 * mul))

    save(square, os.path.join(GEN, "ic_launcher-web.png"), 512)
    save(square, os.path.join(GEN, "preview_square.png"), 512)
    save(rnd,    os.path.join(GEN, "preview_round.png"), 512)
    square.resize((256, 256), Image.LANCZOS).save(os.path.join(GEN, "preview_256.png"))
    save(glyph_only(), os.path.join(GEN, "glyph_only.png"), 432)
    write_svg(os.path.join(GEN, "gboardplus.svg"))
    print("logo written")


if __name__ == "__main__":
    main()
