package com.example.simpleeconomy;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.*;

/**
 * Every menu is a plain chest inventory - no dialogs or text-entry popups, so it all works the
 * same for Bedrock players connecting through Geyser as it does for Java players. Anything that
 * needs typed text (a price, an item name, an amount) is a command instead; menus only need clicks.
 */
public class Gui implements Listener {

    private static abstract class Holder implements InventoryHolder {
        Inventory inv;
        @Override public Inventory getInventory() { return inv; }
    }

    private static class AhHolder extends Holder {
        int page;
        boolean mine;
        final List<Listing> shown = new ArrayList<>();
    }

    private static class BuyConfirmHolder extends Holder {
        Listing listing;
    }

    /** Confirms a /ah sell command. Re-checks the held item hasn't changed before listing it. */
    private static class SellConfirmHolder extends Holder {
        ItemStack snapshot;
        double price;
    }

    private static class OrdersHolder extends Holder {
        int page;
        boolean mine;
        final List<Order> shown = new ArrayList<>();
    }

    /** Confirms an /orders create command. */
    private static class OrderConfirmHolder extends Holder {
        Material material;
        int amount;
        double priceEach;
    }

    private final SimpleEconomyPlugin plugin;
    private final EconomyManager eco;
    private final AuctionManager auction;
    private final OrderManager orders;

    public Gui(SimpleEconomyPlugin plugin, EconomyManager eco, AuctionManager auction, OrderManager orders) {
        this.plugin = plugin;
        this.eco = eco;
        this.auction = auction;
        this.orders = orders;
    }

    // ------------------------------------------------------------ helpers

    private ItemStack button(Material m, String name, String... lore) {
        ItemStack i = new ItemStack(m);
        ItemMeta meta = i.getItemMeta();
        meta.displayName(Msg.lore(name));
        if (lore.length > 0) meta.lore(Arrays.stream(lore).map(Msg::lore).toList());
        i.setItemMeta(meta);
        return i;
    }

    private void fillBar(Inventory inv, int from) {
        ItemStack pane = button(Material.GRAY_STAINED_GLASS_PANE, " ");
        for (int i = from; i < inv.getSize(); i++) inv.setItem(i, pane);
    }

    private void later(Runnable r) {
        Bukkit.getScheduler().runTask(plugin, r);
    }

    // ------------------------------------------------------------ auction house

    public void openAh(Player p, int page, boolean mine) {
        AhHolder h = new AhHolder();
        h.mine = mine;
        List<Listing> list = auction.all().stream()
                .filter(l -> !mine || l.seller().equals(p.getUniqueId()))
                .sorted(Comparator.comparingLong(Listing::expiresAt).reversed())
                .toList();

        int pages = Math.max(1, (list.size() + 44) / 45);
        page = Math.max(0, Math.min(page, pages - 1));
        h.page = page;

        Inventory inv = Bukkit.createInventory(h, 54,
                Component.text(mine ? "Your Listings" : "Auction House (" + (page + 1) + "/" + pages + ")"));
        h.inv = inv;

        for (int i = 0; i < 45; i++) {
            int idx = page * 45 + i;
            if (idx >= list.size()) break;
            Listing l = list.get(idx);
            h.shown.add(l);
            inv.setItem(i, listingDisplay(l, mine));
        }

        fillBar(inv, 45);
        if (page > 0) inv.setItem(45, button(Material.ARROW, "&ePrevious page"));
        inv.setItem(46, mine
                ? button(Material.CHEST, "&eBack to Auction House")
                : button(Material.PLAYER_HEAD, "&eYour listings", "&7View or cancel your listings"));
        inv.setItem(47, button(Material.OAK_SIGN, "&bSearch",
                "&7Use &f/ah search <text>", "&7Use &f/ah &7with no search to see everything"));
        inv.setItem(48, button(Material.ENDER_CHEST, "&6Expired items",
                "&7Items waiting: &f" + auction.expiredCount(p.getUniqueId()), "&eClick to claim"));
        inv.setItem(49, button(Material.SUNFLOWER, "&aBalance: " + MoneyUtil.format(eco.get(p.getUniqueId())),
                "&7Click to refresh"));
        inv.setItem(50, button(Material.BOOK, "&fHow to sell",
                "&7Hold the item and run", "&f/sell <price>", "&7or &f/ah sell <price>"));
        if (page < pages - 1) inv.setItem(53, button(Material.ARROW, "&eNext page"));
        p.openInventory(inv);
    }

