# Inlines Unciv icon PNGs into the mock as data URIs so the HTML is a single offline file.
import base64, json, pathlib, re, sys

ROOT = pathlib.Path(__file__).resolve().parent.parent / "android"
DIRS = ["Images.ConstructionIcons/UnitActionIcons", "Images.ConstructionIcons/UnitIcons",
        "Images.ConstructionIcons/BuildingIcons", "Images.Icons/OtherIcons", "Images.Icons/StatIcons",
        "Images.Icons/UnitTypeIcons"]

# names passed through variables rather than literal ic('...') calls
EXTRA = ["Library", "Warrior", "Granary", "Shrine", "Walls", "Settler", "Bowman", "Temple", "Archer", "Water Mill",
         "Trireme", "Monument", "Swordsman", "Fortify", "Sleep", "Explore", "Skip", "ShowMore", "RangedStrength",
         "Settings", "HexagonOutline", "Cities", "Resources", "CrosshairB", "ForwardArrow", "MenuIcon"]

src = pathlib.Path(sys.argv[1])
html = src.read_text()
names = {n for n in re.findall(r"ic\('([^']+)'", html) if not n.endswith('_')} | set(EXTRA)
# tech tree data exported from the Gods & Kings ruleset: [name, column, row, era, cost, prerequisites, unlocks]
techs = json.loads((src.parent / "techs.json").read_text())
names |= {"t_" + t[0] for t in techs}
unlocks = {u[0] for t in techs for u in t[6]}
res = ["Wheat", "Horses", "Fish", "Iron", "Gems", "Cattle"]

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
    p = next((ROOT / d / f"{u}.png" for d in ("Images.ConstructionIcons/BuildingIcons", "Images.ConstructionIcons/UnitIcons")
              if (ROOT / d / f"{u}.png").exists()), None)
    if p:
        icons["u_" + u] = uri(p)
    else:
        missing.append("u_" + u)
for r in res:
    icons["r_" + r] = uri(ROOT / "Images.Icons/ResourceIcons" / f"{r}.png")

out = src.with_name(src.name.replace(".src", ""))
out.write_text(html.replace("/*ICONS*/{}", json.dumps(icons)).replace("/*TECHS*/[]", json.dumps(techs)))
print(f"{out}: {len(icons)} icons, {out.stat().st_size // 1024} KB; missing: {missing}")
