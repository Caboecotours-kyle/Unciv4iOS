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
MAP_VERTICAL_SCALE = 0.6
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
# portrait placement from the mock's feature table: image name -> (mock size, mock y offset from the tile center)
MOCK_FEATURES = {"Forest": (60, 10), "Jungle": (60, 10), "Hill": (54, 12), "Marsh": (46, 8), "Mountain": (62, 12)}
MOCK = W / 62                      # texture px per mock px: the mock hex radius R=31 is W/2 here
WOFF = 3 * MOCK                    # portrait water faces and their surf sit the mock's WOFF lower than land
DECOR = {"Grassland": "f_grass", "Plains": "f_plains", "Desert": "f_desert1", "Tundra": "f_tundra", "Snow": "f_snow"}
# mock decor size: grass and plains 24, the rest 30; the mock props decor at tile center + 8
DECOR_MOCK_SIZE = {"Grassland": 24, "Plains": 24}


def magick(*args):
    *ops, out = map(str, args)
    # strip timestamps so an unchanged image exports byte-identical and git sees no churn
    subprocess.run(["magick", *ops, "-define", "png:exclude-chunks=date,time,tIME", "-strip", out], check=True)


def hexpts(cy=CY, h=H):
    """Flat-top hex vertices clockwise from the left corner."""
    return [(0, cy), (W / 4, cy - h / 2), (3 * W / 4, cy - h / 2), (W, cy), (3 * W / 4, cy + h / 2), (W / 4, cy + h / 2)]


def project(x, y):
    # Texture Y points down. This is the same +30-degree Y-up rotation as MapProjection.
    dx, dy = x - CX, y - CY
    return CX + math.sqrt(3) / 2 * dx + .5 * dy, CY + MAP_VERTICAL_SCALE * (-.5 * dx + math.sqrt(3) / 2 * dy)


def poly(pts):
    return " ".join(f"{x:.1f},{y:.1f}" for x, y in pts)


def slab(name, tilted=False):
    """Two-tone hex face; land gets a darker slab edge along the lower sides."""
    lit, shade, side = TERRAIN[name]
    top = [project(x, y) for x, y in hexpts()] if tilted else hexpts(CY - (SIDE / 2 if side else 0), H - (SIDE if side else 0))
    dy = WOFF if tilted and not side else 0
    top = [(x, y + dy) for x, y in top]
    draw = []
    if side:
        full = [(x, y + 4 * W / 62) for x, y in top] if tilted else hexpts()
        if tilted:
            for i in (3, 4, 5):
                j = (i + 1) % 6
                # the mock fades the bottom-left side face
                draw.append(f"fill-opacity {.85 if i == 5 else 1} fill {side} polygon {poly([top[i], top[j], full[j], full[i]])} fill-opacity 1")
        else:
            draw.append(f"fill {side} polygon {poly([full[0], top[0], top[5], top[4], top[3], full[3], full[4], full[5]])}")
    c = (CX, CY + dy) if tilted else (CX, (top[1][1] + top[4][1]) / 2)
    # facets facing the light (upper left): the top-left, top and bottom-left wedges
    # portrait follows the mock's corners(): its lit wedges 0, 4, 5 are projected wedges 2, 0, 1, with strokes in mock px
    lit_facets, facet_stroke, seam = ((0, 1, 2), .7 * MOCK, MOCK) if tilted else ((0, 1, 5), 0.8, 1.2)
    for i in range(6):
        colour = lit if i in lit_facets else shade
        draw.append(f"fill {colour} stroke {colour} stroke-width {facet_stroke:g} polygon {poly([c, top[i], top[(i + 1) % 6]])}")
    draw.append(f"fill none stroke rgba(20,40,20,0.10) stroke-width {seam:g} polygon {poly(top)}")
    return draw


def sprite_png(key):
    p = ART / f"{key}.png"
    if not p.exists():
        raise SystemExit(f"missing art: {p}")
    return p


def place(canvas_h, key, size, bottom, cx=CX, head=0):
    """ImageMagick args that composite a square sprite, bottom-anchored inside the hex."""
    return ["(", sprite_png(key), "-resize", f"{size}x{size}", ")", "-geometry", f"+{cx - size / 2:.0f}+{head + bottom - size:.0f}", "-composite"]


def mock_prop(key, size, dy):
    """The mock's prop(): upright sprite whose top sits at tile center + dy - 0.78 * size, in texture px."""
    s = round(size * MOCK)
    return key, s, CY + dy * MOCK + .22 * s


def headroom(size, bottom):
    return max(0, math.ceil(size - bottom))


