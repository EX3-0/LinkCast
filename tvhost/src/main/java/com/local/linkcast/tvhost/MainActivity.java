package com.local.linkcast.tvhost;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

public final class MainActivity extends Activity {
    private static final int PERMISSION_REQUEST = 11;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView status;
    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            status.setText(LinkCastService.describeStatus(MainActivity.this));
            handler.postDelayed(this, 2000);
        }
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        buildUi();
        requestNeededPermissionsAndStart();
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setPadding(dp(48), dp(36), dp(48), dp(36));

        TextView title = new TextView(this);
        title.setText("LinkCast TV Host");
        title.setTextSize(34);
        title.setGravity(Gravity.CENTER);
        root.addView(title, wrap());

        status = new TextView(this);
        status.setTextSize(22);
        status.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams statusParams = wrap();
        statusParams.topMargin = dp(24);
        root.addView(status, statusParams);

        TextView help = new TextView(this);
        help.setText("Install linkcast-tv.user.js in the TV browser.\n"
                + "Keep this host app running; the phone will find it automatically.");
        help.setTextSize(18);
        help.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams helpParams = wrap();
        helpParams.topMargin = dp(28);
        root.addView(help, helpParams);

        Button restart = new Button(this);
        restart.setText("Start / Restart Host");
        LinearLayout.LayoutParams buttonParams = wrap();
        buttonParams.topMargin = dp(28);
        root.addView(restart, buttonParams);
        restart.setOnClickListener(v -> startHost());

        setContentView(root);
    }

    private void requestNeededPermissionsAndStart() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{
                    Manifest.permission.NEARBY_WIFI_DEVICES,
                    Manifest.permission.POST_NOTIFICATIONS
            }, PERMISSION_REQUEST);
        } else {
            startHost();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                           int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST) startHost();
    }

    private void startHost() {
        Intent intent = new Intent(this, LinkCastService.class);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent);
        else startService(intent);
        handler.removeCallbacks(refresh);
        handler.postDelayed(refresh, 400);
    }

    @Override protected void onResume() {
        super.onResume();
        handler.removeCallbacks(refresh);
        handler.post(refresh);
    }

    @Override protected void onPause() {
        handler.removeCallbacks(refresh);
        super.onPause();
    }

    private LinearLayout.LayoutParams wrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
