package com.coinspay.mc.bukkit;

import com.coinspay.mc.core.Sender;
import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.HoverEvent;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

final class BukkitSender implements Sender {
    private static volatile Method localeMethod;
    private static volatile Method spigotLocaleMethod;
    private static volatile boolean localeLooked;

    final CommandSender handle;

    BukkitSender(CommandSender handle) {
        this.handle = handle;
    }

    @Override
    public UUID uuid() {
        return handle instanceof Player ? ((Player) handle).getUniqueId() : null;
    }

    @Override
    public String name() {
        return handle.getName();
    }

    @Override
    public String locale() {
        if (!(handle instanceof Player)) return null;
        lookUpLocale();
        try {
            if (localeMethod != null) return (String) localeMethod.invoke(handle);
            if (spigotLocaleMethod != null) return (String) spigotLocaleMethod.invoke(((Player) handle).spigot());
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // Unknown: English texts.
        }
        return null;
    }

    /** Player#getLocale appeared in 1.12; before it the locale was on Player.Spigot. */
    private static void lookUpLocale() {
        if (localeLooked) return;
        try {
            localeMethod = Player.class.getMethod("getLocale");
        } catch (NoSuchMethodException e) {
            try {
                spigotLocaleMethod = Player.Spigot.class.getMethod("getLocale");
            } catch (NoSuchMethodException ignored) {
                // Neither: English texts.
            }
        }
        localeLooked = true;
    }

    @Override
    public boolean hasPermission(String permission) {
        return handle.hasPermission(permission);
    }

    @Override
    public void send(String legacyText) {
        handle.sendMessage(color(legacyText));
    }

    @Override
    public void send(List<Part> parts) {
        if (!(handle instanceof Player)) {
            StringBuilder sb = new StringBuilder();
            for (Part p : parts) sb.append(p.text).append(p.url != null ? " " + p.url : "");
            send(sb.toString());
            return;
        }
        List<BaseComponent> line = new ArrayList<>();
        for (Part p : parts) {
            BaseComponent[] hover = p.hover != null ? TextComponent.fromLegacyText(color(p.hover)) : null;
            for (BaseComponent c : TextComponent.fromLegacyText(color(p.text))) {
                if (p.url != null) c.setClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, p.url));
                if (p.command != null) c.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, p.command));
                if (hover != null) c.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, hover));
                line.add(c);
            }
        }
        ((Player) handle).spigot().sendMessage(line.toArray(new BaseComponent[0]));
    }

    static String color(String s) {
        return ChatColor.translateAlternateColorCodes('&', s);
    }
}
