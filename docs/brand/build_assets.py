#!/usr/bin/env python3
"""Builds every MaterialAgent brand asset from docs/brand/wuxje01.svg.

The owner artwork is flat line art: 48 <path> elements with no fill and no stroke,
so every shape is a solid black region and the drawing is nothing but line work.
That means the illustration has to be *colourised* here, not merely resized: the
picture is reconstructed by

  1. rendering the SVG transparently (Chromium, headless),
  2. sealing the gaps in the line work with a morphological close (radius 4),
     which is what turns the face into one enclosed mass instead of a silhouette,
  3. flood-filling from outside to label every enclosed interior,
  4. filling those interiors with the brand palette, and
  5. drawing the irises, which the source art simply does not contain (the eye
     whites are blank interiors, which is why the eyes used to read as hollow).

Requirements: Pillow (with numpy), and a Chromium/Chrome binary. Run from the
repository root:

    python3 docs/brand/build_assets.py

Every output path is inside app/src/main/res/, plus preview sheets in
docs/brand/preview/.
"""
import colorsys
import os
import subprocess

import numpy as np
from PIL import Image, ImageDraw, ImageFilter, ImageFont, ImageOps

REPO = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
RES = os.path.join(REPO, 'app', 'src', 'main', 'res')
SVG = os.path.join(REPO, 'docs', 'brand', 'wuxje01.svg')
WORK = os.path.join(REPO, 'docs', 'brand', 'preview')
BUILD = os.path.join(WORK, 'build')
CHROMIUM = os.environ.get(
    'CHROMIUM',
    '/Users/user/.cloakbrowser/chromium-145.0.7632.109.2/Chromium.app/Contents/MacOS/Chromium',
)

# The illustration's own canvas, in SVG viewBox units, and the square resolution
# the colour work happens at.
RENDER_W, RENDER_H = 1600, 1440
ART = 1120

# Brand palette.
INK = (0x14, 0x08, 0x33)      # line work, irises
BODY = (0xFF, 0xF1, 0xD8)     # skin
GOLD = (0xFF, 0xC2, 0x4B)     # hair and the eye beams
CORAL = (0xFF, 0x8A, 0x73)    # mouth
ROSE = (0xFF, 0xB4, 0xAB)     # lips
LILAC = (0xC4, 0xA9, 0xFF)
MINT = (0x7E, 0xE0, 0xC4)
SKY = (0x8C, 0xC8, 0xFF)
BG_TOP = (0x3A, 0x1A, 0x7A)   # launcher gradient, indigo
BG_BOTTOM = (0x6A, 0x35, 0xC8)  # launcher gradient, violet
BG_PLATE = (0x4A, 0x24, 0x84)  # splash icon plate, the gradient's midpoint

# Interiors identified by hand from a labelled render of the region map (see
# preview/regions_labeled.png) and confirmed by an A/B render. They are keyed by
# position relative to the face's bounding box plus a loose area check, rather
# than by region index or exact area, so a re-render that shifts the numbering or
# changes the sampling by a percent cannot silently move an iris into the mouth.
FACE_AREA = 93874
EYE_NEAR = (0.28, 0.25, 2135)   # the wide, fully drawn eye
EYE_FAR = (0.62, 0.12, 573)     # the other eye, only partly visible
MOUTH = (0.77, 0.55, 3019)
LIP_UPPER = (0.78, 0.42, 243)
LIP_LOWER = (0.78, 0.65, 1055)

# Everything right of the face and above this fraction of its height is one of
# the shapes streaming away from the character; the figure's own body sits below
# it, and is painted with the hair.
PARTICLE_BAND = 0.55
PARTICLE_CYCLE = [SKY, MINT, LILAC, GOLD, CORAL, ROSE]


def sh(cmd, **kw):
    return subprocess.run(cmd, check=True, capture_output=True, **kw)


