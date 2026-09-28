# Exports the Polytopia tileset (art rules in DESIGN.md) from mocks/art into Unciv's tileset format.
# Output: android/Images.Tilesets/TileSets/Polytopia/ and android/assets/jsons/TileSets/Polytopia.json
# Every tile image uses one canvas: a flat-top hex W px wide at the bottom, with headroom above only where a sprite
# rises past the hex. Unciv scales each image to the hex width and aligns bottoms, so sprites keep their scale.
# Portrait (Tilted/, Strategic/, Props/) follows direction A "Polished" of mocks/terrain-proposal.html: ground art is
# drawn as SVG in the mock's units and rendered by librsvg, and everything standing on a tile is a prop sprite that
# the engine draws from the "props" table, zoom-damped and with more detail per LOD tier. Landscape output is unchanged.
# Run: python3 tools/polytopia-tileset/export.py   (needs ImageMagick 7 with librsvg on PATH)
import base64, colorsys, json, math, pathlib, re, shutil, subprocess, tempfile

ROOT = pathlib.Path(__file__).resolve().parents[2]
ART = ROOT / "mocks/art"
RULES = ROOT / "android/assets/jsons/Civ V - Gods & Kings"
OUT = ROOT / "android/Images.Tilesets/TileSets/Polytopia"
W = 192
H = round(W * math.sqrt(3) / 2)  # 166, flat-top hex height
MAP_VERTICAL_SCALE = 0.6
SIDE = 8                           # slab edge under the lower three sides
CX, CY = W / 2, H / 2

# Landscape palette: lit facets, shaded facets, slab side (DESIGN.md)
TERRAIN = {
    "Ocean": ("#2f7ad6", "#2a6fc6", None), "Coast": ("#3fb5e3", "#37a8d7", None), "Lakes": ("#46b9e6", "#3eaedb", None),
    "Grassland": ("#88cd50", "#7dc248", "#5f9a36"), "Plains": ("#e3ca52", "#d9be47", "#b39532"),
    "Desert": ("#eba45b", "#e0974d", "#b8763a"), "Tundra": ("#bdb993", "#b1ad87", "#8d8a67"),
    "Snow": ("#f3f7fa", "#e5edf3", "#b9c7d3"), "Mountain": ("#a3acb6", "#959ea9", "#737c87"),
}
# feature image name -> (sprite files for variants, sprite size, bottom y inside the hex)
FEATURES = {
    "Forest": (["f_forest1", "f_forest2", "f_forest3"], 176, 150), "Jungle": (["f_jungle1", "f_jungle2"], 176, 150),
    "Hill": (["f_hill1", "f_hill2"], 164, 146), "Marsh": (["f_marsh"], 150, 146), "Oasis": (["f_atoll"], 130, 144),
    "Flood plains": (["f_flood"], 164, 146), "Ice": (["f_ice"], 160, 146), "Atoll": (["f_atoll"], 150, 146), "Fallout": (["f_fallout"], 150, 146),
}
DECOR = {"Grassland": "f_grass", "Plains": "f_plains", "Desert": "f_desert1", "Tundra": "f_tundra", "Snow": "f_snow"}


def magick(*args):
    *ops, out = map(str, args)
    # strip timestamps so an unchanged image exports byte-identical and git sees no churn
    subprocess.run(["magick", *ops, "-define", "png:exclude-chunks=date,time,tIME", "-strip", out], check=True)


def hexpts(cy=CY, h=H):
    """Flat-top hex vertices clockwise from the left corner."""
    return [(0, cy), (W / 4, cy - h / 2), (3 * W / 4, cy - h / 2), (W, cy), (3 * W / 4, cy + h / 2), (W / 4, cy + h / 2)]


def poly(pts):
    return " ".join(f"{x:.1f},{y:.1f}" for x, y in pts)


def slab(name):
    """Two-tone hex face; land gets a darker slab edge along the lower sides."""
    lit, shade, side = TERRAIN[name]
    top = hexpts(CY - (SIDE / 2 if side else 0), H - (SIDE if side else 0))
    draw = []
    if side:
        full = hexpts()
        draw.append(f"fill {side} polygon {poly([full[0], top[0], top[5], top[4], top[3], full[3], full[4], full[5]])}")
    c = (CX, (top[1][1] + top[4][1]) / 2)
    # facets facing the light (upper left): the top-left, top and bottom-left wedges
    for i in range(6):
        colour = lit if i in (0, 1, 5) else shade
        draw.append(f"fill {colour} stroke {colour} stroke-width 0.8 polygon {poly([c, top[i], top[(i + 1) % 6]])}")
    draw.append(f"fill none stroke rgba(20,40,20,0.10) stroke-width 1.2 polygon {poly(top)}")
    return draw


def sprite_png(key):
    p = ART / f"{key}.png"
    if not p.exists():
        raise SystemExit(f"missing art: {p}")
    return p


def place(canvas_h, key, size, bottom, cx=CX, head=0):
    """ImageMagick args that composite a square sprite, bottom-anchored inside the hex."""
    return ["(", sprite_png(key), "-resize", f"{size}x{size}", ")", "-geometry", f"+{cx - size / 2:.0f}+{head + bottom - size:.0f}", "-composite"]


def headroom(size, bottom):
    return max(0, math.ceil(size - bottom))


def write(rel, draw=(), sprites=(), recolor=None):
    """Landscape image: slab drawing plus sprites baked in."""
    head = max([0] + [headroom(s, b) for _, s, b, *_ in sprites])
    out = OUT / rel
    out.parent.mkdir(parents=True, exist_ok=True)
    args = ["-size", f"{W}x{H + head}", "xc:none"]
    if draw:
        args += ["-draw", f"translate 0,{head} " + " ".join(draw)]
    for key, size, bottom, *rest in sprites:
        args += place(H, key, size, bottom, cx=rest[0] if rest else CX, head=head)
    if recolor:
        args += recolor
    magick(*args, out)


def load(name):
    s = (RULES / name).read_text()
    s = re.sub(r'("(?:\\.|[^"\\])*")|//[^\n]*', lambda m: m.group(1) or "", s)
    s = re.sub(r"/\*.*?\*/", "", s, flags=re.S)
    return json.loads(re.sub(r",(\s*[}\]])", r"\1", s))


