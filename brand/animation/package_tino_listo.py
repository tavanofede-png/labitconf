from pathlib import Path
from PIL import Image

ROOT = Path(__file__).resolve().parent
frames = [Image.open(path).convert("RGBA") for path in sorted((ROOT / "frames").glob("frame_*.png"))]
if not frames:
    raise SystemExit("No Blender frames found")

animated = ROOT / "tino_listo_animated.webp"
frames[0].save(
    animated,
    format="WEBP",
    save_all=True,
    append_images=frames[1:],
    duration=42,
    loop=0,
    lossless=True,
    method=6,
)
frames[-1].save(ROOT / "tino_listo_static.png", format="PNG", optimize=True)
print(animated)
