package com.studyon.tv;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 看电视守护的持久化设置。
 * 用 commit() 同步落盘，保证家长在答题界面点「关闭守护」后，
 * 即使立刻重启电视也不会又被弹题。
 */
public final class GuardPrefs {

    private static final String FILE = "guard_prefs";
    private static final String K_ENABLED = "enabled";
    private static final String K_SNOOZE = "snooze_until";
    private static final String K_SHOWING = "showing";
    private static final String K_SHOWING_AT = "showing_at";
    private static final String K_LAST = "last_trigger";

    /** 连续看满 5 分钟弹一次题 */
    public static final long LIMIT_MS = 5L * 60L * 1000L;
    /** 家长临时暂停 1 小时 */
    public static final long SNOOZE_MS = 60L * 60L * 1000L;
    /** 守护界面显示超过 3 分钟没有结果（被 HOME 键绕过 / 进程被杀）则自动复位 */
    public static final long SHOWING_TIMEOUT_MS = 3L * 60L * 1000L;

    private GuardPrefs() {
    }

    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    public static boolean isEnabled(Context c) {
        return sp(c).getBoolean(K_ENABLED, true);
    }

    public static void setEnabled(Context c, boolean v) {
        sp(c).edit().putBoolean(K_ENABLED, v).commit();
    }

    public static long snoozeUntil(Context c) {
        return sp(c).getLong(K_SNOOZE, 0L);
    }

    public static boolean isSnoozed(Context c) {
        return snoozeUntil(c) > System.currentTimeMillis();
    }

    public static void snooze(Context c) {
        sp(c).edit().putLong(K_SNOOZE, System.currentTimeMillis() + SNOOZE_MS).commit();
    }

    public static void clearSnooze(Context c) {
        sp(c).edit().putLong(K_SNOOZE, 0L).commit();
    }

    public static boolean isShowing(Context c) {
        return sp(c).getBoolean(K_SHOWING, false);
    }

    public static void setShowing(Context c, boolean v) {
        sp(c).edit()
                .putBoolean(K_SHOWING, v)
                .putLong(K_SHOWING_AT, v ? System.currentTimeMillis() : 0L)
                .commit();
    }

    public static long showingAt(Context c) {
        return sp(c).getLong(K_SHOWING_AT, 0L);
    }

    public static long lastTrigger(Context c) {
        return sp(c).getLong(K_LAST, 0L);
    }

    public static void setLastTrigger(Context c, long t) {
        sp(c).edit().putLong(K_LAST, t).commit();
    }

    /** 首页显示的守护状态文字 */
    public static String statusText(Context c) {
        if (!isEnabled(c)) {
            return "已关闭 · 不会再弹题";
        }
        if (isSnoozed(c)) {
            long m = (snoozeUntil(c) - System.currentTimeMillis()) / 60000L + 1;
            return "暂停中 · 约 " + m + " 分钟后恢复";
        }
        return "开启中 · 看满 5 分钟答 1 题";
    }
}
