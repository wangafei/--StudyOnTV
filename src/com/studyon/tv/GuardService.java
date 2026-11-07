package com.studyon.tv;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.KeyEvent;

import java.util.List;

/**
 * 看电视守护服务。
 *
 * 每 10 秒读一次 UsageStats，若前台是视频/直播/信号源类应用，累计连续观看时长；
 * 满 5 分钟就弹出全屏答题界面（GuardActivity），答对一题才关闭、恢复播放。
 *
 * 这是普通第三方应用，不 root、不依赖无障碍：
 *  - 切到我们自己的全屏界面时，底层播放器会自然进入后台并暂停；
 *  - 同时抢 AUDIOFOCUS_GAIN_TRANSIENT，让守规矩的播放器保持暂停；
 *  - 答题结束释放音频焦点，播放器收到 AUDIOFOCUS_GAIN 后自动续播。
 */
public class GuardService extends Service {

    private static final String TAG = "StudyGuard";
    private static final int NOTIFY_ID = 1;
    private static final long TICK_MS = 10000L;

    public static final String ACTION_DONE = "com.studyon.tv.GUARD_DONE";
    public static final String ACTION_TEST = "com.studyon.tv.GUARD_TEST";
    public static final String ACTION_STATUS = "com.studyon.tv.GUARD_STATUS";
    public static final String ACTION_KEEPALIVE = "com.studyon.tv.GUARD_KEEPALIVE";
    public static final String EXTRA_ACTION = "action";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private long watchedMs = 0L;
    private long lastTickMs = 0L;
    private String lastForeground = null;
    private String lastWatchedApp = null;
    private UsageStatsManager usm;
    private AudioManager audio;
    private boolean focusHeld = false;
    private boolean registered = false;
    private PendingIntent keepAliveIntent = null;
    private AlarmManager alarmManager = null;

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            String a = i.getAction();
            if (ACTION_DONE.equals(a)) {
                onGuardFinished(i.getStringExtra(EXTRA_ACTION));
            } else if (ACTION_TEST.equals(a)) {
                Log.i(TAG, "TEST trigger received");
                GuardPrefs.setShowing(c, false);
                trigger();
            } else if (ACTION_STATUS.equals(a)) {
                Log.i(TAG, "STATUS enabled=" + GuardPrefs.isEnabled(c)
                        + " showing=" + GuardPrefs.isShowing(c)
                        + " snoozed=" + GuardPrefs.isSnoozed(c)
                        + " watched=" + watchedMs + "ms"
                        + " foreground=" + lastForeground
                        + " watchedApp=" + lastWatchedApp);
            } else if (ACTION_KEEPALIVE.equals(a)) {
                // 由 BootReceiver 负责把服务拉起来，这里只做存活标记
                Log.i(TAG, "keepalive tick");
            }
        }
    };

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            try {
                doTick();
            } catch (Throwable t) {
                Log.w(TAG, "tick error: " + t);
            }
            handler.postDelayed(this, TICK_MS);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        usm = (UsageStatsManager) getSystemService(Context.USAGE_STATS_SERVICE);
        audio = (AudioManager) getSystemService(Context.AUDIO_SERVICE);

        IntentFilter f = new IntentFilter();
        f.addAction(ACTION_DONE);
        f.addAction(ACTION_TEST);
        f.addAction(ACTION_STATUS);
        f.addAction(ACTION_KEEPALIVE);
        try {
            registerReceiver(receiver, f);
            registered = true;
        } catch (Throwable ignored) {
        }

        startForeground(NOTIFY_ID, buildNotification());
        scheduleKeepAlive();
        handler.postDelayed(tick, TICK_MS);
        Log.i(TAG, "service created, enabled=" + GuardPrefs.isEnabled(this));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (!GuardPrefs.isEnabled(this)) {
            Log.i(TAG, "guard disabled -> stop service");
            stopSelf();
            return START_NOT_STICKY;
        }
        return START_STICKY;
    }

    private void doTick() {
        long now = SystemClock.elapsedRealtime();

        if (!GuardPrefs.isEnabled(this)) {
            stopSelf();
            return;
        }

        // 守护界面逗留过久（按 HOME 绕过 / 进程被杀）自动复位，避免守护卡死
        if (GuardPrefs.isShowing(this)) {
            long at = GuardPrefs.showingAt(this);
            if (at > 0 && System.currentTimeMillis() - at > GuardPrefs.SHOWING_TIMEOUT_MS) {
                Log.i(TAG, "showing timeout -> reset");
                onGuardFinished("skip");
            }
            lastTickMs = now;
            return;
        }

        if (GuardPrefs.isSnoozed(this)) {
            watchedMs = 0L;
            lastTickMs = now;
            return;
        }

        String fg = foregroundPackage();
        boolean watched = GuardApps.isWatched(fg, getPackageManager());
        if (!watched) {
            // 回到桌面 / 设置 / 我们自己的 App：连续观看时长清零
            watchedMs = 0L;
        } else if (fg != null && fg.equals(lastForeground) && lastTickMs > 0) {
            watchedMs += (now - lastTickMs);
            lastWatchedApp = fg;
        }
        lastForeground = fg;
        lastTickMs = now;

        if (watchedMs >= GuardPrefs.LIMIT_MS) {
            trigger();
        }
    }

    /** 用 UsageEvents 找最近一次切到前台的应用（Android 6 上比 getRunningTasks 可靠） */
    private String foregroundPackage() {
        if (usm == null) {
            return null;
        }
        long now = System.currentTimeMillis();
        String pkg = null;
        try {
            UsageEvents events = usm.queryEvents(now - 30L * 60L * 1000L, now);
            if (events != null) {
                UsageEvents.Event e = new UsageEvents.Event();
                while (events.hasNextEvent()) {
                    events.getNextEvent(e);
                    if (e.getEventType() == UsageEvents.Event.MOVE_TO_FOREGROUND) {
                        pkg = e.getPackageName();
                    } else if (e.getEventType() == UsageEvents.Event.MOVE_TO_BACKGROUND) {
                        if (e.getPackageName().equals(pkg)) {
                            pkg = null;
                        }
                    }
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "queryEvents failed: " + t);
        }
        if (pkg == null) {
            pkg = foregroundPackageFallback(now);
        }
        return pkg;
    }

    /** 某些 TV 系统事件记录不全，用 lastTimeUsed 最大的包兜底 */
    private String foregroundPackageFallback(long now) {
        try {
            List<UsageStats> list = usm.queryUsageStats(
                    UsageStatsManager.INTERVAL_DAILY, now - 24L * 3600L * 1000L, now);
            if (list == null) {
                return null;
            }
            long best = 0L;
            String pkg = null;
            for (UsageStats s : list) {
                if (s.getLastTimeUsed() > best) {
                    best = s.getLastTimeUsed();
                    pkg = s.getPackageName();
                }
            }
            // 超过 2 分钟没有新的使用记录，认为不在前台
            if (now - best > 2L * 60L * 1000L) {
                return null;
            }
            return pkg;
        } catch (Throwable t) {
            return null;
        }
    }

    private void trigger() {
        if (!GuardPrefs.isEnabled(this)
                || GuardPrefs.isSnoozed(this)
                || GuardPrefs.isShowing(this)) {
            return;
        }
        GuardPrefs.setShowing(this, true);
        GuardPrefs.setLastTrigger(this, System.currentTimeMillis());
        watchedMs = 0L;
        lastTickMs = SystemClock.elapsedRealtime();
        takeFocus();

        try {
            Intent i = new Intent(this, GuardActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_CLEAR_TOP
                    | Intent.FLAG_ACTIVITY_NO_ANIMATION);
            startActivity(i);
            Log.i(TAG, "guard triggered, resume app = " + lastWatchedApp);
        } catch (Throwable t) {
            Log.w(TAG, "start GuardActivity failed: " + t);
            GuardPrefs.setShowing(this, false);
            releaseFocus(true);
        }
    }

    /** 答题结束：答对 / 跳过 / 暂停 / 关闭守护 */
    private void onGuardFinished(String action) {
        if ("off".equals(action)) {
            GuardPrefs.setEnabled(this, false);
            GuardPrefs.clearSnooze(this);
        } else if ("snooze".equals(action)) {
            GuardPrefs.snooze(this);
        }
        GuardPrefs.setShowing(this, false);
        watchedMs = 0L;
        lastTickMs = SystemClock.elapsedRealtime();
        lastForeground = null;
        releaseFocus(true);
        Log.i(TAG, "guard finished: " + action);
        if ("off".equals(action)) {
            stopSelf();
        }
    }

    private void takeFocus() {
        if (audio == null || focusHeld) {
            return;
        }
        try {
            audio.requestAudioFocus(null, AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT);
            focusHeld = true;
            dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_PAUSE);
        } catch (Throwable ignored) {
        }
    }

    private void releaseFocus(boolean resumePlayback) {
        if (audio != null && focusHeld) {
            try {
                audio.abandonAudioFocus(null);
            } catch (Throwable ignored) {
            }
            focusHeld = false;
        }
        if (resumePlayback) {
            // 兜底：部分播放器不监听音频焦点，显式补一个播放键
            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_PLAY);
                }
            }, 700L);
        }
    }

    private void dispatchMediaKey(int code) {
        if (audio == null) {
            return;
        }
        try {
            audio.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, code));
            audio.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, code));
        } catch (Throwable ignored) {
        }
    }

    private Notification buildNotification() {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder b = new Notification.Builder(this)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("题了个题 · 看电视守护")
                .setContentText("看满 5 分钟答一题，家长可在首页关闭")
                .setContentIntent(pi)
                .setOngoing(true)
                .setPriority(Notification.PRIORITY_MIN);
        return b.build();
    }

    /** 每分钟一次保活广播，服务被系统回收后由 BootReceiver 重新拉起 */
    private void scheduleKeepAlive() {
        try {
            alarmManager = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
            Intent i = new Intent(ACTION_KEEPALIVE);
            keepAliveIntent = PendingIntent.getBroadcast(this, 2, i,
                    PendingIntent.FLAG_UPDATE_CURRENT);
            alarmManager.setRepeating(AlarmManager.RTC,
                    System.currentTimeMillis() + 60000L, 60000L, keepAliveIntent);
        } catch (Throwable ignored) {
        }
    }

    /** 家长彻底关闭守护时，连保活闹钟一起停掉，不再有任何后台唤醒 */
    private void cancelKeepAlive() {
        try {
            if (alarmManager != null && keepAliveIntent != null) {
                alarmManager.cancel(keepAliveIntent);
            }
        } catch (Throwable ignored) {
        }
        keepAliveIntent = null;
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacks(tick);
        if (registered) {
            try {
                unregisterReceiver(receiver);
            } catch (Throwable ignored) {
            }
            registered = false;
        }
        releaseFocus(false);
        cancelKeepAlive();
        super.onDestroy();
        Log.i(TAG, "service destroyed");
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
