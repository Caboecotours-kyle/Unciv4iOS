# Exports the Polytopia tileset (art rules in DESIGN.md) from mocks/art into Unciv's tileset format.
# Output: android/Images.Tilesets/TileSets/Polytopia/ and android/assets/jsons/TileSets/Polytopia.json
# Every tile image uses one canvas: a flat-top hex W px wide at the bottom, with headroom above only where a sprite
# rises past the hex. Unciv scales each image to the hex width and aligns bottoms, so sprites keep their scale.
# Run: python3 tools/polytopia-tileset/export.py   (needs ImageMagick 7 on PATH)
import json, math, pathlib, re, shutil, subprocess

ROOT = pathlib.Path(__file__).resolve().parents[2]
ART = ROOT / "mocks/art"
RULES = ROOT / "android/assets/jsons/Civ V - Gods & Kings"
OUT = ROOT / "android/Images.Tilesets/TileSets/Polytopia"
W = 192
H = round(W * math.sqrt(3) / 2)  # 166, flat-top hex height
SIDE = 8                           # slab edge under the lower three sides
CX, CY = W / 2, H / 2

# Direction A palette: lit facets, shaded facets, slab side (DESIGN.md)
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


def slab(name, variant_decor=None):
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
    """Render one canvas: vector draw ops on the hex, then bottom-anchored sprites, with headroom as needed."""
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
    return out


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
        head = headroom(132, 148)
        common = ["-size", f"{W}x{H + head}", "xc:none", "(", src, "-resize", "132x132", ")", "-geometry", f"+{CX - 66:.0f}+{head + 148 - 132}", "-composite"]
        (OUT / "Units").mkdir(parents=True, exist_ok=True)
        magick(*common, "-channel", "A", "-fx", f"{GREEN} ? 0 : u", "+channel", OUT / "Units" / f"{u['name']}.png")
        # alpha first, while the pixels are still green; then the cloth becomes greys (lit near white) for tinting
        magick(*common, "-channel", "A", "-fx", f"{GREEN} ? u : 0", "-channel", "RGB", "-fx", "min(1, u.g*1.12)", "+channel",
               OUT / "Units" / f"{u['name']}-1.png")
        made += 1
    return made


def edges():
    """Sand band on land edges that meet water; a shallow band with a surf line on water edges that meet land."""
    names = ["Top", "TopRight", "BottomRight", "Bottom", "BottomLeft", "TopLeft"]
    v = hexpts()
    inner = [(CX + (x - CX) * 0.84, CY + (y - CY) * 0.84) for x, y in v]
    edge_of = {"Top": 1, "TopRight": 2, "BottomRight": 3, "Bottom": 4, "BottomLeft": 5, "TopLeft": 0}
    for n in names:
        i = edge_of[n]; a, b, ai, bi = v[i], v[(i + 1) % 6], inner[i], inner[(i + 1) % 6]
        write(f"Edges/Beach-Land-Water-{n}.png", [f"fill #f3dfa8 polygon {poly([a, b, bi, ai])}"])
        write(f"Edges/Surf-Water-Land-{n}.png", [f"fill rgba(143,220,242,0.6) polygon {poly([a, b, bi, ai])}",
                                                 f"stroke rgba(255,255,255,0.75) stroke-width 3 line {a[0]:.1f},{a[1]:.1f} {b[0]:.1f},{b[1]:.1f}"])


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
    for t in load("Terrains.json"):
        if t["type"] == "NaturalWonder":
            # a natural wonder replaces the base image, so it carries the slab of the terrain it turns into
            write(f"Tiles/{t['name']}.png", slab(t.get("turnsInto", "Grassland")), [(f"nw_{t['name']}", 186, 154)]); n += 1
    for r in load("TileResources.json"):
        write(f"Tiles/{r['name']}.png", sprites=[(f"r_{r['name']}", 74, 158, 138)]); n += 1
    for i in load("TileImprovements.json"):
        if (ART / f"i_{i['name']}.png").exists() and i["name"] not in ("Oasis",):
            write(f"Tiles/{i['name']}.png", sprites=[(f"i_{i['name']}", 108, 146, 84)]); n += 1
    for e in load("Eras.json"):
        era = e["name"].split()[0]
        write(f"Tiles/City center-{e['name']}.png", sprites=[(f"c_{era}_medium", 176, 154)], recolor=NEUTRAL_ROOFS); n += 1
    shutil.copy(OUT / "Tiles/City center-Ancient era.png", OUT / "Tiles/City center.png")
    edges(); n += 12
    # unexplored tiles: soft cloud white; not-visible tiles keep the terrain with no crosshatch
    write("UnexploredTile.png", [f"fill #eef3f7 polygon {poly(hexpts())}"])
    magick("-size", "1x1", "xc:none", OUT / "CrosshatchHexagon.png")
    n += units()
    config = {
        "useColorAsBaseTerrain": False,
        "fallbackTileSet": "FantasyHex",
        "unexploredTileColor": {"r": 0.93, "g": 0.95, "b": 0.97, "a": 1},
        "fogOfWarColor": {"r": 0.55, "g": 0.62, "b": 0.72, "a": 1},
        "mapBackgroundColor": {"r": 0.93, "g": 0.95, "b": 0.97, "a": 1},
        "unitFlagsAboveSprites": True,
        "vividUnitTeamColor": True,
    }
    (ROOT / "android/assets/jsons/TileSets/Polytopia.json").write_text(json.dumps(config, indent=4) + "\n")
    print(f"exported {n} images to {OUT.relative_to(ROOT)}")


main()