# Key green in city roofs becomes terracotta until cities get a civ color layer
GREEN = "(u.g>0.24 && u.g>u.r*1.45 && u.g>u.b*1.45)"
NEUTRAL_ROOFS = ["-channel", "RGB", "-fx", f"{GREEN} ? (u.g>0.5 ? channel(0.85,0.47,0.29,0) : channel(0.66,0.36,0.22,0)) : u", "+channel"]


def units():
    """Base sprite with its team cloth removed, plus a -1 layer of that cloth in greys for Unciv to tint."""
    made = 0
    for u in load("Units.json") + [{"name": n} for n in ("EmbarkedUnit-Military", "EmbarkedUnit-Civilian", "EmbarkedUnit-Settler")]:
        src = ART / f"k_{u['name'].lower()}.png"
        if not src.exists():
            print("no unit art:", u["name"]); continue
        for tilted in (False, True):
            if tilted:
                bounds = subprocess.check_output(["magick", str(src), "-alpha", "extract", "-threshold", "9.4%",
                                                  "-format", "%@", "info:"], text=True).strip()
                bw, bh, x0, y0 = map(int, re.fullmatch(r"(\d+)x(\d+)\+(\d+)\+(\d+)", bounds).groups())
                x1, y1 = x0 + bw, y0 + bh
                sprite_h = round(36 * W / 62)
                sprite_w = round((x1 - x0) * sprite_h / (y1 - y0))
                head = headroom(sprite_h, CY)
                common = ["-size", f"{W}x{H + head}", "xc:none", "-draw",
                          f"fill rgba(0,0,0,.24) ellipse {CX},{head + CY + 5} 40,15 0,360 "
                          f"fill none stroke #00e000 stroke-width 8 ellipse {CX},{head + CY} 37,14 0,360",
                          "(", src, "-crop", f"{x1-x0}x{y1-y0}+{x0}+{y0}", "+repage", "-resize", f"{sprite_w}x{sprite_h}!", ")",
                          "-geometry", f"+{CX - sprite_w / 2:.0f}+{head + CY - sprite_h:.0f}", "-composite"]
            else:
                bottom = 148
                head = headroom(132, bottom)
                common = ["-size", f"{W}x{H + head}", "xc:none", "(", src, "-resize", "132x132", ")", "-geometry", f"+{CX - 66:.0f}+{head + bottom - 132:.0f}", "-composite"]
            unit_out = OUT / ("Tilted" if tilted else "") / "Units"
            unit_out.mkdir(parents=True, exist_ok=True)
            magick(*common, "-channel", "A", "-fx", f"{GREEN} ? 0 : u", "+channel", unit_out / f"{u['name']}.png")
            # alpha first, while the pixels are still green; then tintable greys
            magick(*common, "-channel", "A", "-fx", f"{GREEN} ? u : 0", "-channel", "RGB", "-fx", "min(1, u.g*1.12)", "+channel",
                   unit_out / f"{u['name']}-1.png")
        made += 1
    return made


def edges():
    """Landscape shorelines: sand band on land edges that meet water; a shallow band with a surf line on water edges."""
    names = ["Top", "TopRight", "BottomRight", "Bottom", "BottomLeft", "TopLeft"]
    v = hexpts()
    inner = [(CX + (x - CX) * 0.84, CY + (y - CY) * 0.84) for x, y in v]
    edge_of = {"Top": 1, "TopRight": 2, "BottomRight": 3, "Bottom": 4, "BottomLeft": 5, "TopLeft": 0}
    for n in names:
        i = edge_of[n]; a, b, ai, bi = v[i], v[(i + 1) % 6], inner[i], inner[(i + 1) % 6]
        write(f"Edges/Beach-Land-Water-{n}.png", [f"fill #f3dfa8 polygon {poly([a, b, bi, ai])}"])
        write(f"Edges/Surf-Water-Land-{n}.png", [f"fill rgba(143,220,242,0.6) polygon {poly([a, b, bi, ai])}",
                                                 f"stroke rgba(255,255,255,0.75) stroke-width 3 line {a[0]:.1f},{a[1]:.1f} {b[0]:.1f},{b[1]:.1f}"])


# ================= portrait: direction A "Polished" =================
# Geometry is the mock's: a pointy hex of radius R squashed by MAP_VERTICAL_SCALE, centred on the tile's ground centre,
# y down. The texture is 2R units wide, so 1 unit = hexagonImageWidth / 62.
R = 31
SQ = MAP_VERTICAL_SCALE
HW = math.sqrt(3) * R              # hex width on screen, in units
DY = 1.5 * R * SQ                  # row step
WOFF = 3                           # water faces sit lower than land
SLAB = 7                           # land slab side depth
TILT = 1.25                        # slab resolution: the Close stop shows the hex about 1.8x larger than today
EDGE = 1                           # shoreline overlays are thin bands and stay at the landscape resolution (atlas budget)
# Upright props: 3 px per point at the Close stop. hexagonImageWidth is 91.2pt at z = 1.6 (the mock's BASE), and props
# grow on screen by (2.9 / 1.6)^0.4 from there. Ground props follow the hex like the slabs.
UP = 3 * 91.2 / 62 * (2.9 / 1.6) ** 0.4    # 5.6 px per unit
GR = 6.0
# engine neighbour direction -> edge of the tilted hex (0 NE, 1 E, 2 SE, 3 SW, 4 W, 5 NW)
DIRS = {"TopRight": 0, "BottomRight": 1, "Bottom": 2, "BottomLeft": 3, "TopLeft": 4, "Top": 5}

