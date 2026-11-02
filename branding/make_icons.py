from PIL import Image
import os

src = r"C:\Users\mark\.cursor\projects\c-Users-mark-OneDrive-rea-de-Trabalho-APK-DZZ-1-1-9-APKPure\assets\aura-app-icon.jpg"
res = r"c:\Users\mark\OneDrive\Área de Trabalho\APK\DZZ_1.1.9_APKPure\DzzCopilot\app\src\main\res"
master = r"c:\Users\mark\OneDrive\Área de Trabalho\APK\DZZ_1.1.9_APKPure\DzzCopilot\branding"

img = Image.open(src).convert("RGBA")
w, h = img.size
side = min(w, h)
left = (w - side) // 2
top = (h - side) // 2
img = img.crop((left, top, left + side, top + side))
bg_color = (47, 59, 42, 255)


def make_fg(size=1024, pad_ratio=0.10):
    canvas = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    pad = int(size * pad_ratio)
    inner = size - 2 * pad
    scaled = img.resize((inner, inner), Image.Resampling.LANCZOS)
    canvas.paste(scaled, (pad, pad), scaled)
    return canvas


fg = make_fg()
os.makedirs(master, exist_ok=True)
fg.save(os.path.join(master, "aura-icon-1024.png"), "PNG")
img.resize((512, 512), Image.Resampling.LANCZOS).save(
    os.path.join(master, "aura-icon-preview.png"), "PNG"
)

drawable = os.path.join(res, "drawable")
os.makedirs(drawable, exist_ok=True)
fg.save(os.path.join(drawable, "ic_launcher_foreground_art.png"), "PNG")

paths = {
    "mipmap-mdpi": 48,
    "mipmap-hdpi": 72,
    "mipmap-xhdpi": 96,
    "mipmap-xxhdpi": 144,
    "mipmap-xxxhdpi": 192,
}
for folder, size in paths.items():
    d = os.path.join(res, folder)
    os.makedirs(d, exist_ok=True)
    legacy = Image.new("RGBA", (size, size), bg_color)
    art = img.resize((size, size), Image.Resampling.LANCZOS)
    legacy.paste(art, (0, 0), art)
    out = legacy.convert("RGB")
    out.save(os.path.join(d, "ic_launcher.png"), "PNG")
    out.save(os.path.join(d, "ic_launcher_round.png"), "PNG")
    print("wrote", folder)
print("done")
