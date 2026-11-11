package com.coinspay.mc.core;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * plugins/CoinsPay/data.json: orders and timed grants. The only source of truth about which
 * invoices belong to this server. Every change is saved at once through a temp file and a
 * rename, so a crash leaves either the old or the new file, never half of one.
 */
public final class Store {
    static final long WATCH_EXPIRED_MS = 7L * 86_400_000L;
    private static final long KEEP_CLOSED_MS = 30L * 86_400_000L;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private final Path file;
    private Data data = new Data();

    public Store(Path file) {
        this.file = file;
    }

    /** Loads the file; returns orders found mid-delivery, now moved to REVIEW. */
    public synchronized List<Order> load() throws IOException {
        List<Order> crashed = new ArrayList<>();
        if (!Files.exists(file)) {
            data = new Data();
            return crashed;
        }
        try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            Data loaded = GSON.fromJson(r, Data.class);
            data = loaded != null ? loaded : new Data();
        } catch (JsonParseException e) {
            // Keep the broken file for the admin rather than overwrite it with an empty one.
            Path copy = file.resolveSibling(file.getFileName() + ".broken-" + System.currentTimeMillis());
            Files.copy(file, copy, StandardCopyOption.REPLACE_EXISTING);
            throw new IOException("data.json is damaged, copied to " + copy.getFileName() + ": " + e.getMessage(), e);
        }
        if (data.orders == null) data.orders = new ArrayList<>();
        if (data.grants == null) data.grants = new ArrayList<>();
        for (Order o : data.orders) {
            if (o.state == Order.State.DELIVERING) {
                o.state = Order.State.REVIEW;
                crashed.add(o);
            }
        }
        if (!crashed.isEmpty()) save();
        return crashed;
    }

    public synchronized void save() throws IOException {
        Files.createDirectories(file.getParent());
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        try (Writer w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
            GSON.toJson(data, w);
        }
        try {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public synchronized void add(Order order) {
        data.orders.add(order);
    }

    public synchronized Order order(String id) {
        for (Order o : data.orders) if (o.id.equals(id) || id.equals(o.number)) return o;
        return null;
    }

    public synchronized List<Order> orders() {
        return new ArrayList<>(data.orders);
    }

    public synchronized List<Grant> grants() {
        return new ArrayList<>(data.grants);
    }

    public synchronized int openFor(String playerId) {
        int n = 0;
        for (Order o : data.orders) if (o.state == Order.State.OPEN && o.playerId.equals(playerId)) n++;
        return n;
    }

    public synchronized Grant grant(String playerId, String packageId) {
        for (Grant g : data.grants) if (g.playerId.equals(playerId) && g.packageId.equals(packageId)) return g;
        return null;
    }

    public synchronized void addGrant(Grant grant) {
        data.grants.add(grant);
    }

    public synchronized void removeGrant(Grant grant) {
        data.grants.remove(grant);
    }

    /** Drops closed orders older than 30 days and expired ones nobody paid within a week. */
    public synchronized boolean prune(long now) {
        boolean changed = false;
        for (Iterator<Order> it = data.orders.iterator(); it.hasNext(); ) {
            Order o = it.next();
            boolean old = o.closedAt > 0 && now - o.closedAt > KEEP_CLOSED_MS;
            boolean finished = o.state == Order.State.DELIVERED || o.state == Order.State.CANCELLED || o.state == Order.State.EXPIRED;
            if (finished && old) {
                it.remove();
                changed = true;
            }
        }
        return changed;
    }

    static final class Data {
        int version = 1;
        List<Order> orders = new ArrayList<>();
        List<Grant> grants = new ArrayList<>();
    }
}
