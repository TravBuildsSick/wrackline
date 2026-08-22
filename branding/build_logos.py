import os
from fontTools.ttLib import TTFont
from fontTools.pens.svgPathPen import SVGPathPen
from fontTools.pens.transformPen import TransformPen
from fontTools.pens.recordingPen import RecordingPen
from fontTools.misc.transform import Transform

FONTS = "/mnt/skills/examples/canvas-design/canvas-fonts"
OUT = "/mnt/user-data/outputs"
os.makedirs(OUT, exist_ok=True)

# sunset ramp: deep blue -> red -> orange -> yellow
STOPS = [
    (0.00, "#0B1B3A"),
    (0.15, "#7C2622"),
    (0.42, "#CE4E1C"),
    (0.70, "#ED8B1F"),
    (1.00, "#F4C63D"),
]
INK = "#093C39"      # deep blue-green letterforms
INK_SOFT = "#0B4A44"  # slightly lifted, for the strand over the dark top


def text_outline(font_file, text, cap_height, tracking=0.0, target_width=None):
    """Outline `text` at a given optical cap height. tracking is a fraction of
    cap_height inserted between glyphs; target_width solves tracking to fit."""
    if target_width is not None:
        _, natural, _ = text_outline(font_file, text, cap_height, 0.0)
        tracking = (target_width - natural) / (len(text) - 1) / cap_height

    font = TTFont(os.path.join(FONTS, font_file))
    gs = font.getGlyphSet()
    cmap = font.getBestCmap()

    rec = RecordingPen()
    gs[cmap[ord("H")]].draw(rec)
    ys = [pt[1] for _, args in rec.value for pt in args]
    scale = cap_height / (max(ys) - min(ys))
    track_units = (tracking * cap_height) / scale

    pen = SVGPathPen(gs)
    x = 0.0
    for ch in text:
        gname = cmap[ord(ch)]
        gs[gname].draw(TransformPen(pen, Transform(scale, 0, 0, -scale, x * scale, 0)))
        x += gs[gname].width + track_units
    return pen.getCommands(), (x - track_units) * scale, cap_height


def strand(x0, x1, y, amp, teeth, tick_len):
    """Ragged tide-debris line: zigzag polyline plus debris hanging from the lows."""
    pts, step = [], (x1 - x0) / teeth
    for i in range(teeth + 1):
        px = x0 + i * step
        py = y if i in (0, teeth) else (y - amp if i % 2 else y + amp * 0.85)
        pts.append((px, py))
    d = "M" + " L".join(f"{px:.2f} {py:.2f}" for px, py in pts)

    ticks = []
    for i in range(1, teeth):
        px, py = pts[i]
        if py <= y:
            continue
        dx = tick_len * 0.16 * (1 if i % 4 == 1 else -1)
        ticks.append(f"M{px:.2f} {py:.2f} L{px + dx:.2f} {py + tick_len:.2f}")
    return d, " ".join(ticks)


def gradient_def(gid, w, h):
    stops = "\n".join(
        f'      <stop offset="{o:.0%}" stop-color="{c}"/>' for o, c in STOPS
    )
    return (
        f'  <defs>\n    <linearGradient id="{gid}" x1="0" y1="0" x2="0" y2="1">\n'
        f"{stops}\n    </linearGradient>\n  </defs>"
    )


def svg(w, h, body, title):
    return (
        f'<svg xmlns="http://www.w3.org/2000/svg" width="{w:g}" height="{h:g}" '
        f'viewBox="0 0 {w:g} {h:g}" role="img" aria-label="{title}">\n{body}\n</svg>\n'
    )


def build(slug, top_word, bot_word, title):
    # ---------------------------------------------------------- lockup tile
    CAP, PAD, GAP, PAD_TOP = 76.0, 42.0, 40.0, 62.0
    top_d, top_w, _ = text_outline("BigShoulders-Bold.ttf", top_word, CAP, 0.09)
    bot_d, bot_w, _ = text_outline(
        "BigShoulders-Regular.ttf", bot_word, CAP, target_width=top_w
    )
    inner = max(top_w, bot_w)
    W, H = inner + PAD * 2, PAD_TOP + PAD + CAP * 2 + GAP
    sy = PAD_TOP + CAP + GAP / 2
    sd, st = strand(PAD, W - PAD, sy, amp=5.5, teeth=13, tick_len=11)
    gid = f"{slug}-sky"

    body = f"""{gradient_def(gid, W, H)}
  <rect width="{W:g}" height="{H:g}" rx="26" fill="url(#{gid})"/>
  <g fill="{INK}">
    <path transform="translate({PAD + (inner - top_w) / 2:.2f} {PAD_TOP + CAP:.2f})" d="{top_d}"/>
    <path transform="translate({PAD + (inner - bot_w) / 2:.2f} {PAD_TOP + CAP * 2 + GAP:.2f})" d="{bot_d}"/>
  </g>
  <g stroke="{INK}" fill="none" stroke-width="5" stroke-linecap="round" stroke-linejoin="round">
    <path d="{sd}"/>
  </g>
  <g stroke="{INK}" fill="none" stroke-width="3" stroke-linecap="round">
    <path d="{st}"/>
  </g>"""
    open(f"{OUT}/{slug}-lockup.svg", "w").write(svg(W, H, body, title))

    # ------------------------------------------------------------ icon tile
    S = 512.0
    msd, mst = strand(74, S - 74, S / 2 - 44, amp=44, teeth=7, tick_len=112)
    gid2 = f"{slug}-sky-icon"
    mbody = f"""{gradient_def(gid2, S, S)}
  <rect width="{S:g}" height="{S:g}" rx="112" fill="url(#{gid2})"/>
  <g stroke="{INK}" fill="none" stroke-width="36" stroke-linecap="round" stroke-linejoin="round">
    <path d="{msd}"/>
  </g>
  <g stroke="{INK}" fill="none" stroke-width="14" stroke-linecap="round">
    <path d="{mst}"/>
  </g>"""
    open(f"{OUT}/{slug}-icon.svg", "w").write(svg(S, S, mbody, f"{title} icon"))

    # ---------------------------------- adaptive icon: fg + bg as two layers
    A = 108.0
    asd, ast = strand(30, A - 30, A / 2 - 9, amp=9.3, teeth=7, tick_len=23.6)
    fg = f"""  <g stroke="{INK}" fill="none" stroke-width="7.6" stroke-linecap="round" stroke-linejoin="round">
    <path d="{asd}"/>
  </g>
  <g stroke="{INK}" fill="none" stroke-width="3" stroke-linecap="round">
    <path d="{ast}"/>
  </g>"""
    open(f"{OUT}/{slug}-adaptive-foreground.svg", "w").write(
        svg(A, A, fg, f"{title} adaptive foreground")
    )
    gid3 = f"{slug}-sky-bg"
    bg = f"""{gradient_def(gid3, A, A)}
  <rect width="{A:g}" height="{A:g}" fill="url(#{gid3})"/>"""
    open(f"{OUT}/{slug}-adaptive-background.svg", "w").write(
        svg(A, A, bg, f"{title} adaptive background")
    )


build("palewake", "PALE", "WAKE", "Palewake")
build("wrackline", "WRACK", "LINE", "Wrackline")
print("done")
