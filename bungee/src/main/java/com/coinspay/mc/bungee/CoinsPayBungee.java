package com.coinspay.mc.bungee;

import com.coinspay.mc.core.CoinsPay;
import com.coinspay.mc.core.Log;
import com.coinspay.mc.core.Platform;
import com.coinspay.mc.core.Sender;
import net.md_5.bungee.api.ChatColor;
import net.md_5.bungee.api.CommandSender;
import net.md_5.bungee.api.ProxyServer;
import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.HoverEvent;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.chat.hover.content.Text;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.event.PostLoginEvent;
import net.md_5.bungee.api.plugin.Command;
import net.md_5.bungee.api.plugin.Listener;
import net.md_5.bungee.api.plugin.Plugin;
import net.md_5.bungee.api.plugin.TabExecutor;
import net.md_5.bungee.api.scheduler.ScheduledTask;
import net.md_5.bungee.event.EventHandler;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

/** BungeeCord and its forks: /donate in chat, item commands on the proxy console. */
public final class CoinsPayBungee extends Plugin implements Listener, Platform {
    private CoinsPay core;

    @Override
    public void onEnable() {
        core = new CoinsPay(this, getDescription().getVersion());
        try {
            core.enable();
        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "CoinsPay cannot start: " + e.getMessage(), e);
            return;
        }
        ProxyServer proxy = getProxy();
        proxy.getPluginManager().registerCommand(this, new DonateCommand());
        proxy.getPluginManager().registerCommand(this, new AdminCommand());
        proxy.getPluginManager().registerListener(this, this);
    }

    @Override
    public void onDisable() {
        if (core != null) core.disable();
    }

    @EventHandler
    public void onLogin(PostLoginEvent e) {
        core.onJoin(e.getPlayer().getUniqueId());
    }

    private final class DonateCommand extends Command implements TabExecutor {
        DonateCommand() {
            super("donate", null, "cpdonate");
        }

        @Override
        public void execute(CommandSender s, String[] args) {
            BungeeSender sender = new BungeeSender(s);
            if (!(s instanceof ProxiedPlayer)) core.messages().send(sender, "players-only");
            else if (args.length > 0) core.buy(sender, args[0], null);
            else core.showChatMenu(sender);
        }

        @Override
        public Iterable<String> onTabComplete(CommandSender s, String[] args) {
            return core.completeDonate(args);
        }
    }

    private final class AdminCommand extends Command implements TabExecutor {
        AdminCommand() {
            super("coinspay");
        }

        @Override
        public void execute(CommandSender s, String[] args) {
            core.admin(new BungeeSender(s), args);
        }

        @Override
        public Iterable<String> onTabComplete(CommandSender s, String[] args) {
            return core.completeAdmin(new BungeeSender(s), args);
        }
    }

    // ------------------------------------------------------------------ Platform

    @Override
    public String describe() {
        return getProxy().getName() + " " + getProxy().getVersion();
    }

    @Override
    public Log log() {
        return new Log() {
            @Override
            public void info(String message) {
                getLogger().info(message);
            }

            @Override
            public void warn(String message) {
                getLogger().warning(message);
            }

            @Override
            public void error(String message, Throwable error) {
                getLogger().log(Level.SEVERE, message, error);
            }
        };
    }

    @Override
    public Path dataFolder() {
        return getDataFolder().toPath();
    }

    @Override
    public boolean isProxy() {
        return true;
    }

    @Override
    public void async(Runnable task) {
        getProxy().getScheduler().runAsync(this, task);
    }

    @Override
    public Cancellable repeatAsync(Runnable task, long periodSeconds) {
        final ScheduledTask t = getProxy().getScheduler().schedule(this, task, 2, periodSeconds, TimeUnit.SECONDS);
        return t::cancel;
    }

    @Override
    public void console(Runnable task) {
        // BungeeCord has no main thread: console commands may run anywhere.
        task.run();
    }

    @Override
    public boolean dispatch(String command) {
        return getProxy().getPluginManager().dispatchCommand(getProxy().getConsole(), command);
    }

    @Override
    public Sender player(UUID id) {
        ProxiedPlayer p = getProxy().getPlayer(id);
        return p != null ? new BungeeSender(p) : null;
    }

    @Override
    public Sender playerByName(String name) {
        ProxiedPlayer p = getProxy().getPlayer(name);
        return p != null ? new BungeeSender(p) : null;
    }

    @SuppressWarnings("deprecation") // fromLegacy() exists only on newer BungeeCord
    static final class BungeeSender implements Sender {
        private final CommandSender handle;

        BungeeSender(CommandSender handle) {
            this.handle = handle;
        }

        @Override
        public UUID uuid() {
            return handle instanceof ProxiedPlayer ? ((ProxiedPlayer) handle).getUniqueId() : null;
        }

        @Override
        public String name() {
            return handle.getName();
        }

        @Override
        public String locale() {
            if (!(handle instanceof ProxiedPlayer)) return null;
            Locale l = ((ProxiedPlayer) handle).getLocale();
            return l != null ? l.toString() : null;
        }

        @Override
        public boolean hasPermission(String permission) {
            return handle.hasPermission(permission);
        }

        @Override
        public void send(String legacyText) {
            for (String line : legacyText.split("\n")) handle.sendMessage(TextComponent.fromLegacyText(color(line)));
        }

        @Override
        public void send(List<Part> parts) {
            List<BaseComponent> line = new ArrayList<>();
            for (Part p : parts) {
                Text hover = p.hover != null ? new Text(TextComponent.fromLegacyText(color(p.hover))) : null;
                for (BaseComponent c : TextComponent.fromLegacyText(color(p.text))) {
                    if (p.url != null) c.setClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, p.url));
                    if (p.command != null) c.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, p.command));
                    if (hover != null) c.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, hover));
                    line.add(c);
                }
            }
            handle.sendMessage(line.toArray(new BaseComponent[0]));
        }

        private static String color(String s) {
            return ChatColor.translateAlternateColorCodes('&', s);
        }
    }
}
