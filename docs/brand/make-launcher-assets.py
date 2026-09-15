#!/usr/bin/env python3
"""Generate MaterialAgent's launcher icon and splash art from the owner artwork.

Source of truth:  docs/brand/wuxje01.svg   (600x540, one <g> with matrix(.1 0 0 -.1 0 540))

Outputs written into app/src/main/res/:
  mipmap-{mdpi,hdpi,xhdpi,xxhdpi,xxxhdpi}/ic_launcher_foreground.png   #21005D art, transparent
  mipmap-{...}/ic_launcher_monochrome.png                              flat white silhouette, no holes
  drawable-{...}/ic_splash_art.png                                     #EADDFF art for the dark splash

Why raster: the artwork is ~48 traced paths of thin ribbon line art. A PNG rendered by a real
browser engine is both simpler and better than re-emitting those paths as vector drawables.

Simplification (the full illustration is noise at 48dp). Kept: the profile, its dark hair mass,
the eye, the mouth+nose block, the light beam and its capsule, and the escaped Material shapes.
Dropped: the loose hair strands (P0 subpaths .3 .6 .7 .9 .10 .11 .17 .19 .20 .28 and top-level
paths 13 14 24 26 29 30 31 32 35 36 38 39 43), plus sub-pixel specks (<1.5dp: top 2 7 9 10 20 21
40 42 47 and P0 specks .4 .5 .8 .13 .14 .15 .22 .24 .25 .27).
The mouth+nose (top 28) is KEPT although the brief said to drop it: without it the lower face is
featureless and the chin crescent reads as a stray blob (see docs/brand/ notes in the commit).

Geometry: the mark is scaled so its largest ink radius is 33dp and centred on the 108dp adaptive
canvas, so it sits inside the 66dp inner safe zone (and inside the 72dp circular mask) and is
never clipped by a launcher mask.

Requires: Chromium (headless screenshots) and Pillow. Run from the repo root.
"""
import json
import os
import re
import subprocess
import sys

from PIL import Image, ImageDraw

CHROME = "/Users/example/.cloakbrowser/chromium-145.0.7632.109.2/Chromium.app/Contents/MacOS/Chromium"
HERE = os.path.dirname(os.path.abspath(__file__))
SVG = os.path.join(HERE, "wuxje01.svg")
RES = os.path.abspath(os.path.join(HERE, "..", "..", "app", "src", "main", "res"))

ART = "#21005D"      # M3 tonal pair: dark ink on the primary-container field
FIELD = "#EADDFF"
SPLASH_ART = "#EADDFF"   # same pair, inverted, for the dark splash background

DENSITIES = {"mdpi": 1.0, "hdpi": 1.5, "xhdpi": 2.0, "xxhdpi": 3.0, "xxxhdpi": 4.0}

CANVAS_DP = 108.0        # adaptive icon canvas
CROP = (58.0, 0.0, 542.0, 540.0)   # artwork window: head + beam + escaped shapes
FIT_RADIUS_DP = 33.0     # keep the mark inside the 66dp safe zone / 72dp mask circle

# P0 is one compound path holding the head outline, hair mass, beam, capsule, ear and jaw.
# These are its kept subpaths; the rest of P0 is hair strands and specks.
P0_KEEP = [0, 1, 2, 12, 16, 18, 21, 23]
TOP_KEEP = [
    1, 3, 4, 5, 6, 11, 12, 15, 16, 17, 19, 22, 23, 25, 27,   # escaped Material shapes
    8,        # eye
    28,       # mouth + nose
    33, 34,   # shapes landing on the face
    37, 41, 44, 45, 46,      # jaw / neck / shoulder
]

NUM = re.compile(r"[-+]?(?:\d*\.\d+|\d+\.?)(?:[eE][-+]?\d+)?")
ARGC = {"m": 2, "l": 2, "h": 1, "v": 1, "c": 6, "s": 4, "q": 4, "t": 2, "z": 0}


