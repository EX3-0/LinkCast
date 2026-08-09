package com.local.linkcast.tvhost;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

public final class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent incoming) {
        if (!Intent.ACTION_BOOT_COMPLETED.equals(incoming.getAction())) return;
        Intent service = new Intent(context, LinkCastService.class);
        if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(service);
        else context.startService(service);
    }
}
