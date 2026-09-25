# Run after build.py: inlines icons build.py does not cover (nation, city-state type, victory,
# policy branch, every civ's unique units/buildings/improvements) plus the baked map backdrop.
#   python3 build.py setup-cs-victory.src.html && python3 setup-cs-victory.post.py
import base64, json, pathlib, re

HERE = pathlib.Path(__file__).resolve().parent
A = HERE.parent / "android"
out = HERE / "setup-cs-victory.html"
html = out.read_text()

def uri(p):
    return "data:image/png;base64," + base64.b64encode(p.read_bytes()).decode()

extra = {}
for f in (A / "Images.NationIcons/NationIcons").glob("*.png"):
    extra["n_" + f.stem] = uri(f)
for pre, d in [("cs_", "Images.Icons/CityStateIcons"), ("v_", "Images.NationIcons/VictoryTypeIcons"),
               ("vs_", "Images.NationIcons/VictoryScreenIcons"), ("pb_", "Images.PolicyIcons/PolicyBranchIcons")]:
    for f in (A / d).glob("*.png"):
        extra[pre + f.stem] = uri(f)

civs = json.loads(re.search(r"const CIVS = (\[.*?\]);\n", html).group(1))
for c in civs:
    for name, _, _ in c["uq"]:
        p = next((A / d / f"{name}.png" for d in ("Images.ConstructionIcons/UnitIcons", "Images.ConstructionIcons/BuildingIcons", "Images.Icons/ImprovementIcons")
                  if (A / d / f"{name}.png").exists()), None)
        if p:
            extra["uq_" + name] = uri(p)
        else:
            print("no icon for", name)

mapbg = "data:image/webp;base64," + base64.b64encode((HERE / "setup-cs-victory.map.webp").read_bytes()).decode()
html = html.replace("/*EXTRA*/{}", json.dumps(extra)).replace("/*MAPBG*/''", json.dumps(mapbg))
out.write_text(html)
print(f"{out}: +{len(extra)} icons, {out.stat().st_size // 1024} KB")
