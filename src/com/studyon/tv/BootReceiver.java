package com.studyon.tv;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/** 开机自启 + 应用升级后恢复 + 保活广播拉起守护服务 */
public class BootReceiver extends BroadcastReceiver {

    private static final String TAG = "StudyGuard";

    @Override
    public void onReceive(Context c, Intent i) {
        if (!GuardPrefs.isEnabled(c)) {
            return;     // 家长已彻底关闭，重启也不许自己打开
        }
        try {
            c.startService(new Intent(c, GuardService.class));
            Log.i(TAG, "boot/keepalive -> start service, action=" + i.getAction());
        } catch (Throwable t) {
            Log.w(TAG, "start service failed: " + t);
        }
    }
}
