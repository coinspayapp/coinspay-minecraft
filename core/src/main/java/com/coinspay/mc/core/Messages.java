package com.coinspay.mc.core;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * messages_ru.yml and messages_en.yml from the plugin folder, over the defaults from the jar:
 * a key the admin deleted falls back to the built-in text.
 */
public final class Messages {
    private final Map<String, Map<String, String>> byLang = new HashMap<>();
    private final String forced;

    private Messages(String language) {
        this.forced = language;
    }

    public static Messages load(Path folder, String language) throws IOException {
        Messages m = new Messages(language);
        for (String lang : new String[]{"ru", "en"}) {
            String name = "messages_" + lang + ".yml";
            Map<String, String> texts = new HashMap<>(read(Messages.class.getResourceAsStream("/" + name)));
            Path file = Resources.copyDefault(folder, name);
            try (InputStream in = Files.newInputStream(file)) {
                texts.putAll(read(in));
            }
            m.byLang.put(lang, texts);
        }
        return m;
    }

    /** "ru" for a Russian client (or when forced in config), otherwise "en". */
    public String lang(Sender to) {
        if (!"auto".equals(forced)) return forced;
        String locale = to == null ? null : to.locale();
        return locale != null && locale.toLowerCase(Locale.ROOT).startsWith("ru") ? "ru" : "en";
    }

    public String get(Sender to, String key) {
        return raw(lang(to), key);
    }

    public String get(Sender to, String key, Map<String, String> values) {
        return Text.fill(get(to, key), values);
    }

    public String raw(String lang, String key) {
        String s = byLang.get(lang).get(key);
        if (s == null) s = byLang.get("en").get(key);
        return s != null ? s : key;
    }

    /** Sends prefix + text. */
    public void send(Sender to, String key, Map<String, String> values) {
        if (to != null) to.send(get(to, "prefix") + get(to, key, values));
    }

    public void send(Sender to, String key) {
        send(to, key, Collections.<String, String>emptyMap());
    }

    private static Map<String, String> read(InputStream in) throws IOException {
        Map<String, String> out = new HashMap<>();
        if (in == null) return out;
        try (Reader r = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            Object root = new Yaml(new SafeConstructor(new LoaderOptions())).load(r);
            if (root instanceof Map) {
                for (Map.Entry<?, ?> e : ((Map<?, ?>) root).entrySet()) {
                    if (e.getValue() != null) out.put(String.valueOf(e.getKey()), String.valueOf(e.getValue()));
                }
            }
        } catch (RuntimeException e) {
            throw new IOException("cannot read messages: " + e.getMessage(), e);
        }
        return out;
    }
}
