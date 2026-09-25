# Generates art through the CLIProxy Responses API image tool, then keys out the magenta backdrop.
# Usage: python3 gen.py jobs.json   (jobs: [{"name": "...", "prompt": "...", "size": "1024x1024", "key": true}])
import base64, concurrent.futures, json, pathlib, subprocess, sys, urllib.request

PROXY = "http://100.90.27.0:8318/v1/responses"
KEY = pathlib.Path.home().joinpath(".config/cliproxyapi/fleet-proxy.key").read_text().strip()
OUT = pathlib.Path(__file__).parent
STYLE = pathlib.Path(OUT / "style.txt").read_text().strip()


def generate(job):
    body = {
        "model": "gpt-6-luna", "stream": True,
        "input": [{"role": "user", "content": [{"type": "input_text", "text": f"Generate an image. {job['prompt']}\n\n{job.get('style', STYLE)}"}]
                   + ([{"type": "input_image", "image_url": "data:image/png;base64," + base64.b64encode((OUT / job["ref"]).read_bytes()).decode()}] if job.get("ref") else [])}],
        "tools": [{"type": "image_generation", "size": job.get("size", "1024x1024"), "quality": job.get("quality", "medium")}],
        "tool_choice": {"type": "image_generation"},
    }
    req = urllib.request.Request(PROXY, json.dumps(body).encode(), {"Authorization": f"Bearer {KEY}", "Content-Type": "application/json"})
    image = None
    with urllib.request.urlopen(req, timeout=300) as r:
        for line in r:
            if not line.startswith(b"data: "):
                continue
            ev = json.loads(line[6:])
            item = ev.get("item") or {}
            if ev.get("type") == "response.output_item.done" and item.get("type") == "image_generation_call":
                image = item.get("result")
            if ev.get("type") == "error":
                raise RuntimeError(ev)
    if not image:
        raise RuntimeError("no image returned")
    raw = OUT / "raw" / f"{job['name']}.png"
    raw.parent.mkdir(parents=True, exist_ok=True)
    (OUT / job["name"]).parent.mkdir(parents=True, exist_ok=True)
    raw.write_bytes(base64.b64decode(image))
    final = OUT / f"{job['name']}.png"
    if job.get("key", True):
        # drop the magenta backdrop, pull magenta spill off the edges, trim, square-pad
        subprocess.run(["magick", str(raw), "-fuzz", "22%", "-transparent", "#FF00FF",
                        "-channel", "A", "-morphology", "Erode", "Disk:1.2", "+channel",
                        "-trim", "+repage", "-gravity", "center", "-background", "none",
                        "-extent", "%[fx:max(w,h)*1.08]x%[fx:max(w,h)*1.08]", "-resize", "256x256", str(final)], check=True)
    else:
        subprocess.run(["magick", str(raw), "-resize", "512x512", str(final)], check=True)
    return job["name"]


jobs = json.loads(pathlib.Path(sys.argv[1]).read_text())
with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:
    futures = {pool.submit(generate, j): j["name"] for j in jobs}
    for f in concurrent.futures.as_completed(futures):
        try:
            print("ok", f.result(), flush=True)
        except Exception as e:
            print("FAIL", futures[f], str(e)[:200], flush=True)