# A palette: lit facets, shaded facets, lit side, dark side. Snow is not in the mock: today's snow on A's tone steps.
A_LAND = {
    "Grassland": ("#8fd255", "#76bf4a", "#5f9636", "#4b7c2c"), "Plains": ("#ecd05a", "#dcb947", "#b8913a", "#9a7630"),
    "Desert": ("#f0a862", "#e08f4f", "#bd733f", "#9f5d33"), "Tundra": ("#c4c19a", "#aeb08c", "#8a8966", "#737254"),
    "Snow": ("#f4f8fb", "#e2eaf2", "#b7c4d1", "#9aa9b9"), "Mountain": ("#aeb7c2", "#939dab", "#77818f", "#636c79"),
}
# three depths: coast is the shallow ring, lakes sit between, open ocean is the mock's deep tone
A_WATER = {"Coast": ("#4cc3ec", "#44b8e4"), "Lakes": ("#3aa8e2", "#349dd8"), "Ocean": ("#2766c6", "#245ebd")}
# variants differ subtly: hue, saturation, lightness, facet centre x, y
JITTER = [(0, 0, 0, 0, 0), (1.8, 2, -3.2, -2.2, .8), (-1.8, -2, 3.2, 2.2, -.8)]
DEFS = ('<radialGradient id="cs"><stop offset="0" stop-color="#14261a" stop-opacity=".34"/><stop offset=".6" stop-color="#14261a" '
        'stop-opacity=".18"/><stop offset="1" stop-color="#14261a" stop-opacity="0"/></radialGradient>'
        '<linearGradient id="ao" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-opacity="0"/><stop offset="1" stop-opacity=".26"/></linearGradient>'
        f'<radialGradient id="deep" gradientUnits="userSpaceOnUse" cx="0" cy="{WOFF}" r="{R}" '
        f'gradientTransform="translate(0 {WOFF}) scale(1 {SQ}) translate(0 {-WOFF})"><stop offset="0" stop-color="#0b2f78" '
        'stop-opacity=".09"/><stop offset="1" stop-color="#0b2f78" stop-opacity="0"/></radialGradient>'
        '<filter id="soft" x="-30%" y="-30%" width="160%" height="160%"><feGaussianBlur stdDeviation=".9"/></filter>')
TMP = pathlib.Path(tempfile.mkdtemp(prefix="polytopia-"))
SPR = {}    # prop sprite -> (width, bottom below the anchor, size it was drawn at), in units
PROPS = {}  # Tilted image location -> props


def adj(hexc, dh=0, ds=0, dl=0):
    """The mock's HSL nudge: hue in degrees, saturation and lightness in percent."""
    h, l, s = colorsys.rgb_to_hls(*(int(hexc[i:i + 2], 16) / 255 for i in (1, 3, 5)))
    rgb = colorsys.hls_to_rgb((h * 360 + dh) % 360 / 360, min(100, max(0, l * 100 + dl)) / 100, min(100, max(0, s * 100 + ds)) / 100)
    return "#" + "".join(f"{round(v * 255):02x}" for v in rgb)


def f2(v):
    return f"{v:.2f}".rstrip("0").rstrip(".")


def pts(a):
    return " ".join(f"{f2(x)},{f2(y)}" for x, y in a)


def corners(x, y, s=1):
    """Pointy hex corners clockwise from the top, as the mock draws them."""
    return [(x + R * s * math.cos(math.radians(60 * i - 90)), y + R * s * math.sin(math.radians(60 * i - 90)) * SQ) for i in range(6)]


def lerp(a, b, t):
    return (a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t)


def shift(p, dx=0, dy=0):
    return (p[0] + dx, p[1] + dy)


def inward(a, b, c, d):
    """Edge a-b moved d units toward c along its normal."""
    nx, ny = b[1] - a[1], a[0] - b[0]
    if nx * (c[0] - a[0]) + ny * (c[1] - a[1]) < 0:
        nx, ny = -nx, -ny
    k = d / math.hypot(nx, ny)
    return shift(a, nx * k, ny * k), shift(b, nx * k, ny * k)


def polygon(p, fill, extra=""):
    return f'<polygon points="{pts(p)}" fill="{fill}" {extra}/>'


def line(a, b, stroke, width, extra="", cap="round"):
    return (f'<line x1="{f2(a[0])}" y1="{f2(a[1])}" x2="{f2(b[0])}" y2="{f2(b[1])}" stroke="{stroke}" '
            f'stroke-width="{width}" stroke-linecap="{cap}" {extra}/>')


def seeded(n):
    """The mock's Park-Miller generator, so layouts are stable between exports."""
    s = n % 2147483646 + 1

    def rng():
        nonlocal s
        s = s * 16807 % 2147483647
        return s / 2147483647
    return rng


def render(svg, out):
    out.parent.mkdir(parents=True, exist_ok=True)
    src = TMP / "in.svg"
    src.write_text(svg)
    subprocess.run(["magick", "-background", "none", str(src), "-depth", "8", "-define", "png:exclude-chunks=date,time,tIME",
                    "-strip", str(out)], check=True)


def tile_svg(body, out, scale=TILT, head=0.0, defs=""):
    """A tile canvas in mock units: 62 wide, the ground centre where Unciv expects it, headroom above if needed."""
    w = round(W * scale)
    h = round(w * math.sqrt(3) / 2)
    u = w / 62
    head_px = math.ceil(head * u)
    render(f'<svg xmlns="http://www.w3.org/2000/svg" width="{w}" height="{h + head_px}" viewBox="-31 {-(head_px + h / 2) / u:.4f} 62 '
           f'{(h + head_px) / u:.4f}"><defs>{DEFS}{defs}</defs>{body}</svg>', out)


def placeholder(path):
    """Prop-only image: 1x1 transparent so variant discovery and imageExists keep working."""
    path.parent.mkdir(parents=True, exist_ok=True)
    magick("-size", "1x1", "xc:none", path)


# ---------------- ground ----------------
def facets(cx, cy, cs, lit, sh):
    s = ""
    for i in range(6):
        c = lit if i in (4, 5, 0) else sh
        s += polygon([(cx, cy), cs[i], cs[(i + 1) % 6]], c, f'stroke="{c}" stroke-width=".8" stroke-linejoin="round"')
    return s


def slab_svg(name, v=0):
    """A slab: two-tone facets around a jittered centre; land adds a thick two-tone side with an occlusion lip."""
    dh, ds, dl, jx, jy = JITTER[v]
    if name in A_WATER:
        lit, sh = (adj(c, 0, 0, dl / 2) for c in A_WATER[name])
        cs = corners(0, WOFF)
        return facets(0, WOFF, cs, lit, sh) + polygon(cs, "url(#deep)")
    lit, sh, sl, sd = A_LAND[name]
    lit, sh = adj(lit, dh, ds, dl), adj(sh, dh, ds, dl)
    cs = corners(0, 0)
    low = [shift(p, dy=SLAB) for p in cs]
    s = polygon([cs[2], cs[3], low[3], low[2]], sd) + polygon([cs[3], cs[4], low[4], low[3]], sl)
    s += polygon([cs[2], cs[3], cs[4], low[4], low[3], low[2]], "url(#ao)")
    s += facets(jx, jy, cs, lit, sh)
    return s + f'<polyline points="{pts([cs[2], cs[3], cs[4]])}" fill="none" stroke="rgba(0,0,0,.16)" stroke-width="1"/>'


