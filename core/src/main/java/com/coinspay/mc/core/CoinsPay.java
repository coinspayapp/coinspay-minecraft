package com.coinspay.mc.core;

import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The plugin itself, platform-free: purchase, polling, delivery, timed grants and the admin
 * command. Platforms only wire commands, events and schedulers to it.
 */
public final class CoinsPay {
    public static final String ADMIN = "coinspay.admin";
    private static final long CHECK_OPEN_AFTER_MS = 120_000L;
    private static final long CHECK_OPEN_EVERY_MS = 60_000L;
    private static final long CHECK_EXPIRED_FOR_MS = 86_400_000L;
    private static final long CHECK_EXPIRED_EVERY_MS = 15 * 60_000L;
    private static final int CHECKS_PER_TICK = 10;
    private static final List<String> SUBCOMMANDS = Arrays.asList("status", "pending", "check", "redeliver", "markdone", "give", "reload");

    private final Platform platform;
    private final String version;
    private final SecureRandom random = new SecureRandom();
    private final AtomicBoolean polling = new AtomicBoolean();
    private final Map<UUID, Long> lastCreated = new ConcurrentHashMap<>();
    private final Set<String> reportedMismatch = Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());
    private final Set<Grant> expiring = Collections.newSetFromMap(new ConcurrentHashMap<Grant, Boolean>());

    private volatile Settings settings;
    private volatile Messages messages;
    private volatile ApiClient api;
    private volatile ApiClient.ShopInfo shop;
    private volatile String lastError;
    private Store store;
    private Platform.Cancellable task;

    public CoinsPay(Platform platform, String version) {
        this.platform = platform;
        this.version = version;
    }

    // ------------------------------------------------------------------ lifecycle

    public void enable() throws IOException {
        load();
        store = new Store(platform.dataFolder().resolve("data.json"));
        for (Order o : store.load()) {
            platform.log().warn("Invoice " + o.label() + " (" + o.packageId + " for " + o.playerName + ") was being delivered when the server stopped."
                    + " Check whether the player got it, then run /coinspay redeliver " + o.label() + " or /coinspay markdone " + o.label());
        }
        schedule();
        checkKey();
    }

    public void reload() throws IOException {
        load();
        schedule();
        checkKey();
    }

    public void disable() {
        if (task != null) task.cancel();
        task = null;
        if (store != null) save();
    }

    private void load() throws IOException {
        Path folder = platform.dataFolder();
        Path config = Resources.copyDefault(folder, platform.isProxy() ? "config-proxy.yml" : "config.yml", "config.yml");
        Settings s = Settings.parse(Resources.readYaml(config));
        for (String p : s.problems) platform.log().warn("config.yml: " + p);
        messages = Messages.load(folder, s.language);
        settings = s;
        api = s.configured() ? new ApiClient(s.apiUrl, s.apiKey, "CoinsPay-Minecraft/" + version + " (" + platform.describe() + ")") : null;
        shop = null;
        platform.log().info("Loaded " + s.packages.size() + " item(s); server-id " + s.serverId + (s.testKey() ? "; TEST key" : ""));
    }

    private void schedule() {
        if (task != null) task.cancel();
        task = platform.repeatAsync(this::poll, settings.pollSeconds);
    }

    private void checkKey() {
        final ApiClient client = api;
        if (client == null) return;
        platform.async(() -> {
            try {
                ApiClient.ShopInfo info = client.shop();
                shop = info;
                platform.log().info("Connected to CoinsPay shop \"" + info.name + "\" with a " + (info.test ? "test" : "live") + " key");
                if ("full".equals(info.access)) {
                    platform.log().warn("This API key has full access, payouts included. The plugin needs only invoices:"
                            + " create an \"Invoices only\" key in the dashboard and put it in config.yml.");
                }
            } catch (ApiException e) {
                lastError = e.toString();
                platform.log().warn("Cannot check the API key: " + e + (e.status == 401 ? " (the key is wrong or revoked)" : ""));
            }
        });
    }

    public Settings settings() {
        return settings;
    }

    public Messages messages() {
        return messages;
    }

    // ------------------------------------------------------------------ purchase

    /** /donate &lt;item&gt; or a menu click. {@code also} gets a copy of the outcome (the admin of /coinspay give). */
    public void buy(final Sender buyer, String packageId, final Sender also) {
        final Settings s = settings;
        final Messages m = messages;
        if (buyer.uuid() == null) {
            m.send(buyer, "players-only");
            return;
        }
        final PackageDef p = s.packages.get(packageId);
        if (p == null) {
            m.send(also != null ? also : buyer, "unknown-package", vars("id", packageId));
            return;
        }
        if (api == null) {
            m.send(buyer, "not-configured");
            return;
        }
        if (!Text.safeName(buyer.name())) {
            m.send(buyer, "bad-name");
            return;
        }
        final UUID id = buyer.uuid();
        int open = store.openFor(id.toString());
        if (open >= s.maxOpenPerPlayer) {
            m.send(buyer, "too-many-open", vars("count", String.valueOf(open)));
            return;
        }
        long now = System.currentTimeMillis();
        Long last = lastCreated.get(id);
        if (last != null && now - last < s.cooldownSeconds * 1000L) {
            long wait = (s.cooldownSeconds * 1000L - (now - last) + 999) / 1000;
            m.send(buyer, "cooldown", vars("seconds", String.valueOf(wait)));
            return;
        }
        lastCreated.put(id, now);
        m.send(buyer, "creating", vars("name", p.name));
        final ApiClient client = api;
        platform.async(() -> create(client, s, m, buyer, p, also));
    }

    private void create(ApiClient client, Settings s, Messages m, Sender buyer, PackageDef p, Sender also) {
        UUID id = buyer.uuid();
        String reference = "mc:" + s.serverId + ":" + id + ":" + p.id + ":" + nonce();
        JsonObject body = new JsonObject();
        body.addProperty("currency", p.currency);
        body.addProperty("amount", p.price.toPlainString());
        body.addProperty("title", cut(p.plainName(), 200));
        body.addProperty("description", cut("Minecraft: " + buyer.name() + " (" + s.serverId + ")", 1000));
        body.addProperty("reference", reference);
        body.addProperty("lifetime_minutes", s.lifetimeMinutes);
        body.addProperty("buyer_pays_fee", s.buyerPaysFee);
        body.addProperty("buyer_pays_network", s.buyerPaysNetwork);
        if (s.returnUrl != null) body.addProperty("return_url", s.returnUrl);
        Invoice invoice;
        try {
            invoice = client.createInvoice(body, reference);
        } catch (ApiException e) {
            lastError = e.toString();
            platform.log().warn("Cannot create an invoice for " + buyer.name() + " (" + p.id + "): " + e);
            m.send(buyer, e.rateLimited() ? "rate-limited" : "error");
            if (also != null) also.send(m.get(also, "prefix") + e);
            return;
        }
        long now = System.currentTimeMillis();
        Order o = new Order();
        o.id = invoice.id;
        o.number = invoice.number;
        o.reference = reference;
        o.playerId = id.toString();
        o.playerName = buyer.name();
        o.packageId = p.id;
        o.packageName = p.name;
        o.price = p.price.toPlainString();
        o.currency = p.currency;
        o.commands = new ArrayList<>(p.commands);
        o.requireOnline = p.requireOnline;
        o.durationMillis = p.durationMillis;
        o.expireCommands = new ArrayList<>(p.expireCommands);
        o.payUrl = invoice.payUrl;
        o.test = invoice.test;
        o.createdAt = now;
        o.expiresAt = now + s.lifetimeMinutes * 60_000L;
        store.add(o);
        save();
        platform.log().info("Invoice " + o.label() + " for " + o.playerName + ": " + p.id + ", " + o.price + " " + o.currency);
        showPayment(buyer, o, s.lifetimeMinutes);
        if (also != null) m.send(also, "admin-gave", vars("name", p.name, "player", buyer.name()));
        if (s.qrMap) platform.paymentOpened(id, o);
    }

    private void showPayment(Sender to, Order o, int minutes) {
        Messages m = messages;
        String url = payUrl(to, o.payUrl);
        Map<String, String> v = vars("name", o.packageName, "price", o.price, "currency", o.currency,
                "url", url, "minutes", String.valueOf(minutes));
        m.send(to, "created", v);
        to.send(Arrays.asList(
                Sender.Part.text(m.get(to, "prefix")),
                Sender.Part.link(m.get(to, "link"), url, m.get(to, "link-hover", v))));
        to.send(m.get(to, "prefix") + m.get(to, "link-hint", v));
        if (o.test) m.send(to, "test-mode");
    }

    /** The payment page in the player's language. */
    private String payUrl(Sender to, String url) {
        if (url == null) return "";
        return url + (url.contains("?") ? "&" : "?") + "lang=" + messages.lang(to);
    }

    // ------------------------------------------------------------------ polling

    /** Runs every poll-seconds off the server thread. */
    public void poll() {
        if (!polling.compareAndSet(false, true)) return;
        try {
            tick(System.currentTimeMillis());
        } catch (Throwable t) {
            platform.log().error("CoinsPay poll failed", t);
        } finally {
            polling.set(false);
        }
    }

    void tick(long now) {
        if (store == null) return;
        expireGrants(now);
        if (store.prune(now)) save();
        for (Order o : store.orders()) {
            if (o.state == Order.State.WAITING && platform.player(o.player()) != null) deliver(o, null);
        }
        ApiClient client = api;
        if (client == null) return;
        List<Order> watched = new ArrayList<>();
        for (Order o : store.orders()) if (o.watched(now)) watched.add(o);
        if (watched.isEmpty()) return;

        Map<String, Invoice> paid = new HashMap<>();
        try {
            for (Invoice i : client.paidInvoices(100)) paid.put(i.id, i);
        } catch (ApiException e) {
            noteError(e);
            return;
        }
        for (Order o : watched) {
            Invoice i = paid.get(o.id);
            if (i != null) handle(o, i, now);
        }
        int checks = 0;
        boolean failed = false;
        for (Order o : watched) {
            if (checks >= CHECKS_PER_TICK || !o.watched(now) || paid.containsKey(o.id)) continue;
            boolean due = o.state == Order.State.OPEN
                    ? now - o.createdAt >= CHECK_OPEN_AFTER_MS && now - o.lastCheckAt >= CHECK_OPEN_EVERY_MS
                    : now - o.closedAt < CHECK_EXPIRED_FOR_MS && now - o.lastCheckAt >= CHECK_EXPIRED_EVERY_MS;
            if (!due) continue;
            checks++;
            o.lastCheckAt = now;
            try {
                handle(o, client.invoice(o.id), now);
            } catch (ApiException e) {
                noteError(e);
                failed = true;
                if (e.status == 404) {
                    platform.log().warn("Invoice " + o.label() + " is not found with the current key: was the key changed to another shop?");
                }
                if (e.retryable()) break;
            }
        }
        if (!failed) lastError = null;
    }

    private void noteError(ApiException e) {
        String text = e.toString();
        if (!text.equals(lastError)) platform.log().warn("CoinsPay API: " + text);
        lastError = text;
    }

    private void handle(Order o, Invoice i, long now) {
        Sender player = platform.player(o.player());
        Decision d = Decision.decide(o, i, player != null);
        Messages m = messages;
        Map<String, String> v = vars("name", o.packageName, "amount", Text.amount(i.amount), "paid", Text.amount(i.amountPaid),
                "due", Text.amount(i.amountDue), "currency", i.currency != null ? i.currency : o.currency);
        switch (d.action) {
            case DELIVER:
                deliver(o, i);
                break;
            case WAIT_PLAYER:
                synchronized (store) {
                    o.state = Order.State.WAITING;
                    o.closedAt = now;
                }
                save();
                platform.log().info("Invoice " + o.label() + " is paid; " + o.playerName + " gets " + o.packageId + " on the next join");
                break;
            case PARTIAL:
                o.reportedPaid = i.amountPaid.toPlainString();
                save();
                m.send(player, "partial", v);
                break;
            case EXPIRED:
            case CANCELLED:
                synchronized (store) {
                    o.state = d.action == Decision.Action.EXPIRED ? Order.State.EXPIRED : Order.State.CANCELLED;
                    o.closedAt = now;
                }
                save();
                m.send(player, d.action == Decision.Action.EXPIRED ? "expired" : "cancelled", v);
                if (player != null) platform.paymentClosed(o.player(), o);
                break;
            case MISMATCH:
                if (reportedMismatch.add(o.id)) platform.log().warn("Invoice " + o.label() + " is NOT delivered: " + d.reason);
                break;
            default:
                break;
        }
    }

    /** A player joined: paid items that waited for them go out now. */
    public void onJoin(final UUID player) {
        if (store == null) return;
        platform.async(() -> {
            for (Order o : store.orders()) {
                if (o.state == Order.State.WAITING && o.player().equals(player)) deliver(o, null);
            }
        });
    }

    // ------------------------------------------------------------------ delivery

    private void deliver(final Order o, final Invoice invoice) {
        synchronized (store) {
            if (o.state == Order.State.DELIVERING || o.state == Order.State.DELIVERED) return;
            o.state = Order.State.DELIVERING;
        }
        // Saved before any command runs: a crash from here on leaves REVIEW, never a second delivery.
        save();
        platform.console(() -> runDelivery(o, invoice));
    }

    private void runDelivery(Order o, Invoice invoice) {
        Map<String, String> v = commandVars(o, invoice);
        for (String command : o.commands) dispatch(Text.fill(command, v), o);
        long now = System.currentTimeMillis();
        Grant grant = null;
        synchronized (store) {
            o.state = Order.State.DELIVERED;
            o.closedAt = now;
            if (o.durationMillis > 0) grant = extendGrant(o, now);
        }
        platform.async(this::save);
        platform.log().info("Delivered " + o.packageId + " to " + o.playerName + " (invoice " + o.label() + ")");
        Sender player = platform.player(o.player());
        if (player != null) {
            messages.send(player, "paid", vars("name", o.packageName));
            if (grant != null) {
                messages.send(player, "paid-until", vars("name", o.packageName, "left", left(player, grant.expiresAt - now)));
            }
            platform.paymentClosed(o.player(), o);
        }
    }

    private Grant extendGrant(Order o, long now) {
        Grant g = store.grant(o.playerId, o.packageId);
        if (g == null) {
            g = new Grant();
            g.playerId = o.playerId;
            g.packageId = o.packageId;
            g.expiresAt = now + o.durationMillis;
            store.addGrant(g);
        } else {
            g.expiresAt = Math.max(now, g.expiresAt) + o.durationMillis;
        }
        g.playerName = o.playerName;
        g.packageName = o.packageName;
        g.expireCommands = new ArrayList<>(o.expireCommands);
        g.lastOrder = o.id;
        return g;
    }

    private void expireGrants(long now) {
        for (final Grant g : store.grants()) {
            if (g.expiresAt > now || !expiring.add(g)) continue;
            platform.console(() -> {
                Map<String, String> v = vars("player", g.playerName, "uuid", g.playerId, "package", g.packageId);
                for (String command : g.expireCommands) dispatch(Text.fill(command, v), null);
                store.removeGrant(g);
                expiring.remove(g);
                platform.async(this::save);
                platform.log().info("Ended " + g.packageId + " for " + g.playerName);
                messages.send(platform.player(UUID.fromString(g.playerId)), "grant-expired", vars("name", g.packageName));
            });
        }
    }

    private void dispatch(String command, Order o) {
        String c = command.startsWith("/") ? command.substring(1) : command;
        try {
            if (!platform.dispatch(c)) platform.log().warn("Command not found or failed: " + c + (o != null ? " (invoice " + o.label() + ")" : ""));
        } catch (Throwable t) {
            platform.log().error("Command failed: " + c, t);
        }
    }

    private static Map<String, String> commandVars(Order o, Invoice i) {
        return vars("player", o.playerName, "uuid", o.playerId, "package", o.packageId, "amount", o.price,
                "currency", o.currency, "invoice", i != null && i.number != null ? i.number : o.label());
    }

    // ------------------------------------------------------------------ /donate in chat

    public void showChatMenu(Sender to) {
        Messages m = messages;
        Settings s = settings;
        if (s.packages.isEmpty()) {
            m.send(to, "no-packages");
            return;
        }
        to.send(m.get(to, "menu-header"));
        for (PackageDef p : s.packages.values()) {
            String duration = p.durationMillis > 0 ? m.get(to, "menu-duration", vars("duration", left(to, p.durationMillis))) : "";
            String line = m.get(to, "menu-line", vars("name", p.name, "price", Text.amount(p.price), "currency", p.currency, "duration", duration));
            to.send(Arrays.asList(Sender.Part.text(line + " "),
                    Sender.Part.command(m.get(to, "menu-buy"), "/donate " + p.id, m.get(to, "menu-buy-hover"))));
        }
    }

    /** Lore lines of a menu item: price, duration, description, "click to buy". */
    public List<String> lore(Sender to, PackageDef p) {
        Messages m = messages;
        List<String> lore = new ArrayList<>();
        lore.add(m.get(to, "menu-lore-price", vars("price", Text.amount(p.price), "currency", p.currency)));
        if (p.durationMillis > 0) lore.add(m.get(to, "menu-lore-duration", vars("duration", left(to, p.durationMillis))));
        for (String d : p.description) lore.add("&7" + d);
        lore.add("");
        lore.add(m.get(to, "menu-lore-click"));
        return lore;
    }

    public List<String> completeDonate(String[] args) {
        if (args.length != 1) return Collections.emptyList();
        return startingWith(new ArrayList<>(settings.packages.keySet()), args[0]);
    }

    // ------------------------------------------------------------------ /coinspay

    public void admin(Sender s, String[] args) {
        Messages m = messages;
        if (!s.hasPermission(ADMIN)) {
            m.send(s, "no-permission");
            return;
        }
        String sub = args.length > 0 ? args[0].toLowerCase(Locale.ROOT) : "";
        String arg = args.length > 1 ? args[1] : null;
        switch (sub) {
            case "reload":
                try {
                    reload();
                    m = messages;
                    m.send(s, "admin-reloaded", vars("count", String.valueOf(settings.packages.size())));
                } catch (IOException e) {
                    m.send(s, "admin-reload-failed", vars("error", e.getMessage()));
                }
                return;
            case "status":
                status(s);
                return;
            case "pending":
                pending(s);
                return;
            case "check":
                if (arg != null) {
                    check(s, arg);
                    return;
                }
                break;
            case "redeliver":
            case "markdone":
                if (arg != null) {
                    resolve(s, arg, sub.equals("redeliver"));
                    return;
                }
                break;
            case "give":
                if (args.length >= 3) {
                    Sender target = platform.playerByName(args[1]);
                    if (target == null) {
                        m.send(s, "player-not-found", vars("player", args[1]));
                    } else {
                        buy(target, args[2], s);
                    }
                    return;
                }
                break;
            default:
                break;
        }
        s.send(m.get(s, "admin-help", vars("version", version)));
    }

    public List<String> completeAdmin(Sender s, String[] args) {
        if (!s.hasPermission(ADMIN)) return Collections.emptyList();
        if (args.length == 1) return startingWith(SUBCOMMANDS, args[0]);
        String sub = args[0].toLowerCase(Locale.ROOT);
        if (args.length == 2 && (sub.equals("check") || sub.equals("redeliver") || sub.equals("markdone"))) {
            List<String> ids = new ArrayList<>();
            for (Order o : store.orders()) if (o.state != Order.State.DELIVERED || sub.equals("redeliver")) ids.add(o.label());
            return startingWith(ids, args[1]);
        }
        if (args.length == 3 && sub.equals("give")) return startingWith(new ArrayList<>(settings.packages.keySet()), args[2]);
        return Collections.emptyList();
    }

    private void status(Sender s) {
        Messages m = messages;
        int open = 0, waiting = 0, review = 0;
        for (Order o : store.orders()) {
            if (o.state == Order.State.OPEN) open++;
            if (o.state == Order.State.WAITING) waiting++;
            if (o.state == Order.State.REVIEW) review++;
        }
        ApiClient.ShopInfo info = shop;
        String key = info == null ? m.get(s, "key-unknown") : m.get(s, info.test ? "key-test" : "key-live") + ", " + info.access;
        s.send(m.get(s, "admin-status", vars(
                "version", version, "platform", platform.describe(),
                "shop", info != null ? info.name : "?", "key", key,
                "server", settings.serverId, "packages", String.valueOf(settings.packages.size()),
                "open", String.valueOf(open), "waiting", String.valueOf(waiting), "review", String.valueOf(review),
                "grants", String.valueOf(store.grants().size()),
                "error", lastError != null ? lastError : m.get(s, "none"))));
    }

    private void pending(Sender s) {
        Messages m = messages;
        long now = System.currentTimeMillis();
        int shown = 0;
        for (Order o : store.orders()) {
            if (o.state != Order.State.OPEN && o.state != Order.State.WAITING && o.state != Order.State.REVIEW) continue;
            if (shown++ >= 30) break;
            s.send(m.get(s, "admin-pending-order", vars("id", o.label(), "player", o.playerName, "name", o.packageName,
                    "state", o.state.name(), "age", left(s, now - o.createdAt))));
        }
        for (Grant g : store.grants()) {
            if (shown++ >= 60) break;
            s.send(m.get(s, "admin-pending-grant", vars("player", g.playerName, "name", g.packageName, "left", left(s, g.expiresAt - now))));
        }
        if (shown == 0) m.send(s, "admin-pending-empty");
    }

    private void check(final Sender s, final String id) {
        final Order o = store.order(id);
        final ApiClient client = api;
        if (o == null || client == null) {
            messages.send(s, "admin-not-found", vars("id", id));
            return;
        }
        platform.async(() -> {
            try {
                Invoice i = client.invoice(o.id);
                o.lastCheckAt = System.currentTimeMillis();
                handle(o, i, o.lastCheckAt);
                messages.send(s, "admin-checked", vars("id", o.label(), "status", i.status, "state", o.state.name()));
            } catch (ApiException e) {
                messages.send(s, "admin-check-failed", vars("id", o.label(), "error", e.toString()));
            }
        });
    }

    private void resolve(Sender s, String id, boolean run) {
        Order o = store.order(id);
        if (o == null) {
            messages.send(s, "admin-not-found", vars("id", id));
            return;
        }
        boolean allowed = o.state == Order.State.REVIEW || (run && (o.state == Order.State.DELIVERED || o.state == Order.State.WAITING));
        if (!allowed) {
            messages.send(s, "admin-wrong-state", vars("id", o.label(), "state", o.state.name()));
            return;
        }
        if (run) {
            synchronized (store) {
                o.state = Order.State.OPEN;
            }
            deliver(o, null);
            messages.send(s, "admin-redelivered", vars("id", o.label()));
        } else {
            synchronized (store) {
                o.state = Order.State.DELIVERED;
                o.closedAt = System.currentTimeMillis();
            }
            save();
            messages.send(s, "admin-marked", vars("id", o.label()));
        }
        platform.log().info(s.name() + " " + (run ? "redelivered" : "marked delivered") + " invoice " + o.label());
    }

    // ------------------------------------------------------------------ helpers

    private void save() {
        try {
            store.save();
        } catch (IOException e) {
            platform.log().error("Cannot save data.json", e);
        }
    }

    private String left(Sender to, long ms) {
        return Text.duration(ms, "ru".equals(messages.lang(to)));
    }

    private String nonce() {
        byte[] b = new byte[5];
        random.nextBytes(b);
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }

    private static String cut(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }

    private static List<String> startingWith(List<String> options, String prefix) {
        List<String> out = new ArrayList<>();
        String p = prefix.toLowerCase(Locale.ROOT);
        for (String o : options) if (o.toLowerCase(Locale.ROOT).startsWith(p)) out.add(o);
        return out;
    }

    static Map<String, String> vars(String... kv) {
        Map<String, String> m = new HashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put(kv[i], kv[i + 1] == null ? "" : kv[i + 1]);
        return m;
    }

    /** For tests. */
    Store store() {
        return store;
    }
}
