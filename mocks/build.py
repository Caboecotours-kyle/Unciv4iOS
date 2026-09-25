# Inlines Unciv icon PNGs into the mock as data URIs so the HTML is a single offline file.
import base64, json, pathlib, re, sys

ROOT = pathlib.Path(__file__).resolve().parent.parent / "android"
DIRS = ["Images.ConstructionIcons/UnitActionIcons", "Images.ConstructionIcons/UnitIcons",
        "Images.ConstructionIcons/BuildingIcons", "Images.Icons/OtherIcons", "Images.Icons/StatIcons",
        "Images.Icons/UnitTypeIcons"]

# names passed through variables rather than literal ic('...') calls
EXTRA = ["Library", "Warrior", "Granary", "Shrine", "Walls", "Settler", "Bowman", "Temple", "Archer", "Water Mill",
         "Trireme", "Monument", "Swordsman", "Fortify", "Sleep", "Explore", "Skip", "ShowMore", "RangedStrength",
         "Settings", "HexagonOutline", "Cities", "Resources", "Horseman", "Catapult", "Market"]

src = pathlib.Path(sys.argv[1])
html = src.read_text()
names = set(re.findall(r"ic\('([^']+)'", html)) | set(EXTRA)
techs = ["Agriculture", "Pottery", "Animal Husbandry", "Archery", "Mining", "Sailing", "Calendar", "Writing", "Trapping",
         "The Wheel", "Masonry", "Bronze Working", "Optics", "Horseback Riding", "Mathematics", "Construction",
         "Philosophy", "Drama and Poetry", "Currency", "Engineering", "Iron Working", "Theology", "Civil Service",
         "Guilds", "Metal Casting"]
names |= {"t_" + t for t in techs}
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
for r in res:
    icons["r_" + r] = uri(ROOT / "Images.Icons/ResourceIcons" / f"{r}.png")

out = src.with_name(src.name.replace(".src", ""))
out.write_text(html.replace("/*ICONS*/{}", json.dumps(icons)))
print(f"{out}: {len(icons)} icons, {out.stat().st_size // 1024} KB; missing: {missing}")
