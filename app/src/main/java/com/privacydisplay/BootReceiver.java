package com.privacydisplay;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.provider.Settings;

import androidx.preference.PreferenceManager;

public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        SharedPreferences p = PreferenceManager.getDefaultSharedPreferences(context);
        if (!p.getBoolean(Prefs.AUTOSTART, false)) return;
        if (!p.getBoolean(Prefs.ENABLED, false)) return;
        if (!Settings.canDrawOverlays(context)) return;

        Intent svc = new Intent(context, OverlayService.class);
        svc.setAction(OverlayService.ACTION_START);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(svc);
        } else {
            context.startService(svc);
        }
    }
}
