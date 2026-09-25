# Inlines Unciv icon PNGs into the mock as data URIs so the HTML is a single offline file.
import base64, json, pathlib, re, subprocess, sys

ROOT = pathlib.Path(__file__).resolve().parent.parent / "android"
DIRS = ["Images.ConstructionIcons/UnitActionIcons", "Images.ConstructionIcons/UnitIcons",
        "Images.ConstructionIcons/BuildingIcons", "Images.Icons/OtherIcons", "Images.Icons/StatIcons",
        "Images.Icons/UnitTypeIcons"]

# names passed through variables rather than literal ic('...') calls
EXTRA = ["Library", "Warrior", "Granary", "Shrine", "Walls", "Settler", "Bowman", "Temple", "Archer", "Water Mill",
         "Trireme", "Monument", "Swordsman", "Fortify", "Sleep", "Explore", "Skip", "ShowMore", "RangedStrength",
         "Settings", "HexagonOutline", "Cities", "Resources", "CrosshairB", "ForwardArrow", "MenuIcon", "Star", "MoveTo", "NationSwap", "Worker", "Hoplite", "Settler"]

src = pathlib.Path(sys.argv[1])
html = src.read_text()
names = {n for n in re.findall(r"ic\('([^']+)'", html) if not n.endswith('_')} | set(EXTRA)
# tech tree data exported from the Gods & Kings ruleset: [name, column, row, era, cost, prerequisites, unlocks]
techs = json.loads((src.parent / "techs.json").read_text())
names |= {"t_" + t[0] for t in techs}
unlocks = {u[0] for t in techs for u in t[6]}
res = sorted(f.stem for f in (ROOT / "Images.Icons/ResourceIcons").glob("*.png"))

def uri(p):
    return "data:image/png;base64," + base64.b64encode(p.read_bytes()).decode()

icons, missing = {}, []
for n in sorted(names):
    if n.startswith("t_"):
        p = ROOT / "Images.Tech/TechIcons" / f"{n[2:]}.png"
    else:
        p = next((ROOT / d / f"{n}.png" for d in DIRS if (ROOT / d / f"{n}.png").exists()), None)
    if p and p.exists():
        icons[n] = uri(p)
    else:
        missing.append(n)
for u in unlocks:
    p = next((ROOT / d / f"{u}.png" for d in ("Images.ConstructionIcons/BuildingIcons", "Images.ConstructionIcons/UnitIcons", "Images.Icons/ImprovementIcons", "Images.Icons/ResourceIcons")
              if (ROOT / d / f"{u}.png").exists()), None)
    if p:
        icons["u_" + u] = uri(p)
    else:
        missing.append("u_" + u)
for r in res:
    icons["r_" + r] = uri(ROOT / "Images.Icons/ResourceIcons" / f"{r}.png")

# generated art: bare names for icons, p_* kept for portraits; shrunk to webp for the mock payload
art = {}
units = {}
for f in sorted([*(src.parent / "art").glob("k_*.png"), *(src.parent / "art").glob("c_*.png")]):
    webp = subprocess.run(["magick", str(f), "-resize", "160x160", "-quality", "92", "webp:-"], capture_output=True, check=True).stdout
    units[f.stem] = "data:image/webp;base64," + base64.b64encode(webp).decode()
for f in sorted((src.parent / "art").glob("*.png")):
    if f.stem.startswith(("k_", "c_", "tc_", "ic_", "contact", "preview")):
        continue
    whole = f.stem.startswith(("p_", "wl_", "ws_", "r_", "i_", "nw_", "f_", "s_", "tt_"))
    key = f.stem if whole else f.stem[2:]
    size = {"p_": "320x320", "ws": "720x1080", "wl": "160x160", "r_": "96x96", "i_": "112x112", "nw": "160x160", "f_": "160x160", "s_": "96x96", "tt": "683x1024"}.get(f.stem[:2], "128x128")
    webp = subprocess.run(["magick", str(f), "-resize", size, "-quality", "88", "webp:-"], capture_output=True, check=True).stdout
    art[key] = "data:image/webp;base64," + base64.b64encode(webp).decode()
simple = {}
for f in sorted((src.parent / "art" / "simple").glob("*.png")):
    webp = subprocess.run(["magick", str(f), "-resize", "128x128", "-quality", "88", "webp:-"], capture_output=True, check=True).stdout
    simple[f.stem[2:]] = "data:image/webp;base64," + base64.b64encode(webp).decode()
if "Walls" in art:
    art["Walls of Babylon"] = art["Walls"]

out = src.with_name(src.name.replace(".src", ""))
out.write_text(html.replace("/*ICONS*/{}", json.dumps(icons)).replace("/*TECHS*/[]", json.dumps(techs)).replace("/*ART*/{}", json.dumps(art)).replace("/*ART_SIMPLE*/{}", json.dumps(simple)).replace("/*UNITART*/{}", json.dumps(units)))
print(f"{out}: {len(icons)} icons, {out.stat().st_size // 1024} KB; missing: {missing}")