    /** Only used by /ah search <text>: same browse menu, filtered. */
    public void openAhSearch(Player p, String query) {
        AhHolder h = new AhHolder();
        List<Listing> list = auction.all().stream()
                .filter(l -> matches(l, query))
                .sorted(Comparator.comparingLong(Listing::expiresAt).reversed())
                .toList();
        Inventory inv = Bukkit.createInventory(h, 54, Component.text("Search: \"" + query + "\""));
        h.inv = inv;
        for (int i = 0; i < Math.min(45, list.size()); i++) {
            Listing l = list.get(i);
            h.shown.add(l);
            inv.setItem(i, listingDisplay(l, l.seller().equals(p.getUniqueId())));
        }
        fillBar(inv, 45);
        inv.setItem(46, button(Material.CHEST, "&eBack to Auction House"));
        p.openInventory(inv);
    }

    private boolean matches(Listing l, String query) {
        if (query == null || query.isBlank()) return true;
        String hay = (l.item().getType().name().replace('_', ' ') + " " + l.sellerName()).toLowerCase(Locale.ROOT);
        for (String token : query.toLowerCase(Locale.ROOT).trim().split("\\s+")) {
            if (!hay.contains(token)) return false;
        }
        return true;
    }

    private ItemStack listingDisplay(Listing l, boolean mine) {
        ItemStack it = l.item().clone();
        ItemMeta meta = it.getItemMeta();
        List<Component> lore = meta.lore() != null ? new ArrayList<>(meta.lore()) : new ArrayList<>();
        lore.add(Component.empty());
        lore.add(Msg.lore("&7Price: &a" + MoneyUtil.format(l.price())));
        lore.add(Msg.lore("&7Seller: &f" + l.sellerName()));
        lore.add(Msg.lore("&7Expires in: &f" + Msg.time(l.expiresAt() - System.currentTimeMillis())));
        lore.add(Component.empty());
        lore.add(Msg.lore(mine ? "&cClick to cancel and get the item back" : "&eClick to buy"));
        meta.lore(lore);
        it.setItemMeta(meta);
        return it;
    }

    private void openBuyConfirm(Player p, Listing l) {
        BuyConfirmHolder h = new BuyConfirmHolder();
        h.listing = l;
        Inventory inv = Bukkit.createInventory(h, 27, Component.text("Confirm Purchase"));
        h.inv = inv;
        inv.setItem(13, listingDisplay(l, false));
        inv.setItem(11, button(Material.LIME_CONCRETE, "&aConfirm", "&7Pay &a" + MoneyUtil.format(l.price())));
        inv.setItem(15, button(Material.RED_CONCRETE, "&cCancel"));
        p.openInventory(inv);
    }

    /** Opened by the /ah sell (or /sell) command after basic checks pass. */
    public void openSellConfirm(Player p, ItemStack snapshot, double price) {
        SellConfirmHolder h = new SellConfirmHolder();
        h.snapshot = snapshot;
        h.price = price;
        double tax = plugin.getConfig().getDouble("auction.tax-percent", 0);
        Inventory inv = Bukkit.createInventory(h, 27, Component.text("Confirm Listing"));
        h.inv = inv;
        ItemStack display = snapshot.clone();
        ItemMeta meta = display.getItemMeta();
        List<Component> lore = new ArrayList<>();
        lore.add(Msg.lore("&7Price: &a" + MoneyUtil.format(price)));
        if (tax > 0) lore.add(Msg.lore("&7You receive after tax: &e" + MoneyUtil.format(price - price * tax / 100.0)));
        lore.add(Msg.lore("&7Lasts &f" + plugin.getConfig().getLong("auction.duration-hours", 48) + " hours&7."));
        meta.lore(lore);
        display.setItemMeta(meta);
        inv.setItem(13, display);
        inv.setItem(11, button(Material.LIME_CONCRETE, "&aList it"));
        inv.setItem(15, button(Material.RED_CONCRETE, "&cCancel"));
        p.openInventory(inv);
    }

