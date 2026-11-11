package com.coinspay.mc.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TextAndSettingsTest {

    @Test
    void durations() {
        assertEquals(30L * 86_400_000L, Text.parseDuration("30d"));
        assertEquals(12L * 3_600_000L, Text.parseDuration("12h"));
        assertEquals(90L * 60_000L, Text.parseDuration("90m"));
        assertEquals(14L * 86_400_000L, Text.parseDuration("2w"));
        assertEquals(30L * 86_400_000L, Text.parseDuration("1mo"));
        assertEquals(0, Text.parseDuration(""));
        assertEquals(0, Text.parseDuration("0"));
        assertEquals(-1, Text.parseDuration("forever"));
        assertEquals("29 дн. 3 ч", Text.duration(29L * 86_400_000L + 3 * 3_600_000L + 59_000L, true));
        assertEquals("2h 5m", Text.duration(2 * 3_600_000L + 5 * 60_000L, false));
        assertEquals("0m", Text.duration(0, false));
    }

    @Test
    void placeholders() {
        Map<String, String> v = new HashMap<>();
        v.put("player", "Steve");
        v.put("amount", "$1");
        assertEquals("give Steve diamond {unknown} $1", Text.fill("give {player} diamond {unknown} {amount}", v));
    }

    @Test
    void safeNames() {
        assertTrue(Text.safeName("Steve_01"));
        assertTrue(Text.safeName(".BedrockUser"));
        assertFalse(Text.safeName("bad name"));
        assertFalse(Text.safeName("x;op y"));
        assertFalse(Text.safeName(""));
        assertFalse(Text.safeName("a\nop b"));
    }

    @Test
    void amounts() {
        assertEquals("4.99", Text.amount(new BigDecimal("4.990000")));
        assertEquals("5", Text.amount(new BigDecimal("5.00")));
        assertEquals("100", Text.amount(new BigDecimal("1E+2")));
        assertEquals("VIP", Text.strip("&6&lVIP"));
    }

    @Test
    void theShippedConfigsParseCleanly(@TempDir Path dir) throws IOException {
        for (String name : new String[]{"config.yml", "config-proxy.yml"}) {
            Path file = Resources.copyDefault(dir, name);
            Settings s = Settings.parse(Resources.readYaml(file));
            // Only the empty key is reported.
            assertEquals(1, s.problems.size(), name + ": " + s.problems);
            assertFalse(s.configured());
            assertFalse(s.packages.isEmpty());
        }
        Settings s = Settings.parse(Resources.readYaml(dir.resolve("config.yml")));
        assertEquals(3, s.packages.size());
        PackageDef fly = s.packages.get("fly-7");
        assertEquals(7L * 86_400_000L, fly.durationMillis);
        assertEquals(1, fly.expireCommands.size());
        assertTrue(s.packages.get("diamonds").requireOnline);
        assertEquals("USD", fly.currency);
    }

    @Test
    void badItemsAreSkippedWithAReason(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("c.yml");
        Files.write(file, String.join("\n",
                "api:",
                "  key: cpk_test_0123456789",
                "server-id: 'bad id!'",
                "poll-seconds: 1",
                "packages:",
                "  ok:",
                "    price: '1'",
                "    commands: [say hi]",
                "  free:",
                "    price: '0'",
                "    commands: [say hi]",
                "  nothing:",
                "    price: '1'",
                "  weird:",
                "    price: 'abc'",
                "    commands: [say hi]",
                "  forever:",
                "    price: '1'",
                "    duration: forever",
                "    commands: [say hi]").getBytes("UTF-8"));
        Settings s = Settings.parse(Resources.readYaml(file));
        assertTrue(s.configured());
        assertTrue(s.testKey());
        assertEquals("main", s.serverId);
        assertEquals(5, s.pollSeconds);
        assertEquals(1, s.packages.size());
        List<String> p = s.problems;
        assertEquals(5, p.size(), p.toString());
    }
}
