package com.local.linkcast.phone;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class MainActivity extends Activity {
    private static final String SERVICE_TYPE = "_linkcast._tcp.";
    private static final int PERMISSION_REQUEST = 10;
    private static final Pattern URL_IN_TEXT =
            Pattern.compile("https?://[^\\s<>\"']+", Pattern.CASE_INSENSITIVE);

    private TextView ipText;
    private TextView statusText;
    private EditText linkInput;
    private Button findButton;
    private Button sendButton;

    private NsdManager nsd;
    private NsdManager.DiscoveryListener discoveryListener;
    private boolean resolving;
    private boolean autoSendAfterDiscovery;
    private InetAddress tvAddress;
    private int tvPort;
    private String tvToken;
    private final ExecutorService networkExecutor = Executors.newSingleThreadExecutor();

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        nsd = (NsdManager) getSystemService(Context.NSD_SERVICE);
        buildUi();
        handleIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    private void buildUi() {
        int pad = dp(20);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);
        root.setGravity(Gravity.CENTER_HORIZONTAL);

        ipText = new TextView(this);
        ipText.setText("TV IP: Unset");
        ipText.setTextSize(22);
        root.addView(ipText, matchWrap());

        findButton = new Button(this);
        findButton.setText("Find TV");
        LinearLayout.LayoutParams buttonParams = matchWrap();
        buttonParams.topMargin = dp(12);
        root.addView(findButton, buttonParams);

        LinearLayout sendRow = new LinearLayout(this);
        sendRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams rowParams =
                new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT);
        rowParams.topMargin = dp(24);
        root.addView(sendRow, rowParams);

        linkInput = new EditText(this);
        linkInput.setHint("https://example.com");
        linkInput.setSingleLine(true);
        linkInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        sendRow.addView(linkInput, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        sendButton = new Button(this);
        sendButton.setText("Send");
        sendRow.addView(sendButton, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        statusText = new TextView(this);
        statusText.setText("Share a link to LinkCast, or paste one above.");
        statusText.setTextSize(14);
        LinearLayout.LayoutParams statusParams = matchWrap();
        statusParams.topMargin = dp(14);
        root.addView(statusText, statusParams);

        setContentView(root);

        findButton.setOnClickListener(v -> {
            autoSendAfterDiscovery = false;
            ensurePermissionAndDiscover();
        });
        sendButton.setOnClickListener(v -> {
            if (tvAddress == null) {
                autoSendAfterDiscovery = true;
                ensurePermissionAndDiscover();
            } else {
                sendCurrentLink();
            }
        });
    }

    private void handleIntent(Intent intent) {
        if (!Intent.ACTION_SEND.equals(intent.getAction())) return;
        CharSequence shared = intent.getCharSequenceExtra(Intent.EXTRA_TEXT);
        String url = extractUrl(shared == null ? "" : shared.toString());
        if (url == null) {
            statusText.setText("The shared text does not contain an HTTP(S) link.");
            return;
        }
        linkInput.setText(url);
        autoSendAfterDiscovery = true;
        ensurePermissionAndDiscover();
    }

    private static String extractUrl(String text) {
        String value = text.trim();
        if (isHttpUrl(value)) return value;
        Matcher matcher = URL_IN_TEXT.matcher(value);
        if (!matcher.find()) return null;
        value = matcher.group();
        while (!value.isEmpty() && ".,;:!?)]}".indexOf(value.charAt(value.length() - 1)) >= 0) {
            value = value.substring(0, value.length() - 1);
        }
        return isHttpUrl(value) ? value : null;
    }

    private static boolean isHttpUrl(String value) {
        try {
            URI uri = new URI(value);
            String protocol = uri.getScheme();
            return protocol != null
                    && ("http".equalsIgnoreCase(protocol) || "https".equalsIgnoreCase(protocol))
                    && uri.getHost() != null && !uri.getHost().isEmpty()
                    && value.length() <= 8192;
        } catch (Exception ignored) {
            return false;
        }
    }

    private boolean hasDiscoveryPermission() {
        if (Build.VERSION.SDK_INT >= 33) {
            return checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES)
                    == PackageManager.PERMISSION_GRANTED;
        }
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void ensurePermissionAndDiscover() {
        if (!hasDiscoveryPermission()) {
            String permission = Build.VERSION.SDK_INT >= 33
                    ? Manifest.permission.NEARBY_WIFI_DEVICES
                    : Manifest.permission.ACCESS_FINE_LOCATION;
            requestPermissions(new String[]{permission}, PERMISSION_REQUEST);
            return;
        }
        discover();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                           int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST && grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            discover();
        } else if (requestCode == PERMISSION_REQUEST) {
            statusText.setText("Nearby-device permission is required to find the TV.");
        }
    }

    private void discover() {
        stopDiscovery();
        resolving = false;
        tvAddress = null;
        tvToken = null;
        ipText.setText("TV IP: Unset");
        statusText.setText("Looking for LinkCast TV…");
        findButton.setEnabled(false);

        discoveryListener = new NsdManager.DiscoveryListener() {
            @Override public void onDiscoveryStarted(String type) { }

            @Override
            public void onServiceFound(NsdServiceInfo service) {
                if (resolving || !service.getServiceType().equalsIgnoreCase(SERVICE_TYPE)) return;
                resolving = true;
                runOnUiThread(() -> statusText.setText("Found TV; resolving address…"));
                try {
                    nsd.resolveService(service, new NsdManager.ResolveListener() {
                        @Override
                        public void onResolveFailed(NsdServiceInfo info, int errorCode) {
                            resolving = false;
                            runOnUiThread(() ->
                                    statusText.setText("Could not resolve TV (" + errorCode + ")."));
                        }

                        @Override
                        public void onServiceResolved(NsdServiceInfo info) {
                            Map<String, byte[]> attrs = info.getAttributes();
                            byte[] tokenBytes = attrs.get("token");
                            if (tokenBytes == null || tokenBytes.length == 0) {
                                resolving = false;
                                runOnUiThread(() ->
                                        statusText.setText("Found an incompatible LinkCast TV."));
                                return;
                            }
                            tvAddress = info.getHost();
                            tvPort = info.getPort();
                            tvToken = new String(tokenBytes, StandardCharsets.UTF_8);
                            getSharedPreferences("tv", MODE_PRIVATE).edit()
                                    .putString("name", info.getServiceName()).apply();
                            stopDiscovery();
                            runOnUiThread(() -> {
                                ipText.setText("TV IP: " + tvAddress.getHostAddress());
                                statusText.setText("TV found.");
                                findButton.setEnabled(true);
                                if (autoSendAfterDiscovery) {
                                    autoSendAfterDiscovery = false;
                                    sendCurrentLink();
                                }
                            });
                        }
                    });
                } catch (RuntimeException error) {
                    resolving = false;
                    runOnUiThread(() -> statusText.setText("Resolve failed: " + error.getMessage()));
                }
            }

            @Override public void onServiceLost(NsdServiceInfo service) { }

            @Override
            public void onDiscoveryStopped(String type) {
                runOnUiThread(() -> findButton.setEnabled(true));
            }

            @Override
            public void onStartDiscoveryFailed(String type, int errorCode) {
                try { nsd.stopServiceDiscovery(this); } catch (Exception ignored) { }
                discoveryListener = null;
                runOnUiThread(() -> {
                    findButton.setEnabled(true);
                    statusText.setText("Discovery failed (" + errorCode + ").");
                });
            }

            @Override
            public void onStopDiscoveryFailed(String type, int errorCode) {
                discoveryListener = null;
                runOnUiThread(() -> findButton.setEnabled(true));
            }
        };

        try {
            nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener);
        } catch (SecurityException error) {
            findButton.setEnabled(true);
            statusText.setText("Nearby-device permission was denied.");
        } catch (RuntimeException error) {
            findButton.setEnabled(true);
            statusText.setText("Could not start discovery: " + error.getMessage());
        }
    }

    private void stopDiscovery() {
        NsdManager.DiscoveryListener listener = discoveryListener;
        discoveryListener = null;
        if (listener != null) {
            try { nsd.stopServiceDiscovery(listener); } catch (Exception ignored) { }
        }
    }

    private void sendCurrentLink() {
        String link = linkInput.getText().toString().trim();
        if (!isHttpUrl(link)) {
            statusText.setText("Enter a valid HTTP or HTTPS link.");
            return;
        }
        if (tvAddress == null || tvToken == null) {
            autoSendAfterDiscovery = true;
            ensurePermissionAndDiscover();
            return;
        }

        sendButton.setEnabled(false);
        statusText.setText("Sending…");
        InetAddress address = tvAddress;
        int port = tvPort;
        String token = tvToken;

        networkExecutor.execute(() -> {
            HttpURLConnection connection = null;
            try {
                String host = address.getHostAddress();
                if (host.contains(":")) host = "[" + host.replace("%", "%25") + "]";
                URL endpoint = new URL("http://" + host + ":" + port + "/send");
                byte[] body = link.getBytes(StandardCharsets.UTF_8);
                connection = (HttpURLConnection) endpoint.openConnection();
                connection.setRequestMethod("POST");
                connection.setConnectTimeout(4000);
                connection.setReadTimeout(5000);
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "text/plain; charset=utf-8");
                connection.setRequestProperty("X-LinkCast-Token", token);
                connection.setFixedLengthStreamingMode(body.length);
                try (OutputStream out = connection.getOutputStream()) {
                    out.write(body);
                }
                int code = connection.getResponseCode();
                if (code != 202) throw new Exception("TV returned HTTP " + code);
                runOnUiThread(() -> {
                    sendButton.setEnabled(true);
                    statusText.setText("Sent to TV.");
                    Toast.makeText(this, "Sent to TV", Toast.LENGTH_SHORT).show();
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    sendButton.setEnabled(true);
                    statusText.setText("Send failed: " + error.getMessage());
                });
            } finally {
                if (connection != null) connection.disconnect();
            }
        });
    }

    @Override
    protected void onDestroy() {
        stopDiscovery();
        networkExecutor.shutdownNow();
        super.onDestroy();
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