def beach_svg(i):
    """Land edge meeting water: dry sand, a wet sand rim, and a rim light on the edges facing the light."""
    cs, ci, cw = corners(0, 0), corners(0, 0, .82), corners(0, 0, .93)
    j = (i + 1) % 6
    s = polygon([cs[i], cs[j], ci[j], ci[i]], "#f5e3b0") + polygon([cs[i], cs[j], cw[j], cw[i]], "#e3c98f")
    if i in (4, 5, 0):
        s += line(cs[i], cs[j], "#fff", .9, 'stroke-opacity=".35"')
    return s


def cliff_face(i, reach=0):
    """The land side over water edge i; `reach` extends its outer end past the hex corner."""
    flat = corners(0, 0)
    a, b = flat[i], flat[(i + 1) % 6]
    if i == 5:
        a = shift(a, -reach)
    else:
        b = shift(b, reach)
    return a, b, shift(b, dy=SLAB), shift(a, dy=SLAB)


def surf_svg(i):
    """Water edge meeting land: shallows fading into the tile, a dashed swash, a crisp foam line where the water shows."""
    c, face, flat = (0, WOFF), corners(0, WOFF), corners(0, 0)
    j = (i + 1) % 6
    if i in (0, 5):     # land behind: its slab side hangs over the water (Cliff overlays), foam at the cliff foot
        a, b = shift(flat[i], dy=SLAB), shift(flat[j], dy=SLAB)
    elif i in (2, 3):   # land in front is drawn later and covers the water below its own edge
        a, b = flat[i], flat[j]
    else:
        a, b = face[i], face[j]
    s = polygon([a, b, lerp(b, c, .36), lerp(a, c, .36)], "#9fe6f7", 'opacity=".28"')
    s += polygon([a, b, lerp(b, c, .18), lerp(a, c, .18)], "#a9ecfa", 'opacity=".42"')
    if i in (0, 5):     # the land's soft shadow on the water
        s += polygon([a, b, shift(b, dy=3.2), shift(a, dy=3.2)], "#0b2a5a", 'opacity=".24" filter="url(#soft)"')
    if i == 4:
        s += polygon([a, b, lerp(b, c, .08), lerp(a, c, .08)], "#0b2a5a", 'opacity=".12" filter="url(#soft)"')
    s += line(*inward(a, b, c, 2.4), "#fff", .8, 'stroke-opacity=".55" stroke-dasharray="3 2.2"')
    s += line(*inward(a, b, c, .8), "#fff", 1.5)
    clip = f"M{pts(face)}Z" + (f" M{pts(cliff_face(i))}Z" if i in (0, 5) else "")
    return (f'<clipPath id="water"><path d="{clip}" clip-rule="evenodd"/></clipPath>', f'<g clip-path="url(#water)">{s}</g>')


def cliff_svg(name, i):
    """The land slab's side face as seen over the water tile in front of it (NW edge: dark side, NE edge: lit side)."""
    _, _, sl, sd = A_LAND[name]
    # reach past the corner so the next water tile's anti-aliasing overhang never shows on this cliff
    face = cliff_face(i, .6)
    # the land's own occlusion gradient runs from its right corner to the bottom of its side
    y1, y2 = R * .5 * SQ - DY, R * SQ + SLAB - DY
    defs = (f'<linearGradient id="cliffao" gradientUnits="userSpaceOnUse" x1="0" y1="{f2(y1)}" x2="0" y2="{f2(y2)}">'
            '<stop offset="0" stop-opacity="0"/><stop offset="1" stop-opacity=".26"/></linearGradient>')
    s = polygon(face, sd if i == 5 else sl) + polygon(face, "url(#cliffao)")
    s += line(*(shift(p, dy=.25) for p in face[:2]), "rgba(0,0,0,.16)", .5)
    return defs, s


def shelf_svg(i, colour, depth):
    """Depth gradient between water tiles: the neighbour's tone fades into this tile."""
    c, face = (0, WOFF), corners(0, WOFF)
    a, b = face[i], face[(i + 1) % 6]
    m = lerp(a, b, .5)
    mi = lerp(m, c, depth)
    defs = (f'<linearGradient id="shelf" gradientUnits="userSpaceOnUse" x1="{f2(m[0])}" y1="{f2(m[1])}" x2="{f2(mi[0])}" y2="{f2(mi[1])}">'
            f'<stop offset="0" stop-color="{colour}" stop-opacity=".9"/><stop offset="1" stop-color="{colour}" stop-opacity="0"/></linearGradient>')
    return defs, polygon([a, b, lerp(b, c, depth), lerp(a, c, depth)], "url(#shelf)")


def cloud_svg():
    """Soft cloud: one bank, puffs spilling up and sideways over explored tiles, a cool underside along the bottom edges."""
    cs, rng = corners(0, 0), seeded(7)
    # the body reaches past the hex so its soft rim always lands on a neighbour's opaque cloud: no seams inside the bank
    s = polygon(corners(0, 0, 1.08), "#f4f7fa")
    for i in (4, 5, 0, 1):
        for u in (.12, .45, .8):
            q = lerp(lerp(cs[i], cs[(i + 1) % 6], u + rng() * .1), (0, 0), .08)
            s += f'<circle cx="{f2(q[0])}" cy="{f2(q[1])}" r="{f2(8 + rng() * 5)}" fill="#f4f7fa"/>'
    defs = ""
    for i in (2, 3):
        a, b = cs[i], cs[(i + 1) % 6]
        # fade across the band, along the edge's normal
        m, top = lerp(a, b, .5), inward(a, b, (0, 0), 3 * abs(a[0] - b[0]) / math.dist(a, b))[0]
        top = shift(m, top[0] - a[0], top[1] - a[1])
        defs += (f'<linearGradient id="belly{i}" gradientUnits="userSpaceOnUse" x1="{f2(top[0])}" y1="{f2(top[1])}" x2="{f2(m[0])}" '
                 f'y2="{f2(m[1])}">'
                 '<stop offset="0" stop-color="#7f98b5" stop-opacity="0"/><stop offset="1" stop-color="#7f98b5" stop-opacity=".6"/></linearGradient>')
        s += polygon([a, b, shift(b, dy=-3), shift(a, dy=-3)], f"url(#belly{i})")
    return defs + '<filter id="cloud" x="-10%" y="-10%" width="120%" height="120%"><feGaussianBlur stdDeviation=".6"/></filter>', \
        f'<g filter="url(#cloud)">{s}</g>'