def write(rel, draw=(), sprites=(), recolor=None, tilted_draw=None, tilted_sprites=None, tilted_dy=0):
    """Keep the flat export unchanged; add a portrait variant with upright, reanchored sprites."""
    for tilted in (False, True):
        placed = tilted_sprites if tilted and tilted_sprites is not None else \
            [(key, size, CY + (bottom - CY) * MAP_VERTICAL_SCALE if tilted else bottom, *rest) for key, size, bottom, *rest in sprites]
        head = max([0] + [headroom(s, b) for _, s, b, *_ in placed])
        out = OUT / ("Tilted" if tilted else "") / rel
        out.parent.mkdir(parents=True, exist_ok=True)
        args = ["-size", f"{W}x{H + head}", "xc:none"]
        if draw:
            transform = f"translate 0,{head} "
            if tilted and tilted_draw is None:
                if tilted_dy:
                    transform += f"translate 0,{tilted_dy:.2f} "
                transform += f"translate {CX},{CY} scale 1,{MAP_VERTICAL_SCALE} rotate -30 translate {-CX},{-CY} "
            args += ["-draw", transform + " ".join(tilted_draw if tilted and tilted_draw is not None else draw)]
        for key, size, bottom, *rest in placed:
            args += place(H, key, size, bottom, cx=rest[0] if rest else CX, head=head)
        if recolor:
            args += recolor
        magick(*args, out)
        if tilted and draw and rel.startswith("Tiles/"):
            strategic = OUT / "Strategic" / rel
            strategic.parent.mkdir(parents=True, exist_ok=True)
            magick("-size", f"{W}x{H}", "xc:none", "-draw", transform.replace(f"translate 0,{head} ", "") +
                   " ".join(tilted_draw if tilted_draw is not None else draw), strategic)
    return OUT / rel


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
    """Sand band on land edges that meet water; a shallow band with a surf line on water edges that meet land."""
    names = ["Top", "TopRight", "BottomRight", "Bottom", "BottomLeft", "TopLeft"]
    v = hexpts()
    inner = [(CX + (x - CX) * 0.84, CY + (y - CY) * 0.84) for x, y in v]
    edge_of = {"Top": 1, "TopRight": 2, "BottomRight": 3, "Bottom": 4, "BottomLeft": 5, "TopLeft": 0}
    for n in names:
        i = edge_of[n]; a, b, ai, bi = v[i], v[(i + 1) % 6], inner[i], inner[(i + 1) % 6]
        write(f"Edges/Beach-Land-Water-{n}.png", [f"fill #f3dfa8 polygon {poly([a, b, bi, ai])}"])
        write(f"Edges/Surf-Water-Land-{n}.png", [f"fill rgba(143,220,242,0.6) polygon {poly([a, b, bi, ai])}",
                                                 f"stroke rgba(255,255,255,0.75) stroke-width 3 line {a[0]:.1f},{a[1]:.1f} {b[0]:.1f},{b[1]:.1f}"],
              tilted_dy=WOFF)


def main():
    if OUT.exists():
        shutil.rmtree(OUT)
    n = 0
    for name in TERRAIN:
        if name == "Mountain":
            for i, key in enumerate(["f_mountain1", "f_mountain2", "f_mountain3"]):
                write(f"Tiles/Mountain{'' if i == 0 else i + 1}.png", slab(name), [(key, 196, 150)], tilted_draw=slab(name, True),
                      tilted_sprites=[mock_prop(key, *MOCK_FEATURES[name])]); n += 1
            continue
        write(f"Tiles/{name}.png", slab(name), tilted_draw=slab(name, True)); write(f"Tiles/{name}2.png", slab(name), tilted_draw=slab(name, True)); n += 2
        if name in DECOR:
            size, bottom = (70, 132) if name != "Ocean" else (64, 130)
            write(f"Tiles/{name}3.png", slab(name), [(DECOR[name], size, bottom, CX + 22)], tilted_draw=slab(name, True),
                  tilted_sprites=[(*mock_prop(DECOR[name], DECOR_MOCK_SIZE.get(name, 30), 8), CX + 22)]); n += 1
    for name, (keys, size, bottom) in FEATURES.items():
        for i, key in enumerate(keys):
            write(f"Tiles/{name}{'' if i == 0 else i + 1}.png", sprites=[(key, size, bottom)],
                  tilted_sprites=[mock_prop(key, *MOCK_FEATURES[name])] if name in MOCK_FEATURES else None); n += 1
    for t in load("Terrains.json"):
        if t["type"] == "NaturalWonder":
            # a natural wonder replaces the base image, so it carries the slab of the terrain it turns into
            write(f"Tiles/{t['name']}.png", slab(t.get("turnsInto", "Grassland")), [(f"nw_{t['name']}", 186, 154)], tilted_draw=slab(t.get("turnsInto", "Grassland"), True)); n += 1
    for r in load("TileResources.json"):
        write(f"Tiles/{r['name']}.png", sprites=[(f"r_{r['name']}", 74, 158, 138)]); n += 1
    for i in load("TileImprovements.json"):
        if (ART / f"i_{i['name']}.png").exists() and i["name"] not in ("Oasis",):
            write(f"Tiles/{i['name']}.png", sprites=[(f"i_{i['name']}", 108, 146, 84)]); n += 1
    for e in load("Eras.json"):
        era = e["name"].split()[0]
        write(f"Tiles/City center-{e['name']}.png", sprites=[(f"c_{era}_medium", 176, 154)], recolor=NEUTRAL_ROOFS); n += 1
    for variant in (OUT, OUT / "Tilted"):
        shutil.copy(variant / "Tiles/City center-Ancient era.png", variant / "Tiles/City center.png")
    edges(); n += 12
    # unexplored tiles: soft cloud white; not-visible tiles keep the terrain with no crosshatch
    write("UnexploredTile.png", [f"fill #eef3f7 polygon {poly(hexpts())}"])
    # Cloud puffs stay upright and overlap their neighbors, concealing the tile lattice.
    cloud = OUT / "Tilted/UnexploredTile.png"
    magick("-size", f"{W}x{H}", "xc:none", "-draw",
           "fill #e6edf4 ellipse 96,98 92,37 0,360 fill #f7f9fc "
           "ellipse 45,88 43,34 0,360 ellipse 88,70 49,40 0,360 "
           "ellipse 139,80 46,37 0,360 ellipse 104,104 68,31 0,360", cloud)
    magick("-size", "1x1", "xc:none", OUT / "CrosshatchHexagon.png")
    n += units()
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
    (ROOT / "android/assets/jsons/TileSets/Polytopia.json").write_text(json.dumps(config, indent=4) + "\n")
    print(f"exported {n} images to {OUT.relative_to(ROOT)}")


main()
