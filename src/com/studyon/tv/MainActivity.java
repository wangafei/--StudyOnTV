package com.studyon.tv;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.content.res.AssetFileDescriptor;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.Build;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.util.Locale;

/**
 * 题了个题 · StudyOnTV
 * 电视端容器：本地 WebView + 遥控器按键桥 + 中文语音朗读（给还不会认字的小朋友听题）
 */
public class MainActivity extends Activity {

    private WebView web;
    private TextToSpeech tts;
    private MediaPlayer voicePlayer;
    private volatile boolean ttsReady = false;
    private volatile boolean jsReady = false;
    private volatile boolean guardCompleted = false;

    /** GuardActivity 覆写为 true：同一套界面切成「答对一题才放行」 */
    protected boolean isGuardMode() {
        return false;
    }

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        enterImmersive();

        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowFileAccessFromFileURLs(true);
        s.setAllowUniversalAccessFromFileURLs(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        // 电视 1920x1080 / density 320：固定逻辑视口，避免 WebView 自动缩到屏幕左上角
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(false);
        s.setTextZoom(100);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        }

        web.setBackgroundColor(0xFFFFF7EC);
        web.setVerticalScrollBarEnabled(false);
        web.setHorizontalScrollBarEnabled(false);
        web.setOverScrollMode(View.OVER_SCROLL_NEVER);
        web.setFocusable(true);
        web.setFocusableInTouchMode(true);
        web.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                if (!isGuardMode()) {
                    return;
                }
                try {
                    view.evaluateJavascript(
                            "window.__tvGuardStart && window.__tvGuardStart();", null);
                } catch (Throwable ignored) {
                }
            }
        });
        web.addJavascriptInterface(new Bridge(), "AndroidHost");

        web.setOnKeyListener(new View.OnKeyListener() {
            @Override
            public boolean onKey(View v, int keyCode, KeyEvent event) {
                if (!isTvKey(keyCode)) {
                    return false;
                }
                if (event.getAction() == KeyEvent.ACTION_DOWN) {
                    int code = keyCode == KeyEvent.KEYCODE_ENTER
                            ? KeyEvent.KEYCODE_DPAD_CENTER : keyCode;
                    dispatchToJs(code);
                }
                return true;
            }
        });

        setContentView(web, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        web.requestFocus();

        // #guard 让前端启动时直接进入守护答题；onPageFinished 里再兜一次
        web.loadUrl("file:///android_asset/www/index.html" + (isGuardMode() ? "#guard" : ""));
        startTts();

        if (!isGuardMode() && GuardPrefs.isEnabled(this)) {
            try {
                startService(new Intent(this, GuardService.class));
            } catch (Throwable ignored) {
            }
        }
    }

    private boolean isTvKey(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_BUTTON_A:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
            case KeyEvent.KEYCODE_MENU:
                return true;
            default:
                return false;
        }
    }

    private void dispatchToJs(int keyCode) {
        if (web == null) {
            return;
        }
        int code = keyCode == KeyEvent.KEYCODE_BUTTON_A ? KeyEvent.KEYCODE_DPAD_CENTER : keyCode;
        try {
            web.evaluateJavascript("window.__tvKey && window.__tvKey(" + code + ");", null);
        } catch (Throwable ignored) {
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            // 家长快捷键：随时唤出「跳过 / 暂停 1 小时 / 关闭守护」
            dispatchToJs(KeyEvent.KEYCODE_MENU);
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            // 页面没加载成功时，返回键直接退出，避免卡死在黑屏里
            if (!jsReady) {
                finish();
                return true;
            }
            if (web != null) {
                try {
                    if (isGuardMode()) {
                        // 守护模式下返回键不回首页，直接给家长选项，避免孩子一按就放行
                        web.evaluateJavascript("window.__tvKey && window.__tvKey(82);", null);
                    } else {
                        web.evaluateJavascript("window.__tvKey && window.__tvKey(4);", null);
                    }
                } catch (Throwable ignored) {
                }
            }
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    private void enterImmersive() {
        View decor = getWindow().getDecorView();
        decor.setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            enterImmersive();
        }
    }

    private void startTts() {
        tts = new TextToSpeech(getApplicationContext(), new TextToSpeech.OnInitListener() {
            @Override
            public void onInit(int status) {
                if (status != TextToSpeech.SUCCESS || tts == null) {
                    return;
                }
                int r = tts.setLanguage(Locale.SIMPLIFIED_CHINESE);
                if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
                    tts.setLanguage(Locale.CHINA);
                }
                tts.setSpeechRate(0.95f);
                tts.setPitch(1.12f);
                ttsReady = true;
            }
        });
    }

    /** 释放上一段语音，保证新一句能立刻抢播 */
    private void releaseVoice() {
        if (voicePlayer == null) {
            return;
        }
        try {
            if (voicePlayer.isPlaying()) {
                voicePlayer.stop();
            }
        } catch (Throwable ignored) {
        }
        try {
            voicePlayer.release();
        } catch (Throwable ignored) {
        }
        voicePlayer = null;
    }

    private void attachAndPlay(final MediaPlayer mp) throws Exception {
        mp.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
            @Override
            public void onCompletion(MediaPlayer m) {
                m.release();
                if (voicePlayer == m) {
                    voicePlayer = null;
                }
            }
        });
        mp.prepare();
        mp.start();
        voicePlayer = mp;
    }

    /** 把 assets 里的音频复制一份到缓存目录（openFd 不可用时的兜底） */
    private void copyAssetTo(String name, java.io.File out) throws Exception {
        java.io.InputStream in = null;
        java.io.OutputStream os = null;
        try {
            in = getAssets().open("voice/" + name + ".mp3");
            os = new java.io.FileOutputStream(out, false);
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                os.write(buf, 0, n);
            }
        } finally {
            if (in != null) {
                try { in.close(); } catch (Throwable ignored) {}
            }
            if (os != null) {
                try { os.close(); } catch (Throwable ignored) {}
            }
        }
    }

    /** 判断音频名是否安全（只允许字母数字下划线） */
    private boolean safeName(String name) {
        if (name == null || name.length() == 0 || name.length() > 32) {
            return false;
        }
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (!((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '_')) {
                return false;
            }
        }
        return true;
    }

    private void voiceFailed(String name) {
        if (web == null) {
            return;
        }
        try {
            web.evaluateJavascript(
                    "window.__voiceFailed && window.__voiceFailed('" + name + "');", null);
        } catch (Throwable ignored) {
        }
    }

    /* ---------------- 看电视守护 ---------------- */

    /**
     * 守护答题结束（答对 / 家长跳过 / 暂停 / 关闭）。
     * 直接写设置 + 发广播给服务，双保险：即使服务刚被回收，家长选「关闭守护」也立即生效。
     */
    private void finishGuard(final String action) {
        if (guardCompleted) {
            return;
        }
        guardCompleted = true;
        if ("off".equals(action)) {
            GuardPrefs.setEnabled(this, false);
            GuardPrefs.clearSnooze(this);
        } else if ("snooze".equals(action)) {
            GuardPrefs.snooze(this);
        }
        GuardPrefs.setShowing(this, false);
        try {
            sendBroadcast(new Intent(GuardService.ACTION_DONE)
                    .putExtra(GuardService.EXTRA_ACTION, action));
        } catch (Throwable ignored) {
        }
        // 万一服务不在，这里也把音频焦点还回去，避免视频一直没声音
        try {
            AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
            am.abandonAudioFocus(null);
            am.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY));
            am.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY));
        } catch (Throwable ignored) {
        }
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                finish();
            }
        });
    }

    public class Bridge {
        @JavascriptInterface
        public void hello() {
            jsReady = true;
        }

        /**
         * 播放预置的离线中文语音。
         * 小米电视 4X 是 Android 8 / MIUI TV，很多机器没有中文 TTS 引擎，
         * 所以读题走本地 MP3 + 原生 MediaPlayer，不依赖系统语音服务，
         * 也绕开老版本 WebView 对 HTML5 audio 自动播放的限制。
         */
        @JavascriptInterface
        public void playVoice(final String name) {
            if (!safeName(name)) {
                return;
            }
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    releaseVoice();
                    try {
                        MediaPlayer mp = new MediaPlayer();
                        mp.setAudioStreamType(AudioManager.STREAM_MUSIC);
                        // 常规路径：APK 内 MP3 是未压缩的，直接用 fd 播放，最快
                        AssetFileDescriptor fd = null;
                        try {
                            fd = getAssets().openFd("voice/" + name + ".mp3");
                            mp.setDataSource(fd.getFileDescriptor(),
                                    fd.getStartOffset(), fd.getLength());
                        } finally {
                            if (fd != null) {
                                fd.close();
                            }
                        }
                        attachAndPlay(mp);
                    } catch (Throwable t) {
                        // 兜底：个别电视系统不允许对 assets 用 openFd，
                        // 就先把音频复制到缓存目录再用文件路径播放
                        releaseVoice();
                        try {
                            java.io.File out = new java.io.File(getCacheDir(), "v_" + name + ".mp3");
                            copyAssetTo(name, out);
                            MediaPlayer mp2 = new MediaPlayer();
                            mp2.setAudioStreamType(AudioManager.STREAM_MUSIC);
                            mp2.setDataSource(out.getAbsolutePath());
                            attachAndPlay(mp2);
                        } catch (Throwable t2) {
                            releaseVoice();
                            voiceFailed(name);
                        }
                    }
                }
            });
        }

        @JavascriptInterface
        public void stopVoice() {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    releaseVoice();
                }
            });
        }

        @JavascriptInterface
        public boolean hasVoice(String name) {
            if (!safeName(name)) {
                return false;
            }
            try {
                String[] list = getAssets().list("voice");
                if (list != null) {
                    for (String s : list) {
                        if ((name + ".mp3").equals(s)) {
                            return true;
                        }
                    }
                }
            } catch (Throwable ignored) {
            }
            return false;
        }

        @JavascriptInterface
        public void speak(final String text) {
            if (tts == null || text == null || text.length() == 0) {
                return;
            }
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    try {
                        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "q");
                    } catch (Throwable ignored) {
                    }
                }
            });
        }

        @JavascriptInterface
        public boolean ready() {
            return ttsReady;
        }

        @JavascriptInterface
        public void stopSpeak() {
            try {
                if (tts != null) {
                    tts.stop();
                }
            } catch (Throwable ignored) {
            }
        }

        @JavascriptInterface
        public void exit() {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    finish();
                }
            });
        }

        /** 孩子答对了：关闭守护界面，回到刚才的节目 */
        @JavascriptInterface
        public void guardDone() {
            finishGuard("done");
        }

        /** 家长面板：skip / snooze / off */
        @JavascriptInterface
        public void guardAction(String action) {
            finishGuard(action);
        }

        /** 首页「试一次」：立刻弹一次守护题，方便家长确认效果 */
        @JavascriptInterface
        public void guardTest() {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    try {
                        GuardPrefs.setShowing(MainActivity.this, true);
                        Intent i = new Intent(MainActivity.this, GuardActivity.class);
                        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                                | Intent.FLAG_ACTIVITY_CLEAR_TOP
                                | Intent.FLAG_ACTIVITY_NO_ANIMATION);
                        startActivity(i);
                    } catch (Throwable ignored) {
                    }
                }
            });
        }

        /** 首页开关：开 / 关守护，返回最新状态文字 */
        @JavascriptInterface
        public String guardToggle() {
            boolean v = !GuardPrefs.isEnabled(MainActivity.this);
            GuardPrefs.setEnabled(MainActivity.this, v);
            if (v) {
                GuardPrefs.clearSnooze(MainActivity.this);
                try {
                    startService(new Intent(MainActivity.this, GuardService.class));
                } catch (Throwable ignored) {
                }
            } else {
                try {
                    stopService(new Intent(MainActivity.this, GuardService.class));
                } catch (Throwable ignored) {
                }
            }
            return GuardPrefs.statusText(MainActivity.this);
        }

        @JavascriptInterface
        public String guardStatus() {
            return GuardPrefs.statusText(MainActivity.this);
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (isGuardMode() && !guardCompleted && !isFinishing()) {
            // HOME 键是系统级、拦不住：视作跳过这一次，让守护继续正常计时而不是卡死
            finishGuard("skip");
        }
    }

    @Override
    protected void onPause() {
        releaseVoice();
        if (web != null) {
            web.onPause();
        }
        if (tts != null) {
            try {
                tts.stop();
            } catch (Throwable ignored) {
            }
        }
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (web != null) {
            web.onResume();
            web.requestFocus();
        }
        enterImmersive();
    }

    @Override
    protected void onDestroy() {
        if (isGuardMode() && !guardCompleted) {
            guardCompleted = true;
            GuardPrefs.setShowing(this, false);
            try {
                sendBroadcast(new Intent(GuardService.ACTION_DONE)
                        .putExtra(GuardService.EXTRA_ACTION, "skip"));
            } catch (Throwable ignored) {
            }
        }
        releaseVoice();
        if (tts != null) {
            try {
                tts.stop();
                tts.shutdown();
            } catch (Throwable ignored) {
            }
            tts = null;
        }
        if (web != null) {
            try {
                web.loadUrl("about:blank");
                web.destroy();
            } catch (Throwable ignored) {
            }
            web = null;
        }
        super.onDestroy();
    }
}