# ---------------- prop sprites ----------------
def trim_to_anchor(raw, name, ax, ay, u, size):
    """Crop to the drawn pixels, keeping the anchor on the horizontal centre; the sprite's bottom centre is what the
    engine places, so remember how far it sits below the anchor."""
    box = subprocess.check_output(["magick", str(raw), "-alpha", "extract", "-threshold", "1%", "-format", "%@", "info:"], text=True)
    bw, bh, x0, y0 = map(int, re.fullmatch(r"(\d+)x(\d+)\+(\d+)\+(\d+)", box.strip()).groups())
    half = max(ax - x0, x0 + bw - ax)
    out = OUT / "Props" / f"{name}.png"
    out.parent.mkdir(parents=True, exist_ok=True)
    magick(raw, "-crop", f"{2 * half}x{bh}+{ax - half}+{y0}", "+repage", "-depth", "8", out)
    SPR[name] = (2 * half / u, (y0 + bh - ay) / u, size)


def contact(x, y, rx, ry, o=1):
    return f'<ellipse cx="{f2(x)}" cy="{f2(y)}" rx="{f2(rx)}" ry="{f2(ry)}" fill="url(#cs)" opacity="{o}"/>'


def art_sprite(name, key, z, anchor=.78, shadow=(.02, .4, .13), recolor=()):
    """A mocks/art sprite drawn the way the mock places it: a z-unit square whose anchor sits `anchor` of the way down,
    with the mock's contact shadow baked in. Never upscaled past the 256 px source."""
    t = min(256, 2 * math.ceil(z * UP / 2))
    m = math.ceil(t * .12)
    art = TMP / "art.png"
    subprocess.run(["magick", str(sprite_png(key)), *recolor, "-resize", f"{t}x{t}", str(art)], check=True)
    ax, ay = m + t // 2, m + round(anchor * t)
    data = base64.b64encode(art.read_bytes()).decode()
    raw = TMP / "raw.png"
    render(f'<svg xmlns="http://www.w3.org/2000/svg" width="{t + 2 * m}" height="{t + 2 * m}"><defs>{DEFS}</defs>'
           f'{contact(ax, ay - shadow[0] * t, shadow[1] * t, shadow[2] * t)}'
           f'<image href="data:image/png;base64,{data}" x="{m}" y="{m}" width="{t}" height="{t}"/></svg>', raw)
    trim_to_anchor(raw, name, ax, ay, t / z, z)


