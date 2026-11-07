package com.gathin.tvhome;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Base64;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;

import javax.net.ssl.SSLSocketFactory;

/**
 * home_panel: the few Home Assistant calls the panel makes. REST for states, forecasts and snapshots, and one
 * short websocket exchange (camera/stream) for the camera's HLS address. Blocking: call from a worker thread.
 * The token never leaves the Authorization header / auth message and is never logged.
 */
final class HaClient {
    private static final int CONNECT_MS = 4000;
    private static final int READ_MS = 10000;

    private final String base;
    private final String token;

    HaClient(String baseUrl, String token) {
        String b = baseUrl == null ? "" : baseUrl.trim();
        while (b.endsWith("/")) b = b.substring(0, b.length() - 1);
        this.base = b;
        this.token = token == null ? "" : token.trim();
    }

    /** Home Assistant answered, but not with 2xx (an unknown entity, a bad token). */
    static final class HttpError extends IOException {
        HttpError(String m) {
            super(m);
        }
    }

    String base() {
        return base;
    }

    JSONObject state(String entityId) throws Exception {
        return new JSONObject(new String(request("GET", "/api/states/" + entityId, null), StandardCharsets.UTF_8));
    }

    /** weather.get_forecasts for one entity; type hourly or daily. Returns the forecast list's JSON object. */
    JSONObject forecast(String entityId, String type) throws Exception {
        String body = new JSONObject().put("entity_id", entityId).put("type", type).toString();
        JSONObject r = new JSONObject(new String(request("POST",
                "/api/services/weather/get_forecasts?return_response", body), StandardCharsets.UTF_8));
        JSONObject sr = r.optJSONObject("service_response");
        return sr == null ? null : sr.optJSONObject(entityId);
    }

    Bitmap snapshot(String entityId, int maxWidth) throws Exception {
        byte[] jpg = request("GET", "/api/camera_proxy/" + entityId, null);
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(jpg, 0, jpg.length, o);
        int sample = 1;
        while (o.outWidth / (sample * 2) >= maxWidth) sample *= 2;
        BitmapFactory.Options d = new BitmapFactory.Options();
        d.inSampleSize = sample;
        return BitmapFactory.decodeByteArray(jpg, 0, jpg.length, d);
    }

    private byte[] request(String method, String path, String body) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(base + path).openConnection();
        try {
            c.setConnectTimeout(CONNECT_MS);
            c.setReadTimeout(READ_MS);
            c.setRequestMethod(method);
            c.setUseCaches(false);
            c.setRequestProperty("Authorization", "Bearer " + token);
            if (body != null) {
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/json");
                try (OutputStream os = c.getOutputStream()) {
                    os.write(body.getBytes(StandardCharsets.UTF_8));
                }
            }
            int code = c.getResponseCode();
            if (code / 100 != 2) throw new HttpError("HTTP " + code + " for " + path.replaceAll("\\?.*", ""));
            try (InputStream in = c.getInputStream()) {
                return readAll(in);
            }
        } finally {
            c.disconnect();
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(16384);
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        return out.toByteArray();
    }

    // ---- websocket: just enough of RFC 6455 for one request/answer ----

    /** The camera's HLS playlist as a full URL (it carries its own short-lived access token), or null. */
    String hlsUrl(String cameraEntity) throws Exception {
        URL u = new URL(base);
        boolean tls = "https".equalsIgnoreCase(u.getProtocol());
        int port = u.getPort() > 0 ? u.getPort() : (tls ? 443 : 80);
        Socket s = tls ? SSLSocketFactory.getDefault().createSocket() : new Socket();
        try {
            s.connect(new InetSocketAddress(u.getHost(), port), CONNECT_MS);
            s.setSoTimeout(READ_MS);
            InputStream in = s.getInputStream();
            OutputStream out = s.getOutputStream();
            byte[] key = new byte[16];
            new SecureRandom().nextBytes(key);
            String hs = "GET /api/websocket HTTP/1.1\r\nHost: " + u.getHost() + ":" + port
                    + "\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Version: 13\r\nSec-WebSocket-Key: "
                    + Base64.encodeToString(key, Base64.NO_WRAP) + "\r\n\r\n";
            out.write(hs.getBytes(StandardCharsets.US_ASCII));
            out.flush();
            String head = readHead(in);
            if (!head.startsWith("HTTP/1.1 101")) throw new IOException("websocket upgrade refused");
            expectType(readText(in, out), "auth_required");
            writeText(out, new JSONObject().put("type", "auth").put("access_token", token).toString());
            expectType(readText(in, out), "auth_ok");
            writeText(out, new JSONObject().put("id", 1).put("type", "camera/stream")
                    .put("entity_id", cameraEntity).put("format", "hls").toString());
            JSONObject r = new JSONObject(readText(in, out));
            if (!r.optBoolean("success")) throw new IOException("camera/stream failed");
            JSONObject res = r.optJSONObject("result");
            String url = res == null ? null : res.optString("url", null);
            if (url == null || url.isEmpty()) return null;
            return url.startsWith("http") ? url : base + url;
        } finally {
            try { s.close(); } catch (IOException ignored) { }
        }
    }

