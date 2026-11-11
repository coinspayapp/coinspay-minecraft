package com.coinspay.mc.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * The CoinsPay merchant API over HttpURLConnection: Java 8 has no java.net.http, and every
 * server platform has HttpURLConnection. Blocking: call it off the server thread.
 */
public final class ApiClient {
    private static final int TIMEOUT_MS = 15_000;
    private static final int MAX_BODY = 2 * 1024 * 1024;

    private final String base;
    private final String key;
    private final String userAgent;

    public ApiClient(String baseUrl, String key, String userAgent) {
        this.base = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.key = key;
        this.userAgent = userAgent;
    }

    /** GET /shop: the shop name and the key mode and access. */
    public ShopInfo shop() throws ApiException {
        JsonObject o = request("GET", "/shop", null, null);
        JsonObject shop = o.getAsJsonObject("shop");
        JsonObject k = o.getAsJsonObject("key");
        return new ShopInfo(Invoice.string(shop, "name"), Invoice.bool(k, "test"), Invoice.string(k, "access"));
    }

    /** POST /invoices. The Idempotency-Key makes a retry return the same invoice. */
    public Invoice createInvoice(JsonObject body, String idempotencyKey) throws ApiException {
        ApiException last = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                return unwrap(request("POST", "/invoices", body, idempotencyKey));
            } catch (ApiException e) {
                if (!e.retryable() || e.rateLimited()) throw e;
                last = e;
                sleep(1000L * (attempt + 1));
            }
        }
        throw last;
    }

    public Invoice invoice(String id) throws ApiException {
        return unwrap(request("GET", "/invoices/" + encode(id), null, null));
    }

    /** The newest paid invoices of the shop, one page. */
    public List<Invoice> paidInvoices(int limit) throws ApiException {
        JsonObject o = request("GET", "/invoices?status=paid&limit=" + limit, null, null);
        List<Invoice> out = new ArrayList<>();
        JsonArray list = o.getAsJsonArray("invoices");
        if (list != null) for (JsonElement e : list) out.add(Invoice.fromJson(e.getAsJsonObject()));
        return out;
    }

    /** Single-invoice answers are {"invoice": {...}}. */
    private static Invoice unwrap(JsonObject o) throws ApiException {
        JsonElement inv = o.get("invoice");
        if (inv == null || !inv.isJsonObject()) throw new ApiException(200, "BAD_RESPONSE", "no invoice in the response");
        return Invoice.fromJson(inv.getAsJsonObject());
    }

    private JsonObject request(String method, String path, JsonObject body, String idempotencyKey) throws ApiException {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(base + path).openConnection();
            c.setRequestMethod(method);
            c.setConnectTimeout(TIMEOUT_MS);
            c.setReadTimeout(TIMEOUT_MS);
            c.setInstanceFollowRedirects(false);
            c.setRequestProperty("Authorization", "Bearer " + key);
            c.setRequestProperty("Accept", "application/json");
            c.setRequestProperty("User-Agent", userAgent);
            if (idempotencyKey != null) c.setRequestProperty("Idempotency-Key", idempotencyKey);
            if (body != null) {
                byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/json");
                c.setFixedLengthStreamingMode(bytes.length);
                try (OutputStream out = c.getOutputStream()) {
                    out.write(bytes);
                }
            }
            int status = c.getResponseCode();
            String text = read(status >= 400 ? c.getErrorStream() : c.getInputStream());
            if (status >= 200 && status < 300) {
                JsonElement parsed = JsonParser.parseString(text);
                if (!parsed.isJsonObject()) throw new ApiException(status, "BAD_RESPONSE", "not a JSON object");
                return parsed.getAsJsonObject();
            }
            throw error(status, text);
        } catch (IOException e) {
            throw new ApiException(e.getClass().getSimpleName() + ": " + e.getMessage(), e);
        } catch (RuntimeException e) {
            // A proxy page or a truncated body instead of JSON.
            throw new ApiException(0, "BAD_RESPONSE", e.getMessage());
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private static ApiException error(int status, String text) {
        String code = "HTTP_" + status;
        String message = text.length() > 200 ? text.substring(0, 200) : text;
        try {
            JsonObject err = JsonParser.parseString(text).getAsJsonObject().getAsJsonObject("error");
            if (err != null) {
                if (err.has("code")) code = err.get("code").getAsString();
                if (err.has("message")) message = err.get("message").getAsString();
            }
        } catch (RuntimeException ignored) {
            // Not our JSON: a proxy or Cloudflare page.
        }
        return new ApiException(status, code, message);
    }

    private static String read(InputStream in) throws IOException {
        if (in == null) return "";
        try (InputStream stream = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int n;
            while ((n = stream.read(buffer)) != -1) {
                out.write(buffer, 0, n);
                if (out.size() > MAX_BODY) throw new IOException("response too large");
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static String encode(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** GET /shop: the shop and the key that called. */
    public static final class ShopInfo {
        public final String name;
        public final boolean test;
        public final String access;

        public ShopInfo(String name, boolean test, String access) {
            this.name = name;
            this.test = test;
            this.access = access;
        }
    }
}
