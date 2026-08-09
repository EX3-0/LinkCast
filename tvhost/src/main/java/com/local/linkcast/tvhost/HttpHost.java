package com.local.linkcast.tvhost;

import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class HttpHost {
    private static final String TAG = "LinkCastHttp";
    private static final int MAX_HEADER_BYTES = 16 * 1024;
    private static final int MAX_BODY_BYTES = 8192;

    private final int port;
    interface CommandListener {
        void onCommand(String url);
    }

    private final String token;
    private final CommandListener commandListener;
    private final ExecutorService clients = Executors.newCachedThreadPool();
    private final Object commandLock = new Object();
    private volatile boolean active;
    private volatile ServerSocket server;
    private String pendingUrl;

    HttpHost(int port, String token, CommandListener commandListener) {
        this.port = port;
        this.token = token;
        this.commandListener = commandListener;
    }

    void start() throws IOException {
        server = new ServerSocket(port);
        active = true;
        Thread acceptThread = new Thread(this::acceptLoop, "linkcast-accept");
        acceptThread.setDaemon(true);
        acceptThread.start();
    }

    void stop() {
        active = false;
        try {
            if (server != null) server.close();
        } catch (IOException ignored) { }
        clients.shutdownNow();
        synchronized (commandLock) {
            commandLock.notifyAll();
        }
    }

    private void acceptLoop() {
        while (active) {
            try {
                Socket socket = server.accept();
                socket.setSoTimeout(35_000);
                clients.execute(() -> handle(socket));
            } catch (IOException error) {
                if (active) Log.e(TAG, "Accept failed", error);
            }
        }
    }

    private void handle(Socket socket) {
        try (Socket connection = socket) {
            Request request = readRequest(connection.getInputStream());
            if (request == null) return;
            String path = request.target.split("\\?", 2)[0];

            if ("OPTIONS".equals(request.method)) {
                respond(connection, 204, "No Content", "", "text/plain");
                return;
            }

            if ("GET".equals(request.method) && "/status".equals(path)) {
                respond(connection, 200, "OK", "{\"ready\":true}", "application/json");
                return;
            }

            if ("GET".equals(request.method) && "/next".equals(path)) {
                InetAddress remote = connection.getInetAddress();
                if (remote == null || !remote.isLoopbackAddress()) {
                    respond(connection, 403, "Forbidden", "Loopback only", "text/plain");
                    return;
                }
                String next = awaitCommand(25_000);
                if (next == null) {
                    respond(connection, 204, "No Content", "", "text/plain");
                } else {
                    respond(connection, 200, "OK", next, "text/plain; charset=utf-8");
                }
                return;
            }

            if ("POST".equals(request.method) && "/send".equals(path)) {
                String supplied = request.headers.get("x-linkcast-token");
                if (!constantTimeEquals(token, supplied)) {
                    respond(connection, 401, "Unauthorized", "Bad token", "text/plain");
                    return;
                }
                String url = new String(request.body, StandardCharsets.UTF_8).trim();
                if (!validHttpUrl(url)) {
                    respond(connection, 400, "Bad Request", "Invalid HTTP(S) URL", "text/plain");
                    return;
                }
                synchronized (commandLock) {
                    pendingUrl = url;
                    commandLock.notifyAll();
                }
                try {
                    commandListener.onCommand(url);
                } catch (RuntimeException error) {
                    Log.w(TAG, "Could not deliver browser command", error);
                }
                respond(connection, 202, "Accepted", "Queued", "text/plain");
                return;
            }

            respond(connection, 404, "Not Found", "Not found", "text/plain");
        } catch (Exception error) {
            Log.w(TAG, "Client request failed", error);
        }
    }

    private String awaitCommand(long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        synchronized (commandLock) {
            while (active && pendingUrl == null) {
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0) break;
                commandLock.wait(remaining);
            }
            String result = pendingUrl;
            pendingUrl = null;
            return result;
        }
    }

    private static boolean validHttpUrl(String value) {
        if (value.isEmpty() || value.length() > MAX_BODY_BYTES) return false;
        try {
            URI uri = new URI(value);
            String scheme = uri.getScheme();
            return scheme != null
                    && ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))
                    && uri.getHost() != null && !uri.getHost().isEmpty();
        } catch (Exception ignored) {
            return false;
        }
    }

    private static boolean constantTimeEquals(String expected, String actual) {
        if (actual == null) return false;
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                actual.getBytes(StandardCharsets.UTF_8));
    }

    private static Request readRequest(InputStream input) throws IOException {
        ByteArrayOutputStream header = new ByteArrayOutputStream();
        int state = 0;
        while (header.size() < MAX_HEADER_BYTES) {
            int value = input.read();
            if (value < 0) return null;
            header.write(value);
            if ((state == 0 || state == 2) && value == '\r') state++;
            else if ((state == 1 || state == 3) && value == '\n') state++;
            else state = value == '\r' ? 1 : 0;
            if (state == 4) break;
        }
        if (state != 4) throw new IOException("Headers too large");

        String text = header.toString(StandardCharsets.ISO_8859_1.name());
        String[] lines = text.split("\\r\\n");
        if (lines.length == 0) throw new IOException("Missing request line");
        String[] first = lines[0].split(" ", 3);
        if (first.length < 2) throw new IOException("Malformed request line");

        Map<String, String> headers = new HashMap<>();
        for (int i = 1; i < lines.length; i++) {
            int colon = lines[i].indexOf(':');
            if (colon > 0) {
                headers.put(lines[i].substring(0, colon).trim().toLowerCase(Locale.ROOT),
                        lines[i].substring(colon + 1).trim());
            }
        }

        int length = 0;
        String rawLength = headers.get("content-length");
        if (rawLength != null) {
            try {
                length = Integer.parseInt(rawLength);
            } catch (NumberFormatException error) {
                throw new IOException("Bad content length");
            }
        }
        if (length < 0 || length > MAX_BODY_BYTES) throw new IOException("Body too large");
        byte[] body = new byte[length];
        int read = 0;
        while (read < length) {
            int amount = input.read(body, read, length - read);
            if (amount < 0) throw new IOException("Truncated body");
            read += amount;
        }
        return new Request(first[0].toUpperCase(Locale.ROOT), first[1], headers, body);
    }

    private static void respond(Socket socket, int code, String reason, String body,
                                String contentType) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        String head = "HTTP/1.1 " + code + " " + reason + "\r\n"
                + "Content-Type: " + contentType + "\r\n"
                + "Content-Length: " + bytes.length + "\r\n"
                + "Access-Control-Allow-Origin: *\r\n"
                + "Access-Control-Allow-Headers: X-LinkCast-Token, Content-Type\r\n"
                + "Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n"
                + "Cache-Control: no-store\r\n"
                + "Connection: close\r\n\r\n";
        OutputStream output = socket.getOutputStream();
        output.write(head.getBytes(StandardCharsets.ISO_8859_1));
        output.write(bytes);
        output.flush();
    }

    private static final class Request {
        final String method;
        final String target;
        final Map<String, String> headers;
        final byte[] body;

        Request(String method, String target, Map<String, String> headers, byte[] body) {
            this.method = method;
            this.target = target;
            this.headers = headers;
            this.body = body;
        }
    }
}