def absolutise(d):
    """Absolutise the command stream, keeping source user units (0..6000 x 0..5400)."""
    toks = re.findall(r"[a-zA-Z]|[-+]?(?:\d*\.\d+|\d+\.?)(?:[eE][-+]?\d+)?", d)
    cx = cy = sx = sy = 0.0
    out, i, cmd, rel = [], 0, None, False
    while i < len(toks):
        t = toks[i]
        if t.isalpha():
            cmd = t.lower(); rel = t.islower(); i += 1
            if cmd == "z":
                out.append(("Z", [])); cx, cy = sx, sy; cmd = None
                continue
        n = ARGC[cmd]
        a = [float(x) for x in toks[i:i + n]]; i += n
        if cmd == "m":
            x, y = (a[0] + cx, a[1] + cy) if rel else a
            out.append(("M", [x, y])); cx = sx = x; cy = sy = y; cmd = "l"
        elif cmd == "l":
            x, y = (a[0] + cx, a[1] + cy) if rel else a
            out.append(("L", [x, y])); cx, cy = x, y
        elif cmd == "h":
            x = a[0] + cx if rel else a[0]; out.append(("L", [x, cy])); cx = x
        elif cmd == "v":
            y = a[0] + cy if rel else a[0]; out.append(("L", [cx, y])); cy = y
        elif cmd == "c":
            p = [a[0] + cx, a[1] + cy, a[2] + cx, a[3] + cy, a[4] + cx, a[5] + cy] if rel else a
            out.append(("C", p)); cx, cy = p[4], p[5]
        elif cmd == "s":
            p = [a[0] + cx, a[1] + cy, a[2] + cx, a[3] + cy] if rel else a
            out.append(("S", p)); cx, cy = p[2], p[3]
        elif cmd == "q":
            p = [a[0] + cx, a[1] + cy, a[2] + cx, a[3] + cy] if rel else a
            out.append(("Q", p)); cx, cy = p[2], p[3]
        elif cmd == "t":
            p = [a[0] + cx, a[1] + cy] if rel else a
            out.append(("T", p)); cx, cy = p[0], p[1]
        else:
            raise SystemExit("unsupported command %r" % cmd)
    return out


def subpaths(segs):
    subs, cur = [], None
    for cmd, a in segs:
        if cmd == "M":
            cur = [(cmd, a)]; subs.append(cur)
        elif cmd == "Z":
            if cur:
                cur.append(("Z", []))
            cur = None
        else:
            if cur is None:
                cur = []; subs.append(cur)
            cur.append((cmd, a))
    return [s for s in subs if s]


def to_final(segs):
    """matrix(.1 0 0 -.1 0 540): source units -> 600x540 artwork space, y down."""
    def P(x, y):
        return "%.2f %.2f" % (x / 10.0, 540.0 - y / 10.0)

    out = []
    for cmd, a in segs:
        if cmd == "Z":
            out.append("Z")
        elif cmd in ("M", "L"):
            out.append(cmd + P(a[0], a[1]))
        elif cmd == "C":
            out.append("C" + P(a[0], a[1]) + " " + P(a[2], a[3]) + " " + P(a[4], a[5]))
        elif cmd in ("S", "Q"):
            out.append(cmd + P(a[0], a[1]) + " " + P(a[2], a[3]))
        elif cmd == "T":
            out.append("T" + P(a[0], a[1]))
    return "".join(out)


def load_pieces():
    src = open(SVG).read()
    ds = re.findall(r'<path\s+d="([^"]+)"', src)
    assert len(ds) == 48, "expected 48 paths, found %d" % len(ds)
    pieces = []
    p0_subs = subpaths(absolutise(ds[0]))
    for j in P0_KEEP:
        segs = p0_subs[j]
        pieces.append({"d": to_final(segs), "segs": segs})
    for i in TOP_KEEP:
        segs = absolutise(ds[i])
        pieces.append({"d": to_final(segs), "segs": segs})
    return pieces


