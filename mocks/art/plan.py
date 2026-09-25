# Builds generation job files for the remaining art, straight from the ruleset, in the styles recorded in DESIGN.md.
# Usage: python3 plan.py units-test | units | wonders | leaders | nwscenes | boats | cities   -> writes jobs_<name>.json
import json, pathlib, re, sys

HERE = pathlib.Path(__file__).resolve().parent
RULES = HERE.parent.parent / "android/assets/jsons/Civ V - Gods & Kings"


def load(name):
    s = (RULES / name).read_text()
    s = re.sub(r'("(?:\\.|[^"\\])*")|//[^\n]*', lambda m: m.group(1) or "", s)
    s = re.sub(r"/\*.*?\*/", "", s, flags=re.S)
    return json.loads(re.sub(r",(\s*[}\]])", r"\1", s))


UNIT_STYLE = (HERE / "style_unit.txt").read_text().strip().replace(
    "big simple head, stocky toy proportions",
    "big simple heads and stocky toy proportions for people; vehicles, ships and aircraft are chunky toy models with the same faceting")
SIMPLE = (HERE / "style_simple.txt").read_text().strip()
LANDMARK = SIMPLE.replace("Three-quarter isometric view, object centered and filling about 70 percent of the frame.",
                          "Three-quarter isometric view matching a tile-based strategy map seen from above at 45 degrees, object centered and filling about 80 percent of the frame, no ground tile, no base plate.")
CITY = SIMPLE.replace("Three-quarter isometric view, object centered and filling about 70 percent of the frame.",
                      "Seen from above at 45 degrees like a tile-based strategy map. The city covers one hexagonal map tile: a compact cluster, wide and fairly flat, centered, filling about 85 percent of the frame width. No ground tile, no base plate, no grass under it.").replace(
    "Palette:", "TEAM COLOR RULE: all roofs, flags and banners are pure bright green #00FF00 on lit facets and #00A000 on shaded facets; nothing else is green. Other palette:").replace("grass green #8BD14F, ", "")
SCENE = ("Style: a polished low-poly 3D diorama illustration in the spirit of The Battle of Polytopia but richer and more detailed, crisp flat-shaded facets, "
         "warm light from the upper left, soft light rays, saturated but harmonious palette. No text, no UI, no border, no people unless named. "
         "Vertical portrait composition for a phone screen, the subject in the middle third, generous sky above and a soft cloud sea or landscape below.")
PORTRAIT = ("Style: a chunky low-poly faceted 3D bust portrait in the style of The Battle of Polytopia leaders, large flat facets with flat shading and 3 to 4 tones per color, "
            "light from the upper left, no text, no outlines. Historically grounded clothing and regalia. Facing three-quarters toward the viewer. "
            "Flat solid background color {bg} with a subtle lighter circle behind the head.")

KIND = {
    "Melee Water": "ship", "Ranged Water": "ship", "Submarine": "ship", "Aircraft Carrier": "ship", "Civilian Water": "ship",
    "Fighter": "air", "Bomber": "air", "Atomic Bomber": "air", "Helicopter": "air", "Missile": "missile",
    "Armored": "vehicle", "Siege": "vehicle",
}
FRAMING = {
    "ship": "Show it as a chunky toy ship facing right, floating with no water. Team color goes on sails, flags or a painted hull stripe.",
    "air": "Show it as a chunky toy aircraft flying to the right, seen from slightly above. Team color goes on wing and tail stripes.",
    "missile": "Show it as a chunky toy missile pointing up and to the right. Team color goes on a painted band.",
    "vehicle": "Show it as a chunky toy model facing right. Team color goes on painted panels or a flag.",
    "person": "Show it as a full-body chunky toy figure facing right. Team color goes on tunic, uniform, cloak or plume.",
}


def unit_job(u, era):
    kind = KIND.get(u.get("unitType"), "person")
    air_era = {"Industrial era": " It is a World War I era aircraft.", "Modern era": " It is a 1940s propeller-driven aircraft, not a jet.",
               "Atomic era": " It is a 1950s aircraft.", "Information era": " It is a modern aircraft."}
    era_hint = air_era.get(era, "") if kind == "air" and u.get("unitType") != "Helicopter" else ""
    unique = f" It is the unique unit of {u['uniqueTo']}, replacing the {u.get('replaces')}; show its distinctive historical look." if u.get("uniqueTo") else ""
    prompt = (f"Subject: the {u['name']} from Civilization V, a {era.lower()} {u.get('unitType', '').lower()} unit, with historically accurate gear for its era.{era_hint}{unique} "
              f"{FRAMING[kind]}")
    return {"name": f"k_{u['name'].lower()}", "prompt": prompt, "style": UNIT_STYLE, "ref": "simple/u_Bowman.png"}


