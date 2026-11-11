package com.coinspay.mc.bukkit;

import com.coinspay.mc.core.CoinsPay;
import com.coinspay.mc.core.PackageDef;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** The chest menu of /donate: one item per product, a click asks for an invoice. */
final class DonateMenu implements InventoryHolder {
    private final Map<Integer, String> slots = new HashMap<>();
    private Inventory inventory;

    static void open(CoinsPay core, Player player, BukkitSender sender) {
        DonateMenu menu = new DonateMenu();
        List<PackageDef> packages = new ArrayList<>(core.settings().packages.values());
        int count = Math.min(54, packages.size());
        int size = Math.max(9, (count + 8) / 9 * 9);
        // 1.8 clients crash on titles over 32 characters.
        String title = BukkitSender.color(core.messages().get(sender, "menu-title"));
        if (title.length() > 32) title = title.substring(0, 32);
        menu.inventory = Bukkit.createInventory(menu, size, title);
        for (int i = 0; i < count; i++) {
            PackageDef p = packages.get(i);
            ItemStack item = new ItemStack(material(p.icon));
            ItemMeta meta = item.getItemMeta();
            meta.setDisplayName(BukkitSender.color("&r" + p.name));
            List<String> lore = new ArrayList<>();
            for (String line : core.lore(sender, p)) lore.add(BukkitSender.color(line));
            meta.setLore(lore);
            item.setItemMeta(meta);
            menu.inventory.setItem(i, item);
            menu.slots.put(i, p.id);
        }
        player.openInventory(menu.inventory);
    }

    String packageAt(int slot) {
        return slots.get(slot);
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    /** Modern names work on 1.13+; on older servers an unknown name shows an emerald. */
    private static Material material(String name) {
        Material m = Material.matchMaterial(name);
        if (m == null || m == Material.AIR) m = Material.matchMaterial(LEGACY.getOrDefault(name.toUpperCase(), ""));
        return m != null && m != Material.AIR ? m : Material.EMERALD;
    }

    /** A few common modern names and their pre-1.13 equivalents. */
    private static final Map<String, String> LEGACY = new HashMap<>();

    static {
        LEGACY.put("GOLDEN_APPLE", "GOLDEN_APPLE");
        LEGACY.put("ENCHANTED_GOLDEN_APPLE", "GOLDEN_APPLE");
        LEGACY.put("EXPERIENCE_BOTTLE", "EXP_BOTTLE");
        LEGACY.put("FIREWORK_ROCKET", "FIREWORK");
        LEGACY.put("GOLDEN_SWORD", "GOLD_SWORD");
        LEGACY.put("GOLDEN_HELMET", "GOLD_HELMET");
        LEGACY.put("GOLDEN_CHESTPLATE", "GOLD_CHESTPLATE");
        LEGACY.put("CLOCK", "WATCH");
        LEGACY.put("PLAYER_HEAD", "SKULL_ITEM");
        LEGACY.put("NETHER_STAR", "NETHER_STAR");
        LEGACY.put("ENDER_EYE", "EYE_OF_ENDER");
        LEGACY.put("WRITABLE_BOOK", "BOOK_AND_QUILL");
        LEGACY.put("OAK_SIGN", "SIGN");
        LEGACY.put("TOTEM_OF_UNDYING", "TOTEM");
        LEGACY.put("ELYTRA", "ELYTRA");
        LEGACY.put("SPAWNER", "MOB_SPAWNER");
        LEGACY.put("BEACON", "BEACON");
    }
}