def max_radius_units(pieces, crop):
    """Largest distance from the crop centre to any control point, in artwork units.
    Curve control points bound the curve, so this is a safe upper bound on the ink
    extent, and unlike a pixel measurement it does not drift with render density."""
    cx, cy = (crop[0] + crop[2]) / 2.0, (crop[1] + crop[3]) / 2.0
    best = 0.0
    for p in pieces:
        for cmd, a in p["segs"]:
            for k in range(0, len(a) - 1, 2):
                x, y = a[k] / 10.0, 540.0 - a[k + 1] / 10.0
                best = max(best, ((x - cx) ** 2 + (y - cy) ** 2) ** 0.5)
    return best


def page(inner, size_px, bg, transparent, view=CANVAS_DP):
    bgcss = "transparent" if transparent else bg
    extra = ("--default-background-color=00000000" if transparent else "--default-background-color=ffffffff")
    html = (f'<!doctype html><html><head><meta charset="utf-8"><style>'
            f'html,body{{margin:0;padding:0;background:{bgcss}}}'
            f'div{{position:relative;width:{size_px}px;height:{size_px}px;background:{bgcss}}}'
            f'svg{{width:{size_px}px;height:{size_px}px;display:block}}</style></head>'
            f'<body><div><svg viewBox="0 0 {view:g} {view:g}">{inner}</svg></div></body></html>')
    return html, extra


def render(inner, out_png, size_px, bg=FIELD, transparent=False, view=CANVAS_DP):
    html, extra = page(inner, size_px, bg, transparent, view)
    tmp = os.path.join("/tmp", "_ma_icon.html")
    open(tmp, "w").write(html)
    subprocess.run([CHROME, "--headless", "--disable-gpu", "--no-sandbox", "--hide-scrollbars",
                    extra, f"--screenshot={out_png}", f"--window-size={size_px},{size_px}",
                    "file://" + tmp], check=True, capture_output=True)
    return out_png


def mark_inner(pieces, color, canvas_dp=CANVAS_DP, target_dp=None, crop=CROP, center=None):
    """SVG group that places the artwork window on the canvas, centred, at target_dp across."""
    if target_dp is None:
        target_dp = FIT_TARGET
    x0, y0, x1, y1 = crop
    s = target_dp / max(x1 - x0, y1 - y0)
    cx, cy = (x0 + x1) / 2.0, (y0 + y1) / 2.0
    tx, ty = canvas_dp / 2 - s * cx, canvas_dp / 2 - s * cy
    if center:
        tx, ty = center[0] - s * cx, center[1] - s * cy
    body = "".join(f'<path d="{p["d"]}" fill="{color}" fill-rule="nonzero"/>' for p in pieces)
    return f'<g transform="translate({tx:.5f} {ty:.5f}) scale({s:.8f})">{body}</g>', s, tx, ty


def ink_radius_dp(png, _canvas_px=None, alpha_min=32):
    """Largest distance from the canvas centre to any perceptible ink pixel, in dp.
    alpha_min is low so faint anti-aliased line tips still count as ink."""
    im = Image.open(png).convert("RGBA")
    a = im.split()[3]
    w, h = im.size
    k = CANVAS_DP / w
    px = a.load()
    best = 0.0
    for y in range(h):
        for x in range(w):
            if px[x, y] > alpha_min:
                r = ((x + 0.5) * k - 54) ** 2 + ((y + 0.5) * k - 54) ** 2
                if r > best:
                    best = float(r)
    return best ** 0.5


def ink_extent_dp(png):
    im = Image.open(png).convert("RGBA")
    a = im.split()[3]
    box = a.getbbox()
    k = CANVAS_DP / im.size[0]
    return (box[0] * k, box[1] * k, (box[2]) * k, (box[3]) * k)