def units(names=None):
    techs = {t["name"]: c.get("era") for c in load("Techs.json") for t in c["techs"]}
    jobs = []
    for u in load("Units.json"):
        if names and u["name"] not in names:
            continue
        if not names and (HERE / f"k_{u['name'].lower()}.png").exists():
            continue
        jobs.append(unit_job(u, techs.get(u.get("requiredTech"), "Ancient era")))
    return jobs


def wonders():
    jobs = []
    for b in load("Buildings.json"):
        if not b.get("isWonder"):
            continue
        n = b["name"]
        if not (HERE / f"wl_{n}.png").exists():
            jobs.append({"name": f"wl_{n}", "prompt": f"Subject: {n}, the world wonder, as a compact landmark building true to its real appearance.", "style": LANDMARK})
        if not (HERE / f"ws_{n}.png").exists():
            jobs.append({"name": f"ws_{n}", "key": False, "size": "1024x1536", "quality": "high", "out_size": "683x1024",
                         "prompt": f"Subject: {n} standing on a floating hexagonal island at its most majestic, true to its real appearance and setting, with a few low-poly trees and details around it.",
                         "style": SCENE})
    return jobs


def leaders():
    jobs = []
    for n in load("Nations.json"):
        if not n.get("leaderName") or n.get("cityStateType") or n["name"] in ("Barbarians", "Spectator"):
            continue
        key = f"p_{n['leaderName'].split()[0]}"
        if (HERE / f"{key}.png").exists():
            continue
        r, g, b = n.get("outerColor", [80, 80, 80])
        jobs.append({"name": key, "key": False, "quality": "high",
                     "prompt": f"Subject: {n['leaderName']}, leader of {n['name']}, historically accurate appearance, clothing and headwear, calm confident expression.",
                     "style": PORTRAIT.format(bg=f"#{r:02X}{g:02X}{b:02X}")})
    return jobs


def nwscenes():
    return [{"name": f"nws_{t['name']}", "key": False, "size": "1024x1536", "quality": "high", "out_size": "683x1024",
             "prompt": f"Subject: the natural wonder {t['name']}, true to its real appearance, discovered at dawn, with low-poly landscape around it.", "style": SCENE}
            for t in load("Terrains.json") if t["type"] == "NaturalWonder" and not (HERE / f"nws_{t['name']}.png").exists()]


def boats():
    d = {"EmbarkedUnit-Military": "a small wooden troop boat with a square sail and a few shields along the side",
         "EmbarkedUnit-Civilian": "a small wooden rowing boat with a cloth canopy",
         "EmbarkedUnit-Settler": "a small wooden boat loaded with bundles, a barrel and a rolled blanket"}
    return [{"name": f"k_{n.lower()}", "prompt": f"Subject: {v}, the boat a land unit rides at sea. Show it as a chunky toy boat facing right, floating with no water. Team color goes on the sail or canopy.",
             "style": UNIT_STYLE, "ref": "simple/u_Bowman.png"} for n, v in d.items()]


def cities():
    look = {"Classical": "cream stone houses with columns and terracotta roofs, a small temple", "Renaissance": "plastered townhouses with tall roofs and a domed church",
            "Atomic": "mid-century concrete blocks, a radio mast and a factory", "Information": "glass office towers and curved modern buildings",
            "Future": "sleek white futuristic towers with glowing panels and a small dome"}
    return [{"name": f"c_{e}_medium", "prompt": f"Subject: a town of about seven buildings of {v}, with a low wall, a {e.lower()} era city.", "style": CITY}
            for e, v in look.items() if not (HERE / f"c_{e}_medium.png").exists()]


what = sys.argv[1]
jobs = {"units-test": lambda: units({"Knight", "Musketman", "Rifleman", "Cannon", "Tank", "Fighter", "Battleship", "Great General"}),
        "units": units, "wonders": wonders, "leaders": leaders, "nwscenes": nwscenes, "boats": boats, "cities": cities}[what]()
(HERE / f"jobs_{what}.json").write_text(json.dumps(jobs, indent=1))
print(what, len(jobs), "jobs")
