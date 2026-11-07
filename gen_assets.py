# -*- coding: utf-8 -*-
"""生成 App 图标与电视 banner（纯 PIL，无外部依赖）"""
import os
from PIL import Image, ImageDraw, ImageFont

ROOT = os.path.dirname(os.path.abspath(__file__))
FONT_CANDIDATES = [
    r"C:\Windows\Fonts\msyhbd.ttc",
    r"C:\Windows\Fonts\msyh.ttc",
    r"C:\Windows\Fonts\simhei.ttf",
    "/usr/share/fonts/opentype/noto/NotoSansCJK-Bold.ttc",
]


def get_font(size):
    for p in FONT_CANDIDATES:
        if os.path.exists(p):
            try:
                return ImageFont.truetype(p, size)
            except Exception:
                continue
    return ImageFont.load_default()


def gradient(size, c1, c2):
    w, h = size
    base = Image.new("RGB", (w, h), c1)
    top = Image.new("RGB", (w, h), c2)
    mask = Image.new("L", (w, h))
    md = mask.load()
    for y in range(h):
        v = int(255 * y / max(1, h - 1))
        for x in range(w):
            md[x, y] = v
    base.paste(top, (0, 0), mask)
    return base


def rounded(img, radius):
    mask = Image.new("L", img.size, 0)
    ImageDraw.Draw(mask).rounded_rectangle([0, 0, img.size[0] - 1, img.size[1] - 1],
                                           radius=radius, fill=255)
    out = Image.new("RGBA", img.size, (0, 0, 0, 0))
    out.paste(img, (0, 0), mask)
    return out


def make_icon(size):
    S = size * 4
    img = gradient((S, S), (255, 165, 58), (255, 74, 44))
    img = rounded(img, int(S * 0.24))
    d = ImageDraw.Draw(img)
    f = get_font(int(S * 0.62))
    text = "题"
    box = d.textbbox((0, 0), text, font=f)
    tw, th = box[2] - box[0], box[3] - box[1]
    d.text(((S - tw) / 2 - box[0], (S - th) / 2 - box[1]), text, font=f, fill=(255, 255, 255))
    img = img.resize((size, size), Image.LANCZOS)
    return img


def make_banner(w=320, h=180):
    W, H = w * 3, h * 3
    bg = gradient((W, H), (255, 244, 226), (255, 213, 232))
    bg = rounded(bg, int(H * 0.16))
    d = ImageDraw.Draw(bg)
    # 左侧小图标
    pad = int(H * 0.14)
    box_s = int(H * 0.5)
    icon = make_icon(box_s)
    bg.paste(icon, (pad, (H - box_s) // 2), icon)
    # 文字
    f1 = get_font(int(H * 0.26))
    f2 = get_font(int(H * 0.115))
    tx = pad * 2 + box_s
    d.text((tx, int(H * 0.30)), "题了个题", font=f1, fill=(60, 40, 50))
    d.text((tx, int(H * 0.60)), "4 岁启蒙 · 看图答题", font=f2, fill=(140, 110, 120))
    return bg.resize((w, h), Image.LANCZOS)


def main():
    for dpi, px in [("mdpi", 48), ("hdpi", 72), ("xhdpi", 96), ("xxhdpi", 144), ("xxxhdpi", 192)]:
        d = os.path.join(ROOT, "res", "mipmap-" + dpi)
        os.makedirs(d, exist_ok=True)
        make_icon(px).save(os.path.join(d, "ic_launcher.png"))
    d = os.path.join(ROOT, "res", "drawable-xhdpi")
    os.makedirs(d, exist_ok=True)
    make_banner().save(os.path.join(d, "tv_banner.png"))
    print("icons + banner done")


if __name__ == "__main__":
    main()
