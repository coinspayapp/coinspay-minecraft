package com.coinspay.mc.velocity;

import com.coinspay.mc.core.CoinsPay;
import com.coinspay.mc.core.Log;
import com.coinspay.mc.core.Platform;
import com.coinspay.mc.core.Sender;
import com.google.inject.Inject;
import com.velocitypowered.api.command.CommandMeta;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.PostLoginEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.scheduler.ScheduledTask;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.slf4j.Logger;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** Velocity 3.3+: /donate in chat, item commands on the proxy console. */
public final class CoinsPayVelocity implements Platform {
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();

    private final ProxyServer proxy;
    private final Logger logger;
    private final Path dataFolder;
    private CoinsPay core;

    @Inject
    public CoinsPayVelocity(ProxyServer proxy, Logger logger, @DataDirectory Path dataFolder) {
        this.proxy = proxy;
        this.logger = logger;
        this.dataFolder = dataFolder;
    }

    @Subscribe
    public void onInit(ProxyInitializeEvent e) {
        String version = proxy.getPluginManager().fromInstance(this)
                .flatMap(c -> c.getDescription().getVersion()).orElse("dev");
        core = new CoinsPay(this, version);
        try {
            core.enable();
        } catch (Exception ex) {
            logger.error("CoinsPay cannot start: " + ex.getMessage(), ex);
            core = null;
            return;
        }
        register("donate", new SimpleCommand() {
            @Override
            public void execute(Invocation inv) {
                VelocitySender sender = new VelocitySender(inv.source());
                if (!(inv.source() instanceof Player)) core.messages().send(sender, "players-only");
                else if (inv.arguments().length > 0) core.buy(sender, inv.arguments()[0], null);
                else core.showChatMenu(sender);
            }

            @Override
            public List<String> suggest(Invocation inv) {
                return core.completeDonate(args(inv.arguments()));
            }
        }, "cpdonate");
        register("coinspay", new SimpleCommand() {
            @Override
            public void execute(Invocation inv) {
                core.admin(new VelocitySender(inv.source()), inv.arguments());
            }

            @Override
            public List<String> suggest(Invocation inv) {
                return core.completeAdmin(new VelocitySender(inv.source()), args(inv.arguments()));
            }
        });
    }

    @Subscribe
    public void onShutdown(ProxyShutdownEvent e) {
        if (core != null) core.disable();
    }

    @Subscribe
    public void onLogin(PostLoginEvent e) {
        if (core != null) core.onJoin(e.getPlayer().getUniqueId());
    }

    private void register(String name, SimpleCommand command, String... aliases) {
        CommandMeta meta = proxy.getCommandManager().metaBuilder(name).aliases(aliases).plugin(this).build();
        proxy.getCommandManager().register(meta, command);
    }

    /** Velocity passes no empty last argument while the player types the first one. */
    private static String[] args(String[] a) {
        return a.length == 0 ? new String[]{""} : a;
    }

    // ------------------------------------------------------------------ Platform

    @Override
    public String describe() {
        return proxy.getVersion().getName() + " " + proxy.getVersion().getVersion();
    }

    @Override
    public Log log() {
        return new Log() {
            @Override
            public void info(String message) {
                logger.info(message);
            }

            @Override
            public void warn(String message) {
                logger.warn(message);
            }

            @Override
            public void error(String message, Throwable error) {
                logger.error(message, error);
            }
        };
    }

    @Override
    public Path dataFolder() {
        return dataFolder;
    }

    @Override
    public boolean isProxy() {
        return true;
    }

    @Override
    public void async(Runnable task) {
        proxy.getScheduler().buildTask(this, task).schedule();
    }

    @Override
    public Cancellable repeatAsync(Runnable task, long periodSeconds) {
        final ScheduledTask t = proxy.getScheduler().buildTask(this, task)
                .delay(2, TimeUnit.SECONDS).repeat(periodSeconds, TimeUnit.SECONDS).schedule();
        return t::cancel;
    }

    @Override
    public void console(Runnable task) {
        // Off the command and event threads: dispatch() waits for the command to finish.
        proxy.getScheduler().buildTask(this, task).schedule();
    }

    @Override
    public boolean dispatch(String command) {
        try {
            return proxy.getCommandManager().executeAsync(proxy.getConsoleCommandSource(), command).get(30, TimeUnit.SECONDS);
        } catch (Exception e) {
            logger.warn("Command failed: " + command + ": " + e);
            return false;
        }
    }

    @Override
    public Sender player(UUID id) {
        return proxy.getPlayer(id).map(p -> (Sender) new VelocitySender(p)).orElse(null);
    }

    @Override
    public Sender playerByName(String name) {
        return proxy.getPlayer(name).map(p -> (Sender) new VelocitySender(p)).orElse(null);
    }

    static final class VelocitySender implements Sender {
        private final CommandSource handle;

        VelocitySender(CommandSource handle) {
            this.handle = handle;
        }

        @Override
        public UUID uuid() {
            return handle instanceof Player ? ((Player) handle).getUniqueId() : null;
        }

        @Override
        public String name() {
            return handle instanceof Player ? ((Player) handle).getUsername() : "CONSOLE";
        }

        @Override
        public String locale() {
            if (!(handle instanceof Player)) return null;
            Locale l = ((Player) handle).getEffectiveLocale();
            return l != null ? l.toString() : null;
        }

        @Override
        public boolean hasPermission(String permission) {
            return handle.hasPermission(permission);
        }

        @Override
        public void send(String legacyText) {
            for (String line : legacyText.split("\n")) handle.sendMessage(LEGACY.deserialize(line));
        }

        @Override
        public void send(List<Part> parts) {
            TextComponent.Builder line = Component.text();
            for (Part p : parts) {
                Component c = LEGACY.deserialize(p.text);
                if (p.url != null) c = c.clickEvent(ClickEvent.openUrl(p.url));
                if (p.command != null) c = c.clickEvent(ClickEvent.runCommand(p.command));
                if (p.hover != null) c = c.hoverEvent(HoverEvent.showText(LEGACY.deserialize(p.hover)));
                line.append(c);
            }
            handle.sendMessage(line.build());
        }
    }
}
