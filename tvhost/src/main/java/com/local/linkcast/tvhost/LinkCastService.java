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
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Base64;
import android.util.Log;

import java.lang.ref.WeakReference;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Collections;
import java.util.Enumeration;

public final class LinkCastService extends Service {
    public static final int PORT = 8765;
    public static final String EXTRA_URL = "com.local.linkcast.tvhost.URL";

    private static final String TAG = "LinkCastHost";
    private static final String CHANNEL_ID = "linkcast_host";
    private static final String SERVICE_TYPE = "_linkcast._tcp.";
    private static final String PREFS = "host";
    private static final String PENDING_URL = "pending_url";
    private static final Object LISTENER_LOCK = new Object();

    interface NavigationListener {
        void onNavigate(String url);
    }

    private HttpHost httpHost;
    private NsdManager nsd;
    private NsdManager.RegistrationListener registration;
    private WifiManager.MulticastLock multicastLock;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private static WeakReference<NavigationListener> navigationListener =
            new WeakReference<>(null);
    private static volatile String registeredName;
    private static volatile boolean running;
    private static volatile String lastError;

    @Override
    public void onCreate() {
        super.onCreate();
        startForeground(1, createNotification(
                "Ready to receive browser links", null));
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
            httpHost = new HttpHost(PORT, token, this::handleCommand);
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

    private void handleCommand(String url) {
        getSharedPreferences(PREFS, MODE_PRIVATE)
                .edit()
                .putString(PENDING_URL, url)
                .apply();

        NotificationManager manager =
                (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        manager.notify(1, createNotification("Link received — open LinkCast", url));

        mainHandler.post(() -> {
            NavigationListener listener = currentNavigationListener();
            if (listener != null) {
                listener.onNavigate(url);
                return;
            }

            Intent open = new Intent(this, MainActivity.class)
                    .putExtra(EXTRA_URL, url)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                            | Intent.FLAG_ACTIVITY_CLEAR_TOP
                            | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            try {
                startActivity(open);
            } catch (RuntimeException error) {
                Log.w(TAG, "Fire OS blocked automatic browser launch", error);
            }
        });
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
        info.setAttribute("version", "2");
        info.setAttribute("browser", "webview");

        registration = new NsdManager.RegistrationListener() {
            @Override
            public void onServiceRegistered(NsdServiceInfo serviceInfo) {
                registeredName = serviceInfo.getServiceName();
                running = true;
            }

            @Override
            public void onRegistrationFailed(
                    NsdServiceInfo serviceInfo, int errorCode) {
                lastError = "mDNS registration failed (" + errorCode + ")";
                Log.e(TAG, lastError);
            }

            @Override
            public void onServiceUnregistered(NsdServiceInfo serviceInfo) {
                registeredName = null;
            }

            @Override
            public void onUnregistrationFailed(
                    NsdServiceInfo serviceInfo, int errorCode) {
                Log.w(TAG, "mDNS unregistration failed: " + errorCode);
            }
        };
        nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, registration);
    }

    private synchronized void stopHost() {
        if (nsd != null && registration != null) {
            try {
                nsd.unregisterService(registration);
            } catch (Exception ignored) {
            }
        }
        registration = null;
        nsd = null;
        registeredName = null;

        if (httpHost != null) httpHost.stop();
        httpHost = null;

        if (multicastLock != null && multicastLock.isHeld()) {
            multicastLock.release();
        }
        multicastLock = null;
        running = false;
    }

    @Override
    public void onDestroy() {
        stopHost();
        mainHandler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private Notification createNotification(String text, String url) {
        NotificationManager manager =
                (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "TV link receiver",
                    NotificationManager.IMPORTANCE_LOW);
            manager.createNotificationChannel(channel);
        }

        Intent open = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP
                        | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        if (url != null) open.putExtra(EXTRA_URL, url);

        PendingIntent pending = PendingIntent.getActivity(
                this,
                url == null ? 0 : 1,
                open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);

        return builder
                .setContentTitle("LinkCast TV Browser")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setOngoing(true)
                .setContentIntent(pending)
                .build();
    }

    static void setNavigationListener(NavigationListener listener) {
        synchronized (LISTENER_LOCK) {
            navigationListener = new WeakReference<>(listener);
        }
    }

    static void clearNavigationListener(NavigationListener listener) {
        synchronized (LISTENER_LOCK) {
            if (navigationListener.get() == listener) {
                navigationListener.clear();
            }
        }
    }

    private static NavigationListener currentNavigationListener() {
        synchronized (LISTENER_LOCK) {
            return navigationListener.get();
        }
    }

    static String peekPendingUrl(Context context) {
        return context.getSharedPreferences(PREFS, MODE_PRIVATE)
                .getString(PENDING_URL, null);
    }

    static void markUrlDisplayed(Context context, String url) {
        SharedPreferences preferences =
                context.getSharedPreferences(PREFS, MODE_PRIVATE);
        if (url.equals(preferences.getString(PENDING_URL, null))) {
            preferences.edit().remove(PENDING_URL).apply();
        }
    }

    static String describeStatus(Context context) {
        String ip = findLanIpv4();
        if (!running) {
            return "Receiver stopped"
                    + (lastError == null ? "" : "\n" + lastError);
        }
        String discovery = registeredName == null
                ? "Starting discovery…"
                : registeredName;
        return "Ready\n" + discovery + "\nTV IP: "
                + (ip == null ? "Unavailable" : ip) + ":" + PORT;
    }

    private static String getOrCreateToken(Context context) {
        SharedPreferences preferences =
                context.getSharedPreferences(PREFS, MODE_PRIVATE);
        String token = preferences.getString("token", null);
        if (token != null) return token;

        byte[] random = new byte[18];
        new SecureRandom().nextBytes(random);
        token = Base64.encodeToString(
                random,
                Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
        preferences.edit().putString("token", token).apply();
        return token;
    }

    private static String findLanIpv4() {
        try {
            Enumeration<NetworkInterface> interfaces =
                    NetworkInterface.getNetworkInterfaces();
            for (NetworkInterface item : Collections.list(interfaces)) {
                if (!item.isUp() || item.isLoopback()) continue;
                for (InetAddress address :
                        Collections.list(item.getInetAddresses())) {
                    if (address instanceof Inet4Address
                            && address.isSiteLocalAddress()) {
                        return address.getHostAddress();
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }
}
