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
    private final String pin;
    private final ActionHandler actionHandler;
    private final SharedPreferences pairingPreferences;
    private volatile boolean running;
    private volatile Thread serverThread;
    private volatile String sessionToken = "";
    private volatile ServerSocket serverSocket;
    private volatile long blockedUntil;
    private int badPins;

    LocalRemoteServer(Context context, ActionHandler actionHandler) {
        this.actionHandler = actionHandler;
        pairingPreferences = context.getSharedPreferences(PAIRING_PREFS, Context.MODE_PRIVATE);
        sessionToken = pairingPreferences.getString(SESSION_TOKEN_KEY, "");
        this.pin = makePin();
    }

    String getPin() { return pin; }

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
            respond(output, 200, "text/html; charset=utf-8", REMOTE_PAGE);
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
        actionHandler.onAction(key);
        respond(output, 200, "application/json; charset=utf-8", "{\"ok\":true}");
    }

    private boolean isSupportedAction(String action) {
        return "up".equals(action) || "down".equals(action)
                || "left".equals(action) || "right".equals(action)
                || "ok".equals(action) || "back".equals(action)
                || "home".equals(action) || "volume_up".equals(action)
                || "volume_down".equals(action) || "mute".equals(action)
                || "previous".equals(action) || "next".equals(action)
                || "play_pause".equals(action);
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

    private static final String REMOTE_PAGE = "<!doctype html><html lang='es'><head>"
            + "<meta charset='utf-8'><meta name='viewport' content='width=device-width,initial-scale=1,maximum-scale=1,user-scalable=no'>"
            + "<meta name='theme-color' content='#090c13'><title>Polaroid · Control remoto</title>"
            + "<style>"
            + "*{box-sizing:border-box}body{margin:0;min-height:100vh;background:radial-gradient(ellipse at 50% -10%,#202b42 0,#0d1119 44%,#090c13 100%);color:#f5f7fb;font:16px -apple-system,BlinkMacSystemFont,sans-serif;display:flex;justify-content:center;align-items:center;padding:20px}"
            + ".shell{width:min(100%,390px)}.card{background:rgba(16,21,31,.94);border:1px solid rgba(211,222,244,.09);border-radius:30px;padding:23px 20px;box-shadow:0 24px 70px #0008}.brand{font-size:11px;font-weight:800;letter-spacing:.15em;color:#9caac0;margin:0 0 12px 5px}h1{font-size:24px;line-height:1.1;margin:0 0 7px}.sub{color:#9ba7ba;font-size:13px;margin:0 0 19px;line-height:1.45}"
            + ".pinrow{display:flex;gap:9px}input{min-width:0;flex:1;border:1px solid #303a4d;border-radius:14px;background:#0c111a;color:#fff;padding:14px;font-size:20px;letter-spacing:.22em;text-align:center;outline:none}button{font:inherit;color:#f8f9fc;border:0;touch-action:manipulation;cursor:pointer;-webkit-tap-highlight-color:transparent;transition:transform .12s,background .12s}button:active{transform:scale(.94)}.pair{background:#e9edf5;color:#111722;border-radius:14px;padding:0 17px;font-weight:750}.msg{min-height:19px;margin:10px 2px 0;color:#ffb6ba;font-size:12px}.remote{display:none}.remote.show{display:block}.pairbox.hide{display:none}.head{display:flex;align-items:center;justify-content:space-between;margin-bottom:20px}.status{font-size:13px;color:#a8b3c4;margin:6px 0 0}.dot{display:inline-block;width:9px;height:9px;border-radius:50%;background:#26c276;margin-right:7px}.power{width:54px;height:54px;border-radius:50%;background:#b8322b;font-size:24px}.tools{display:flex;gap:10px;margin-bottom:17px}.tool{flex:1;height:56px;border-radius:50px;background:#191f2b;font-size:24px}.pad{width:min(76vw,282px);height:min(76vw,282px);max-width:282px;max-height:282px;margin:0 auto 18px;border-radius:50%;position:relative;background:linear-gradient(145deg,#1e2533,#141a25);border:1px solid #343c4b;box-shadow:inset 0 1px 0 #ffffff0d,0 16px 32px #0005}.key{position:absolute;width:68px;height:62px;border-radius:19px;background:transparent;font-size:30px;font-weight:500}.key:active{background:#303a4d}.up{left:calc(50% - 34px);top:9px}.down{left:calc(50% - 34px);bottom:9px}.left{left:9px;top:calc(50% - 31px)}.right{right:9px;top:calc(50% - 31px)}.ok{left:calc(50% - 40px);top:calc(50% - 38px);width:80px;height:76px;border-radius:50%;background:#111721;font-size:16px;font-weight:700}.bar{display:flex;border-radius:50px;background:#191f2b;overflow:hidden;margin:0 0 11px}.bar button{flex:1;height:54px;background:transparent;font-size:20px}.bar button:active,.tool:active{background:#303a4d}.tip{text-align:center;color:#7f8ba0;font-size:11px;margin:13px 0 0}.reconnect{display:block;margin:14px auto 0;background:transparent;color:#8894a9;font-size:12px;text-decoration:underline}"
            + "</style></head><body><main class='shell'><p class='brand'>POLAROID · LITE HOME</p><section class='card'>"
            + "<div class='pairbox' id='pairbox'><h1>Conecta tu control</h1><p class='sub'>Escribe el PIN que aparece en la pantalla de tu tele. La sesión se conserva aunque la página se reinicie o cambie el PIN.</p><div class='pinrow'><input id='pin' inputmode='numeric' maxlength='6' autocomplete='one-time-code' placeholder='••••••'><button class='pair' id='pair'>Conectar</button></div><p class='msg' id='msg'></p></div>"
            + "<div class='remote' id='remote'><div class='head'><div><h1>Control remoto</h1><p class='status'><span class='dot'></span>Conectado a Polaroid</p></div></div><div class='tools'><button class='tool' data-key='back' aria-label='Atrás'>← Atrás</button><button class='tool' data-key='home' aria-label='Inicio'>⌂ Inicio</button></div><div class='pad'><button class='key up' data-key='up' aria-label='Arriba'>⌃</button><button class='key left' data-key='left' aria-label='Izquierda'>‹</button><button class='key ok' data-key='ok'>OK</button><button class='key right' data-key='right' aria-label='Derecha'>›</button><button class='key down' data-key='down' aria-label='Abajo'>⌄</button></div><div class='bar'><button data-key='volume_down' aria-label='Bajar volumen'>−</button><button data-key='mute' aria-label='Silenciar'>◖×</button><button data-key='volume_up' aria-label='Subir volumen'>＋</button></div><div class='bar'><button data-key='previous' aria-label='Anterior'>◀◀</button><button data-key='play_pause' aria-label='Reproducir o pausar'>▶Ⅱ</button><button data-key='next' aria-label='Siguiente'>▶▶</button></div><p class='tip'>Flechas, volumen y reproducción · Atrás e Inicio</p><button class='reconnect' id='forget'>Desconectar este teléfono</button></div>"
            + "</section></main><script>var pairbox=document.getElementById('pairbox'),remote=document.getElementById('remote'),msg=document.getElementById('msg');function readToken(){try{return localStorage.getItem('polaroidRemoteToken')||''}catch(e){return ''}}var token=readToken();function post(path,data){var body=Object.keys(data).map(function(k){return encodeURIComponent(k)+'='+encodeURIComponent(data[k])}).join('&');return fetch(path,{method:'POST',headers:{'Content-Type':'application/x-www-form-urlencoded'},body:body})}function showRemote(){pairbox.className='pairbox hide';remote.className='remote show'}function clearSession(message){token='';try{localStorage.removeItem('polaroidRemoteToken')}catch(e){}remote.className='remote';pairbox.className='pairbox';msg.textContent=message||''}function pair(){var pin=document.getElementById('pin').value.replace(/\\D/g,'');post('/pair',{pin:pin}).then(function(r){if(!r.ok)throw 0;return r.json()}).then(function(d){token=d.token;try{localStorage.setItem('polaroidRemoteToken',token)}catch(e){}showRemote();msg.textContent=''}).catch(function(){msg.textContent='PIN incorrecto. Revisa el código de la TV.'})}document.getElementById('pair').onclick=pair;document.getElementById('pin').onkeydown=function(e){if(e.key==='Enter')pair()};document.querySelectorAll('[data-key]').forEach(function(b){b.onclick=function(){post('/key',{token:token,key:b.getAttribute('data-key')}).then(function(r){if(r.status===401)clearSession('La sesión venció. Empareja otra vez con el PIN de la TV.')}).catch(function(){msg.textContent='No hay conexión con la TV.'})}});document.getElementById('forget').onclick=function(){post('/unpair',{token:token}).catch(function(){});clearSession('Sesión desconectada.')};if(token){post('/resume',{token:token}).then(function(r){if(r.ok)showRemote();else clearSession('Empareja con el PIN actual de la TV.')}).catch(function(){clearSession('No hay conexión con la TV.')})}</script></body></html>";
}
