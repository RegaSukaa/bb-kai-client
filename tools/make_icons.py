#!/usr/bin/env python3
"""Генерация иконок приложения «Blackboard КАИ» из векторной отрисовки логотипа.

Логотип воспроизводится кодом (плитка-«доска» в золотой рамке с белыми «Bb»),
чтобы не тянуть с сайта картинку 60x58 — она мылит на иконке.
"""
import os
import sys
from PIL import Image, ImageDraw, ImageFont

ART = os.path.dirname(os.path.abspath(__file__))
RES = os.path.abspath(os.path.join(ART, "..", "android", "app", "src", "main", "res"))

GOLD = (242, 179, 46, 255)
GOLD_HI = (255, 213, 122, 255)
GOLD_LO = (176, 118, 20, 255)
BOARD = (30, 30, 33, 255)
BOARD_HI = (44, 44, 49, 255)
BG_DARK = (32, 34, 38, 255)
WHITE = (250, 250, 248, 255)

FONT = "C:/Windows/Fonts/georgiab.ttf"


def gold_gradient(size):
    """Вертикальный градиент «под металл» для рамки."""
    w, h = size
    g = Image.new("RGBA", size)
    d = ImageDraw.Draw(g)
    for y in range(h):
        t = y / max(1, h - 1)
        if t < 0.35:
            k = t / 0.35
            c = tuple(int(GOLD[i] + (GOLD_HI[i] - GOLD[i]) * k) for i in range(4))
        else:
            k = (t - 0.35) / 0.65
            c = tuple(int(GOLD_HI[i] + (GOLD_LO[i] - GOLD_HI[i]) * k) for i in range(4))
        d.line([(0, y), (w, y)], fill=c)
    return g


def make_plaque(width, height, with_shadow=True):
    """Плитка «доска»: золотая рамка + тёмное поле + белые Bb. Возвращает RGBA-слой."""
    pad = int(width * 0.10)
    layer = Image.new("RGBA", (width + pad * 2, height + pad * 2), (0, 0, 0, 0))
    d = ImageDraw.Draw(layer)
    r = int(width * 0.11)

    if with_shadow:
        sh = Image.new("RGBA", layer.size, (0, 0, 0, 0))
        ImageDraw.Draw(sh).rounded_rectangle(
            [pad, pad + int(width * 0.03), pad + width, pad + height + int(width * 0.03)],
            radius=r, fill=(0, 0, 0, 90))
        sh = sh.filter(__import__("PIL.ImageFilter", fromlist=["GaussianBlur"]).GaussianBlur(width * 0.03))
        layer.alpha_composite(sh)

    # золотая рамка
    grad = gold_gradient((width, height)).convert("RGBA")
    mask = Image.new("L", (width, height), 0)
    ImageDraw.Draw(mask).rounded_rectangle([0, 0, width - 1, height - 1], radius=r, fill=255)
    layer.paste(grad, (pad, pad), mask)

    # тёмное поле
    inset = int(width * 0.055)
    d.rounded_rectangle(
        [pad + inset, pad + inset, pad + width - inset, pad + height - inset],
        radius=int(r * 0.72), fill=BOARD)
    # лёгкий блик сверху поля
    sheen = Image.new("RGBA", (width, height), (0, 0, 0, 0))
    ImageDraw.Draw(sheen).rounded_rectangle(
        [pad + inset, pad + inset, pad + width - inset, pad + height - inset],
        radius=int(r * 0.72), fill=(255, 255, 255, 12))
    layer.alpha_composite(sheen, (0, 0))

    # буквы Bb
    fs = int(height * 0.66)
    font = ImageFont.truetype(FONT, fs)
    text = "Bb"
    bbox = d.textbbox((0, 0), text, font=font)
    tw, th = bbox[2] - bbox[0], bbox[3] - bbox[1]
    tx = pad + (width - tw) / 2 - bbox[0]
    ty = pad + (height - th) / 2 - bbox[1] - int(height * 0.01)
    d.text((tx + int(width * 0.012), ty + int(width * 0.012)), text, font=font, fill=(0, 0, 0, 130))
    d.text((tx, ty), text, font=font, fill=WHITE)

    # короткие штрихи по бокам от букв
    dy = pad + height // 2
    for sx in (pad + int(width * 0.135), pad + width - int(width * 0.175)):
        d.rounded_rectangle([sx, dy - int(height * 0.012), sx + int(width * 0.04), dy + int(height * 0.012)],
                            radius=int(height * 0.012), fill=(200, 200, 200, 150))
    return layer


