"""Export the approved mock's vector stat glyphs to one small runtime image."""
from pathlib import Path
import re
import subprocess
from tempfile import TemporaryDirectory

root = Path(__file__).resolve().parents[2]
source = (root / 'mocks/portrait-hud.src.html').read_text()
block = source.split('const STAT = {', 1)[1].split('\n};', 1)[0]
colors = dict(re.findall(r'--([a-z]+):(#[0-9a-fA-F]+)', source))
glyphs = dict(re.findall(r'(\w+):`([^`]+)`', block))
names = ['food', 'prod', 'gold', 'sci', 'cul', 'fai', 'hap', 'str', 'rng', 'mov']
with TemporaryDirectory(prefix='unciv-portrait-icons-') as directory:
    images = []
    for index, name in enumerate(names):
        paths = re.sub(r'var\(--(\w+)\)', lambda match: colors[match[1]], glyphs[name])
        svg = f'<svg xmlns="http://www.w3.org/2000/svg" width="64" height="64" viewBox="0 0 16 16">{paths}</svg>'
        path = str(Path(directory) / f'{index}.png')
        subprocess.run(['magick', '-background', 'none', 'svg:-', path], input=svg.encode(), check=True)
        images.append(path)
    subprocess.run(['magick', *images, '+append', '-define', 'png:exclude-chunks=date,time,tIME',
                    '-strip', str(root / 'android/assets/ExtraImages/PortraitStats.png')], check=True)
