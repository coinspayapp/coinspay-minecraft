package com.coinspay.mc.core;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** config.yml, parsed and checked. Problems go to {@link #problems}: the plugin logs them on load. */
public final class Settings {
    public static final String DEFAULT_URL = "https://coinspay.app/api/v1";
    private static final String ID = "[A-Za-z0-9_-]{1,32}";

    public final String apiUrl;
    public final String apiKey;
    public final String serverId;
    /** "auto", "ru" or "en". */
    public final String language;
    public final String currency;
    public final boolean buyerPaysFee;
    public final boolean buyerPaysNetwork;
    public final int lifetimeMinutes;
    public final int maxOpenPerPlayer;
    public final int cooldownSeconds;
    public final int pollSeconds;
    /** "gui" or "chat"; proxies always use chat. */
    public final String menu;
    public final boolean qrMap;
    public final String returnUrl;
    public final Map<String, PackageDef> packages;
    public final List<String> problems;

    private Settings(Map<String, Object> root) {
        List<String> problems = new ArrayList<>();
        Map<String, Object> api = map(root.get("api"));
        String url = str(api.get("url"), DEFAULT_URL).trim();
        if (!url.startsWith("https://") && !url.startsWith("http://")) {
            problems.add("api.url must start with https://");
            url = DEFAULT_URL;
        }
        apiUrl = url;
        apiKey = str(api.get("key"), "").trim();
        if (!configured()) problems.add("api.key is empty: create an \"Invoices only\" key in the CoinsPay dashboard");

        String server = str(root.get("server-id"), "main").trim();
        if (!server.matches(ID)) {
            problems.add("server-id may hold only letters, digits, _ and -, up to 32 characters");
            server = "main";
        }
        serverId = server;
        String lang = str(root.get("language"), "auto").trim().toLowerCase(Locale.ROOT);
        language = lang.equals("ru") || lang.equals("en") ? lang : "auto";
        currency = str(root.get("currency"), "USD").trim().toUpperCase(Locale.ROOT);
        buyerPaysFee = bool(root.get("buyer-pays-fee"), false);
        buyerPaysNetwork = bool(root.get("buyer-pays-network"), false);
        lifetimeMinutes = clamp(integer(root.get("invoice-lifetime-minutes"), 60), 15, 43200);
        maxOpenPerPlayer = clamp(integer(root.get("max-open-per-player"), 2), 1, 10);
        cooldownSeconds = clamp(integer(root.get("cooldown-seconds"), 10), 0, 3600);
        pollSeconds = clamp(integer(root.get("poll-seconds"), 10), 5, 300);
        menu = "chat".equalsIgnoreCase(str(root.get("menu"), "gui").trim()) ? "chat" : "gui";
        qrMap = bool(root.get("qr-map"), true);
        String ret = str(root.get("return-url"), "").trim();
        returnUrl = ret.startsWith("https://") || ret.startsWith("http://") ? ret : null;

        Map<String, PackageDef> packs = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : map(root.get("packages")).entrySet()) {
            PackageDef p = parsePackage(e.getKey(), map(e.getValue()), problems);
            if (p != null) packs.put(p.id, p);
        }
        packages = Collections.unmodifiableMap(packs);
        this.problems = Collections.unmodifiableList(problems);
    }

    public static Settings parse(Map<String, Object> root) {
        return new Settings(root == null ? Collections.<String, Object>emptyMap() : root);
    }

    public boolean configured() {
        return apiKey.startsWith("cpk_") && apiKey.length() > 10;
    }

    public boolean testKey() {
        return apiKey.startsWith("cpk_test_");
    }

    private PackageDef parsePackage(String id, Map<String, Object> m, List<String> problems) {
        String where = "packages." + id;
        if (!id.matches(ID)) {
            problems.add(where + ": the id may hold only letters, digits, _ and -, up to 32 characters");
            return null;
        }
        BigDecimal price;
        try {
            price = new BigDecimal(str(m.get("price"), "").trim());
        } catch (NumberFormatException e) {
            problems.add(where + ".price is not a number");
            return null;
        }
        if (price.signum() <= 0) {
            problems.add(where + ".price must be above zero");
            return null;
        }
        List<String> commands = list(m.get("commands"));
        if (commands.isEmpty()) {
            problems.add(where + ".commands is empty: nothing to deliver");
            return null;
        }
        long duration = Text.parseDuration(str(m.get("duration"), ""));
        if (duration < 0) {
            problems.add(where + ".duration: use 30d, 12h, 90m, 2w or 1mo");
            return null;
        }
        List<String> expire = list(m.get("expire-commands"));
        if (duration == 0 && !expire.isEmpty()) problems.add(where + ": expire-commands without duration never run");
        String cur = str(m.get("currency"), currency).trim().toUpperCase(Locale.ROOT);
        return new PackageDef(id, str(m.get("name"), id), list(m.get("description")), str(m.get("icon"), "EMERALD").trim(),
                price, cur, bool(m.get("require-online"), false), commands, duration, expire);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object o) {
        if (!(o instanceof Map)) return Collections.emptyMap();
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<Object, Object> e : ((Map<Object, Object>) o).entrySet()) out.put(String.valueOf(e.getKey()), e.getValue());
        return out;
    }

    private static List<String> list(Object o) {
        List<String> out = new ArrayList<>();
        if (o instanceof List) {
            for (Object item : (List<?>) o) if (item != null && !String.valueOf(item).trim().isEmpty()) out.add(String.valueOf(item));
        } else if (o != null && !String.valueOf(o).trim().isEmpty()) {
            out.add(String.valueOf(o));
        }
        return out;
    }

    private static String str(Object o, String fallback) {
        return o == null ? fallback : String.valueOf(o);
    }

    private static boolean bool(Object o, boolean fallback) {
        if (o instanceof Boolean) return (Boolean) o;
        if (o == null) return fallback;
        return "true".equalsIgnoreCase(String.valueOf(o).trim());
    }

    private static int integer(Object o, int fallback) {
        if (o instanceof Number) return ((Number) o).intValue();
        try {
            return o == null ? fallback : Integer.parseInt(String.valueOf(o).trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }
}
