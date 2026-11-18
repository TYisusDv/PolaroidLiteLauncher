package tv.polaroid.litehome;

import android.content.Context;
import android.content.SharedPreferences;
import android.view.KeyEvent;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.Charset;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Small, pairing-code-protected HTTP remote for the launcher or accessibility service. */
final class LocalRemoteServer {
    static final int PORT = 8765;

    interface ActionHandler {
        void onAction(String action);
    }

    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final String PAIRING_PREFS = "remote_pairing";
    private static final String SESSION_TOKEN_KEY = "session_token";
    private volatile String pin;
    private final ActionHandler actionHandler;
    private final SharedPreferences pairingPreferences;
    private final Context context;
    private volatile boolean running;
    private volatile Thread serverThread;
    private volatile String sessionToken = "";
    private volatile ServerSocket serverSocket;
    private volatile long blockedUntil;
    private int badPins;

    LocalRemoteServer(Context context, ActionHandler actionHandler) {
        this.context = context.getApplicationContext();
        this.actionHandler = actionHandler;
        pairingPreferences = context.getSharedPreferences(PAIRING_PREFS, Context.MODE_PRIVATE);
        sessionToken = pairingPreferences.getString(SESSION_TOKEN_KEY, "");
        this.pin = makePin();
    }

    String getPin() { return pin; }

    private String loadRemotePage() throws IOException {
        try (InputStream input = context.getAssets().open("remote_page.html")) {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int read;
            while ((read = input.read(buffer)) != -1) output.write(buffer, 0, read);
            return new String(output.toByteArray(), UTF8);
        }
    }

