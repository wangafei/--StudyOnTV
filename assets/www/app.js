/* 题了个题 · StudyOnTV —— 交互逻辑（遥控器 / 键盘 / 鼠标均可操作） */
(function () {
  "use strict";

  var BANK = (window.QUESTION_BANK || []).slice();
  var ROUND = 10;
  var LS_KEY = "studyon.tv.stars.v1";

  var ICON = {
    "颜色": "🎨", "形状": "🔷", "数数": "🔢", "动物": "🐶",
    "果蔬": "🍎", "比一比": "⚖️", "生活常识": "🏠", "动脑筋": "💡"
  };

  function $(id) { return document.getElementById(id); }

  /* ---------------- 内置图形 ----------------
     电视系统没有 emoji 字体，所有图形都换成随包提供的 PNG */
  var EMOJI_KEYS = Object.keys(window.EMOJI_MAP || {}).sort(function (a, b) {
    return b.length - a.length;
  });

  function emoSrc(file) {
    var inline = window.EMOJI_INLINE;
    if (inline && inline[file]) { return inline[file]; }
    return "emoji/" + file;
  }

  function emoHtml(text) {
    var s = String(text == null ? "" : text);
    var out = "", i = 0;
    while (i < s.length) {
      var hit = null;
      for (var k = 0; k < EMOJI_KEYS.length; k++) {
        var key = EMOJI_KEYS[k];
        if (s.substr(i, key.length) === key) { hit = key; break; }
      }
      if (hit) {
        out += '<img class="emo" alt="" src="' + emoSrc(window.EMOJI_MAP[hit]) + '">';
        i += hit.length;
      } else {
        var ch = s.charAt(i);
        out += ch === "&" ? "&amp;" : (ch === "<" ? "&lt;" : (ch === ">" ? "&gt;" : ch));
        i++;
      }
    }
    return out;
  }

  function initStaticEmoji() {
    var list = document.querySelectorAll("[data-emo]");
    for (var i = 0; i < list.length; i++) {
      list[i].innerHTML = emoHtml(list[i].getAttribute("data-emo"));
    }
  }

  var ui = {
    screens: { home: $("home"), quiz: $("quiz"), result: $("result") },
    totalStars: $("totalStars"),
    cats: $("cats"),
    startBtn: $("startBtn"),
    qCat: $("qCat"), qDots: $("qDots"), qCount: $("qCount"),
    qTitle: $("qTitle"), qBig: $("qBig"), options: $("options"),
    toast: $("toast"),
    rEmoji: $("rEmoji"), rTitle: $("rTitle"), rStars: $("rStars"), rSub: $("rSub"),
    againBtn: $("againBtn"), homeBtn: $("homeBtn"),
    guardStatus: $("guardStatus"), guardToggleBtn: $("guardToggleBtn"),
    guardTestBtn: $("guardTestBtn"), quizHint: $("quizHint"),
    parentPanel: $("parentPanel")
  };

  var state = {
    screen: "home",
    cat: "全部",
    queue: [],
    idx: 0,
    sel: 0,
    correct: 0,
    total: 0,
    locked: false,
    options: [],
    focus: [],
    focusIdx: 0,
    stars: 0,
    guard: false,
    guardStarted: false,
    parentOpen: false,
    lastGuardIndex: -1
  };

  /* ---------------- 声音 ---------------- */
  var actx = null;

  function audioCtx() {
    if (actx) {
      if (actx.state === "suspended") { try { actx.resume(); } catch (e) {} }
      return actx;
    }
    var C = window.AudioContext || window.webkitAudioContext;
    if (!C) { return null; }
    try { actx = new C(); } catch (e) { actx = null; }
    return actx;
  }

  function beep(freq, delay, dur, type, vol) {
    var ac = audioCtx();
    if (!ac) { return; }
    try {
      var t0 = ac.currentTime + delay;
      var osc = ac.createOscillator();
      var gain = ac.createGain();
      osc.type = type || "sine";
      osc.frequency.value = freq;
      gain.gain.setValueAtTime(0, t0);
      gain.gain.linearRampToValueAtTime(vol || 0.1, t0 + 0.015);
      gain.gain.exponentialRampToValueAtTime(0.0001, t0 + dur);
      osc.connect(gain);
      gain.connect(ac.destination);
      osc.start(t0);
      osc.stop(t0 + dur + 0.03);
    } catch (e) {}
  }

  function sfxMove() { beep(720, 0, 0.07, "sine", 0.07); }
  function sfxOk() {
    beep(523.25, 0.00, 0.16, "triangle", 0.13);
    beep(659.25, 0.10, 0.16, "triangle", 0.13);
    beep(783.99, 0.20, 0.16, "triangle", 0.13);
    beep(1046.50, 0.30, 0.30, "triangle", 0.12);
  }
  function sfxNo() {
    beep(233.08, 0.00, 0.18, "sawtooth", 0.08);
    beep(196.00, 0.16, 0.24, "sawtooth", 0.07);
  }
  function sfxStar() {
    beep(880, 0, 0.12, "sine", 0.10);
    beep(1174.66, 0.09, 0.22, "sine", 0.10);
  }

  /* ---------------- 语音 ----------------
   * 优先播放打包进 APK 的离线中文 MP3（小米电视 4X 上不需要装任何语音引擎），
   * 找不到音频时才退回系统 TTS / 浏览器语音。
   */
  var lastVoice = { key: "", text: "" };
  var audioCache = {};
  var lastLabelAt = 0;

  function ttsSay(text) {
    if (!text) { return; }
    try {
      if (window.AndroidHost && typeof window.AndroidHost.speak === "function") {
        window.AndroidHost.speak(text);
        return;
      }
    } catch (e) {}
    try {
      if (window.speechSynthesis) {
        window.speechSynthesis.cancel();
        var u = new SpeechSynthesisUtterance(text);
        u.lang = "zh-CN";
        u.rate = 0.95;
        u.pitch = 1.15;
        window.speechSynthesis.speak(u);
      }
    } catch (e) {}
  }

  function hasOffline(key) {
    var list = window.VOICE_OFFLINE;
    return !!(key && list && list.length && list.indexOf(key) >= 0);
  }

  function voiceText(key, fallback) {
    var m = window.VOICE_SPOKEN;
    if (m && key && m[key]) { return m[key]; }
    return plain(fallback);
  }

  function speak(text, key) {
    text = text || "";
    key = key || "";
    lastVoice = { key: key, text: text };
    if (hasOffline(key)) {
      try {
        if (window.AndroidHost && typeof window.AndroidHost.playVoice === "function") {
          window.AndroidHost.playVoice(key);   // 电视：原生 MediaPlayer
          return;
        }
      } catch (e) {}
      var data = window.__VOICE_B64__ && window.__VOICE_B64__[key];
      if (data) {                              // 电脑：内联音频
        try {
          if (!audioCache[key]) {
            audioCache[key] = new Audio("data:audio/mpeg;base64," + data);
          }
          var a = audioCache[key];
          a.currentTime = 0;
          var p = a.play();
          if (p && p.catch) { p.catch(function () {}); }
          return;
        } catch (e) {}
      }
    }
    ttsSay(text);
  }

  /* 原生播放失败时（音频缺失/解码失败）用 TTS 补上 */
  window.__voiceFailed = function (key) {
    if (lastVoice.key === key) { ttsSay(lastVoice.text); }
  };

  function repeatQuestion() {
    var q = state.queue[state.idx];
    if (!q || state.locked) { return; }
    sfxMove();
    speak(voiceText(q.id, q.q), q.id);
  }

  function speakSelectedOption() {
    var now = Date.now();
    if (now - lastLabelAt < 320) { return; }   // 连按太快时不叠音
    lastLabelAt = now;
    var o = state.options[state.sel];
    if (!o) { return; }
    var key = (window.VOICE_LABELS || {})[o.label] || "";
    speak(voiceText(key, o.label), key);
  }

  function plain(text) {
    return String(text || "")
      .replace(/[\u{1F000}-\u{1FAFF}]/gu, "")
      .replace(/[\u{2190}-\u{2BFF}\u{FE0F}\u{200D}]/gu, "")
      .replace(/\s+/g, " ")
      .trim();
  }

  /* ---------------- 工具 ---------------- */
  function shuffle(arr) {
    for (var i = arr.length - 1; i > 0; i--) {
      var j = Math.floor(Math.random() * (i + 1));
      var t = arr[i]; arr[i] = arr[j]; arr[j] = t;
    }
    return arr;
  }

  function clearFocus() {
    for (var i = 0; i < state.focus.length; i++) {
      if (state.focus[i]) { state.focus[i].classList.remove("focused"); }
    }
  }

  function setFocus(idx) {
    if (!state.focus.length) { return; }
    clearFocus();
    state.focusIdx = (idx + state.focus.length) % state.focus.length;
    var node = state.focus[state.focusIdx];
    if (node) { node.classList.add("focused"); }
  }

  function showScreen(name) {
    for (var k in ui.screens) {
      if (ui.screens[k]) { ui.screens[k].classList.toggle("is-active", k === name); }
    }
    state.screen = name;
  }

  function toast(text, ok) {
    ui.toast.innerHTML = emoHtml(text);
    ui.toast.style.color = ok === false ? "#ef4444" : "#ff5a36";
    ui.toast.classList.add("show");
    clearTimeout(ui.toast.__t);
    ui.toast.__t = setTimeout(function () {
      ui.toast.classList.remove("show");
    }, 900);
  }

  /* ---------------- 星星存档 ---------------- */
  function loadStars() {
    var v = 0;
    try { v = parseInt(window.localStorage.getItem(LS_KEY) || "0", 10); } catch (e) {}
    if (isNaN(v) || v < 0) { v = 0; }
    state.stars = v;
    ui.totalStars.textContent = v;
  }

  function addStars(n) {
    state.stars += n;
    try { window.localStorage.setItem(LS_KEY, String(state.stars)); } catch (e) {}
    ui.totalStars.textContent = state.stars;
  }

  /* ---------------- 首页 ---------------- */
  function buildHome() {
    var sub = document.getElementById("subline");
    if (sub) { sub.textContent = "StudyOnTV · 4 岁启蒙 · " + BANK.length + " 题"; }
    var cats = [];
    BANK.forEach(function (q) {
      if (cats.indexOf(q.c) < 0) { cats.push(q.c); }
    });

    ui.cats.innerHTML = "";
    cats.forEach(function (c) {
      var b = document.createElement("button");
      b.className = "cat-chip focusable";
      b.__cat = c;

      var ico = document.createElement("span");
      ico.className = "ico";
      ico.innerHTML = emoHtml(ICON[c] || "🎯");

      var name = document.createElement("span");
      name.textContent = c;

      b.appendChild(ico);
      b.appendChild(name);
      b.addEventListener("click", function () { startRound(c); });
      ui.cats.appendChild(b);
    });

    state.focus = [ui.startBtn, ui.guardToggleBtn, ui.guardTestBtn]
      .concat(Array.prototype.slice.call(ui.cats.children));
    setFocus(0);
  }

  /* ---------------- 开始一轮 ---------------- */
  function startRound(cat) {
    audioCtx();
    state.cat = cat || "全部";
    var pool = state.cat === "全部"
      ? BANK.slice()
      : BANK.filter(function (q) { return q.c === state.cat; });
    if (!pool.length) { pool = BANK.slice(); }

    shuffle(pool);
    state.queue = pool.slice(0, Math.min(ROUND, pool.length));
    state.total = state.queue.length;
    state.idx = 0;
    state.correct = 0;
    state.locked = false;

    showScreen("quiz");
    renderQuestion();
  }

  function renderDots() {
    ui.qDots.innerHTML = "";
    for (var i = 0; i < state.total; i++) {
      var d = document.createElement("span");
      d.className = "dot" + (i < state.idx ? " done" : (i === state.idx ? " now" : ""));
      ui.qDots.appendChild(d);
    }
  }

  function glyphLen(txt) {
    return Array.from(String(txt).replace(/[\uFE0F\u200D]/g, "")).length;
  }

  function renderQuestion() {
    var q = state.queue[state.idx];
    if (!q) { showResult(); return; }

    state.locked = false;
    state.sel = 0;
    state.options = [];

    ui.qCat.textContent = q.c;
    ui.qCount.textContent = (state.idx + 1) + " / " + state.total;
    renderDots();

    ui.qTitle.innerHTML = emoHtml(q.q);
    ui.qBig.innerHTML = emoHtml(q.big || "");
    ui.options.innerHTML = "";

    var order = [];
    for (var i = 0; i < q.o.length; i++) { order.push(i); }
    shuffle(order);

    order.forEach(function (origIdx, pos) {
      var item = q.o[origIdx];
      var text = String(item[0]);
      var isNum = /^\d+$/.test(text);
      var len = glyphLen(text);

      var node = document.createElement("div");
      node.className = "opt";

      var glyph = document.createElement("div");
      glyph.className = "glyph" + (isNum ? " num" : (len > 1 ? " multi" : ""));
      glyph.innerHTML = emoHtml(text);
      node.appendChild(glyph);

      if (!isNum && item[1]) {
        var lab = document.createElement("div");
        lab.className = "label";
        lab.textContent = item[1];
        node.appendChild(lab);
      }

      node.addEventListener("click", function () { answer(pos); });
      ui.options.appendChild(node);

      state.options.push({ node: node, ok: origIdx === q.a, label: item[1] || text });
    });

    updateSel();
    if (state.guard) {
      ui.qCount.textContent = "答对解锁";
      ui.qDots.innerHTML = "";
    }
    speak(voiceText(q.id, q.q), q.id);
  }

  function updateSel() {
    state.options.forEach(function (o, i) {
      o.node.classList.toggle("sel", i === state.sel);
    });
  }

  function moveSel(step) {
    if (!state.options.length) { return; }
    state.sel = (state.sel + step + state.options.length) % state.options.length;
    updateSel();
    sfxMove();
    speakSelectedOption();
  }

  function answer(pos) {
    if (state.locked) { return; }
    var chosen = state.options[pos];
    if (!chosen) { return; }
    var q = state.queue[state.idx];
    state.locked = true;

    if (chosen.ok) {
      chosen.node.classList.add("correct");
      state.correct++;
      sfxOk();
      toast("⭐ 真棒！", true);
      speak(voiceText("ok", "答对啦，你真棒！"), "ok");
      if (state.guard) {
        setTimeout(guardPassed, 1500);
      } else {
        setTimeout(nextQuestion, 1250);
      }
    } else {
      chosen.node.classList.add("wrong");
      var right = null;
      for (var i = 0; i < state.options.length; i++) {
        if (state.options[i].ok) { right = state.options[i]; }
      }
      if (right) {
        setTimeout(function () { right.node.classList.add("correct"); }, 240);
      }
      sfxNo();
      toast("再想想～", false);
      var aKey = q.id + "a";
      speak(voiceText(aKey, "没关系，再想一想。正确答案是" + (right ? right.label : "")), aKey);
      if (state.guard) {
        setTimeout(startGuardQuestion, 2700);   // 答错不放行，换一题再来
      } else {
        setTimeout(nextQuestion, 2700);
      }
    }
  }

  function nextQuestion() {
    state.idx++;
    if (state.idx >= state.total) {
      showResult();
    } else {
      renderQuestion();
    }
  }

  /* ---------------- 结算 ---------------- */
  function showResult() {
    var c = state.correct;
    var t = state.total || ROUND;
    var ratio = t ? c / t : 0;

    ui.rStars.innerHTML = emoHtml("⭐ " + c + " / " + t);
    if (ratio >= 0.9) {
      ui.rEmoji.innerHTML = emoHtml("🏆");
      ui.rTitle.textContent = "太厉害啦！";
      ui.rSub.innerHTML = emoHtml("几乎全部答对，给你一朵大红花 🌺");
    } else if (ratio >= 0.6) {
      ui.rEmoji.innerHTML = emoHtml("🎉");
      ui.rTitle.textContent = "真不错！";
      ui.rSub.innerHTML = emoHtml("大部分都答对啦，再玩一轮会更棒 💪");
    } else {
      ui.rEmoji.innerHTML = emoHtml("💪");
      ui.rTitle.textContent = "我们再来一次！";
      ui.rSub.innerHTML = emoHtml("答错也没关系，慢慢就记住啦 😊");
    }

    addStars(c);
    sfxStar();
    var rKey = "r" + c;
    speak(voiceText(rKey, "这一轮你答对了" + c + "题"), rKey);

    state.focus = [ui.againBtn, ui.homeBtn];
    showScreen("result");
    setFocus(0);
  }

  function goHome() {
    state.focus = [ui.startBtn, ui.guardToggleBtn, ui.guardTestBtn]
      .concat(Array.prototype.slice.call(ui.cats.children));
    showScreen("home");
    setFocus(0);
    refreshGuardStatus();   // 家长刚选过「暂停 / 关闭」时，首页状态要跟着变
  }

  function exitApp() {
    try {
      if (window.AndroidHost && typeof window.AndroidHost.exit === "function") {
        window.AndroidHost.exit();
      }
    } catch (e) {}
  }

  /* ---------------- 看电视守护 ----------------
   * 电视端由 GuardService 计时，到点启动 GuardActivity（本页 #guard）；
   * 这里只负责「随机一题 + 答对放行 + 家长面板」。电脑版没有原生守护，
   * 但「试一次」可以直接预览守护界面。
   */
  function host() { return window.AndroidHost; }

  function hasHost(fn) {
    try { return !!(host() && typeof host()[fn] === "function"); } catch (e) { return false; }
  }

  function quitGuardPreview() {
    state.guard = false;
    state.guardStarted = false;
    document.getElementById("quiz").classList.remove("guard-on");
  }

  function refreshGuardStatus() {
    var txt = "电脑版不生效 · 装到电视上才会弹题";
    if (hasHost("guardStatus")) {
      try { txt = host().guardStatus(); } catch (e) {}
    }
    ui.guardStatus.textContent = txt;
    ui.guardToggleBtn.textContent = hasHost("guardStatus")
      ? (txt.indexOf("已关闭") >= 0 ? "开启守护" : "关闭守护")
      : "仅电视可用";
  }

  function doGuardToggle() {
    if (hasHost("guardToggle")) {
      try {
        var txt = host().guardToggle();
        ui.guardStatus.textContent = txt;
        ui.guardToggleBtn.textContent = txt.indexOf("已关闭") >= 0 ? "开启守护" : "关闭守护";
        toast("⭐ 守护已更新", true);
        return;
      } catch (e) {}
    }
    toast("电脑版没有守护哦", false);
  }

  function doGuardTest() {
    if (hasHost("guardTest")) {
      try { host().guardTest(); return; } catch (e) {}
    }
    window.__tvGuardStart();     // 电脑版预览
  }

  function startGuardQuestion() {
    audioCtx();
    var pick = Math.floor(Math.random() * BANK.length);
    if (BANK.length > 1 && pick === state.lastGuardIndex) {
      pick = (pick + 1) % BANK.length;
    }
    state.lastGuardIndex = pick;
    state.queue = [BANK[pick]];
    state.total = 1;
    state.idx = 0;
    state.correct = 0;
    state.cat = "动动脑筋";
    state.locked = false;
    showScreen("quiz");
    renderQuestion();
  }

  window.__tvGuardStart = function () {
    if (state.guardStarted) { return; }
    state.guardStarted = true;
    state.guard = true;
    document.getElementById("quiz").classList.add("guard-on");
    ui.quizHint.innerHTML = "← → 选一选 &nbsp;·&nbsp; ↑ 再听一遍 &nbsp;·&nbsp; OK 确定 &nbsp;·&nbsp; 菜单键 / 返回键 → 家长选项";
    sfxStar();
    startGuardQuestion();
  };

  function guardPassed() {
    if (hasHost("guardDone")) {
      try { host().guardDone(); return; } catch (e) {}
    }
    quitGuardPreview();
    goHome();
  }

  function showParentPanel() {
    state.parentOpen = true;
    ui.parentPanel.classList.add("show");
    state.focus = Array.prototype.slice.call(document.querySelectorAll(".parent-btn"));
    setFocus(0);
    sfxMove();
  }

  function hideParentPanel() {
    state.parentOpen = false;
    ui.parentPanel.classList.remove("show");
    clearFocus();
    state.focus = [];
  }

  function parentAction(act) {
    if (hasHost("guardAction")) {
      try { host().guardAction(act); return; } catch (e) {}
    }
    quitGuardPreview();
    hideParentPanel();
    goHome();
  }

  /* ---------------- 按键 ---------------- */
  function activateFocused() {
    var node = state.focus[state.focusIdx];
    if (!node) { return; }
    if (node === ui.startBtn) {
      startRound("全部");
    } else if (node === ui.againBtn) {
      startRound(state.cat);
    } else if (node === ui.homeBtn) {
      goHome();
    } else if (node === ui.guardToggleBtn) {
      doGuardToggle();
    } else if (node === ui.guardTestBtn) {
      doGuardTest();
    } else if (node.__cat) {
      startRound(node.__cat);
    } else {
      node.click();
    }
  }

  function handleKey(k) {
    audioCtx();

    if (state.parentOpen) {
      if (k === "left" || k === "up") { setFocus(state.focusIdx - 1); sfxMove(); }
      else if (k === "right" || k === "down") { setFocus(state.focusIdx + 1); sfxMove(); }
      else if (k === "ok") {
        var pn = state.focus[state.focusIdx];
        if (pn) { parentAction(pn.getAttribute("data-act")); }
      } else if (k === "back" || k === "menu") { hideParentPanel(); }
      return;
    }

    if (k === "menu") { showParentPanel(); return; }

    if (state.screen === "home") {
      if (k === "left" || k === "up") { setFocus(state.focusIdx - 1); sfxMove(); }
      else if (k === "right" || k === "down") { setFocus(state.focusIdx + 1); sfxMove(); }
      else if (k === "ok") { activateFocused(); }
      else if (k === "back") { exitApp(); }
      return;
    }

    if (state.screen === "quiz") {
      if (k === "up") { repeatQuestion(); }
      else if (k === "left") { moveSel(-1); }
      else if (k === "right" || k === "down") { moveSel(1); }
      else if (k === "ok") { answer(state.sel); }
      else if (k === "back") { if (state.guard) { showParentPanel(); } else { goHome(); } }
      return;
    }

    if (state.screen === "result") {
      if (k === "left" || k === "up" || k === "right" || k === "down") {
        setFocus(state.focusIdx + 1);
        sfxMove();
      } else if (k === "ok") {
        activateFocused();
      } else if (k === "back") {
        goHome();
      }
    }
  }

  window.__tvKey = function (code) {
    var map = { 19: "up", 20: "down", 21: "left", 22: "right", 23: "ok", 66: "ok", 4: "back", 82: "menu" };
    var k = map[code];
    if (k) { handleKey(k); }
  };
  window.__tvBack = function () { handleKey("back"); };
  window.__tvStart = startRound;

  document.addEventListener("keydown", function (e) {
    var k = null;
    switch (e.key) {
      case "ArrowLeft": k = "left"; break;
      case "ArrowRight": k = "right"; break;
      case "ArrowUp": k = "up"; break;
      case "ArrowDown": k = "down"; break;
      case "Enter": case " ": case "Spacebar": k = "ok"; break;
      case "Escape": case "Backspace": k = "back"; break;
      case "m": case "M": case "ContextMenu": k = "menu"; break;
      default: return;
    }
    e.preventDefault();
    handleKey(k);
  });

  document.addEventListener("contextmenu", function (e) { e.preventDefault(); });

  /* ---------------- 启动 ---------------- */
  try {
    if (window.AndroidHost && typeof window.AndroidHost.hello === "function") {
      window.AndroidHost.hello();
    }
  } catch (e) {}

  BANK.forEach(function (q, i) {
    q.id = "q" + ("00" + i).slice(-3);
  });

  initStaticEmoji();
  try {
    console.log("[TVFIT] inner=" + window.innerWidth + "x" + window.innerHeight +
      " client=" + document.documentElement.clientWidth + "x" + document.documentElement.clientHeight +
      " dpr=" + window.devicePixelRatio + " screen=" + screen.width + "x" + screen.height +
      " vv=" + (window.visualViewport ? window.visualViewport.width + "x" + window.visualViewport.height : "na"));
  } catch (e) {}
  refreshGuardStatus();
  (function () {
    var bs = Array.prototype.slice.call(document.querySelectorAll(".parent-btn"));
    bs.forEach(function (b) {
      b.addEventListener("click", function () { parentAction(b.getAttribute("data-act")); });
    });
    ui.guardToggleBtn.addEventListener("click", doGuardToggle);
    ui.guardTestBtn.addEventListener("click", doGuardTest);
  })();
  loadStars();
  buildHome();
  showScreen("home");
  if (location.hash.indexOf("guard") >= 0) {
    window.__tvGuardStart();
  } else {
    speak(voiceText("welcome", "欢迎来玩题了个题，按开始答题就可以玩啦！"), "welcome");
  }
})();
