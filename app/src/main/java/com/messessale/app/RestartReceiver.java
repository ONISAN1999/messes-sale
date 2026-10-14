package com.messessale.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.provider.Settings;

/** หลังอัปเดตแอป → เปิดฟองกลับมาอัตโนมัติ */
public class RestartReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context c, Intent i) {
        if (i == null || !Intent.ACTION_MY_PACKAGE_REPLACED.equals(i.getAction())) return;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(c)) return;
            c.startForegroundService(new Intent(c, BubbleService.class));
        } catch (Exception ignored) {}
    }
}
