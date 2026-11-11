package com.coinspay.mc.core;

import java.nio.file.Path;
import java.util.UUID;

/** What the core needs from a server or proxy. */
public interface Platform {

    /** "Paper 1.21.4", "Velocity 3.4.0" and so on: goes to the User-Agent and /coinspay status. */
    String describe();

    Log log();

    Path dataFolder();

    /** True on Velocity and BungeeCord: the default config differs (no item commands there). */
    boolean isProxy();

    /** Runs off the server thread: network calls. */
    void async(Runnable task);

    /** Runs the task every periodSeconds off the server thread until cancelled. */
    Cancellable repeatAsync(Runnable task, long periodSeconds);

    /** Runs where console commands may be dispatched: the main thread or the Folia global region. */
    void console(Runnable task);

    /** Dispatches a console command; call only inside {@link #console(Runnable)}. */
    boolean dispatch(String command);

    /** An online player on this server or proxy, or null. */
    Sender player(UUID id);

    /** An online player by exact name (case-insensitive), or null. */
    Sender playerByName(String name);

    /** A payment link was just issued to an online player: Bukkit hands out a QR map. */
    default void paymentOpened(UUID player, Order order) {
    }

    /** The order is paid, expired or cancelled: Bukkit takes the QR map back. */
    default void paymentClosed(UUID player, Order order) {
    }

    interface Cancellable {
        void cancel();
    }
}
