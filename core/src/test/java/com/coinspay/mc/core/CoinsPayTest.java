package com.coinspay.mc.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The whole flow against a fake CoinsPay API and a fake server. */
class CoinsPayTest {
    @TempDir
    Path dir;

    private HttpServer server;
    private final Map<String, JsonObject> invoices = Collections.synchronizedMap(new LinkedHashMap<String, JsonObject>());
    private final List<JsonObject> created = new CopyOnWriteArrayList<>();
    private final List<String> idempotency = new CopyOnWriteArrayList<>();
    private volatile int createStatus = 201;
    private FakePlatform platform;
    private CoinsPay plugin;
    private FakeSender steve;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/", this::api);
        server.start();
        platform = new FakePlatform(dir);
        steve = new FakeSender(UUID.fromString("00000000-0000-0000-0000-00000000000a"), "Steve", "ru_ru");
        platform.online.put(steve.uuid(), steve);
        writeConfig("");
        plugin = new CoinsPay(platform, "test");
        plugin.enable();
    }

    @AfterEach
    void stop() {
        plugin.disable();
        server.stop(0);
    }

    private void writeConfig(String extra) throws IOException {
        Files.createDirectories(dir);
        Files.write(dir.resolve("config.yml"), String.join("\n",
                "api:",
                "  url: http://127.0.0.1:" + server.getAddress().getPort() + "/api/v1",
                "  key: cpk_test_0123456789abcdef",
                "server-id: survival",
                "cooldown-seconds: 0",
                "packages:",
                "  vip:",
                "    name: '&6VIP'",
                "    price: '4.99'",
                "    commands:",
                "      - 'lp user {player} parent add vip'",
                "      - '/say {player} bought {package} for {amount} {currency}'",
                "  kit:",
                "    price: '1'",
                "    require-online: true",
                "    commands: ['give {player} diamond 1']",
                "  fly:",
                "    price: '2'",
                "    duration: 7d",
                "    commands: ['op {player}']",
                "    expire-commands: ['deop {player}']",
                extra).getBytes(StandardCharsets.UTF_8));
    }

    // ------------------------------------------------------------------ the fake API

    private void api(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath().substring("/api/v1".length());
        String method = ex.getRequestMethod();
        if (!"Bearer cpk_test_0123456789abcdef".equals(ex.getRequestHeaders().getFirst("Authorization"))) {
            send(ex, 401, "{\"error\":{\"code\":\"UNAUTHORIZED\",\"message\":\"bad key\"}}");
            return;
        }
        if (path.equals("/shop")) {
            send(ex, 200, "{\"shop\":{\"id\":\"s\",\"name\":\"Craft\",\"created_at\":\"x\"},\"key\":{\"test\":true,\"access\":\"invoices\"}}");
        } else if (path.equals("/invoices") && method.equals("POST")) {
            if (createStatus != 201) {
                send(ex, createStatus, "{\"error\":{\"code\":\"RATE_LIMITED\",\"message\":\"slow down\"}}");
                return;
            }
            JsonObject body = JsonParser.parseString(new String(readAll(ex), StandardCharsets.UTF_8)).getAsJsonObject();
            created.add(body);
            idempotency.add(ex.getRequestHeaders().getFirst("Idempotency-Key"));
            JsonObject inv = new JsonObject();
            String id = "inv-" + (invoices.size() + 1);
            inv.addProperty("id", id);
            inv.addProperty("number", "T-" + (1000 + invoices.size()));
            inv.addProperty("status", "open");
            inv.addProperty("currency", body.get("currency").getAsString());
            inv.addProperty("amount", body.get("amount").getAsString());
            inv.addProperty("amount_paid", "0");
            inv.addProperty("amount_due", body.get("amount").getAsString());
            inv.addProperty("reference", body.get("reference").getAsString());
            inv.addProperty("pay_url", "https://pay.test/i/" + id);
            inv.addProperty("test", true);
            inv.addProperty("paid_late", false);
            invoices.put(id, inv);
            send(ex, 201, wrap(inv));
        } else if (path.equals("/invoices")) {
            JsonArray list = new JsonArray();
            List<JsonObject> all = new ArrayList<>(invoices.values());
            Collections.reverse(all);
            for (JsonObject i : all) if ("paid".equals(i.get("status").getAsString())) list.add(i);
            JsonObject out = new JsonObject();
            out.add("invoices", list);
            out.add("next_cursor", null);
            send(ex, 200, out.toString());
        } else if (path.startsWith("/invoices/")) {
            JsonObject inv = invoices.get(path.substring("/invoices/".length()));
            if (inv == null) send(ex, 404, "{\"error\":{\"code\":\"NOT_FOUND\",\"message\":\"no\"}}");
            else send(ex, 200, wrap(inv));
        } else {
            send(ex, 404, "{}");
        }
    }

    private static String wrap(JsonObject invoice) {
        JsonObject o = new JsonObject();
        o.add("invoice", invoice);
        return o.toString();
    }

    private static byte[] readAll(HttpExchange ex) throws IOException {
        return ex.getRequestBody().readAllBytes();
    }

    private static void send(HttpExchange ex, int status, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, b.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(b);
        }
    }

    private void pay(String id, String amount) {
        JsonObject inv = invoices.get(id);
        inv.addProperty("amount_paid", amount);
        if (new java.math.BigDecimal(amount).compareTo(inv.get("amount").getAsBigDecimal()) >= 0) {
            inv.addProperty("status", "paid");
            inv.addProperty("amount_due", "0");
        }
    }

    // ------------------------------------------------------------------ tests

    @Test
    void buyPayDeliverOnce() {
        plugin.buy(steve, "vip", null);
        assertEquals(1, created.size());
        JsonObject body = created.get(0);
        assertEquals("4.99", body.get("amount").getAsString());
        assertEquals("USD", body.get("currency").getAsString());
        assertEquals("VIP", body.get("title").getAsString());
        String ref = body.get("reference").getAsString();
        assertTrue(ref.startsWith("mc:survival:" + steve.uuid() + ":vip:"), ref);
        assertEquals(ref, idempotency.get(0));
        assertTrue(steve.text().contains("https://pay.test/i/inv-1?lang=ru"), steve.text());
        assertEquals(1, platform.opened.size(), "QR map handed out");

        plugin.tick(System.currentTimeMillis());
        assertTrue(platform.commands.isEmpty(), "nothing before payment");

        pay("inv-1", "4.99");
        plugin.tick(System.currentTimeMillis());
        assertEquals(2, platform.commands.size(), platform.commands.toString());
        assertEquals("lp user Steve parent add vip", platform.commands.get(0));
        assertEquals("say Steve bought vip for 4.99 USD", platform.commands.get(1));
        assertEquals(Order.State.DELIVERED, plugin.store().order("inv-1").state);
        assertTrue(steve.text().contains("Оплата получена"));
        assertEquals(1, platform.closed.size());

        plugin.tick(System.currentTimeMillis());
        plugin.tick(System.currentTimeMillis());
        assertEquals(2, platform.commands.size(), "never twice");
    }

    @Test
    void anInvoiceTheServerDidNotCreateIsIgnored() {
        JsonObject foreign = new JsonObject();
        foreign.addProperty("id", "inv-99");
        foreign.addProperty("status", "paid");
        foreign.addProperty("currency", "USD");
        foreign.addProperty("amount", "1000");
        foreign.addProperty("reference", "mc:survival:" + steve.uuid() + ":vip:zzz");
        foreign.addProperty("test", true);
        invoices.put("inv-99", foreign);
        plugin.buy(steve, "kit", null);
        plugin.tick(System.currentTimeMillis());
        assertTrue(platform.commands.isEmpty());
    }

    @Test
    void underpaidAmountIsNotDelivered() {
        plugin.buy(steve, "vip", null);
        JsonObject inv = invoices.get("inv-1");
        inv.addProperty("amount", "0.01");
        inv.addProperty("status", "paid");
        plugin.tick(System.currentTimeMillis());
        assertTrue(platform.commands.isEmpty());
        assertTrue(platform.warnings.toString().contains("NOT delivered"), platform.warnings.toString());
    }

    @Test
    void itemsWaitForAnOfflinePlayer() {
        plugin.buy(steve, "kit", null);
        platform.online.clear();
        pay("inv-1", "1");
        plugin.tick(System.currentTimeMillis());
        assertTrue(platform.commands.isEmpty());
        assertEquals(Order.State.WAITING, plugin.store().order("inv-1").state);

        platform.online.put(steve.uuid(), steve);
        plugin.onJoin(steve.uuid());
        assertEquals(Collections.singletonList("give Steve diamond 1"), platform.commands);
        plugin.tick(System.currentTimeMillis());
        assertEquals(1, platform.commands.size());
    }

    @Test
    void timedItemsExtendAndExpireOffline() {
        plugin.buy(steve, "fly", null);
        pay("inv-1", "2");
        long now = System.currentTimeMillis();
        plugin.tick(now);
        Grant g = plugin.store().grant(steve.uuid().toString(), "fly");
        assertNotNull(g);
        long firstEnd = g.expiresAt;

        plugin.buy(steve, "fly", null);
        pay("inv-2", "2");
        plugin.tick(now);
        assertEquals(1, plugin.store().grants().size(), "extended, not duplicated");
        assertEquals(firstEnd + 7L * 86_400_000L, plugin.store().grant(steve.uuid().toString(), "fly").expiresAt, 2000);

        platform.online.clear();
        plugin.tick(firstEnd + 7L * 86_400_000L + 5000);
        assertEquals("deop Steve", platform.commands.get(platform.commands.size() - 1));
        assertTrue(plugin.store().grants().isEmpty());
    }

    @Test
    void limitsAndErrors() {
        plugin.buy(steve, "vip", null);
        plugin.buy(steve, "vip", null);
        plugin.buy(steve, "vip", null);
        assertEquals(2, created.size(), "max-open-per-player");
        assertTrue(steve.text().contains("Неоплаченных счетов у вас уже 2"));

        FakeSender alex = new FakeSender(UUID.randomUUID(), "Alex", "en_us");
        createStatus = 429;
        plugin.buy(alex, "vip", null);
        assertTrue(alex.text().contains("Too many requests"), alex.text());

        FakeSender bad = new FakeSender(UUID.randomUUID(), "x y", "en_us");
        plugin.buy(bad, "vip", null);
        assertTrue(bad.text().contains("characters"));
        plugin.buy(alex, "nope", null);
        assertTrue(alex.text().contains("No such item"));
    }

    @Test
    void expiredThenPaidLate() {
        plugin.buy(steve, "vip", null);
        invoices.get("inv-1").addProperty("status", "expired");
        Order o = plugin.store().order("inv-1");
        o.createdAt -= 10 * 60_000L;
        plugin.tick(System.currentTimeMillis());
        assertEquals(Order.State.EXPIRED, o.state);
        assertTrue(steve.text().contains("вышло"));

        invoices.get("inv-1").addProperty("status", "paid");
        invoices.get("inv-1").addProperty("paid_late", true);
        plugin.tick(System.currentTimeMillis());
        assertEquals(Order.State.DELIVERED, o.state);
        assertEquals(2, platform.commands.size());
    }

    @Test
    void crashMidDeliveryNeedsTheAdmin() throws IOException {
        plugin.buy(steve, "vip", null);
        pay("inv-1", "4.99");
        platform.holdConsole = true;
        plugin.tick(System.currentTimeMillis());
        assertTrue(platform.commands.isEmpty());
        plugin.disable();

        platform.holdConsole = false;
        CoinsPay restarted = new CoinsPay(platform, "test");
        restarted.enable();
        assertEquals(Order.State.REVIEW, restarted.store().order("inv-1").state);
        assertTrue(platform.warnings.toString().contains("redeliver"));
        restarted.tick(System.currentTimeMillis());
        assertTrue(platform.commands.isEmpty(), "not delivered again on its own");

        FakeSender console = new FakeSender(null, "CONSOLE", null);
        restarted.admin(console, new String[]{"redeliver", "T-1000"});
        assertEquals(2, platform.commands.size());
        assertEquals(Order.State.DELIVERED, restarted.store().order("inv-1").state);
        restarted.disable();
        plugin = new CoinsPay(platform, "test");
        plugin.enable();
    }

    @Test
    void adminCommands() {
        FakeSender console = new FakeSender(null, "CONSOLE", null);
        plugin.admin(console, new String[]{"give", "Steve", "vip"});
        assertEquals(1, created.size());
        assertTrue(console.text().contains("Steve"));
        plugin.admin(console, new String[]{"status"});
        assertTrue(console.text().contains("open &f1"), console.text());
        plugin.admin(console, new String[]{"pending"});
        assertTrue(console.text().contains("T-1000"));
        plugin.admin(console, new String[]{"markdone", "T-1000"});
        assertTrue(console.text().contains("this action does not apply"));
        FakeSender player = new FakeSender(UUID.randomUUID(), "Bob", "en_us");
        player.admin = false;
        plugin.admin(player, new String[]{"status"});
        assertTrue(player.text().contains("permission"));
        assertFalse(plugin.completeDonate(new String[]{"v"}).isEmpty());
    }

    // ------------------------------------------------------------------ fakes

    static final class FakePlatform implements Platform {
        final Path dir;
        final Map<UUID, Sender> online = new LinkedHashMap<>();
        final List<String> commands = new CopyOnWriteArrayList<>();
        final List<String> warnings = new CopyOnWriteArrayList<>();
        final List<Order> opened = new CopyOnWriteArrayList<>();
        final List<Order> closed = new CopyOnWriteArrayList<>();
        boolean holdConsole;

        FakePlatform(Path dir) {
            this.dir = dir;
        }

        @Override public String describe() { return "Fake 1.0"; }
        @Override public Path dataFolder() { return dir; }
        @Override public boolean isProxy() { return false; }
        @Override public void async(Runnable task) { task.run(); }
        @Override public Cancellable repeatAsync(Runnable task, long periodSeconds) { return () -> { }; }
        @Override public void console(Runnable task) { if (!holdConsole) task.run(); }
        @Override public boolean dispatch(String command) { commands.add(command); return true; }
        @Override public Sender player(UUID id) { return online.get(id); }
        @Override public Sender playerByName(String name) {
            for (Sender s : online.values()) if (s.name().equalsIgnoreCase(name)) return s;
            return null;
        }
        @Override public void paymentOpened(UUID player, Order order) { opened.add(order); }
        @Override public void paymentClosed(UUID player, Order order) { closed.add(order); }
        @Override public Log log() {
            return new Log() {
                @Override public void info(String message) { }
                @Override public void warn(String message) { warnings.add(message); }
                @Override public void error(String message, Throwable error) { warnings.add(message + ": " + error); }
            };
        }
    }

    static final class FakeSender implements Sender {
        final UUID id;
        final String name;
        final String locale;
        final StringBuilder out = new StringBuilder();
        boolean admin = true;

        FakeSender(UUID id, String name, String locale) {
            this.id = id;
            this.name = name;
            this.locale = locale;
        }

        @Override public UUID uuid() { return id; }
        @Override public String name() { return name; }
        @Override public String locale() { return locale; }
        @Override public boolean hasPermission(String permission) { return admin; }
        @Override public synchronized void send(String legacyText) { out.append(legacyText).append('\n'); }
        @Override public synchronized void send(List<Part> parts) {
            for (Part p : parts) out.append(p.text).append(p.url != null ? " <" + p.url + ">" : "");
            out.append('\n');
        }

        synchronized String text() {
            return out.toString();
        }
    }
}