def drawn_sprite(name, body, half_w, top, bottom, u=UP):
    """Procedural sprite in mock units around its anchor (0, 0)."""
    wpx, head, foot = 2 * math.ceil(half_w * u), math.ceil(top * u), math.ceil(bottom * u)
    raw = TMP / "raw.png"
    render(f'<svg xmlns="http://www.w3.org/2000/svg" width="{wpx}" height="{head + foot}" '
           f'viewBox="{-wpx / 2 / u:.4f} {-head / u:.4f} {wpx / u:.4f} {(head + foot) / u:.4f}"><defs>{DEFS}</defs>{body}</svg>', raw)
    trim_to_anchor(raw, name, wpx // 2, head, u, 1)


# the mock's procedural decor, anchored at its base
def tuft(d, l, s=1):
    return (f'<path d="M{f2(-2.6 * s)} 0L{f2(-1.2 * s)} {f2(-4.6 * s)}L{f2(.2 * s)} 0Z" fill="{d}"/>'
            f'<path d="M{f2(-.8 * s)} 0L{f2(.9 * s)} {f2(-5.8 * s)}L{f2(2.5 * s)} 0Z" fill="{l}"/>'
            f'<path d="M{f2(1.2 * s)} 0L{f2(3 * s)} {f2(-3.4 * s)}L{f2(3.8 * s)} 0Z" fill="{d}"/>')


def pebble(a, b, s=1, x=0, y=0):
    return (f'<path d="M{f2(x - 2.4 * s)} {f2(y)}L{f2(x - 1.2 * s)} {f2(y - 2 * s)}L{f2(x + 1.4 * s)} {f2(y - 2.2 * s)}L{f2(x + 2.6 * s)} {f2(y)}Z" fill="{a}"/>'
            f'<path d="M{f2(x + 1.4 * s)} {f2(y - 2.2 * s)}L{f2(x + 2.6 * s)} {f2(y)}L{f2(x + .4 * s)} {f2(y)}Z" fill="{b}"/>')


def decor_sprites():
    for name, d, l in (("Grassland", "#5e9d33", "#a6dd68"), ("Plains", "#c49f2e", "#f2d66c"), ("Desert", "#b98f45", "#d9b060"),
                       ("Tundra", "#8a8f5e", "#aab27a")):
        drawn_sprite(f"Tuft-{name}", contact(.6, -.2, 3.4, 1, .55) + tuft(d, l), 4.2, 6.2, 1.2)
    for name, a, b in (("Grassland", "#c9cdc4", "#a3a99c"), ("Plains", "#d9c9a0", "#b8a57a"), ("Desert", "#e7b27a", "#c98a4f"),
                       ("Tundra", "#c9c9bd", "#a2a397"), ("Snow", "#e6ecf2", "#b9c5d2")):
        drawn_sprite(f"Pebble-{name}", contact(.2, -.2, 3, .9, .7) + pebble(a, b), 3.2, 2.6, 1.2)
    for n, c in enumerate(("#fff", "#fff4c2", "#ffc7d6"), 1):
        drawn_sprite(f"Flower-{n}", contact(0, -.1, 1.5, .5, .5) + '<path d="M0 0v-3" stroke="#4f8a2c" stroke-width=".7"/>'
                     f'<circle cy="-3.4" r="1.25" fill="{c}"/><circle cy="-3.4" r=".45" fill="#f5c23a"/>', 1.8, 4.9, 1)
    drawn_sprite("Shrub-Tundra", contact(0, -.2, 3.8, 1.1, .7) + '<path d="M-3.4 0L-1.6 -3.4L1.2 -3.8L3.4 0Z" fill="#8e9a66"/>'
                 '<path d="M1.2 -3.8L3.4 0L0 0Z" fill="#6f7b4c"/>', 4.2, 4.2, 1.3)
    drawn_sprite("Rabbit", contact(0, -.2, 2.8, .8, .7) + '<ellipse cy="-1.6" rx="2.2" ry="1.6" fill="#b89b7a"/>'
                 '<circle cx="1.9" cy="-2.8" r="1.1" fill="#a88a68"/><path d="M2 -3.6l-.3 -2.2M2.6 -3.6l.4 -2" stroke="#a88a68" '
                 'stroke-width=".7" stroke-linecap="round"/><circle cx="-2" cy="-2" r=".7" fill="#fff"/>', 3.2, 6.2, 1.1)
    drawn_sprite("Rocks", contact(0, -.5, 5, 1.6) + pebble("#c3c7cc", "#9aa1aa", 1.6) + pebble("#b5bac0", "#8e959e", 1.1, 4, 1.5)
                 + pebble("#cfd3d7", "#a4abb3", .8, -3.5, 1), 6.6, 4.4, 3.4)
    # flying: no contact shadow
    drawn_sprite("Gull", '<path d="M-4 -1Q-2 -3 0 0Q2 -3 4 -1" stroke="#fff" stroke-width="1.1" fill="none" stroke-linecap="round"/>'
                 '<path d="M-4 -1l1 -.3M4 -1l-1 -.3" stroke="#6b7a88" stroke-width="1.1" stroke-linecap="round"/>', 4.8, 3.2, 1)
    # ground art, already projected like the slab it lies on
    drawn_sprite("Wave", '<path d="M-4 0q2 -1.8 4 0q2 1.8 4 0" stroke="#fff" stroke-opacity=".5" stroke-width=".9" fill="none" '
                 'stroke-linecap="round"/>', 4.8, 1.6, 1.6, GR)
    fc = corners(0, 0, .9)
    d, e, n = ((corners(0, 0)[1][0] - corners(0, 0)[4][0]) / 2, (corners(0, 0)[1][1] - corners(0, 0)[4][1]) / 2), (0, R * SQ), 9
    rows = polygon(fc, "#e2c14e")
    for j in range(-n, n + 1):
        q = (e[0] * j / n, e[1] * j / n)
        rows += line(shift(q, -d[0] * 1.3, -d[1] * 1.3), shift(q, d[0] * 1.3, d[1] * 1.3), "#f3dc78" if j % 2 else "#b99432", f2(18 / n),
                     cap="butt")
    drawn_sprite("Crop-rows", f'<clipPath id="field"><polygon points="{pts(fc)}"/></clipPath><g clip-path="url(#field)">{rows}</g>'
                 + polygon(fc, "none", 'stroke="#a8862c" stroke-width=".8" opacity=".6"'), 25, 17.5, 17.5, GR)
    rng, dots = seeded(3), ""
    for _ in range(14):
        u, w = rng() * 2 - 1, rng() * 2 - 1
        dots += f'<circle cx="{f2(d[0] * u * .8)}" cy="{f2(d[1] * u * .8 + e[1] * w * .6)}" r=".7" fill="#7aa83a"/>'
    drawn_sprite("Crop-sprouts", dots, 25, 17.5, 17.5, GR)


# ---------------- props table ----------------
def add(rel, spr, x, y, z=1, lod=0, ground=False):
    """A prop at mock position (x, y) (y down, from the ground centre) and mock size z, in the contract's units:
    fractions of hexagonImageWidth, y up, anchored at the sprite's bottom centre."""
    width, below, size = SPR[spr]
    k = z / size
    e = {"image": f"TileSets/Polytopia/Props/{spr}", "x": round(x / 62, 3) + 0, "y": round(-(y + below * k) / 62, 3) + 0,
         "w": round(width * k / 62, 3), "lod": lod}
    if ground:
        e["ground"] = True
    PROPS.setdefault(f"TileSets/Polytopia/Tilted/{rel}", []).append(e)


def spread(rel, keyf, spots, base, hs, lods=None):
    """The mock's spread(): sprites on fixed spots with a little jitter in place and size."""
    rng = seeded(hs + 11)
    for j, (qx, qy, *k) in enumerate(spots):
        x, y = qx + (rng() - .5) * 3, qy + (rng() - .5) * 2
        z = base * (k[0] if k else 1) * (.9 + rng() * .2)
        rng()  # the mock's flip
        add(rel, keyf(j), x, y, z, lods[j] if lods else 0)


def decor(rel, terrain, hs):
    """Tufts, flowers, pebbles: 6 from LOD 1, 6 more at LOD 2, in a ring that leaves the middle and the resource spot free."""
    rng, placed, tries = seeded(hs * 97 + 5), 0, 0
    count = 8 if terrain == "Snow" else 12
    while placed < count and tries < 200:
        tries += 1
        a, d = rng() * 2 * math.pi, math.sqrt(rng()) * .66
        x, y, q = math.cos(a) * d * HW / 2, math.sin(a) * d * R * SQ + 1.5, rng()
        if d < .32 or math.hypot(x - 13.6, (y - 9) / SQ) < 9:
            continue
        if terrain == "Grassland":
            spr, z = ("Tuft-Grassland", .9) if q < .55 else (f"Flower-{placed % 3 + 1}", 1) if q < .85 else ("Pebble-Grassland", .8)
        elif terrain == "Plains":
            spr, z = ("Tuft-Plains", .85) if q < .6 else ("Pebble-Plains", .8)
        elif terrain == "Desert":
            spr, z = ("Pebble-Desert", .9) if q < .7 else ("Tuft-Desert", .7)
        elif terrain == "Tundra":
            spr, z = ("Shrub-Tundra", 1) if q < .5 else ("Pebble-Tundra", .9) if q < .8 else ("Tuft-Tundra", .8)
        else:
            spr, z = ("Pebble-Snow", .9 if q < .6 else .6)
        add(rel, spr, x, y, z, 1 if placed < count // 2 else 2)
        placed += 1


TREES = [(-11, -2), (10, -3), (0, 4), (-15, 8), (14, 8)]
HILLS = [(-7, 4, 1), (11, 8, .75)]
PEAKS = [(-9, 3, 1), (10, 5, .82), (1, 11, .66)]
HERD = {"Sheep", "Cattle", "Horses", "Deer", "Wheat", "Cotton", "Fish", "Whales"}
# features without a layout in the mock: one sprite, mock size
SINGLE = {"Marsh": ("Marsh", "f_marsh", 40, 8), "Oasis": ("Atoll", "f_atoll", 38, 7), "Atoll": ("Atoll", "f_atoll", 44, 7),
          "Flood plains": ("Flood-plains", "f_flood", 44, 7), "Ice": ("Ice", "f_ice", 46, 7), "Fallout": ("Fallout", "f_fallout", 44, 7)}


def variant(name, i):
    return f"Tiles/{name}{'' if i == 0 else i + 1}"


def portrait(eras, wonders, resources, improvements):
    tilted, strategic = OUT / "Tilted", OUT / "Strategic"
    # slabs, with decor props on land and life on the water
    for name in [*A_WATER, *A_LAND]:
        for i in range(3 if name in A_LAND else 2):
            rel = variant(name, i)
            tile_svg(slab_svg(name, i), tilted / f"{rel}.png")
            tile_svg(slab_svg(name, i), strategic / f"{rel}.png", scale=1)
            if name == "Mountain":
                spread(rel, lambda j, i=i: f"Mountain-{1 + (i + j) % 3}", PEAKS, 46, i)
                add(rel, "Rocks", ((i + 1) % 3 - 1) * 14, 11, 1, 2)
            elif name in A_LAND:
                decor(rel, name, list(A_LAND).index(name) * 3 + i)
                if i == 0 and name != "Snow":
                    add(rel, "Rocks", -14, 11, 1, 2)
                if i == 2 and name == "Grassland":
                    add(rel, "Rabbit", -10, 7, 1, 2); add(rel, "Rabbit", 12, -3, 1, 2)
            elif name == "Ocean":
                rng = seeded(40 + i)
                for k in range(3):
                    add(rel, "Wave", (rng() - .5) * 30, WOFF + (rng() - .5) * 14, 1, 1 if k < 2 else 2, True)
            elif name == "Coast" and i == 1:
                rng = seeded(55)
                add(rel, "Gull", (rng() - .5) * 18, WOFF - 10 - rng() * 6, .9, 2)
                add(rel, "Gull", (rng() - .5) * 18, WOFF - 14 - rng() * 6, .7, 2)
    # features become props
    for name, (keys, *_) in FEATURES.items():
        for i in range(len(keys)):
            rel = variant(name, i)
            placeholder(tilted / f"{rel}.png")
            if name == "Forest":
                spread(rel, lambda j, i=i: f"Forest-{1 + (i + j) % 3}", TREES, 30, i, [0, 0, 0, 1, 2])
            elif name == "Jungle":
                spread(rel, lambda j, i=i: f"Jungle-{1 + (i + j) % 2}", TREES, 30, i, [0, 0, 0, 1, 2])
            elif name == "Hill":
                spread(rel, lambda j, i=i: f"Hill-{1 + (i + j) % 2}", HILLS, 42, i)
                add(rel, "Rocks", -14, 11, 1, 2)
            else:
                spr, _, z, y = SINGLE[name]
                add(rel, spr, 0, y, z)
    # a natural wonder replaces the base image, so its portrait image is the slab it turns into
    for name, turns_into in wonders:
        tile_svg(slab_svg(turns_into), tilted / f"Tiles/{name}.png")
        tile_svg(slab_svg(turns_into), strategic / f"Tiles/{name}.png", scale=1)
        add(f"Tiles/{name}", f"Wonder-{name}", 0, .56, 60)
    # resources, improvements and cities stand on the mock's spots
    for name in resources:
        placeholder(tilted / f"Tiles/{name}.png")
        add(f"Tiles/{name}", f"Res-{name}", 13.6, 14.5, 24)
        if name in HERD:
            add(f"Tiles/{name}", f"Res-{name}", 13.6 - 17, 15.5, 24 * .78, 2)
    for name in improvements:
        placeholder(tilted / f"Tiles/{name}.png")
        if name == "Farm":
            add("Tiles/Farm", "Crop-rows", 0, 0, 1, 0, True)
            add("Tiles/Farm", "Crop-sprouts", 0, 0, 1, 2, True)
            add("Tiles/Farm", f"Imp-{name}", -12, -1, 24)
        else:
            add(f"Tiles/{name}", f"Imp-{name}", -3.9, 4.5, 35)
    for era in eras:
        for rel in [f"Tiles/City center-{era}"] + (["Tiles/City center"] if era == eras[0] else []):
            placeholder(tilted / f"{rel}.png")
            add(rel, f"City-{era.split()[0]}", 0, 13.8, 56.8)
    # shorelines and depth, all at the ground resolution except the soft shelf gradients
    for n, i in DIRS.items():
        tile_svg(beach_svg(i), tilted / f"Edges/Beach-Land-Water-{n}.png", scale=EDGE)
        defs, body = surf_svg(i)
        tile_svg(body, tilted / f"Edges/Surf-Water-Land-{n}.png", scale=EDGE, defs=defs)
        new = []
        if i in (0, 5):
            for land in A_LAND:
                defs, body = cliff_svg(land, i)
                tile_svg(body, tilted / f"Edges/Cliff-Water-{land}-{n}.png", scale=EDGE, defs=defs)
                new.append(f"Cliff-Water-{land}-{n}")
        for origin, dest, colour, depth in (("Ocean", "Coast", "#2f7bd9", .55), ("Coast", "Ocean", "#3b9fe0", .4)):
            defs, body = shelf_svg(i, colour, depth)
            tile_svg(body, tilted / f"Edges/Shelf-{origin}-{dest}-{n}.png", scale=1, defs=defs)
            new.append(f"Shelf-{origin}-{dest}-{n}")
        # palms by the water, as a prop-only edge: one shore only, the front left, clear of the resource spot
        if n == "BottomLeft":
            for land in ("Grassland", "Plains"):
                rel = f"Edges/Palms-{land}-Water-{n}"
                placeholder(tilted / f"{rel}.png")
                add(rel, "Palm", -12, 8, 28, 1)
                add(rel, "Palm", -19, 4, 20, 2)
                new.append(f"Palms-{land}-Water-{n}")
        # edges are discovered from the landscape folder, so each new one needs an invisible landscape twin
        for e in new:
            placeholder(OUT / f"Edges/{e}.png")
    defs, body = cloud_svg()
    tile_svg(body, tilted / "UnexploredTile.png", head=5, defs=defs)


def prop_sprites(eras, wonders, resources, improvements):
    for k in range(3):
        art_sprite(f"Forest-{k + 1}", f"f_forest{k + 1}", 33)
        art_sprite(f"Mountain-{k + 1}", f"f_mountain{k + 1}", 50.6)
    for k in range(2):
        art_sprite(f"Jungle-{k + 1}", f"f_jungle{k + 1}", 33)
        art_sprite(f"Hill-{k + 1}", f"f_hill{k + 1}", 46.2)
    for spr, key, z, _ in SINGLE.values():
        if spr not in SPR:
            art_sprite(spr, key, max(v[2] for v in SINGLE.values() if v[0] == spr))
    art_sprite("Palm", "s_palm", 28)
    for name in resources:
        art_sprite(f"Res-{name}", f"r_{name}", 24, 1, (4 / 24, 9 / 24, 3.2 / 24))
    for name in improvements:
        art_sprite(f"Imp-{name}", f"i_{name}", 35)
    for era in eras:
        era = era.split()[0]
        art_sprite(f"City-{era}", f"c_{era}_medium", 56.8, 1, (6.8 / 56.8, .44, .14), NEUTRAL_ROOFS)
    for name, _ in wonders:
        art_sprite(f"Wonder-{name}", f"nw_{name}", 60)
    decor_sprites()


def props_json():
    """One prop per line: ground first, then upright back to front."""
    keys = []
    for k in sorted(PROPS):
        items = sorted(PROPS[k], key=lambda e: (not e.get("ground", False), -e["y"]))
        keys.append(f"        {json.dumps(k)}: [\n" + ",\n".join(f"            {json.dumps(e)}" for e in items) + "\n        ]")
    return "{\n" + ",\n".join(keys) + "\n    }"


def main():
    if OUT.exists():
        shutil.rmtree(OUT)
    n = 0
    for name in TERRAIN:
        if name == "Mountain":
            for i, key in enumerate(["f_mountain1", "f_mountain2", "f_mountain3"]):
                write(f"Tiles/Mountain{'' if i == 0 else i + 1}.png", slab(name), [(key, 196, 150)]); n += 1
            continue
        write(f"Tiles/{name}.png", slab(name)); write(f"Tiles/{name}2.png", slab(name)); n += 2
        if name in DECOR:
            size, bottom = (70, 132) if name != "Ocean" else (64, 130)
            write(f"Tiles/{name}3.png", slab(name), [(DECOR[name], size, bottom, CX + 22)]); n += 1
    for name, (keys, size, bottom) in FEATURES.items():
        for i, key in enumerate(keys):
            write(f"Tiles/{name}{'' if i == 0 else i + 1}.png", sprites=[(key, size, bottom)]); n += 1
    wonders = [(t["name"], t.get("turnsInto", "Grassland")) for t in load("Terrains.json") if t["type"] == "NaturalWonder"]
    for name, turns_into in wonders:
        # a natural wonder replaces the base image, so it carries the slab of the terrain it turns into
        write(f"Tiles/{name}.png", slab(turns_into), [(f"nw_{name}", 186, 154)]); n += 1
    resources = [r["name"] for r in load("TileResources.json")]
    for name in resources:
        write(f"Tiles/{name}.png", sprites=[(f"r_{name}", 74, 158, 138)]); n += 1
    improvements = [i["name"] for i in load("TileImprovements.json") if (ART / f"i_{i['name']}.png").exists() and i["name"] != "Oasis"]
    for name in improvements:
        write(f"Tiles/{name}.png", sprites=[(f"i_{name}", 108, 146, 84)]); n += 1
    eras = [e["name"] for e in load("Eras.json")]
    for era in eras:
        write(f"Tiles/City center-{era}.png", sprites=[(f"c_{era.split()[0]}_medium", 176, 154)], recolor=NEUTRAL_ROOFS); n += 1
    shutil.copy(OUT / "Tiles/City center-Ancient era.png", OUT / "Tiles/City center.png")
    edges(); n += 12
    # unexplored tiles: soft cloud white; not-visible tiles keep the terrain with no crosshatch
    write("UnexploredTile.png", [f"fill #eef3f7 polygon {poly(hexpts())}"])
    magick("-size", "1x1", "xc:none", OUT / "CrosshatchHexagon.png")
    n += units()
    prop_sprites(eras, wonders, resources, improvements)
    portrait(eras, wonders, resources, improvements)
    shutil.rmtree(TMP)
    config = {
        "mapVerticalScale": MAP_VERTICAL_SCALE,
        "useColorAsBaseTerrain": False,
        "fallbackTileSet": "FantasyHex",
        "unexploredTileColor": {"r": 0.93, "g": 0.95, "b": 0.97, "a": 1},
        "fogOfWarColor": {"r": 0.55, "g": 0.62, "b": 0.72, "a": 1},
        "mapBackgroundColor": {"r": 0.93, "g": 0.95, "b": 0.97, "a": 1},
        "unitFlagsAboveSprites": True,
        "vividUnitTeamColor": True,
    }
    text = json.dumps(config, indent=4)[:-2] + ',\n    "props": ' + props_json() + "\n}\n"
    (ROOT / "android/assets/jsons/TileSets/Polytopia.json").write_text(text)
    print(f"exported {n} landscape images, {len(SPR)} prop sprites, {sum(map(len, PROPS.values()))} props on {len(PROPS)} images")


main()
