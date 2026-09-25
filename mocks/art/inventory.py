# Builds the map art inventory from the Gods & Kings ruleset: every map-facing item, how we make it, and whether it exists yet.
# Writes inventory.json (production checklist) and INVENTORY.md (readable summary). Run from anywhere.
import json, pathlib, re

HERE = pathlib.Path(__file__).resolve().parent
RULES = HERE.parent.parent / "android/assets/jsons/Civ V - Gods & Kings"


def load(name):
    s = (RULES / name).read_text()
    s = re.sub(r'("(?:\\.|[^"\\])*")|//[^\n]*', lambda m: m.group(1) or "", s)
    s = re.sub(r"/\*.*?\*/", "", s, flags=re.S)
    return json.loads(re.sub(r",(\s*[}\]])", r"\1", s))


terrains, resources, improvements = load("Terrains.json"), load("TileResources.json"), load("TileImprovements.json")
buildings, units, nations, eras = load("Buildings.json"), load("Units.json"), load("Nations.json"), load("Eras.json")
ACTIONS = {"Remove Forest", "Remove Jungle", "Remove Fallout", "Remove Marsh", "Remove Road", "Remove Railroad",
           "Cancel improvement order", "Repair"}

# method: code = drawn procedurally in the renderer; sprite = generated low-poly sprite (unit style, keyed);
# scene = generated high-detail flat picture; unciv = Unciv's existing asset is kept.
items = []


def add(category, name, method, files, note="", done=False):
    items.append({"category": category, "name": name, "method": method, "files": files, "note": note, "done": bool(done)})


def have(*paths):
    return any((HERE / p).exists() for p in paths)


for t in terrains:
    n, kind = t["name"], t["type"]
    if kind in ("Land", "Water"):
        add("Terrain", n, "code", [f"Tiles/{n}.png"], "faceted hex slab with dirt sides; water sits lower", done=True if n in ("Ocean", "Coast", "Grassland", "Plains", "Desert", "Mountain", "Lakes", "Tundra", "Snow") else False)
    elif kind == "TerrainFeature":
        add("Terrain feature", n, "code", [f"Tiles/{n}.png"], "rivers run on hex edges" if n == "River" else "",
            done=n in ("Hill", "Forest", "Jungle", "Marsh", "Oasis", "River"))
    else:
        add("Natural wonder", n, "sprite", [f"Tiles/{n}.png"], "landmark sprite; discovery uses a scene",
            done=have(f"nw_{n}.png") or n == "Grand Mesa")
        add("Natural wonder scene", n, "scene", [], "shown when first discovered", done=have(f"nws_{n}.png"))

for r in resources:
    add(f"Resource ({r.get('resourceType', 'Bonus').lower()})", r["name"], "sprite", [f"Tiles/{r['name']}.png"],
        "small tile prop, readable at map zoom", done=have(f"r_{r['name']}.png"))

for i in improvements:
    n = i["name"]
    if n in ACTIONS or n == "City center":
        continue
    method = "code" if n in ("Road", "Railroad") else "sprite"
    note = {"Road": "drawn between tile centers", "Railroad": "drawn between tile centers",
            "Ancient ruins": "goody hut", "Barbarian encampment": "hostile camp"}.get(n, "")
    add("Improvement", n, method, [f"Tiles/{n}.png"], note, done=have(f"i_{n}.png") or n in ("Farm", "Mine"))

for e in eras:
    add("City center", e["name"], "code", [f"Tiles/City center-{e['name']}.png"],
        "houses kit grows with population; roofs and walls change by era", done=e["name"] == "Ancient era")
for label in ("Capital marker", "City-state marker (5 types)", "Puppet city", "City being razed", "City under siege", "City ruins"):
    add("City state", label, "code", [], "", done=label == "Capital marker")

replaced = {u.get("replaces") for u in units if u.get("uniqueTo")}
for u in units:
    unique = u.get("uniqueTo")
    add("Unit (civ unique)" if unique else "Unit", u["name"], "sprite", [f"Units/{u['name']}.png", f"Units/{u['name']}-1.png"],
        f"unique to {unique}, replaces {u.get('replaces')}" if unique else u.get("unitType", ""),
        done=have(f"k_{u['name'].lower()}.png"))
for n in ("EmbarkedUnit-Military", "EmbarkedUnit-Civilian", "EmbarkedUnit-Settler"):
    add("Unit (embarked)", n, "sprite", [f"Units/{n}.png", f"Units/{n}-Modern era.png"], "boat a land unit rides at sea", done=False)

for b in buildings:
    if b.get("isWonder"):
        add("World wonder landmark", b["name"], "sprite", [], "stands on its city tile", done=have(f"wl_{b['name']}.png"))
        add("World wonder scene", b["name"], "scene", [], "completion celebration", done=have(f"ws_{b['name']}.png"))

for n in nations:
    if n.get("leaderName") and not n.get("cityStateType") and n["name"] not in ("Barbarians", "Spectator"):
        add("Leader portrait", f"{n['leaderName']} ({n['name']})", "scene", [], "diplomacy", done=have(f"p_{n['leaderName'].split()[0]}.png"))

OVERLAYS = [
    ("Borders", "civ-colored edge line with a soft inner tint", True),
    ("Unexplored fog", "cloud cover", True),
    ("Not-visible tiles", "dimmed, no crosshatch", False),
    ("Selection ring", "pulsing ring under the unit", True),
    ("Movement range", "dashed white hexes", True),
    ("Attack target", "red ring with a cross", True),
    ("Path preview", "dotted route with turn numbers", False),
    ("Unit arrows", "moved, attacked, withdrew, teleported: 7 kinds", False),
    ("City banner", "population, name, capital star, wonder badge, build turns", True),
    ("City health bar", "shown when a city is damaged", False),
    ("Unit health bar", "shown when damaged", True),
    ("Unit status icons", "fortified, sleeping, automated, embarked", False),
    ("Tile yields", "food, production, gold pips when toggled", False),
    ("Worked tiles", "citizen markers in city view", False),
    ("Resource amount", "strategic resource count badge", False),
    ("Improvement in progress", "worker build with turns left", False),
    ("Pillaged improvement", "broken variant plus smoke", False),
    ("Combat effects", "hit flash, damage numbers, arrows, dust, burst", True),
]
for n, note, done in OVERLAYS:
    add("Map overlay", n, "code", [], note, done=done)

out = HERE / "inventory.json"
out.write_text(json.dumps(items, indent=1))

cats = {}
for it in items:
    c = cats.setdefault(it["category"], {"method": set(), "total": 0, "done": 0, "names": []})
    c["method"].add(it["method"]); c["total"] += 1; c["done"] += it["done"]; c["names"].append(it["name"])
lines = ["# Map art inventory", "", "Generated by `inventory.py` from the Gods & Kings ruleset. `inventory.json` is the per-item checklist.", "",
         "| Category | Count | Done | How it's made |", "|---|---|---|---|"]
for k, c in cats.items():
    lines.append(f"| {k} | {c['total']} | {c['done']} | {', '.join(sorted(c['method']))} |")
t = sum(c["total"] for c in cats.values()); d = sum(c["done"] for c in cats.values())
lines += [f"| **Total** | **{t}** | **{d}** | |", ""]
for k, c in cats.items():
    lines += [f"## {k}", ", ".join(c["names"]), ""]
(HERE / "INVENTORY.md").write_text("\n".join(lines))
print("\n".join(lines[:len(cats) + 7]))
