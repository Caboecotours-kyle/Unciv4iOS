# Post-build step for policies-religion.html: build.py only searches its own icon folders, so this
# inlines policy/religion icons and the Gods & Kings policy and belief data exported from the ruleset.
import base64, json, pathlib, re, sys

HERE = pathlib.Path(__file__).resolve().parent
ROOT = HERE.parent / "android"
RS = ROOT / "assets/jsons/Civ V - Gods & Kings"
LEADER = "Nebuchadnezzar"


def L(p):
    s = open(p).read()
    s = re.sub(r'("(?:\\.|[^"\\])*")|//[^\n]*', lambda m: m.group(1) or '', s)
    s = re.sub(r'/\*.*?\*/', '', s, flags=re.S)
    return json.loads(re.sub(r',(\s*[}\]])', r'\1', s))


def clean(u):
    """Ruleset unique -> readable line. Returns None for AI hints, alerts and availability rules."""
    if re.search(r"weight to this choice|global alert|Only available|leader title", u):
        return None
    u = u.replace("[All] ", "").replace("[all] ", "all ").replace("<for [All] units>", "for all units")
    u = u.replace("{Military} {Water}", "naval military").replace("{pre-[Industrial era]} {Military} {Land}", "pre-Industrial land military")
    u = re.sub(r"<([^<>]*)>", r"\1", u)
    u = u.replace("[", "").replace("]", "").replace("{", "").replace("}", "")
    u = re.sub(r"\s+", " ", u).strip()
    for a, b in (("in all cities with a garrison", "in garrisoned cities"), (" (modified by game speed)", ""),
                 ("in in cities following this religion cities", "in cities following this religion"),
                 ("Provides a Aqueduct", "Provides an Aqueduct"),
                 ("in cities following this religion in cities with at least", "in cities following this religion with at least"),
                 ("in all cities in which the majority religion is a major religion at an increasing price (500) starting from the Industrial era",
                  "in cities following a major religion, +500 each, from the Industrial era")):
        u = u.replace(a, b)
    return u[0].upper() + u[1:]


def title(us):
    for u in us:
        m = re.search(r"leader title of \[(.*)\]$", u)
        if m:
            return m.group(1).replace("[leaderName]", LEADER)
    return None


def excl(us):
    return [m for u in us for m in re.findall(r"Only available <before adopting \[([^\]]+)\]>", u)]


def pol_data():
    out = []
    for b in L(RS / "Policies.json"):
        pols = b["policies"]
        fin = pols[-1]
        out.append({
            "name": b["name"], "era": b["era"].replace(" era", ""),
            "fx": [c for c in map(clean, b.get("uniques", [])) if c], "excl": excl(b.get("uniques", [])),
            "pol": [{"name": p["name"], "req": p.get("requires") or [b["name"]], "row": p["row"], "col": p["column"],
                     "fx": [c for c in map(clean, p.get("uniques", [])) if c]} for p in pols[:-1]],
            "fin": {"fx": [c for c in map(clean, fin.get("uniques", [])) if c], "title": title(fin.get("uniques", []))},
        })
    return out


def bel_data():
    out = {}
    for b in L(RS / "Beliefs.json"):
        out.setdefault(b["type"], []).append({"name": b["name"], "fx": [c for c in map(clean, b.get("uniques", [])) if c]})
    return out


def uri(p):
    return "data:image/png;base64," + base64.b64encode(p.read_bytes()).decode()


def icons():
    d = {}
    for f in (ROOT / "Images.PolicyIcons/PolicyIcons").glob("*.png"):
        d["pol_" + f.stem] = uri(f)
    for f in (ROOT / "Images.PolicyIcons/PolicyBranchIcons").glob("*.png"):
        d["br_" + f.stem] = uri(f)
    for f in (ROOT / "Images.ReligionIcons/ReligionIcons").glob("*.png"):
        d["rel_" + f.stem] = uri(f)
    return d


if __name__ == "__main__":
    if sys.argv[1:] == ["--print"]:
        for b in pol_data():
            print(b["name"], b["era"], b["excl"], b["fx"])
            for p in b["pol"]:
                print("   ", p["name"], p["req"], p["row"], p["col"], p["fx"])
            print("   FIN", b["fin"])
        for k, v in bel_data().items():
            for b in v:
                print(k, b["name"], b["fx"])
        sys.exit()
    out = HERE / "policies-religion.html"
    html = out.read_text()
    for token, val in (("/*PICONS*/{}", icons()), ("/*POLDATA*/[]", pol_data()), ("/*BELDATA*/{}", bel_data()),
                       ("/*RELIGIONS*/[]", L(RS / "Religions.json"))):
        assert token in html, token
        html = html.replace(token, json.dumps(val))
    out.write_text(html)
    print(f"{out}: {out.stat().st_size // 1024} KB after policy/religion icons and data")
