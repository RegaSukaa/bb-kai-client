#!/usr/bin/env python3
"""Превью иконок: legacy, round, adaptive в круглой и squircle-маске."""
import os
from PIL import Image, ImageDraw, ImageChops

ART = os.path.dirname(os.path.abspath(__file__))
RES = os.path.abspath(os.path.join(ART, "..", "android", "app", "src", "main", "res"))
BG = (238, 238, 238, 255)
DARK = (32, 34, 38, 255)


def load(*p):
    return Image.open(os.path.join(RES, *p)).convert("RGBA")


def masked(img, kind, size=216):
    out = Image.new("RGBA", (size, size), DARK)
    m = Image.new("L", (size, size), 0)
    d = ImageDraw.Draw(m)
    if kind == "circle":
        d.ellipse([0, 0, size - 1, size - 1], fill=255)
    else:
        d.rounded_rectangle([0, 0, size - 1, size - 1], radius=int(size * 0.28), fill=255)
    fg = img.resize((size, size), Image.LANCZOS)
    # ВАЖНО: paste(mask) заменяет альфу, а не умножает — композитим через alpha_composite
    fg.putalpha(ImageChops.multiply(fg.getchannel("A"), m))
    out.alpha_composite(fg)
    return out


def main():
    legacy = load("mipmap-xxxhdpi", "ic_launcher.png")
    roundi = load("mipmap-xxxhdpi", "ic_launcher_round.png")
    fg = load("mipmap-xxxhdpi", "ic_launcher_foreground.png")
    logo = load("drawable-nodpi", "logo_art.png")

    prev = Image.new("RGBA", (1180, 300), BG)
    items = [(roundi, "ic_launcher_round"), (masked(fg, "circle"), "adaptive circle"),
             (masked(fg, "squircle"), "adaptive squircle"), (legacy, "legacy"),
             (logo.resize((200, 200), Image.LANCZOS), "logo_art")]
    x = 30
    for img, _ in items:
        prev.alpha_composite(img.resize((216, 216), Image.LANCZOS), (x, 42))
        x += 230
    prev.save(os.path.join(ART, "preview.png"))

    # иконка в реальном размере на экране телефона (48dp ≈ 132 px на xxhdpi)
    real = Image.new("RGBA", (360, 130), BG)
    real.alpha_composite(legacy.resize((132, 132), Image.LANCZOS), (14, 0))
    real.alpha_composite(masked(fg, "circle").resize((96, 96), Image.LANCZOS), (160, 18))
    real.save(os.path.join(ART, "preview_small.png"))
    print("ok")


if __name__ == "__main__":
    main()