    private void claimExpired(Player p) {
        List<ItemStack> items = auction.takeExpired(p.getUniqueId());
        if (items.isEmpty()) {
            p.sendMessage(Msg.c("&cYou have no expired items."));
            return;
        }
        int given = 0;
        for (ItemStack it : items) {
            Map<Integer, ItemStack> left = p.getInventory().addItem(it);
            if (left.isEmpty()) given++;
            else left.values().forEach(x -> auction.addExpired(p.getUniqueId(), x));
        }
        p.sendMessage(Msg.c("&aClaimed &f" + given + "&a expired item stack(s)."
                + (auction.expiredCount(p.getUniqueId()) > 0 ? " &eInventory full - claim the rest later." : "")));
    }

    // ------------------------------------------------------------ orders

    public void openOrders(Player p, int page, boolean mine) {
        OrdersHolder h = new OrdersHolder();
        h.mine = mine;
        List<Order> list = orders.all().stream()
                .filter(o -> mine ? o.owner.equals(p.getUniqueId()) : o.remaining() > 0)
                .sorted(Comparator.<Order>comparingDouble(o -> o.priceEach).reversed())
                .toList();

        int pages = Math.max(1, (list.size() + 44) / 45);
        page = Math.max(0, Math.min(page, pages - 1));
        h.page = page;

        Inventory inv = Bukkit.createInventory(h, 54,
                Component.text(mine ? "Your Orders" : "Orders (" + (page + 1) + "/" + pages + ")"));
        h.inv = inv;

        for (int i = 0; i < 45; i++) {
            int idx = page * 45 + i;
            if (idx >= list.size()) break;
            Order o = list.get(idx);
            h.shown.add(o);
            inv.setItem(i, orderDisplay(o, mine));
        }

        fillBar(inv, 45);
        if (page > 0) inv.setItem(45, button(Material.ARROW, "&ePrevious page"));
        inv.setItem(46, mine
                ? button(Material.CHEST, "&eBack to all orders")
                : button(Material.PLAYER_HEAD, "&eYour orders", "&7Collect items or cancel orders"));
        inv.setItem(48, button(Material.BOOK, "&fCreate an order",
                "&7Use &f/orders create <item> <amount> <price each>"));
        inv.setItem(49, button(Material.SUNFLOWER, "&aBalance: " + MoneyUtil.format(eco.get(p.getUniqueId())),
                "&7Click to refresh"));
        if (page < pages - 1) inv.setItem(53, button(Material.ARROW, "&eNext page"));
        p.openInventory(inv);
    }

    private ItemStack orderDisplay(Order o, boolean mine) {
        ItemStack it = new ItemStack(o.material);
        ItemMeta meta = it.getItemMeta();
        meta.displayName(Msg.lore("&f" + ItemNames.pretty(o.material)));
        List<Component> lore = new ArrayList<>();
        lore.add(Msg.lore("&7Price each: &a" + MoneyUtil.format(o.priceEach)));
        if (mine) {
            lore.add(Msg.lore("&7Filled: &f" + o.filled + "/" + o.total));
            lore.add(Msg.lore("&7Waiting to collect: &f" + o.pending));
            lore.add(Component.empty());
            lore.add(Msg.lore("&aLeft-click: collect items"));
            lore.add(Msg.lore("&cRight-click: cancel & refund"));
        } else {
            lore.add(Msg.lore("&7Buyer: &f" + o.ownerName));
            lore.add(Msg.lore("&7Still wanted: &f" + o.remaining() + "/" + o.total));
            lore.add(Msg.lore("&7Total payout: &a" + MoneyUtil.format(o.remaining() * o.priceEach)));
            lore.add(Component.empty());
            lore.add(Msg.lore("&eClick to fill with items from your inventory"));
            lore.add(Msg.lore("&8Only plain items (no custom name/enchants)"));
        }
        meta.lore(lore);
        it.setItemMeta(meta);
        return it;
    }