    private static void expectType(String msg, String type) throws Exception {
        String t = new JSONObject(msg).optString("type");
        if (!type.equals(t)) throw new IOException("websocket: expected " + type + ", got " + t);
    }

    private static String readHead(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        int c, state = 0;
        while ((c = in.read()) >= 0 && sb.length() < 8192) {
            sb.append((char) c);
            state = (c == '\r' && (state == 0 || state == 2)) ? state + 1 : (c == '\n' && (state == 1 || state == 3)) ? state + 1 : 0;
            if (state == 4) return sb.toString();
        }
        throw new IOException("websocket: no handshake answer");
    }

    private static void readFully(InputStream in, byte[] b) throws IOException {
        int off = 0;
        while (off < b.length) {
            int n = in.read(b, off, b.length - off);
            if (n < 0) throw new IOException("websocket closed");
            off += n;
        }
    }

    /** The next whole text message; answers pings, ignores pongs. */
    private static String readText(InputStream in, OutputStream out) throws IOException {
        ByteArrayOutputStream msg = new ByteArrayOutputStream();
        while (true) {
            byte[] h = new byte[2];
            readFully(in, h);
            boolean fin = (h[0] & 0x80) != 0;
            int op = h[0] & 0x0F;
            boolean masked = (h[1] & 0x80) != 0;
            long len = h[1] & 0x7F;
            if (len == 126) {
                byte[] e = new byte[2];
                readFully(in, e);
                len = ((e[0] & 0xFF) << 8) | (e[1] & 0xFF);
            } else if (len == 127) {
                byte[] e = new byte[8];
                readFully(in, e);
                len = 0;
                for (byte x : e) len = (len << 8) | (x & 0xFF);
            }
            if (len > 4 * 1024 * 1024) throw new IOException("websocket frame too large");
            byte[] mask = null;
            if (masked) {
                mask = new byte[4];
                readFully(in, mask);
            }
            byte[] p = new byte[(int) len];
            readFully(in, p);
            if (mask != null) for (int i = 0; i < p.length; i++) p[i] ^= mask[i & 3];
            if (op == 8) throw new IOException("websocket closed by server");
            if (op == 9) {
                writeFrame(out, 0xA, p);
                continue;
            }
            if (op == 0xA) continue;
            msg.write(p);
            if (fin) return new String(msg.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static void writeText(OutputStream out, String s) throws IOException {
        writeFrame(out, 1, s.getBytes(StandardCharsets.UTF_8));
    }

    private static void writeFrame(OutputStream out, int op, byte[] p) throws IOException {
        ByteArrayOutputStream f = new ByteArrayOutputStream(p.length + 14);
        f.write(0x80 | op);
        if (p.length < 126) {
            f.write(0x80 | p.length);
        } else if (p.length < 65536) {
            f.write(0x80 | 126);
            f.write(p.length >> 8);
            f.write(p.length & 0xFF);
        } else {
            f.write(0x80 | 127);
            for (int i = 7; i >= 0; i--) f.write((int) (((long) p.length >> (8 * i)) & 0xFF));
        }
        byte[] mask = new byte[4];
        new SecureRandom().nextBytes(mask);
        f.write(mask);
        for (int i = 0; i < p.length; i++) f.write(p[i] ^ mask[i & 3]);
        out.write(f.toByteArray());
        out.flush();
    }
}
