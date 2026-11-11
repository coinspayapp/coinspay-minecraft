package com.coinspay.mc.bukkit;

import io.nayuki.qrcodegen.QrCode;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.map.MapCanvas;
import org.bukkit.map.MapPalette;
import org.bukkit.map.MapRenderer;
import org.bukkit.map.MapView;

import java.awt.Color;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A map item with the payment link's QR code, to scan with a phone. One map per player is
 * reused for later invoices, so the world folder does not fill up with map files. The item is
 * marked by a lore line and removed after payment, expiry, quit and death.
 */
final class QrMaps {
    private static final String MARKER = "§8CoinsPay QR";

    private final Map<UUID, MapView> views = new ConcurrentHashMap<>();
    private volatile boolean broken;

    /** Gives the map; call on the player's thread. False when the QR could not be made. */
    boolean give(Player player, String url, String title) {
        if (broken) return false;
        try {
            QrCode qr = QrCode.encodeText(url, QrCode.Ecc.MEDIUM);
            MapView view = views.get(player.getUniqueId());
            if (view == null) {
                view = Bukkit.createMap(player.getWorld());
                views.put(player.getUniqueId(), view);
            }
            for (MapRenderer r : new ArrayList<>(view.getRenderers())) view.removeRenderer(r);
            view.addRenderer(new QrRenderer(qr));

            remove(player);
            ItemStack item = item(view);
            ItemMeta meta = item.getItemMeta();
            meta.setDisplayName(BukkitSender.color("&6" + title));
            meta.setLore(Arrays.asList(MARKER));
            item.setItemMeta(meta);

            PlayerInventory inv = player.getInventory();
            for (int slot = 0; slot < 9; slot++) {
                ItemStack in = inv.getItem(slot);
                if (in == null || in.getType() == Material.AIR) {
                    inv.setItem(slot, item);
                    inv.setHeldItemSlot(slot);
                    return true;
                }
            }
            return inv.addItem(item).isEmpty();
        } catch (Throwable t) {
            // Maps differ a lot between versions and forks; the chat link still works.
            broken = true;
            Bukkit.getLogger().warning("[CoinsPay] QR maps are off on this server: " + t);
            return false;
        }
    }

    /** Removes CoinsPay maps from the inventory; call on the player's thread. */
    void remove(Player player) {
        PlayerInventory inv = player.getInventory();
        ItemStack[] contents = inv.getContents();
        for (int i = 0; i < contents.length; i++) {
            if (isQr(contents[i])) inv.setItem(i, null);
        }
    }

    static boolean isQr(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return false;
        List<String> lore = item.getItemMeta().getLore();
        return lore != null && lore.contains(MARKER);
    }

    /** 1.8 - 1.12: MAP with the id as durability. 1.13+: FILLED_MAP with the view in MapMeta. */
    @SuppressWarnings("deprecation")
    private static ItemStack item(MapView view) throws ReflectiveOperationException {
        int id = ((Number) MapView.class.getMethod("getId").invoke(view)).intValue();
        Material filled = Material.matchMaterial("FILLED_MAP");
        if (filled == null) return new ItemStack(Material.MAP, 1, (short) id);
        ItemStack item = new ItemStack(filled);
        ItemMeta meta = item.getItemMeta();
        // Through the public interface: the CraftBukkit class behind it is not accessible.
        Class<?> mapMeta = Class.forName("org.bukkit.inventory.meta.MapMeta");
        try {
            Method set = mapMeta.getMethod("setMapView", MapView.class);
            set.invoke(meta, view);
        } catch (NoSuchMethodException e) {
            mapMeta.getMethod("setMapId", int.class).invoke(meta, id);
        }
        item.setItemMeta(meta);
        return item;
    }

    private static final class QrRenderer extends MapRenderer {
        private final QrCode qr;
        private final Map<UUID, Boolean> drawn = new ConcurrentHashMap<>();

        QrRenderer(QrCode qr) {
            super(true);
            this.qr = qr;
        }

        @Override
        @SuppressWarnings("deprecation")
        public void render(MapView view, MapCanvas canvas, Player player) {
            if (drawn.put(player.getUniqueId(), Boolean.TRUE) != null) return;
            byte white = MapPalette.matchColor(Color.WHITE);
            byte black = MapPalette.matchColor(Color.BLACK);
            int quiet = 2;
            int scale = Math.max(1, 128 / (qr.size + quiet * 2));
            int offset = (128 - qr.size * scale) / 2;
            for (int x = 0; x < 128; x++) for (int y = 0; y < 128; y++) canvas.setPixel(x, y, white);
            for (int y = 0; y < qr.size; y++) {
                for (int x = 0; x < qr.size; x++) {
                    if (!qr.getModule(x, y)) continue;
                    for (int dx = 0; dx < scale; dx++) {
                        for (int dy = 0; dy < scale; dy++) canvas.setPixel(offset + x * scale + dx, offset + y * scale + dy, black);
                    }
                }
            }
        }
    }
}