    /** Opened by the /orders create command after basic checks pass. */
    public void openOrderConfirm(Player p, Material mat, int amount, double priceEach) {
        OrderConfirmHolder h = new OrderConfirmHolder();
        h.material = mat;
        h.amount = amount;
        h.priceEach = priceEach;
        Inventory inv = Bukkit.createInventory(h, 27, Component.text("Confirm Order"));
        h.inv = inv;
        ItemStack display = new ItemStack(mat);
        ItemMeta meta = display.getItemMeta();
        meta.displayName(Msg.lore("&f" + amount + "x " + ItemNames.pretty(mat)));
        meta.lore(List.of(
                Msg.lore("&7Price each: &a" + MoneyUtil.format(priceEach)),
                Msg.lore("&7Total held now: &e" + MoneyUtil.format(MoneyUtil.round(amount * priceEach))),
                Msg.lore("&7Refunded if you cancel before it's filled.")));
        display.setItemMeta(meta);
        inv.setItem(13, display);
        inv.setItem(11, button(Material.LIME_CONCRETE, "&aPlace order"));
        inv.setItem(15, button(Material.RED_CONCRETE, "&cCancel"));
        p.openInventory(inv);
    }

    // ------------------------------------------------------------ leaderboard

    public void openLeaderboard(Player p) {
        Holder h = new Holder() {};
        Inventory inv = Bukkit.createInventory(h, 27, Component.text("Top Balances"));
        h.inv = inv;
        fillBar(inv, 0);

        int[] slots = {11, 10, 12, 19, 20, 21, 22, 23, 24, 25};
        List<Map.Entry<UUID, Double>> top = eco.top(10);
        for (int i = 0; i < top.size() && i < slots.length; i++) {
            UUID id = top.get(i).getKey();
            OfflinePlayer op = Bukkit.getOfflinePlayer(id);
            String name = op.getName() != null ? op.getName() : id.toString().substring(0, 8);
            ItemStack head = new ItemStack(Material.PLAYER_HEAD);
            SkullMeta meta = (SkullMeta) head.getItemMeta();
            meta.setOwningPlayer(op);
            meta.displayName(Msg.lore("&e#" + (i + 1) + " &f" + name));
            meta.lore(List.of(Msg.lore("&a" + MoneyUtil.format(top.get(i).getValue()))));
            head.setItemMeta(meta);
            inv.setItem(slots[i], head);
        }
        inv.setItem(4, button(Material.SUNFLOWER, "&aYour balance: " + MoneyUtil.format(eco.get(p.getUniqueId())),
                "&7Rank: &f#" + eco.rank(p.getUniqueId())));
        p.openInventory(inv);
    }

    // ------------------------------------------------------------ events

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (e.getView().getTopInventory().getHolder() instanceof Holder) e.setCancelled(true);
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        Inventory top = e.getView().getTopInventory();
        if (!(top.getHolder() instanceof Holder holder)) return;
        e.setCancelled(true);
        if (e.getClickedInventory() != top) return;
        if (!(e.getWhoClicked() instanceof Player p)) return;
        int slot = e.getRawSlot();