def rotated(img, angle):
    return img.rotate(angle, expand=True, resample=Image.BICUBIC)


def compose(canvas, art_frac, bg=None, angle=-7.0, radius_frac=None):
    """Кладёт наклонённую плитку на канву. art_frac — ширина плитки относительно канвы."""
    n = canvas
    plaque = make_plaque(int(n * art_frac), int(n * art_frac * 0.84))
    plaque = rotated(plaque, angle)
    out = Image.new("RGBA", (n, n), (0, 0, 0, 0))
    if bg is not None:
        d = ImageDraw.Draw(out)
        if radius_frac:
            d.rounded_rectangle([0, 0, n - 1, n - 1], radius=int(n * radius_frac), fill=bg)
        else:
            d.rectangle([0, 0, n - 1, n - 1], fill=bg)
    out.alpha_composite(plaque, ((n - plaque.width) // 2, (n - plaque.height) // 2))
    return out


def save(img, path, size):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    img.resize((size, size), Image.LANCZOS).save(path, optimize=True)


def main():
    dens = {"mdpi": 1, "hdpi": 1.5, "xhdpi": 2, "xxhdpi": 3, "xxxhdpi": 4}

    # adaptive foreground: 108dp, арт не больше 60% канвы
    fg = compose(432, 0.60)
    for d, k in dens.items():
        save(fg, os.path.join(RES, f"mipmap-{d}", "ic_launcher_foreground.png"), int(108 * k))

    # legacy (квадратная с фоном) + круглая
    legacy = compose(512, 0.66, bg=BG_DARK, radius_frac=0.22)
    roundi = compose(512, 0.60, bg=BG_DARK)
    for d, k in dens.items():
        save(legacy, os.path.join(RES, f"mipmap-{d}", "ic_launcher.png"), int(48 * k))
        save(roundi, os.path.join(RES, f"mipmap-{d}", "ic_launcher_round.png"), int(48 * k))

    # логотип без подложки для UI (шапка, экран приветствия, «О приложении»)
    logo = compose(512, 0.72, angle=-7.0)
    save(logo, os.path.join(RES, "drawable-nodpi", "logo_art.png"), 512)

    # превью для проверки глазами
    prev = Image.new("RGBA", (1080, 400), (245, 245, 245, 255))
    for i, (img, lbl) in enumerate([(legacy, "legacy"), (roundi, "round"), (fg, "adaptive fg")]):
        prev.alpha_composite(img.resize((220, 220), Image.LANCZOS) if img.width != 512 else
                             img.resize((220, 220), Image.LANCZOS), (40 + i * 340, 60))
    # круглая маска поверх adaptive
    mask = Image.new("L", (216, 216), 0)
    ImageDraw.Draw(mask).ellipse([0, 0, 215, 215], fill=255)
    circ = Image.new("RGBA", (216, 216), BG_DARK)
    circ.paste(fg.resize((216, 216), Image.LANCZOS), (0, 0), mask)
    prev.alpha_composite(circ, (40 + 2 * 340 + 60, 60))
    prev.save(os.path.join(ART, "preview.png"))

    # размер приложения-иконки в «натуральную величину» 48dp на телефоне
    print("ok")


if __name__ == "__main__":
    sys.exit(main())
