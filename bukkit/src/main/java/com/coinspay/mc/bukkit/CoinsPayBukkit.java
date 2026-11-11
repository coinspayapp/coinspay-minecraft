package com.coinspay.mc.bukkit;

import com.coinspay.mc.core.CoinsPay;
import com.coinspay.mc.core.Log;
import com.coinspay.mc.core.Order;
import com.coinspay.mc.core.Platform;
import com.coinspay.mc.core.Sender;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.logging.Level;

/** Spigot, Paper and Folia 1.8.8 - 1.21. */
public final class CoinsPayBukkit extends JavaPlugin implements Listener, Platform {
    private CoinsPay core;
    private Schedulers schedulers;
    private final QrMaps maps = new QrMaps();

    @Override
    public void onEnable() {
        schedulers = Schedulers.create(this);
        core = new CoinsPay(this, getDescription().getVersion());
        try {
            core.enable();
        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "CoinsPay cannot start: " + e.getMessage(), e);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        bind("donate", new TabExecutor() {
            @Override
            public boolean onCommand(CommandSender s, Command c, String label, String[] args) {
                donate(s, args);
                return true;
            }

            @Override
            public List<String> onTabComplete(CommandSender s, Command c, String label, String[] args) {
                return core.completeDonate(args);
            }
        });
        bind("coinspay", new TabExecutor() {
            @Override
            public boolean onCommand(CommandSender s, Command c, String label, String[] args) {
                core.admin(new BukkitSender(s), args);
                return true;
            }

            @Override
            public List<String> onTabComplete(CommandSender s, Command c, String label, String[] args) {
                return core.completeAdmin(new BukkitSender(s), args);
            }
        });
        getServer().getPluginManager().registerEvents(this, this);
    }

    @Override
    public void onDisable() {
        if (core != null) core.disable();
        for (Player p : Bukkit.getOnlinePlayers()) {
            try {
                maps.remove(p);
            } catch (RuntimeException ignored) {
                // Folia: not this player's thread at shutdown; the map stays a plain map.
            }
        }
    }

    private void bind(String name, TabExecutor executor) {
        PluginCommand command = getCommand(name);
        if (command == null) return;
        command.setExecutor(executor);
        command.setTabCompleter(executor);
    }

    private void donate(CommandSender s, String[] args) {
        BukkitSender sender = new BukkitSender(s);
        if (!(s instanceof Player)) {
            core.messages().send(sender, "players-only");
            return;
        }
        if (!s.hasPermission("coinspay.donate")) {
            core.messages().send(sender, "no-permission");
            return;
        }
        if (args.length > 0) {
            core.buy(sender, args[0], null);
        } else if ("chat".equals(core.settings().menu) || core.settings().packages.isEmpty()) {
            core.showChatMenu(sender);
        } else {
            DonateMenu.open(core, (Player) s, sender);
        }
    }

    // ------------------------------------------------------------------ events

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof DonateMenu)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player) || e.getRawSlot() < 0 || e.getRawSlot() >= e.getInventory().getSize()) return;
        String id = ((DonateMenu) e.getInventory().getHolder()).packageAt(e.getRawSlot());
        if (id == null) return;
        Player player = (Player) e.getWhoClicked();
        player.closeInventory();
        core.buy(new BukkitSender(player), id, null);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDrag(InventoryDragEvent e) {
        if (e.getInventory().getHolder() instanceof DonateMenu) e.setCancelled(true);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        core.onJoin(e.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        maps.remove(e.getPlayer());
    }

    @EventHandler(ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent e) {
        if (QrMaps.isQr(e.getItemDrop().getItemStack())) e.getItemDrop().remove();
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent e) {
        e.getDrops().removeIf(QrMaps::isQr);
    }

    // ------------------------------------------------------------------ Platform

    @Override
    public String describe() {
        return Bukkit.getName() + " " + Bukkit.getBukkitVersion() + (Schedulers.folia() ? " (Folia)" : "");
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
        return false;
    }

    @Override
    public void async(Runnable task) {
        schedulers.async(task);
    }

    @Override
    public Cancellable repeatAsync(Runnable task, long periodSeconds) {
        return schedulers.repeatAsync(task, periodSeconds);
    }

    @Override
    public void console(Runnable task) {
        schedulers.global(task);
    }

    @Override
    public boolean dispatch(String command) {
        return Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);
    }

    @Override
    public Sender player(UUID id) {
        Player p = Bukkit.getPlayer(id);
        return p != null && p.isOnline() ? new BukkitSender(p) : null;
    }

    @Override
    public Sender playerByName(String name) {
        Player p = Bukkit.getPlayerExact(name);
        return p != null ? new BukkitSender(p) : null;
    }

    @Override
    public void paymentOpened(UUID id, final Order order) {
        final Player p = Bukkit.getPlayer(id);
        if (p == null) return;
        schedulers.entity(p, () -> {
            if (p.isOnline() && maps.give(p, payUrl(p, order), order.packageName)) {
                core.messages().send(new BukkitSender(p), "qr-given", Collections.<String, String>emptyMap());
            }
        });
    }

    @Override
    public void paymentClosed(UUID id, Order order) {
        final Player p = Bukkit.getPlayer(id);
        if (p != null) schedulers.entity(p, () -> maps.remove(p));
    }

    private String payUrl(Player p, Order order) {
        String lang = core.messages().lang(new BukkitSender(p));
        return order.payUrl + (order.payUrl.contains("?") ? "&" : "?") + "lang=" + lang;
    }
}
