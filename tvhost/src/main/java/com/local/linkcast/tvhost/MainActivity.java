package com.local.linkcast.tvhost;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Locale;

public final class MainActivity extends Activity
        implements LinkCastService.NavigationListener {
    private static final int PERMISSION_REQUEST = 11;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private FrameLayout root;
    private LinearLayout homePanel;
    private TextView status;
    private WebView webView;
    private View customView;
    private WebChromeClient.CustomViewCallback customViewCallback;
    private String lastRequestedUrl;

    private final Runnable refresh = new Runnable() {
        @Override
        public void run() {
            status.setText(LinkCastService.describeStatus(MainActivity.this)
                    + "\n\nWaiting for a link from your phone.");
            handler.postDelayed(this, 2000);
        }
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setSoftInputMode(
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        buildUi();
        requestNeededPermissionsAndStart();
        handleLaunchIntent(getIntent());
    }

    private void buildUi() {
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        webView = new WebView(this);
        configureWebView();
        webView.setVisibility(View.GONE);
        root.addView(webView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        homePanel = new LinearLayout(this);
        homePanel.setOrientation(LinearLayout.VERTICAL);
        homePanel.setGravity(Gravity.CENTER);
        homePanel.setPadding(dp(48), dp(36), dp(48), dp(36));
        homePanel.setBackgroundColor(Color.rgb(16, 24, 32));

        TextView title = new TextView(this);
        title.setText("LinkCast TV Browser");
        title.setTextColor(Color.WHITE);
        title.setTextSize(34);
        title.setGravity(Gravity.CENTER);
        homePanel.addView(title, wrap());

        status = new TextView(this);
        status.setTextColor(Color.LTGRAY);
        status.setTextSize(21);
        status.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams statusParams = wrap();
        statusParams.topMargin = dp(24);
        homePanel.addView(status, statusParams);

        TextView help = new TextView(this);
        help.setText("Share a web link to LinkCast on your phone.\n"
                + "It will open here automatically—no browser extension required.");
        help.setTextColor(Color.WHITE);
        help.setTextSize(18);
        help.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams helpParams = wrap();
        helpParams.topMargin = dp(28);
        homePanel.addView(help, helpParams);

        Button restart = new Button(this);
        restart.setText("Start / Restart Receiver");
        LinearLayout.LayoutParams buttonParams = wrap();
        buttonParams.topMargin = dp(28);
        homePanel.addView(restart, buttonParams);
        restart.setOnClickListener(v -> startHost());

        root.addView(homePanel, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);
    }

    private void configureWebView() {
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);
        settings.setSupportZoom(true);
        settings.setBuiltInZoomControls(true);
        settings.setDisplayZoomControls(false);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setGeolocationEnabled(false);
        settings.setSupportMultipleWindows(false);
        if (Build.VERSION.SDK_INT >= 21) {
            settings.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
            CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true);
        }
        CookieManager.getInstance().setAcceptCookie(true);

        webView.setBackgroundColor(Color.BLACK);
        webView.setFocusable(true);
        webView.setFocusableInTouchMode(true);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(
                    WebView view, WebResourceRequest request) {
                return !isAllowedWebUrl(request.getUrl().toString());
            }

            @Override
            @SuppressWarnings("deprecation")
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return !isAllowedWebUrl(url);
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onShowCustomView(
                    View view, WebChromeClient.CustomViewCallback callback) {
                if (customView != null) {
                    callback.onCustomViewHidden();
                    return;
                }
                customView = view;
                customViewCallback = callback;
                webView.setVisibility(View.GONE);
                homePanel.setVisibility(View.GONE);
                root.addView(view, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT));
                applyImmersiveMode(true);
            }

            @Override
            public void onHideCustomView() {
                hideCustomView();
            }

            @Override
            public void onPermissionRequest(PermissionRequest request) {
                request.deny();
            }
        });
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
    public void onRequestPermissionsResult(
            int requestCode, String[] permissions, int[] grantResults) {
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

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleLaunchIntent(intent);
    }

    private void handleLaunchIntent(Intent intent) {
        if (intent == null) return;
        String url = intent.getStringExtra(LinkCastService.EXTRA_URL);
        intent.removeExtra(LinkCastService.EXTRA_URL);
        if (url != null) loadRemoteUrl(url);
    }

    @Override
    public void onNavigate(String url) {
        runOnUiThread(() -> loadRemoteUrl(url));
    }

    private void loadRemoteUrl(String url) {
        if (!isAllowedWebUrl(url)) {
            Toast.makeText(this, "Blocked invalid web address", Toast.LENGTH_SHORT).show();
            return;
        }

        LinkCastService.markUrlDisplayed(this, url);
        if (url.equals(lastRequestedUrl)
                && webView.getVisibility() == View.VISIBLE) {
            return;
        }

        lastRequestedUrl = url;
        homePanel.setVisibility(View.GONE);
        webView.setVisibility(View.VISIBLE);
        applyImmersiveMode(true);
        webView.loadUrl(url);
        webView.requestFocus();
    }

    private static boolean isAllowedWebUrl(String value) {
        if (value == null || value.length() > 8192) return false;
        try {
            Uri uri = Uri.parse(value);
            String scheme = uri.getScheme();
            String host = uri.getHost();
            return scheme != null
                    && ("http".equals(scheme.toLowerCase(Locale.ROOT))
                    || "https".equals(scheme.toLowerCase(Locale.ROOT)))
                    && host != null && !host.isEmpty();
        } catch (Exception ignored) {
            return false;
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        webView.onResume();
        LinkCastService.setNavigationListener(this);
        handler.removeCallbacks(refresh);
        handler.post(refresh);

        String pending = LinkCastService.peekPendingUrl(this);
        if (pending != null) loadRemoteUrl(pending);
    }

    @Override
    protected void onPause() {
        LinkCastService.clearNavigationListener(this);
        handler.removeCallbacks(refresh);
        webView.onPause();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        LinkCastService.clearNavigationListener(this);
        if (webView != null) {
            webView.stopLoading();
            webView.setWebChromeClient(null);
            webView.setWebViewClient(null);
            webView.destroy();
        }
        super.onDestroy();
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        if (customView != null) {
            hideCustomView();
        } else if (webView.getVisibility() == View.VISIBLE && webView.canGoBack()) {
            webView.goBack();
        } else if (webView.getVisibility() == View.VISIBLE) {
            webView.setVisibility(View.GONE);
            homePanel.setVisibility(View.VISIBLE);
            applyImmersiveMode(false);
        } else {
            super.onBackPressed();
        }
    }

    private void hideCustomView() {
        if (customView == null) return;
        root.removeView(customView);
        customView = null;
        webView.setVisibility(View.VISIBLE);
        homePanel.setVisibility(View.GONE);
        if (customViewCallback != null) {
            customViewCallback.onCustomViewHidden();
            customViewCallback = null;
        }
        applyImmersiveMode(true);
    }

    private void applyImmersiveMode(boolean enabled) {
        if (!enabled) {
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);
            return;
        }
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }

    private LinearLayout.LayoutParams wrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
