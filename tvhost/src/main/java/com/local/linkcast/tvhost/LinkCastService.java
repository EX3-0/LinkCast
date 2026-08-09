package com.local.linkcast.tvhost;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.IBinder;
import android.util.Base64;
import android.util.Log;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Collections;
import java.util.Enumeration;

public final class LinkCastService extends Service {
    public static final int PORT = 8765;
    private static final String TAG = "LinkCastHost";
    private static final String CHANNEL_ID = "linkcast_host";
    private static final String SERVICE_TYPE = "_linkcast._tcp.";

    private HttpHost httpHost;
    private NsdManager nsd;
    private NsdManager.RegistrationListener registration;
    private WifiManager.MulticastLock multicastLock;
    private static volatile String registeredName;
    private static volatile boolean running;
    private static volatile String lastError;

    @Override
    public void onCreate() {
        super.onCreate();
        startForeground(1, createNotification());
        startHost();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (httpHost == null) startHost();
        return START_STICKY;
    }

    private synchronized void startHost() {
        stopHost();
        lastError = null;
        try {
            String token = getOrCreateToken(this);
            httpHost = new HttpHost(PORT, token);
            httpHost.start();
            acquireMulticastLock();
            registerNsd(token);
            running = true;
        } catch (Exception error) {
            running = false;
            lastError = error.getMessage();
            Log.e(TAG, "Could not start host", error);
        }
    }

    private void acquireMulticastLock() {
        WifiManager wifi = (WifiManager) getApplicationContext()
                .getSystemService(Context.WIFI_SERVICE);
        if (wifi == null) return;
        multicastLock = wifi.createMulticastLock("linkcast-mdns");
        multicastLock.setReferenceCounted(false);
        multicastLock.acquire();
    }

    private void registerNsd(String token) {
        nsd = (NsdManager) getSystemService(Context.NSD_SERVICE);
        NsdServiceInfo info = new NsdServiceInfo();
        info.setServiceName("LinkCast-TV-" + token.substring(0, 4));
        info.setServiceType(SERVICE_TYPE);
        info.setPort(PORT);
        info.setAttribute("token", token);
        info.setAttribute("version", "1");

        registration = new NsdManager.RegistrationListener() {
            @Override
            public void onServiceRegistered(NsdServiceInfo serviceInfo) {
                registeredName = serviceInfo.getServiceName();
                running = true;
            }

            @Override
            public void onRegistrationFailed(NsdServiceInfo serviceInfo, int errorCode) {
                lastError = "mDNS registration failed (" + errorCode + ")";
                Log.e(TAG, lastError);
            }

            @Override public void onServiceUnregistered(NsdServiceInfo serviceInfo) {
                registeredName = null;
            }

            @Override
            public void onUnregistrationFailed(NsdServiceInfo serviceInfo, int errorCode) {
                Log.w(TAG, "mDNS unregistration failed: " + errorCode);
            }
        };
        nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, registration);
    }

    private synchronized void stopHost() {
        if (nsd != null && registration != null) {
            try { nsd.unregisterService(registration); } catch (Exception ignored) { }
        }
        registration = null;
        nsd = null;
        registeredName = null;
        if (httpHost != null) httpHost.stop();
        httpHost = null;
        if (multicastLock != null && multicastLock.isHeld()) multicastLock.release();
        multicastLock = null;
        running = false;
    }

    @Override
    public void onDestroy() {
        stopHost();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) {
        return null;
    }

    private Notification createNotification() {
        NotificationManager manager =
                (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "TV link receiver", NotificationManager.IMPORTANCE_LOW);
            manager.createNotificationChannel(channel);
        }
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pending = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        return builder
                .setContentTitle("LinkCast TV Host")
                .setContentText("Ready to receive browser links")
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setOngoing(true)
                .setContentIntent(pending)
                .build();
    }

    static String describeStatus(Context context) {
        String ip = findLanIpv4();
        if (!running) {
            return "Host stopped" + (lastError == null ? "" : "\n" + lastError);
        }
        String discovery = registeredName == null ? "Starting discovery…" : registeredName;
        return "Ready\n" + discovery + "\nTV IP: "
                + (ip == null ? "Unavailable" : ip) + ":" + PORT;
    }

    private static String getOrCreateToken(Context context) {
        SharedPreferences prefs = context.getSharedPreferences("host", MODE_PRIVATE);
        String token = prefs.getString("token", null);
        if (token != null) return token;
        byte[] random = new byte[18];
        new SecureRandom().nextBytes(random);
        token = Base64.encodeToString(random, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
        prefs.edit().putString("token", token).apply();
        return token;
    }

    private static String findLanIpv4() {
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            for (NetworkInterface item : Collections.list(interfaces)) {
                if (!item.isUp() || item.isLoopback()) continue;
                for (InetAddress address : Collections.list(item.getInetAddresses())) {
                    if (address instanceof Inet4Address && address.isSiteLocalAddress()) {
                        return address.getHostAddress();
                    }
                }
            }
        } catch (Exception ignored) { }
        return null;
    }
}