        if (holder instanceof AhHolder h) handleAh(p, h, slot);
        else if (holder instanceof BuyConfirmHolder h) handleBuyConfirm(p, h, slot);
        else if (holder instanceof SellConfirmHolder h) handleSellConfirm(p, h, slot);
        else if (holder instanceof OrdersHolder h) handleOrders(p, h, slot, e.isRightClick());
        else if (holder instanceof OrderConfirmHolder h) handleOrderConfirm(p, h, slot);
    }

    private void handleAh(Player p, AhHolder h, int slot) {
        if (slot < 45) {
            if (slot >= h.shown.size()) return;
            Listing l = h.shown.get(slot);
            if (h.mine) {
                if (auction.cancel(l)) {
                    Map<Integer, ItemStack> left = p.getInventory().addItem(l.item().clone());
                    left.values().forEach(x -> auction.addExpired(p.getUniqueId(), x));
                    p.sendMessage(Msg.c("&aListing cancelled."
                            + (left.isEmpty() ? "" : " &eInventory full - item moved to expired items.")));
                }
                later(() -> openAh(p, h.page, true));
            } else {
                later(() -> openBuyConfirm(p, l));
            }
            return;
        }
        switch (slot) {
            case 45 -> later(() -> openAh(p, h.page - 1, h.mine));
            case 53 -> later(() -> openAh(p, h.page + 1, h.mine));
            case 46 -> later(() -> openAh(p, 0, !h.mine));
            case 48 -> {
                claimExpired(p);
                later(() -> openAh(p, h.page, h.mine));
            }
            case 49 -> later(() -> openAh(p, h.page, h.mine));
            default -> {}
        }
    }

    private void handleBuyConfirm(Player p, BuyConfirmHolder h, int slot) {
        if (slot == 11) {
            String err = auction.buy(p, h.listing);
            if (err != null) {
                p.sendMessage(Msg.c(err));
            } else {
                p.sendMessage(Msg.c("&aPurchased for &2" + MoneyUtil.format(h.listing.price()) + "&a."));
            }
            later(() -> openAh(p, 0, false));
        } else if (slot == 15) {
            later(() -> openAh(p, 0, false));
        }
    }

    private void handleSellConfirm(Player p, SellConfirmHolder h, int slot) {
        if (slot == 11) {
            ItemStack hand = p.getInventory().getItemInMainHand();
            if (!hand.isSimilar(h.snapshot) || hand.getAmount() != h.snapshot.getAmount()) {
                p.sendMessage(Msg.c("&cThe item in your hand changed, so the listing was cancelled."));
                later(() -> openAh(p, 0, false));
                return;
            }
            int max = plugin.getConfig().getInt("auction.max-listings", 10);
            if (auction.countBy(p.getUniqueId()) >= max) {
                p.sendMessage(Msg.c("&cYou can only have " + max + " active listings."));
                later(() -> openAh(p, 0, false));
                return;
            }
            p.getInventory().setItemInMainHand(null);
            auction.create(p, h.snapshot, h.price);
            p.sendMessage(Msg.c("&aListed &f" + h.snapshot.getAmount() + "x " + ItemNames.pretty(h.snapshot.getType())
                    + " &afor &2" + MoneyUtil.format(h.price) + "&a."));
            later(() -> openAh(p, 0, true));
        } else if (slot == 15) {
            p.sendMessage(Msg.c("&7Listing cancelled."));
            later(() -> openAh(p, 0, false));
        }
    }

    private void handleOrders(Player p, OrdersHolder h, int slot, boolean right) {
        if (slot < 45) {
            if (slot >= h.shown.size()) return;
            Order o = h.shown.get(slot);
            String result;
            if (h.mine) result = right ? orders.cancel(p, o) : orders.collect(p, o);
            else result = orders.fill(p, o);
            p.sendMessage(Msg.c(result));
            later(() -> openOrders(p, h.page, h.mine));
            return;
        }
        switch (slot) {
            case 45 -> later(() -> openOrders(p, h.page - 1, h.mine));
            case 53 -> later(() -> openOrders(p, h.page + 1, h.mine));
            case 46 -> later(() -> openOrders(p, 0, !h.mine));
            case 49 -> later(() -> openOrders(p, h.page, h.mine));
            default -> {}
        }
    }

    private void handleOrderConfirm(Player p, OrderConfirmHolder h, int slot) {
        if (slot == 11) {
            String err = orders.create(p, h.material, h.amount, h.priceEach);
            if (err != null) {
                p.sendMessage(Msg.c(err));
                later(() -> openOrders(p, 0, false));
                return;
            }
            p.sendMessage(Msg.c("&aOrder placed for &f" + h.amount + "x " + ItemNames.pretty(h.material)
                    + " &aat &2" + MoneyUtil.format(h.priceEach) + " &aeach."));
            later(() -> openOrders(p, 0, true));
        } else if (slot == 15) {
            p.sendMessage(Msg.c("&7Order cancelled."));
            later(() -> openOrders(p, 0, false));
        }
    }
}
