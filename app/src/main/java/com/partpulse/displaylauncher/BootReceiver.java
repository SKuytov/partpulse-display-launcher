package com.partpulse.displaylauncher;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Launches DisplayActivity automatically after the device boots.
 * Registered for BOOT_COMPLETED and QUICKBOOT_POWERON (some Chinese TV firmwares).
 */
public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (Intent.ACTION_BOOT_COMPLETED.equals(action)
                || "android.intent.action.QUICKBOOT_POWERON".equals(action)) {

            Intent launch = new Intent(context, NativeDisplayActivity.class);
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            context.startActivity(launch);
        }
    }
}
