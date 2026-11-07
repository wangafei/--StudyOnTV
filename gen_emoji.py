# -*- coding: utf-8 -*-
"""
把题库/界面里用到的所有 emoji 渲染成透明 PNG，打包进 assets。

背景：小米电视 4X（Android 6.0.1）的 /system/fonts 里没有任何 emoji 字体，
WebView 会把所有 emoji 显示成豆腐块。因此改为随 APK 提供图片。
"""
import json
import os
import re
import subprocess
import sys

from PIL import Image, ImageDraw, ImageFont

ROOT = os.path.dirname(os.path.abspath(__file__))
WWW = os.path.join(ROOT, "assets", "www")
OUT = os.path.join(WWW, "emoji")
BUILD = os.path.join(ROOT, "build")
FONT_PATH = r"C:\Windows\Fonts\seguiemj.ttf"
FONT_SIZE = 109          # Segoe UI Emoji 彩色字形的最佳点位
CANVAS = 220
OUT_SIZE = 136

os.makedirs(OUT, exist_ok=True)
os.makedirs(BUILD, exist_ok=True)


def is_emoji_cp(cp):
    return (0x1F000 <= cp <= 0x1FAFF or
            0x2600 <= cp <= 0x27BF or
            0x2B00 <= cp <= 0x2BFF or
            0x1F1E6 <= cp <= 0x1F1FF or
            cp in (0x00A9, 0x00AE, 0x203C, 0x2049, 0x2122,
                   0x2139, 0x3030, 0x303D, 0x3297, 0x3299))


def clusters(text):
    """按 grapheme 近似规则切出 emoji 序列（含 VS16 / ZWJ / 肤色 / 键帽）"""
    out, i = [], 0
    s = str(text)
    while i < len(s):
        cp = ord(s[i])
        if not is_emoji_cp(cp):
            i += 1
            continue
        j = i + 1
        while j < len(s):
            c2 = ord(s[j])
            if c2 in (0xFE0E, 0xFE0F, 0x20E3) or 0x1F3FB <= c2 <= 0x1F3FF:
                j += 1
                continue
            if c2 == 0x200D and j + 1 < len(s) and is_emoji_cp(ord(s[j + 1])):
                j += 2
                continue
            if (0x1F1E6 <= cp <= 0x1F1FF and
                    0x1F1E6 <= c2 <= 0x1F1FF and j == i + 1):
                j += 1
                continue
            break
        out.append(s[i:j])
        i = j
    return out


def collect():
    """收集所有需要图片的 emoji 序列"""
    # 题库用 node 求值，拿到结构化数据
    node_code = (
        "global.window={};require('./assets/www/questions.js');"
        "var b=global.window.QUESTION_BANK,s=[];"
        "b.forEach(function(q){s.push(q.q||'',q.big||'');"
        "(q.o||[]).forEach(function(o){s.push(o[0],o[1]||'');});});"
        "process.stdout.write(JSON.stringify(s));"
    )
    raw = subprocess.check_output(["node", "-e", node_code], cwd=ROOT)
    for text in json.loads(raw.decode("utf-8")):
        for c in clusters(text):
            yield c

    # 界面源码里的静态 emoji
    for name in ("index.html", "app.js"):
        with open(os.path.join(WWW, name), encoding="utf-8") as f:
            for c in clusters(f.read()):
                yield c


def fname(cluster):
    return "e_" + "_".join("%x" % ord(ch) for ch in cluster) + ".png"


def render(cluster, font):
    canvas = Image.new("RGBA", (CANVAS, CANVAS), (0, 0, 0, 0))
    d = ImageDraw.Draw(canvas)
    try:
        d.text((18, 8), cluster, font=font, embedded_color=True)
    except Exception:
        return None
    box = canvas.getbbox()
    if not box:
        return None
    im = canvas.crop(box)
    # 统计不透明像素，避免把「豆腐块 / 空白」当成成功
    alpha = im.getchannel("A")
    solid = sum(1 for p in alpha.getdata() if p > 24)
    if solid < 120:
        return None
    scale = min((OUT_SIZE - 8) / float(im.width), (OUT_SIZE - 8) / float(im.height))
    nw, nh = max(1, int(im.width * scale)), max(1, int(im.height * scale))
    im = im.resize((nw, nh), Image.LANCZOS)
    target = Image.new("RGBA", (OUT_SIZE, OUT_SIZE), (0, 0, 0, 0))
    target.paste(im, ((OUT_SIZE - nw) // 2, (OUT_SIZE - nh) // 2), im)
    return target


def make_sheet(keys, mapping):
    cols, cell = 12, 150
    rows = (len(keys) + cols - 1) // cols
    sheet = Image.new("RGB", (cols * cell, rows * (cell + 24)), (255, 250, 242))
    draw = ImageDraw.Draw(sheet)
    label_font = ImageFont.truetype(r"C:\Windows\Fonts\consola.ttf", 13)
    for i, k in enumerate(keys):
        x, y = (i % cols) * cell, (i // cols) * (cell + 24)
        im = Image.open(os.path.join(OUT, mapping[k])).convert("RGBA")
        sheet.paste(im, (x + (cell - im.width) // 2, y + 4), im)
        draw.text((x + 6, y + cell + 2), mapping[k][2:-4][:22],
                  fill=(60, 50, 80), font=label_font)
    sheet.save(os.path.join(BUILD, "emoji_sheet.png"))


def main():
    keys = []
    seen = set()
    for c in collect():
        if c not in seen:
            seen.add(c)
            keys.append(c)
    keys.sort(key=lambda c: (-len(c), c))

    font = ImageFont.truetype(FONT_PATH, FONT_SIZE)
    mapping, failed = {}, []
    for old in os.listdir(OUT):
        if old.endswith(".png"):
            os.remove(os.path.join(OUT, old))

    for c in keys:
        im = render(c, font)
        if im is None:
            failed.append(c)
            continue
        name = fname(c)
        im.save(os.path.join(OUT, name), optimize=True)
        mapping[c] = name

    with open(os.path.join(WWW, "emoji_map.js"), "w", encoding="utf-8") as f:
        f.write("/* 自动生成：emoji 序列 -> 内置图片文件 */\n")
        f.write("window.EMOJI_MAP = %s;\n" %
                json.dumps(mapping, ensure_ascii=False,
                           separators=(",", ":"), sort_keys=True))

    total = sum(os.path.getsize(os.path.join(OUT, n)) for n in mapping.values())
    print("emoji clusters: %d, rendered: %d, failed: %d" %
          (len(keys), len(mapping), len(failed)), flush=True)
    print("failed codepoints: %s" %
          [" ".join("%x" % ord(ch) for ch in c) for c in failed], flush=True)
    print("png total: %.1f KB" % (total / 1024.0), flush=True)
    make_sheet([k for k in keys if k in mapping], mapping)
    if failed:
        sys.exit("有 emoji 渲染失败，需要补替代图形")


if __name__ == "__main__":
    main()
