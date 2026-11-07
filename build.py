# -*- coding: utf-8 -*-
"""手工构建 APK：aapt2 -> javac -> d8 -> zip(assets/dex) -> zipalign -> apksigner"""
import base64
import json
import os
import re
import shutil
import subprocess
import sys
import zipfile

ROOT = os.path.dirname(os.path.abspath(__file__))
BUILD = os.path.join(ROOT, "build")
DIST = os.path.join(ROOT, "dist")

SDK = os.path.join(os.environ.get("LOCALAPPDATA", ""), "Android", "Sdk")
BT = os.path.join(SDK, "build-tools", "36.0.0")
PLATFORM = os.path.join(SDK, "platforms", "android-36", "android.jar")
D8_JAR = os.path.join(BT, "lib", "d8.jar")
APKSIGNER = os.path.join(BT, "lib", "apksigner.jar")
KEYSTORE = os.path.join(os.path.expanduser("~"), ".android", "debug.keystore")

APP_NAME = "题了个题-StudyOnTV-1.0"


def run(cmd, cwd=None):
    print("$", " ".join(cmd) if isinstance(cmd, list) else cmd)
    p = subprocess.run(cmd, cwd=cwd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    out = p.stdout.decode("utf-8", "replace")
    if out.strip():
        print(out.strip()[:4000])
    if p.returncode != 0:
        raise SystemExit("FAILED: %s" % cmd)
    return out


def java_sources():
    files = []
    for base, _, names in os.walk(os.path.join(ROOT, "src")):
        for n in names:
            if n.endswith(".java"):
                files.append(os.path.join(base, n))
    gen = os.path.join(BUILD, "gen")
    for base, _, names in os.walk(gen):
        for n in names:
            if n.endswith(".java"):
                files.append(os.path.join(base, n))
    return files


def add_to_apk(src, dst, entries):
    """把 classes.dex 与 assets 追加进 aapt2 产出的 apk。

    小米电视 4X 上原生 MediaPlayer 用 AssetManager.openFd() 直接读 APK 里的 MP3，
    这要求音频文件在包内是「未压缩 / STORED」的，否则 openFd 会抛异常导致没声音。
    """
    with zipfile.ZipFile(src, "r") as zin, zipfile.ZipFile(dst, "w", zipfile.ZIP_DEFLATED) as zout:
        for item in zin.infolist():
            zout.writestr(item, zin.read(item.filename))
        for arc, path in entries:
            info = zipfile.ZipInfo(arc, date_time=(2026, 1, 1, 0, 0, 0))
            # PNG 本身已压缩，STORED 可让老 WebView 直接读包内图片，速度也更快
            info.compress_type = (zipfile.ZIP_STORED if arc.endswith((".mp3", ".png"))
                                  else zipfile.ZIP_DEFLATED)
            info.external_attr = 0o600 << 16
            with open(path, "rb") as f:
                zout.writestr(info, f.read())


def build_apk():
    # 只清理编译中间产物；build/ 里的语音缓存（voice_b64.json 等）要保留，
    # 因为电脑版 HTML 需要内联这些数据。
    os.makedirs(BUILD, exist_ok=True)
    for d in ("gen", "classes", "dex"):
        p = os.path.join(BUILD, d)
        if os.path.isdir(p):
            shutil.rmtree(p)
    for n in ("res.zip", "base.apk", "unsigned.apk", "aligned.apk",
              "sources.txt", "classes.dex"):
        p = os.path.join(BUILD, n)
        if os.path.exists(p):
            os.remove(p)
    os.makedirs(os.path.join(BUILD, "classes"), exist_ok=True)
    os.makedirs(os.path.join(BUILD, "dex"), exist_ok=True)
    os.makedirs(DIST, exist_ok=True)

    res_zip = os.path.join(BUILD, "res.zip")
    run([os.path.join(BT, "aapt2.exe"), "compile", "--dir", os.path.join(ROOT, "res"), "-o", res_zip])

    base_apk = os.path.join(BUILD, "base.apk")
    run([os.path.join(BT, "aapt2.exe"), "link",
         "-o", base_apk,
         "-I", PLATFORM,
         "--manifest", os.path.join(ROOT, "AndroidManifest.xml"),
         "-R", res_zip,
         "--java", os.path.join(BUILD, "gen"),
         "--min-sdk-version", "21",
         "--target-sdk-version", "30",
         "--version-code", "1",
         "--version-name", "1.0",
         "--auto-add-overlay"])

    sources = java_sources()
    argfile = os.path.join(BUILD, "sources.txt")
    with open(argfile, "w", encoding="utf-8") as f:
        f.write("\n".join('"%s"' % s.replace("\\", "/") for s in sources))
    run(["javac", "-source", "8", "-target", "8", "-encoding", "UTF-8", "-nowarn",
         "-bootclasspath", PLATFORM, "-d", os.path.join(BUILD, "classes"), "@" + argfile])

    class_files = []
    for base, _, names in os.walk(os.path.join(BUILD, "classes")):
        for n in names:
            if n.endswith(".class"):
                class_files.append(os.path.join(base, n))
    run(["java", "-cp", D8_JAR, "com.android.tools.r8.D8", "--release", "--min-api", "21",
         "--lib", PLATFORM, "--output", os.path.join(BUILD, "dex")] + class_files)

    entries = [("classes.dex", os.path.join(BUILD, "dex", "classes.dex"))]
    for sub in ("www", "voice"):
        folder = os.path.join(ROOT, "assets", sub)
        if not os.path.isdir(folder):
            continue
        for base, _, names in os.walk(folder):
            for n in names:
                full = os.path.join(base, n)
                rel = os.path.relpath(full, os.path.join(ROOT, "assets")).replace("\\", "/")
                entries.append(("assets/" + rel, full))

    unsigned = os.path.join(BUILD, "unsigned.apk")
    add_to_apk(base_apk, unsigned, entries)

    aligned = os.path.join(BUILD, "aligned.apk")
    run([os.path.join(BT, "zipalign.exe"), "-f", "-p", "4", unsigned, aligned])

    out_apk = os.path.join(DIST, APP_NAME + ".apk")
    if os.path.exists(out_apk):
        os.remove(out_apk)
    run(["java", "-jar", APKSIGNER, "sign",
         "--ks", KEYSTORE, "--ks-pass", "pass:android", "--key-pass", "pass:android",
         "--ks-key-alias", "androiddebugkey", "--min-sdk-version", "21",
         "--out", out_apk, aligned])

    run(["java", "-jar", APKSIGNER, "verify", "--print-certs", out_apk])
    print(run([os.path.join(BT, "aapt2.exe"), "dump", "badging", out_apk]))
    print("APK OK ->", out_apk, "%.2f MB" % (os.path.getsize(out_apk) / 1048576.0))
    return out_apk


def build_pc_html():
    www = os.path.join(ROOT, "assets", "www")
    with open(os.path.join(www, "index.html"), encoding="utf-8") as f:
        html = f.read()
    with open(os.path.join(www, "style.css"), encoding="utf-8") as f:
        css = f.read()
    with open(os.path.join(www, "questions.js"), encoding="utf-8") as f:
        qs = f.read()
    with open(os.path.join(www, "voice_map.js"), encoding="utf-8") as f:
        vm = f.read()
    with open(os.path.join(www, "emoji_map.js"), encoding="utf-8") as f:
        em = f.read()
    with open(os.path.join(www, "app.js"), encoding="utf-8") as f:
        js = f.read()

    # 优先用本地刚生成的缓存；clone 仓库后 build/ 为空，则回退到随仓库提供的 voice_cache/
    b64_path = os.path.join(BUILD, "voice_b64.json")
    if not os.path.exists(b64_path):
        b64_path = os.path.join(ROOT, "voice_cache", "voice_b64.json")
    if not os.path.exists(b64_path):
        raise SystemExit("缺少离线语音缓存，请先执行 python gen_voice.py")
    with open(b64_path, encoding="utf-8") as f:
        b64 = f.read()

    # 图形同样内联成 data URI，保证单文件 HTML 双击即可看图
    emoji_inline = {}
    emoji_dir = os.path.join(www, "emoji")
    for n in sorted(os.listdir(emoji_dir)):
        if n.endswith(".png"):
            with open(os.path.join(emoji_dir, n), "rb") as f:
                emoji_inline[n] = ("data:image/png;base64," +
                                   base64.b64encode(f.read()).decode("ascii"))

    link_tag = '<link rel="stylesheet" href="style.css">'
    qs_tag = '<script src="questions.js"></script>'
    em_tag = '<script src="emoji_map.js"></script>'
    vm_tag = '<script src="voice_map.js"></script>'
    js_tag = '<script src="app.js"></script>'
    for tag in (link_tag, qs_tag, em_tag, vm_tag, js_tag):
        if tag not in html:
            raise SystemExit("index.html 缺少引用: " + tag)
    html = html.replace(link_tag, "<style>\n%s\n</style>" % css)
    html = html.replace(qs_tag, "<script>\n%s\n</script>" % qs)
    html = html.replace(em_tag, "<script>\n%s\nwindow.EMOJI_INLINE = %s;\n</script>" %
                        (em, json.dumps(emoji_inline, separators=(",", ":"))))
    # 电脑上把语音以 base64 内联，单文件双击即可听，不需要额外文件夹
    html = html.replace(vm_tag,
                        "<script>\n%s\nwindow.__VOICE_B64__ = %s;\n</script>" % (vm, b64))
    html = html.replace(js_tag, "<script>\n%s\n</script>" % js)
    html = html.replace("题了个题 · StudyOnTV", "题了个题 · StudyOnTV（电脑版）")

    out = os.path.join(DIST, "题了个题-电脑版.html")
    with open(out, "w", encoding="utf-8") as f:
        f.write(html)
    print("PC HTML OK ->", out)
    return out


if __name__ == "__main__":
    if not os.path.exists(PLATFORM):
        raise SystemExit("找不到 android.jar: " + PLATFORM)
    os.makedirs(DIST, exist_ok=True)
    build_apk()
    build_pc_html()
