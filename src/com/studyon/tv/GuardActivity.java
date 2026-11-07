package com.studyon.tv;

/**
 * 守护弹出的全屏答题界面。
 * 与 MainActivity 共用同一套 WebView / 题库 / 离线语音，只把交互切成「答对一题才放行」。
 * 独立 task，关闭后系统会回到刚才正在播放的视频应用。
 */
public class GuardActivity extends MainActivity {

    @Override
    protected boolean isGuardMode() {
        return true;
    }
}