def render_svg():
    """Render the SVG transparently, at its own aspect ratio so nothing is clipped."""
    os.makedirs(BUILD, exist_ok=True)
    html_path = os.path.join(BUILD, 'art.html')
    png_path = os.path.join(BUILD, 'art_raw.png')
    with open(SVG) as f:
        svg = f.read()
    svg = svg.replace('height="540pt"', f'height="{RENDER_H}px"')
    svg = svg.replace('width="600pt"', f'width="{RENDER_W}px"')
    with open(html_path, 'w') as f:
        f.write('<html><body style="margin:0">'
                '<style>path{fill:#000}svg{display:block}</style>'
                + svg + '</body></html>')
    sh([CHROMIUM, '--headless', '--disable-gpu', '--hide-scrollbars',
        '--force-device-scale-factor=1', '--default-background-color=00000000',
        f'--window-size={RENDER_W},{RENDER_H}', f'--screenshot={png_path}',
        'file://' + html_path])
    return png_path


def build_regions(png_path):
    """Ink mask (bolded, gaps closed) and every enclosed interior."""
    im = Image.open(png_path).convert('RGBA')
    a = np.array(im)[:, :, 3] > 40
    ys, xs = np.where(a)
    im = im.crop((xs.min(), ys.min(), xs.max() + 1, ys.max() + 1))
    scale = ART / max(im.size)
    im = im.resize((max(1, round(im.size[0] * scale)), max(1, round(im.size[1] * scale))), Image.LANCZOS)
    canvas = Image.new('RGBA', (ART, ART), (0, 0, 0, 0))
    canvas.alpha_composite(im, ((ART - im.size[0]) // 2, (ART - im.size[1]) // 2))
    inp = np.array(canvas)[:, :, 3] > 90
    # Close radius 4 (9x9) seals the breaks in the line work; without it the face
    # leaks to the outside and is filled as background.
    closed = np.array(Image.fromarray((inp * 255).astype('uint8'))
                      .filter(ImageFilter.MaxFilter(9)).filter(ImageFilter.MinFilter(9))) > 127
    ink = np.array(Image.fromarray((closed * 255).astype('uint8')).filter(ImageFilter.MaxFilter(5))) > 127

    # After trimming, (0,0) is ink, so the flood seed needs a one-pixel margin.
    lab = ImageOps.expand(
        Image.fromarray(np.where(ink, 0, 255).astype('uint8')).convert('RGB'), 1, fill=(255, 255, 255))
    ImageDraw.floodfill(lab, (0, 0), (255, 0, 0))
    arr = np.array(lab)
    regions = []
    while True:
        free = np.where((arr[1:-1, 1:-1, 0] == 255) & (arr[1:-1, 1:-1, 1] == 255) & (arr[1:-1, 1:-1, 2] == 255))
        if len(free[0]) == 0:
            break
        y0, x0 = int(free[0][0]) + 1, int(free[1][0]) + 1
        col = (len(regions) + 1, 0, 0)
        ImageDraw.floodfill(lab, (x0, y0), col)
        arr = np.array(lab)
        sel = (arr[1:-1, 1:-1, 0] == col[0]) & (arr[1:-1, 1:-1, 1] == 0) & (arr[1:-1, 1:-1, 2] == 0)
        n = int(sel.sum())
        if n < 12:
            continue
        ys2, xs2 = np.where(sel)
        regions.append(dict(n=n, mask=sel, cx=float(xs2.mean()), cy=float(ys2.mean()),
                            x0=int(xs2.min()), x1=int(xs2.max()), y0=int(ys2.min()), y1=int(ys2.max())))
    regions.sort(key=lambda r: -r['n'])
    outside = (arr[1:-1, 1:-1, 0] == 255) & (arr[1:-1, 1:-1, 1] == 0)
    stats = dict(outside=float(outside.mean()), ink=float(ink.mean()),
                 interior=sum(r['n'] for r in regions) / (ART * ART))
    return ink, regions, stats


def write_region_map(regions, ink, path):
    """Label every interior with a distinct hue and its index.

    This is the map the eye, mouth and lip coordinates above were read off; it is
    regenerated on every build so the next person can re-check them rather than
    trust them.
    """
    out = np.zeros((ART, ART, 3), np.uint8)
    out[ink] = (0, 0, 0)
    for i, r in enumerate(regions):
        c = tuple(int(v * 255) for v in colorsys.hsv_to_rgb((i * 0.61803398875) % 1.0, 0.75, 0.95))
        out[r['mask']] = c
    img = Image.fromarray(out)
    d = ImageDraw.Draw(img)
    font = ImageFont.load_default(size=22)
    for i, r in enumerate(regions):
        d.rectangle([r['x0'], r['y0'], r['x1'], r['y1']], outline=(255, 255, 255), width=2)
        tx, ty = r['cx'] - 10, r['cy'] - 11
        d.rectangle([tx - 2, ty - 2, tx + 8 * len(str(i)) + 4, ty + 24], fill=(0, 0, 0))
        d.text((tx, ty), str(i), fill=(255, 255, 255), font=font)
    img.save(path)
    return path


def face_of(regions):
    """The illustration's face: its largest enclosed interior, by a wide margin."""
    face = regions[0]
    assert face['n'] > 2 * regions[1]['n'], 'largest region is not the face'
    assert abs(face['n'] - FACE_AREA) < FACE_AREA * 0.2, f"face area {face['n']} drifted"
    return face


def find(regions, key, face):
    """The region nearest a proven position inside the face, within a size band."""
    relx, rely, area = key
    w = face['x1'] - face['x0']
    h = face['y1'] - face['y0']
    best, bestd = None, None
    for i, r in enumerate(regions):
        if r is face or not (face['x0'] < r['cx'] < face['x1'] and face['y0'] < r['cy'] < face['y1']):
            continue
        if not (area * 0.7 < r['n'] < area * 1.4):
            continue
        d = ((r['cx'] - face['x0']) / w - relx) ** 2 + ((r['cy'] - face['y0']) / h - rely) ** 2
        if bestd is None or d < bestd:
            best, bestd = i, d
    if best is None or bestd > 0.02:
        raise SystemExit(f'region {key} not found ({bestd}) -- re-check the region map')
    return best


def iris(region, dilate=1.0, dx=0.03, dy=-0.06, glint=0.30):
    """A filled iris inside an eye, nudged toward the gaze, plus a catchlight."""
    w = region['x1'] - region['x0'] + 1
    h = region['y1'] - region['y0'] + 1
    r = min(w, h) * 0.46 / 2 * dilate
    cx = region['cx'] + w * dx
    cy = region['cy'] + h * dy
    m = Image.new('L', (ART, ART), 0)
    d = ImageDraw.Draw(m)
    d.ellipse((cx - r, cy - r, cx + r, cy + r), fill=255)
    g = Image.new('L', (ART, ART), 0)
    gr = max(1.5, r * glint)
    ImageDraw.Draw(g).ellipse((cx - r * 0.45 - gr, cy - r * 0.5 - gr,
                               cx - r * 0.45 + gr, cy - r * 0.5 + gr), fill=255)
    return np.array(m) > 127, np.array(g) > 127


def paint(ink, regions):
    """Flat colour beneath one ink pass: the whole illustration in one image."""
    face = face_of(regions)
    eyes = [find(regions, EYE_NEAR, face), find(regions, EYE_FAR, face)]
    mouth = find(regions, MOUTH, face)
    lips = [find(regions, LIP_UPPER, face), find(regions, LIP_LOWER, face)]
    particle_floor = face['y0'] + (face['y1'] - face['y0']) * PARTICLE_BAND
    particles = sorted(
        (i for i, r in enumerate(regions) if r['cx'] > face['x1'] and r['cy'] < particle_floor),
        key=lambda i: regions[i]['cx'] + regions[i]['cy'])

    rgb = np.zeros((ART, ART, 3), np.uint8)
    alpha = np.zeros((ART, ART), np.uint8)
    for i, r in enumerate(regions):
        if r is face or i in eyes:
            c = BODY
        elif i == mouth:
            c = CORAL
        elif i in lips:
            c = ROSE
        elif i in particles:
            c = PARTICLE_CYCLE[particles.index(i) % len(PARTICLE_CYCLE)]
        else:
            c = GOLD
        rgb[r['mask']] = c
        alpha[r['mask']] = 255
    for e in eyes:
        m, g = iris(regions[e])
        m = m & ~ink
        rgb[m] = INK
        alpha[m] = 255
        rgb[g & m] = BODY
    rgb[ink] = INK
    alpha[ink] = 255
    return Image.fromarray(np.dstack([rgb, alpha]))


def content_box(fg):
    a = np.array(fg)[:, :, 3] > 40
    ys, xs = np.where(a)
    return xs.min(), ys.min(), xs.max() + 1, ys.max() + 1


def place(fg, canvas_px, box_px, out, mono=False):
    """Centre the art's content inside a square canvas, its longest side at box_px."""
    x0, y0, x1, y1 = content_box(fg)
    crop = fg.crop((x0, y0, x1, y1))
    scale = box_px / max(crop.size)
    size = (max(1, round(crop.size[0] * scale)), max(1, round(crop.size[1] * scale)))
    art = crop.resize(size, Image.LANCZOS)
    if mono:
        a = np.array(art)[:, :, 3]
        sil = np.array(Image.fromarray(a).filter(ImageFilter.MaxFilter(3))) > 90
        art = Image.fromarray(np.dstack([np.full((size[1], size[0]), 255, np.uint8)] * 3
                                        + [np.where(sil, 255, 0).astype(np.uint8)]))
    canvas = Image.new('RGBA', (canvas_px, canvas_px), (0, 0, 0, 0))
    canvas.alpha_composite(art, ((canvas_px - size[0]) // 2, (canvas_px - size[1]) // 2))
    os.makedirs(os.path.dirname(out), exist_ok=True)
    canvas.save(out)
    return out


def on_bg(fg, size, bg=None, art_dp=74, circle=False):
    """Preview helper: the art on the launcher gradient.

    With `circle`, this reproduces what a launcher actually shows: the layers are
    drawn at 108dp but only the central 72dp square is visible, so the mask is a
    circle inscribed in *that* square, and the crop is scaled to `size`.
    """
    canvas_px = 108 * 4
    if bg is None:
        canvas = Image.new('RGBA', (canvas_px, canvas_px), (0, 0, 0, 0))
    else:
        yy = np.linspace(0, 1, canvas_px)[:, None]
        rows = np.zeros((canvas_px, canvas_px, 3), np.uint8)
        for c in range(3):
            rows[:, :, c] = (bg[0][c] * (1 - yy) + bg[1][c] * yy)[:, 0]
        canvas = Image.fromarray(rows).convert('RGBA')
    im = place_to_size(fg, canvas_px, art_dp)
    canvas.alpha_composite(im, ((canvas_px - im.size[0]) // 2, (canvas_px - im.size[1]) // 2))
    if not circle:
        return canvas.resize((size, size), Image.LANCZOS)
    inner = int(round(canvas_px * 72 / 108))
    off = (canvas_px - inner) // 2
    m = Image.new('L', (canvas_px, canvas_px), 0)
    ImageDraw.Draw(m).ellipse((off + 1, off + 1, off + inner - 2, off + inner - 2), fill=255)
    canvas.putalpha(m)
    return canvas.crop((off, off, off + inner, off + inner)).resize((size, size), Image.LANCZOS)


def place_to_size(fg, canvas_px, art_dp, full_dp=108):
    x0, y0, x1, y1 = content_box(fg)
    crop = fg.crop((x0, y0, x1, y1))
    box = canvas_px * art_dp / full_dp
    scale = box / max(crop.size)
    size = (max(1, round(crop.size[0] * scale)), max(1, round(crop.size[1] * scale)))
    return crop.resize(size, Image.LANCZOS)


def write(path, im):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    im.save(path)
    return path


def write_path(*parts):
    return os.path.join(*parts)


def main():
    os.makedirs(WORK, exist_ok=True)
    png = render_svg()
    ink, regions, stats = build_regions(png)
    print(f'outside {stats["outside"]*100:.1f}%  ink {stats["ink"]*100:.1f}%  '
          f'interior {stats["interior"]*100:.1f}%  regions {len(regions)}')
    write_region_map(regions, ink, os.path.join(WORK, 'regions_labeled.png'))
    fg = paint(ink, regions)
    write(os.path.join(WORK, 'colour_art.png'), fg)

    face = face_of(regions)
    for label, key in (('face', None), ('eye', EYE_NEAR), ('eye', EYE_FAR), ('mouth', MOUTH)):
        if key is None:
            r = face
        else:
            r = regions[find(regions, key, face)]
        print(f'  {label:6s} area={r["n"]:6d} centre=({r["cx"]:.0f},{r["cy"]:.0f})')

    # --- launcher -----------------------------------------------------------
    # 108dp canvas; the art's longest side is 74dp, which is the adaptive icon's
    # safe area plus the 1dp the system may crop on each side under a round mask.
    for bucket, density in (('mdpi', 1), ('hdpi', 1.5), ('xhdpi', 2),
                            ('xxhdpi', 3), ('xxxhdpi', 4)):
        px = int(round(108 * density))
        place(fg, px, 74 * density, write_path(RES, f'mipmap-{bucket}', 'ic_launcher_foreground.png'))
        place(fg, px, 74 * density, write_path(RES, f'mipmap-{bucket}', 'ic_launcher_monochrome.png'), mono=True)

    # --- splash -------------------------------------------------------------
    # 288dp canvas. The platform masks the splash icon to a 192dp circle, so the
    # art is held at 150dp -- inside the circle's inscribed rectangle, corners and
    # all -- and sits on the brand plate the theme draws behind it.
    for bucket, density in (('mdpi', 1), ('hdpi', 1.5), ('xhdpi', 2),
                            ('xxhdpi', 3), ('xxxhdpi', 4)):
        px = int(round(288 * density))
        place(fg, px, 150 * density, write_path(RES, f'drawable-{bucket}', 'ic_splash_art.png'))

    # --- in-app -------------------------------------------------------------
    place(fg, 1024, 1024, write_path(RES, 'drawable-nodpi', 'ic_agent_art.png'))
    place(fg, 256, 256, write_path(RES, 'drawable-nodpi', 'ic_agent_art_silhouette.png'), mono=True)

    # --- preview sheets -----------------------------------------------------
    items = []
    for dp, art_dp in ((48, 33), (72, 49), (96, 66), (144, 99), (192, 132)):
        items.append(on_bg(fg, dp * 4, (BG_TOP, BG_BOTTOM), art_dp, circle=True).resize((dp, dp), Image.LANCZOS))
    items.append(on_bg(fg, 432, (BG_TOP, BG_BOTTOM), 74, circle=False))
    items.append(on_bg(fg, 432, None, 74, circle=False))
    sheet_preview(items, os.path.join(WORK, 'icon_sizes.png'))
    print('wrote preview/icon_sizes.png')


def sheet_preview(items, path, bgc=(238, 238, 238), pad=14):
    w = sum(i.size[0] for i in items) + pad * (len(items) + 1)
    h = max(i.size[1] for i in items) + pad * 2
    out = Image.new('RGB', (w, h), bgc)
    x = pad
    for i in items:
        out.paste(i, (x, pad + (h - 2 * pad - i.size[1]) // 2), i if i.mode == 'RGBA' else None)
        x += i.size[0] + pad
    out.save(path)


if __name__ == '__main__':
    main()