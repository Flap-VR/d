package com.contest.sell;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class SellPlugin extends JavaPlugin implements Listener, TabExecutor {

    private static final int MENU_SIZE = 54;      // 6 rows
    private static final int CONFIRM_SLOT = 53;   // bottom-right lime pane

    /** Shared key for the future /settings plugin. 0 = player muted plugin sounds. */
    private static final NamespacedKey SOUNDS_KEY = NamespacedKey.fromString("settings:sounds_enabled");

    private final Map<Material, Double> prices = new EnumMap<>(Material.class);

    private static class SellHolder implements InventoryHolder {
        Inventory inventory;
        boolean sold = false;

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    // ------------------------------------------------------------- lifecycle

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadPrices();
        getServer().getPluginManager().registerEvents(this, this);

        var cmd = getCommand("sell");
        if (cmd != null) {
            cmd.setExecutor(this);
            cmd.setTabCompleter(this);
        }
    }

    @Override
    public void onDisable() {
        // Close open sell menus so nobody loses the items they put in.
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.getOpenInventory().getTopInventory().getHolder() instanceof SellHolder) {
                p.closeInventory();
            }
        }
    }

    private void loadPrices() {
        prices.clear();
        ConfigurationSection section = getConfig().getConfigurationSection("prices");
        if (section == null) return;
        for (String key : section.getKeys(false)) {
            Material material = Material.matchMaterial(key);
            double price = section.getDouble(key);
            if (material == null) {
                getLogger().warning("Unknown material in prices: " + key);
            } else if (price > 0) {
                prices.put(material, price);
            }
        }
        getLogger().info("Loaded " + prices.size() + " sell prices.");
    }

    private Economy economy() {
        RegisteredServiceProvider<Economy> rsp = getServer().getServicesManager().getRegistration(Economy.class);
        return rsp == null ? null : rsp.getProvider();
    }

    // ---------------------------------------------------------------- command

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length > 0 && args[0].equalsIgnoreCase("reload")) {
            if (!sender.hasPermission("sell.admin")) {
                sender.sendMessage(Component.text("You can't do that.", NamedTextColor.RED));
                return true;
            }
            reloadConfig();
            loadPrices();
            sender.sendMessage(color("&7System &r>> &aSell prices reloaded."));
            return true;
        }

        if (!(sender instanceof Player player)) {
            sender.sendMessage("Only players can use /sell.");
            return true;
        }
        if (economy() == null) {
            player.sendMessage(color(getConfig().getString("messages.no-economy")));
            return true;
        }
        openMenu(player);
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1 && sender.hasPermission("sell.admin") && "reload".startsWith(args[0].toLowerCase())) {
            return List.of("reload");
        }
        return List.of();
    }

    // ------------------------------------------------------------------- menu

    /** Open slots are columns 1-8 of rows 1-5 (slots 0-44 except the right-hand column). */
    private static boolean isSellSlot(int slot) {
        return slot >= 0 && slot < 45 && slot % 9 < 8;
    }

    private void openMenu(Player player) {
        SellHolder holder = new SellHolder();
        Inventory inv = Bukkit.createInventory(holder, MENU_SIZE, Component.text("Sell"));
        holder.inventory = inv;

        for (int i = 0; i < MENU_SIZE; i++) {
            if (!isSellSlot(i)) inv.setItem(i, darkPane());
        }
        refreshButton(inv);
        player.openInventory(inv);
    }

    private ItemStack darkPane() {
        ItemStack pane = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta meta = pane.getItemMeta();
        meta.displayName(Component.empty());
        pane.setItemMeta(meta);
        return pane;
    }

    /** Recalculates the payout and rewrites the lime pane. */
    private void refreshButton(Inventory inv) {
        double total = 0;
        for (int i = 0; i < 45; i++) {
            if (!isSellSlot(i)) continue;
            ItemStack item = inv.getItem(i);
            if (isSellable(item)) total += prices.get(item.getType()) * item.getAmount();
        }

        ItemStack button = new ItemStack(Material.LIME_STAINED_GLASS_PANE);
        ItemMeta meta = button.getItemMeta();
        meta.displayName(noItalic(Component.text("Sell Items?", NamedTextColor.GRAY, TextDecoration.BOLD)));
        meta.lore(List.of(noItalic(Component.text(money(total), NamedTextColor.GREEN, TextDecoration.BOLD))));
        button.setItemMeta(meta);
        inv.setItem(CONFIRM_SLOT, button);
    }

    /** Only plain, unmodified items that have a price can be sold. */
    private boolean isSellable(ItemStack item) {
        if (item == null || item.getType().isAir() || !prices.containsKey(item.getType())) return false;
        return item.isSimilar(new ItemStack(item.getType()));
    }

    // ----------------------------------------------------------------- events

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        Inventory top = event.getView().getTopInventory();
        if (!(top.getHolder() instanceof SellHolder holder)) return;
        if (!(event.getWhoClicked() instanceof Player player)) return;

        int raw = event.getRawSlot();
        if (raw >= 0 && raw < MENU_SIZE) {
            if (raw == CONFIRM_SLOT) {
                event.setCancelled(true);
                sell(player, holder);
                return;
            }
            if (!isSellSlot(raw)) {
                event.setCancelled(true);
                return;
            }
        }
        scheduleRefresh(top);
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        Inventory top = event.getView().getTopInventory();
        if (!(top.getHolder() instanceof SellHolder)) return;

        for (int raw : event.getRawSlots()) {
            if (raw < MENU_SIZE && !isSellSlot(raw)) {
                event.setCancelled(true);
                return;
            }
        }
        scheduleRefresh(top);
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        Inventory inv = event.getInventory();
        if (!(inv.getHolder() instanceof SellHolder)) return;
        if (!(event.getPlayer() instanceof Player player)) return;

        // Give back everything that wasn't sold.
        for (int i = 0; i < 45; i++) {
            if (!isSellSlot(i)) continue;
            ItemStack item = inv.getItem(i);
            if (item == null || item.getType().isAir()) continue;
            inv.setItem(i, null);
            for (ItemStack leftover : player.getInventory().addItem(item).values()) {
                player.getWorld().dropItemNaturally(player.getLocation(), leftover);
            }
        }
    }

    private void scheduleRefresh(Inventory top) {
        // Run next tick so the click/drag has finished moving items.
        Bukkit.getScheduler().runTask(this, () -> refreshButton(top));
    }

    // ------------------------------------------------------------------- sell

    private void sell(Player player, SellHolder holder) {
        if (holder.sold) return;
        Inventory inv = holder.inventory;

        double total = 0;
        List<Integer> slots = new ArrayList<>();
        for (int i = 0; i < 45; i++) {
            if (!isSellSlot(i)) continue;
            ItemStack item = inv.getItem(i);
            if (isSellable(item)) {
                total += prices.get(item.getType()) * item.getAmount();
                slots.add(i);
            }
        }

        if (slots.isEmpty()) {
            play(player, Sound.ENTITY_VILLAGER_NO, 1f);
            player.sendMessage(color(getConfig().getString("messages.nothing")));
            return;
        }

        Economy eco = economy();
        if (eco == null) {
            player.sendMessage(color(getConfig().getString("messages.no-economy")));
            return;
        }

        // Pay first; only remove the items if the deposit worked.
        EconomyResponse response = eco.depositPlayer(player, total);
        if (!response.transactionSuccess()) {
            play(player, Sound.ENTITY_VILLAGER_NO, 1f);
            player.sendMessage(color("&7System &r>> &cSale failed: " + response.errorMessage));
            return;
        }

        holder.sold = true;
        for (int slot : slots) inv.setItem(slot, null);

        play(player, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1.5f);
        String message = getConfig().getString("messages.sold", "&7System &r>> &aYou sold your items for &2{amount}&a!");
        player.sendMessage(color(message.replace("{amount}", money(total))));

        // Close next tick (also returns any items that couldn't be sold).
        Bukkit.getScheduler().runTask(this, () -> player.closeInventory());
    }

    // ---------------------------------------------------------------- helpers

    private String money(double amount) {
        return getConfig().getString("currency-symbol", "$") + compact(amount);
    }

    /**
     * 450 -> "450", 1,150 -> "1.2k", 100,430 -> "100.4k" (nearest hundred),
     * 1,140,000 -> "1.1m" (nearest 100k), then b and t the same way.
     */
    static String compact(double amount) {
        if (amount < 1000) {
            double r = Math.round(amount * 100.0) / 100.0;
            return r == Math.rint(r) ? String.valueOf((long) r) : String.format(Locale.US, "%.2f", r);
        }

        String[] suffix = {"k", "m", "b", "t"};
        double[] unit = {1e3, 1e6, 1e9, 1e12};

        int i = 0;
        for (int j = unit.length - 1; j >= 0; j--) {
            if (amount >= unit[j]) {
                i = j;
                break;
            }
        }

        double rounded = Math.round(amount / unit[i] * 10.0) / 10.0;
        if (rounded >= 1000 && i < unit.length - 1) { // e.g. 999,960 -> 1m, not 1000k
            i++;
            rounded = Math.round(amount / unit[i] * 10.0) / 10.0;
        }

        String number = rounded == Math.rint(rounded)
                ? String.valueOf((long) rounded)
                : String.format(Locale.US, "%.1f", rounded);
        return number + suffix[i];
    }

    private static Component noItalic(Component c) {
        return c.decoration(TextDecoration.ITALIC, false);
    }

    private static Component color(String text) {
        return LegacyComponentSerializer.legacyAmpersand().deserialize(text == null ? "" : text);
    }

    /** Respects the /settings sound toggle (settings:sounds_enabled = 0 mutes). */
    private void play(Player player, Sound sound, float pitch) {
        Byte enabled = player.getPersistentDataContainer().get(SOUNDS_KEY, PersistentDataType.BYTE);
        if (enabled != null && enabled == 0) return;
        player.playSound(player, sound, 1f, pitch);
    }
}