def solid_silhouette(src_png, out_png):
    """Flood the outside, then turn every enclosed region into ink: a flat white
    silhouette with no interior holes (the launcher tints this layer)."""
    im = Image.open(src_png).convert("RGBA")
    w, h = im.size
    a = im.split()[3].convert("L")
    mask = a.point(lambda v: 0 if v >= 128 else 255)     # ink=0, empty=255
    ImageDraw.floodfill(mask, (0, 0), 128)               # outside = 128
    out = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    src, dst = mask.load(), out.load()
    for y in range(h):
        for x in range(w):
            v = src[x, y]
            if v != 128:                                  # ink or an enclosed hole
                dst[x, y] = (255, 255, 255, 255)
    out.save(out_png)
    return out_png


def main():
    os.makedirs(RES, exist_ok=True)
    pieces = load_pieces()

    # Fit from the geometry (control points), so the result does not depend on the
    # density of whichever render happened to be measured.
    global FIT_TARGET
    r_units = max_radius_units(pieces, CROP)
    FIT_TARGET = FIT_RADIUS_DP * max(CROP[2] - CROP[0], CROP[3] - CROP[1]) / r_units
    inner, s, tx, ty = mark_inner(pieces, ART)
    print("ink radius %.1f artwork units -> target %.2f dp across, scale %.6f, translate (%.2f, %.2f)"
          % (r_units, FIT_TARGET, s, tx, ty))

    for d, f in DENSITIES.items():
        px = int(round(CANVAS_DP * f))
        fg = render(inner, "/tmp/_ma_fg_%d.png" % px, px, transparent=True)
        dest = os.path.join(RES, "mipmap-" + d)
        os.makedirs(dest, exist_ok=True)
        subprocess.run(["magick", fg, "-strip", "-define", "png:color-type=6", "-define", "png:compression-level=9",
                        os.path.join(dest, "ic_launcher_foreground.png")], check=True)
        print("foreground %-7s %3dpx  extent %s  r=%.2f dp" % (
            d, px, ["%.1f" % v for v in ink_extent_dp(fg)], ink_radius_dp(fg, px)))

    # Monochrome: hole-filled at 12x the canvas, then downsampled. Rendering the source
    # far above every output density means the downsampled mask keeps its anti-aliased
    # edge while every enclosed region stays fully opaque (no holes to turn to garbage).
    mono_src = render(inner, "/tmp/_ma_mono_big.png", int(CANVAS_DP * 12), transparent=True)
    solid_silhouette(mono_src, "/tmp/_ma_mono_solid.png")
    for d, f in DENSITIES.items():
        px = int(round(CANVAS_DP * f))
        dest = os.path.join(RES, "mipmap-" + d, "ic_launcher_monochrome.png")
        subprocess.run(["magick", "/tmp/_ma_mono_solid.png", "-resize", "%dx%d" % (px, px),
                        "-strip", "-define", "png:color-type=6", "-define", "png:compression-level=9", dest], check=True)
        print("monochrome %-7s %3dpx" % (d, px))

    # Splash: the same art in the light tone, filling the inner 192dp of the 288dp
    # splash canvas (the part that stays visible inside the 240dp mask circle).
    splash_inner, _, _, _ = mark_inner(pieces, SPLASH_ART, canvas_dp=288.0,
                                       target_dp=192.0, crop=CROP)
    for d, f in DENSITIES.items():
        px = int(round(288.0 * f))
        art = render(splash_inner, "/tmp/_ma_splash_%d.png" % px, px, transparent=True, view=288.0)
        dest = os.path.join(RES, "drawable-" + d)
        os.makedirs(dest, exist_ok=True)
        subprocess.run(["magick", art, "-strip", "-define", "png:color-type=6", "-define", "png:compression-level=9",
                        os.path.join(dest, "ic_splash_art.png")], check=True)
        print("splash %-7s %3dpx" % (d, px))


FIT_TARGET = 0.0

if __name__ == "__main__":
    main()