    static String getLocalIpv4() {
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces != null && interfaces.hasMoreElements()) {
                NetworkInterface network = interfaces.nextElement();
                if (!network.isUp() || network.isLoopback()) continue;
                Enumeration<InetAddress> addresses = network.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress address = addresses.nextElement();
                    if (address instanceof Inet4Address && address.isSiteLocalAddress()) {
                        return address.getHostAddress();
                    }
                }
            }
        } catch (Exception ignored) { }
        return null;
    }

    synchronized void start() {
        if (running) return;
        running = true;
        Thread thread = new Thread(new Runnable() {
            @Override public void run() { serve(); }
        }, "polaroid-local-remote");
        thread.setDaemon(true);
        serverThread = thread;
        thread.start();
    }

    void stop() {
        Thread thread;
        synchronized (this) {
        running = false;
        thread = serverThread;
        serverThread = null;
        ServerSocket socket = serverSocket;
        serverSocket = null;
        if (socket != null) {
            try { socket.close(); } catch (IOException ignored) { }
        }
        }
        if (thread != null && thread != Thread.currentThread()) {
            thread.interrupt();
            try { thread.join(1400); }
            catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
        }
    }

    boolean restart() {
        stop();
        start();
        long deadline = android.os.SystemClock.uptimeMillis() + 2500;
        while (android.os.SystemClock.uptimeMillis() < deadline) {
            ServerSocket socket = serverSocket;
            if (running && socket != null && socket.isBound() && !socket.isClosed()) return true;
            try { Thread.sleep(40); }
            catch (InterruptedException ignored) { Thread.currentThread().interrupt(); return false; }
        }
        return false;
    }

    private void serve() {
        while (running) {
            ServerSocket listener = null;
            try {
                listener = new ServerSocket(PORT, 4, InetAddress.getByName("0.0.0.0"));
                serverSocket = listener;
                listener.setSoTimeout(1000);
                while (running) {
                    try (Socket client = listener.accept()) {
                        client.setSoTimeout(3000);
                        handle(client);
                    } catch (java.net.SocketTimeoutException ignored) {
                        // Periodically check whether this server should stop.
                    } catch (IOException ignored) {
                        if (running) continue;
                    }
                }
            } catch (IOException ignored) {
                // Retry if the foreground activity still owns the port during a handoff.
            } finally {
                if (serverSocket == listener) serverSocket = null;
                if (listener != null) {
                    try { listener.close(); } catch (IOException ignored) { }
                }
            }
            if (running) {
                try { Thread.sleep(400); }
                catch (InterruptedException ignored) { if (!running) return; }
            }
        }
    }

    private void handle(Socket client) throws IOException {
        InputStream input = client.getInputStream();
        String requestLine = readLine(input, 2048);
        if (requestLine == null) return;
        String[] requestParts = requestLine.split(" ");
        if (requestParts.length < 2) {
            respond(client.getOutputStream(), 400, "text/plain; charset=utf-8", "Bad request");
            return;
        }
        String method = requestParts[0];
        String path = requestParts[1];
        Map<String, String> headers = new HashMap<>();
        String line;
        while ((line = readLine(input, 4096)) != null && line.length() > 0) {
            int colon = line.indexOf(':');
            if (colon > 0) {
                headers.put(line.substring(0, colon).trim().toLowerCase(),
                        line.substring(colon + 1).trim());
            }
        }
        int contentLength = 0;
        try { contentLength = Integer.parseInt(headers.get("content-length")); }
        catch (Exception ignored) { }
        if (contentLength < 0 || contentLength > 2048) {
            respond(client.getOutputStream(), 413, "text/plain; charset=utf-8", "Too large");
            return;
        }
        byte[] bodyBytes = readBytes(input, contentLength);
        String body = new String(bodyBytes, UTF8);
        OutputStream output = client.getOutputStream();

        if ("GET".equals(method) && ("/".equals(path) || "/index.html".equals(path))) {
            respond(output, 200, "text/html; charset=utf-8", loadRemotePage());
        } else if ("POST".equals(method) && "/pair".equals(path)) {
            pair(output, body);
        } else if ("POST".equals(method) && "/resume".equals(path)) {
            resume(output, body);
        } else if ("POST".equals(method) && "/key".equals(path)) {
            sendKey(output, body);
        } else if ("POST".equals(method) && "/unpair".equals(path)) {
            unpair(output, body);
        } else {
            respond(output, 404, "text/plain; charset=utf-8", "Not found");
        }
    }

    private synchronized void pair(OutputStream output, String body) throws IOException {
        long now = System.currentTimeMillis();
        if (now < blockedUntil) {
            respond(output, 429, "application/json; charset=utf-8", "{\"error\":\"wait\"}");
            return;
        }
        String suppliedPin = formValue(body, "pin");
        if (pin.equals(suppliedPin)) {
            badPins = 0;
            String existingToken = pairingPreferences.getString(SESSION_TOKEN_KEY, "");
            if (existingToken.length() == 0) {
                existingToken = UUID.randomUUID().toString().replace("-", "");
                pairingPreferences.edit().putString(SESSION_TOKEN_KEY, existingToken).apply();
            }
            sessionToken = existingToken;
            respond(output, 200, "application/json; charset=utf-8",
                    "{\"token\":\"" + sessionToken + "\"}");
            pin = makePin();
            HomeActivity.refreshRemoteServiceState();
        } else {
            badPins++;
            if (badPins >= 5) {
                badPins = 0;
                blockedUntil = now + 30000;
            }
            respond(output, 401, "application/json; charset=utf-8", "{\"error\":\"pin\"}");
        }
    }

    private synchronized void resume(OutputStream output, String body) throws IOException {
        if (isValidToken(formValue(body, "token"))) {
            respond(output, 200, "application/json; charset=utf-8", "{\"ok\":true}");
        } else {
            respond(output, 401, "application/json; charset=utf-8", "{\"error\":\"pair\"}");
        }
    }

    private synchronized void unpair(OutputStream output, String body) throws IOException {
        if (!isValidToken(formValue(body, "token"))) {
            respond(output, 401, "application/json; charset=utf-8", "{\"error\":\"pair\"}");
            return;
        }
        sessionToken = "";
        pairingPreferences.edit().remove(SESSION_TOKEN_KEY).apply();
        respond(output, 200, "application/json; charset=utf-8", "{\"ok\":true}");
    }

    private boolean isValidToken(String suppliedToken) {
        String validToken = sessionToken;
        return validToken.length() > 0 && suppliedToken != null
                && MessageDigest.isEqual(validToken.getBytes(UTF8), suppliedToken.getBytes(UTF8));
    }

    private void sendKey(OutputStream output, String body) throws IOException {
        String key = formValue(body, "key");
        if (!isValidToken(formValue(body, "token"))) {
            respond(output, 401, "application/json; charset=utf-8", "{\"error\":\"pair\"}");
            return;
        }
        if (!isSupportedAction(key)) {
            respond(output, 400, "application/json; charset=utf-8", "{\"error\":\"key\"}");
            return;
        }
        if ("text".equals(key)) {
            String value = formValue(body, "text");
            if (value == null || value.length() == 0 || value.length() > 400) {
                respond(output, 400, "application/json; charset=utf-8", "{\"error\":\"text\"}");
                return;
            }
            actionHandler.onAction("text:" + value);
        } else {
            actionHandler.onAction(key);
        }
        respond(output, 200, "application/json; charset=utf-8", "{\"ok\":true}");
    }

    private boolean isSupportedAction(String action) {
        return "up".equals(action) || "down".equals(action)
                || "left".equals(action) || "right".equals(action)
                || "ok".equals(action) || "back".equals(action)
                || "home".equals(action) || "volume_up".equals(action)
                || "volume_down".equals(action) || "mute".equals(action)
                || "previous".equals(action) || "next".equals(action)
                || "play_pause".equals(action) || "apps".equals(action)
                || "voice".equals(action) || "power".equals(action)
                || "text".equals(action);
    }

    private String formValue(String body, String name) {
        String[] values = body.split("&");
        for (String value : values) {
            int equals = value.indexOf('=');
            if (equals < 0) continue;
            try {
                if (name.equals(URLDecoder.decode(value.substring(0, equals), "UTF-8"))) {
                    return URLDecoder.decode(value.substring(equals + 1), "UTF-8");
                }
            } catch (Exception ignored) { }
        }
        return null;
    }

    private String readLine(InputStream input, int maximum) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        int value;
        while ((value = input.read()) != -1) {
            if (value == '\n') break;
            if (value != '\r') bytes.write(value);
            if (bytes.size() > maximum) throw new IOException("Line too long");
        }
        if (value == -1 && bytes.size() == 0) return null;
        return new String(bytes.toByteArray(), Charset.forName("ISO-8859-1"));
    }

    private byte[] readBytes(InputStream input, int length) throws IOException {
        byte[] data = new byte[length];
        int offset = 0;
        while (offset < length) {
            int read = input.read(data, offset, length - offset);
            if (read < 0) throw new IOException("Unexpected end of request");
            offset += read;
        }
        return data;
    }

    private void respond(OutputStream output, int code, String type, String body) throws IOException {
        byte[] bytes = body.getBytes(UTF8);
        String status = code == 200 ? "OK" : code == 400 ? "Bad Request"
                : code == 401 ? "Unauthorized" : code == 404 ? "Not Found"
                : code == 413 ? "Payload Too Large" : "Too Many Requests";
        String headers = "HTTP/1.1 " + code + " " + status + "\r\n"
                + "Content-Type: " + type + "\r\n"
                + "Content-Length: " + bytes.length + "\r\n"
                + "Cache-Control: no-store\r\n"
                + "X-Content-Type-Options: nosniff\r\n"
                + "Connection: close\r\n\r\n";
        output.write(headers.getBytes(Charset.forName("ISO-8859-1")));
        output.write(bytes);
        output.flush();
    }

    private String makePin() {
        SecureRandom random = new SecureRandom();
        return String.valueOf(100000 + random.nextInt(900000));
    }


}
