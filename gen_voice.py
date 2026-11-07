# -*- coding: utf-8 -*-
"""
生成「离线中文语音」——小米电视 4X（Android 8 / MIUI TV）通常没有中文 TTS 引擎，
所以把每句话预先合成成 MP3 打包进 APK，用原生 MediaPlayer 播放，零依赖。

输出：
  assets/voice/*.mp3              离线音频
  assets/www/voice_map.js         选项名称 -> 音频名 映射
  build/voice_b64.json            电脑版 HTML 内联用的 base64
"""
import asyncio
import base64
import json
import os
import re
import sys

import edge_tts

ROOT = os.path.dirname(os.path.abspath(__file__))
VOICE_DIR = os.path.join(ROOT, "assets", "voice")
WWW = os.path.join(ROOT, "assets", "www")
BUILD = os.path.join(ROOT, "build")

VOICE = "zh-CN-XiaoxiaoNeural"   # 温柔女声
RATE = "-8%"                      # 稍慢一点，小朋友听得清
PITCH = "+10Hz"
CONCURRENCY = 6

EMOJI_WORD = {
    "🔴": "红色球", "🔵": "蓝色球", "💙": "蓝色球", "💚": "绿色球",
    "💛": "黄色球", "💜": "紫色球", "❤️": "红色爱心",
    "⭐": "星星", "🌙": "月亮", "❓": "", "？": "？",
}

# 带图案的规律题：直接给一句自然的口语，避免把符号一个个念出来
SPECIAL = {
    "🔴 💙 🔴 💙 ？ 接下来是哪个？": "红色球，蓝色球，红色球，蓝色球，接下来是哪个？",
    "⭐ 🌙 ⭐ 🌙 ？ 接下来是哪个？": "星星，月亮，星星，月亮，接下来是哪个？",
}

CN_NUM = {"0": "零", "1": "一", "2": "二", "3": "三", "4": "四",
          "5": "五", "6": "六", "7": "七", "8": "八", "9": "九", "10": "十"}


def spoken(text):
    """把题干里的图形符号换成能朗读的中文"""
    s = str(text).strip()
    if s in SPECIAL:
        return SPECIAL[s]
    for k, v in EMOJI_WORD.items():
        s = s.replace(k, v)
    s = re.sub(r"[，,\s]+", "，", s.strip())
    s = s.replace("，？", "？").replace("？，", "？")
    s = s.replace("？，接下来", "，接下来")
    s = re.sub(r"，+", "，", s)
    return s.strip("，")


def cn_label(label):
    """选项读法：纯数字转成中文，避免 TTS 读成英文"""
    s = str(label)
    if s in CN_NUM:
        return CN_NUM[s]
    return s


def main():
    os.makedirs(VOICE_DIR, exist_ok=True)
    os.makedirs(BUILD, exist_ok=True)

    bank = json.load(open(os.path.join(BUILD, "questions.json"), encoding="utf-8"))
    tasks = {}      # key -> text
    labels = []     # 选项读法（去重）

    for i, q in enumerate(bank):
        key = "q%03d" % i
        tasks[key] = spoken(q["q"])

        right = q["o"][q["a"]]
        label = right[1] if len(right) > 1 and right[1] else right[0]
        tasks[key + "a"] = "没关系，再想一想，正确答案是" + cn_label(label) + "。"

        for o in q["o"]:
            lab = o[1] if len(o) > 1 and o[1] else o[0]
            if lab not in labels:
                labels.append(lab)

    label_map = {}
    for i, lab in enumerate(labels):
        key = "o%03d" % i
        label_map[lab] = key
        tasks[key] = cn_label(lab)

    tasks["ok"] = "答对啦，你真棒！"
    tasks["welcome"] = "欢迎来玩题了个题，按开始答题就可以玩啦！"
    for n in range(11):
        if n >= 9:
            tail = "太厉害啦！"
        elif n >= 6:
            tail = "真不错！"
        else:
            tail = "我们再试一次吧！"
        tasks["r%d" % n] = "这一轮你答对了%d题，%s" % (n, tail)

    # 增量：文案没变且音频已存在就跳过，改题库时只补生成变化的部分
    cache_path = os.path.join(BUILD, "voice_texts.json")
    prev = {}
    if os.path.exists(cache_path):
        try:
            prev = json.load(open(cache_path, encoding="utf-8"))
        except Exception:
            prev = {}
    todo = {k: t for k, t in tasks.items()
            if prev.get(k) != t or not os.path.exists(os.path.join(VOICE_DIR, k + ".mp3"))}
    print("共 %d 段，本次需合成 %d 段" % (len(tasks), len(todo)), flush=True)

    sem = asyncio.Semaphore(CONCURRENCY)
    failed = []

    async def one(key, text):
        path = os.path.join(VOICE_DIR, key + ".mp3")
        async with sem:
            for attempt in range(3):
                try:
                    c = edge_tts.Communicate(text, VOICE, rate=RATE, pitch=PITCH)
                    await c.save(path)
                    if os.path.getsize(path) < 800:
                        raise RuntimeError("文件过小")
                    return key, None
                except Exception as e:
                    if attempt == 2:
                        return key, str(e)
                    await asyncio.sleep(1.2 * (attempt + 1))

    async def run_all():
        for key, err in await asyncio.gather(*[one(k, t) for k, t in todo.items()]):
            if err:
                failed.append((key, err))

    if todo:
        asyncio.run(run_all())

    b64 = {}
    for key in tasks:
        path = os.path.join(VOICE_DIR, key + ".mp3")
        if os.path.exists(path):
            with open(path, "rb") as f:
                b64[key] = base64.b64encode(f.read()).decode("ascii")

    # 前端映射：选项读法 -> 音频名；每句音频对应的文字（用于兜底朗读）
    with open(os.path.join(WWW, "voice_map.js"), "w", encoding="utf-8") as f:
        f.write("/* 自动生成，请勿手改：离线语音索引 */\n")
        f.write("window.VOICE_LABELS = %s;\n" %
                json.dumps(label_map, ensure_ascii=False, separators=(",", ":")))
        f.write("window.VOICE_SPOKEN = %s;\n" %
                json.dumps(tasks, ensure_ascii=False, separators=(",", ":")))
        f.write("window.VOICE_OFFLINE = %s;\n" % json.dumps(sorted(b64.keys())))

    # 同时写入随仓库提交的 voice_cache/，保证 clone 后无需联网也能重新构建
    vc_dir = os.path.join(ROOT, "voice_cache")
    os.makedirs(vc_dir, exist_ok=True)
    with open(os.path.join(BUILD, "voice_b64.json"), "w", encoding="utf-8") as f:
        json.dump(b64, f)
    with open(os.path.join(vc_dir, "voice_b64.json"), "w", encoding="utf-8") as f:
        json.dump(b64, f)
    with open(cache_path, "w", encoding="utf-8") as f:
        json.dump(tasks, f, ensure_ascii=False)
    with open(os.path.join(vc_dir, "voice_texts.json"), "w", encoding="utf-8") as f:
        json.dump(tasks, f, ensure_ascii=False)

    total = sum(os.path.getsize(os.path.join(VOICE_DIR, k + ".mp3")) for k in b64)
    print("完成：%d 段，失败 %d，总大小 %.2f MB" %
          (len(b64), len(failed), total / 1048576.0), flush=True)
    if failed:
        print("失败示例：", failed[:5], flush=True)
    print("题干示例：", tasks["q000"], flush=True)


if __name__ == "__main__":
    main()
