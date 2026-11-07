package com.studyon.tv;

import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;

/**
 * 判断当前前台应用算不算「看电视」。
 * 规则：视频/直播/信号源类应用计入；桌面、设置、系统组件、本 App 不计入。
 * 采用「关键字 + 第三方应用自动计入」的方式，以后装新的播放器无需改代码。
 */
public final class GuardApps {

    /** 系统预装但确实在看电视的：HDMI / 数字电视信号源 */
    private static final String[] EXTRA_WATCH = {
            "com.droidlogic.tvinput", "com.mitv.tvinput", "com.xiaomi.mitv.tvinput",
            "com.mitv.dtv", "com.xiaomi.mitv.dvb"
    };

    /** 明确排除：桌面、设置、清理、升级、账号、商店插件等 */
    private static final String[] SKIP_WORDS = {
            "clean", "update", "codec", "care", "shoplugin", "karaoke",
            "account", "settings", "download", "launcher", "tvhome", "providers",
            "studyon", "defcontainer", "webview", "inputmethod", "bluetooth",
            "alarmcenter", "mitv.service", "codec.update"
    };

    /** 明确算作看视频的包名关键字 */
    private static final String[] WATCH_WORDS = {
            "video", "tvinput", "dvb", "dtv", "player", "iqiyi", "qqlive",
            "youku", "mgtv", "pptv", "letv", "bilibili", "cibn", "fongmi",
            "quark", "xiaodianshi", ".yst", "sohu", "migu", "wasu", "hunantv",
            "ktcp", "letv", "baidu.video", "xigua", "douyin", "kuaishou"
    };

    private GuardApps() {
    }

    public static boolean isWatched(String pkg, PackageManager pm) {
        if (pkg == null || pkg.length() == 0) {
            return false;
        }
        if ("com.studyon.tv".equals(pkg)) {
            return false;
        }
        String low = pkg.toLowerCase();
        for (String s : SKIP_WORDS) {
            if (low.contains(s)) {
                return false;
            }
        }
        for (String s : EXTRA_WATCH) {
            if (low.equals(s) || low.startsWith(s)) {
                return true;
            }
        }
        for (String s : WATCH_WORDS) {
            if (low.contains(s)) {
                return true;
            }
        }
        try {
            ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
            // 用户自己安装的第三方应用，在这台电视上基本都是播放器，默认计入
            if ((ai.flags & ApplicationInfo.FLAG_SYSTEM) == 0
                    || (ai.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0) {
                return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }
}
