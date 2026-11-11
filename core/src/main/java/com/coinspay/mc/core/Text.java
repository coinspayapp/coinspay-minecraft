package com.coinspay.mc.core;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Small text helpers shared by every platform. */
public final class Text {
    private static final Pattern CODES = Pattern.compile("(?i)[&§][0-9a-fk-orx]");
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([a-z_]+)}");
    private static final Pattern DURATION = Pattern.compile("(?i)^(\\d+)\\s*(s|m|h|d|w|mo)$");

    private Text() {
    }

    public static String strip(String s) {
        return s == null ? "" : CODES.matcher(s).replaceAll("");
    }

    /** Replaces {key} with values; unknown keys stay as they are. */
    public static String fill(String template, Map<String, String> values) {
        if (template == null) return "";
        Matcher m = PLACEHOLDER.matcher(template);
        StringBuffer out = new StringBuffer();
        while (m.find()) {
            String value = values.get(m.group(1));
            m.appendReplacement(out, Matcher.quoteReplacement(value != null ? value : m.group()));
        }
        m.appendTail(out);
        return out.toString();
    }

    /** "30d", "12h", "90m", "2w", "1mo" (30 days); 0 for empty or "0". -1 for an invalid value. */
    public static long parseDuration(String s) {
        if (s == null) return 0;
        String v = s.trim();
        if (v.isEmpty() || v.equals("0")) return 0;
        Matcher m = DURATION.matcher(v);
        if (!m.matches()) return -1;
        long n = Long.parseLong(m.group(1));
        String unit = m.group(2).toLowerCase(Locale.ROOT);
        long ms;
        switch (unit) {
            case "s": ms = 1000L; break;
            case "m": ms = 60_000L; break;
            case "h": ms = 3_600_000L; break;
            case "d": ms = 86_400_000L; break;
            case "w": ms = 7 * 86_400_000L; break;
            default: ms = 30 * 86_400_000L; break;
        }
        return n * ms;
    }

    /** A short human duration in the given language: "29 дн. 3 ч" or "29d 3h". */
    public static String duration(long ms, boolean russian) {
        long minutes = Math.max(0, ms) / 60_000L;
        long days = minutes / 1440;
        long hours = (minutes % 1440) / 60;
        long mins = minutes % 60;
        StringBuilder sb = new StringBuilder();
        if (days > 0) sb.append(days).append(russian ? " дн." : "d");
        if (hours > 0) sb.append(sb.length() > 0 ? " " : "").append(hours).append(russian ? " ч" : "h");
        if (days == 0 && (mins > 0 || sb.length() == 0)) sb.append(sb.length() > 0 ? " " : "").append(mins).append(russian ? " мин" : "m");
        return sb.toString();
    }

    /** 4.9 stays "4.9", 5 stays "5", 4.990000 becomes "4.99". */
    public static String amount(BigDecimal value) {
        if (value == null) return "0";
        BigDecimal v = value.stripTrailingZeros();
        return v.scale() < 0 ? v.setScale(0).toPlainString() : v.toPlainString();
    }

    /**
     * A name that is safe inside a console command: no spaces, no control characters. Java and
     * Bedrock (Floodgate prefixes "." or "*") names fit.
     */
    public static boolean safeName(String name) {
        return name != null && name.matches("[A-Za-z0-9_.*\\-]{1,32}");
    }
}
