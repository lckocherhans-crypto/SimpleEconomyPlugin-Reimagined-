package com.example.simpleeconomy;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.*;
import java.util.stream.Collectors;

public class Commands implements CommandExecutor, TabCompleter {
    private final SimpleEconomyPlugin plugin;
    private final EconomyManager eco;
    private final AuctionManager auction;
    private final OrderManager orders;
    private final PriceManager prices;
    private final Gui gui;

    public Commands(SimpleEconomyPlugin plugin, EconomyManager eco, AuctionManager auction, OrderManager orders,
                     PriceManager prices, Gui gui) {
        this.plugin = plugin;
        this.eco = eco;
        this.auction = auction;
        this.orders = orders;
        this.prices = prices;
        this.gui = gui;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        switch (cmd.getName().toLowerCase(Locale.ROOT)) {
            case "balance" -> balance(sender, args);
            case "pay" -> pay(sender, args);
            case "baltop" -> baltop(sender);
            case "eco" -> ecoAdmin(sender, args);
            case "ah" -> ah(sender, args);
            case "sell" -> sellCmd(sender, args);
            case "orders" -> ordersCmd(sender, args);
            default -> { return false; }
        }
        return true;
    }

    private boolean playersOnly(CommandSender s) {
        s.sendMessage(Msg.c("&cOnly players can use this."));
        return true;
    }

    // ---------------------------------------------------------- economy

    private void balance(CommandSender sender, String[] args) {
        if (args.length == 0) {
            if (!(sender instanceof Player p)) { playersOnly(sender); return; }
            p.sendMessage(Msg.c("&7Balance: &a" + MoneyUtil.format(eco.get(p.getUniqueId()))));
            return;
        }
        OfflinePlayer t = Bukkit.getOfflinePlayerIfCached(args[0]);
        if (t == null) { sender.sendMessage(Msg.c("&cPlayer not found.")); return; }
        sender.sendMessage(Msg.c("&7" + t.getName() + "'s balance: &a" + MoneyUtil.format(eco.get(t.getUniqueId()))));
    }

    private void pay(CommandSender sender, String[] args) {
        if (!(sender instanceof Player p)) { playersOnly(sender); return; }
        if (args.length < 2) { p.sendMessage(Msg.c("&cUsage: /pay <player> <amount>")); return; }
        Player t = Bukkit.getPlayerExact(args[0]);
        if (t == null) { p.sendMessage(Msg.c("&cThat player is not online.")); return; }
        if (t.equals(p)) { p.sendMessage(Msg.c("&cYou can't pay yourself.")); return; }
        OptionalDouble amt = MoneyUtil.parse(args[1]);
        if (amt.isEmpty() || amt.getAsDouble() < 0.01) { p.sendMessage(Msg.c("&cInvalid amount.")); return; }
        if (!eco.withdraw(p.getUniqueId(), amt.getAsDouble())) { p.sendMessage(Msg.c("&cYou can't afford that.")); return; }
        eco.deposit(t.getUniqueId(), amt.getAsDouble());
        p.sendMessage(Msg.c("&aYou paid &f" + t.getName() + " &2" + MoneyUtil.format(amt.getAsDouble()) + "&a."));
        t.sendMessage(Msg.c("&aYou received &2" + MoneyUtil.format(amt.getAsDouble()) + " &afrom &f" + p.getName() + "&a."));
    }

    private void baltop(CommandSender sender) {
        if (sender instanceof Player p) {
            gui.openLeaderboard(p);
            return;
        }
        sender.sendMessage(Msg.c("&6&lTop Balances"));
        int i = 1;
        for (Map.Entry<UUID, Double> e : eco.top(10)) {
            OfflinePlayer op = Bukkit.getOfflinePlayer(e.getKey());
            String name = op.getName() != null ? op.getName() : e.getKey().toString().substring(0, 8);
            sender.sendMessage(Msg.c("&e" + i++ + ". &f" + name + " &7- &a" + MoneyUtil.format(e.getValue())));
        }
    }

    private void ecoAdmin(CommandSender sender, String[] args) {
        if (!sender.hasPermission("simpleeconomy.admin")) { sender.sendMessage(Msg.c("&cNo permission.")); return; }
        if (args.length == 0) {
            sender.sendMessage(Msg.c("&cUsage: /eco <give|take|set> <player> <amount>"));
            sender.sendMessage(Msg.c("&cUsage: /eco sellprice <item> <price|remove>"));
            sender.sendMessage(Msg.c("&cUsage: /eco sellmultiplier <value>"));
            return;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "give", "take", "set" -> {
                if (args.length < 3) { sender.sendMessage(Msg.c("&cUsage: /eco " + sub + " <player> <amount>")); return; }
                OfflinePlayer t = Bukkit.getOfflinePlayerIfCached(args[1]);
                if (t == null) { sender.sendMessage(Msg.c("&cPlayer not found.")); return; }
                OptionalDouble amt = MoneyUtil.parse(args[2]);
                if (amt.isEmpty()) { sender.sendMessage(Msg.c("&cInvalid amount.")); return; }
                UUID id = t.getUniqueId();
                switch (sub) {
                    case "give" -> eco.deposit(id, amt.getAsDouble());
                    case "take" -> eco.set(id, eco.get(id) - amt.getAsDouble());
                    case "set" -> eco.set(id, amt.getAsDouble());
                }
                sender.sendMessage(Msg.c("&a" + t.getName() + " now has &2" + MoneyUtil.format(eco.get(id)) + "&a."));
            }
            case "sellprice" -> {
                if (args.length < 3) { sender.sendMessage(Msg.c("&cUsage: /eco sellprice <item> <price|remove>")); return; }
                Material mat = Material.matchMaterial(args[1]);
                if (mat == null || mat.isAir() || !mat.isItem()) { sender.sendMessage(Msg.c("&cUnknown item.")); return; }
                if (args[2].equalsIgnoreCase("remove")) {
                    prices.removeBase(mat);
                    sender.sendMessage(Msg.c("&a" + ItemNames.pretty(mat) + " is no longer sellable via /sell."));
                    return;
                }
                OptionalDouble price = MoneyUtil.parse(args[2]);
                if (price.isEmpty() || price.getAsDouble() < 0) { sender.sendMessage(Msg.c("&cInvalid price.")); return; }
                prices.setBase(mat, price.getAsDouble());
                sender.sendMessage(Msg.c("&aBase sell price for " + ItemNames.pretty(mat) + " set to &2"
                        + MoneyUtil.format(price.getAsDouble()) + " &aeach."));
            }
            case "sellmultiplier" -> {
                if (args.length < 2) {
                    sender.sendMessage(Msg.c("&7Current multiplier: &f" + prices.multiplier()));
                    return;
                }
                OptionalDouble v = MoneyUtil.parse(args[1]);
                if (v.isEmpty()) { sender.sendMessage(Msg.c("&cInvalid value.")); return; }
                prices.setMultiplier(v.getAsDouble());
                sender.sendMessage(Msg.c("&aSell multiplier set to &f" + prices.multiplier() + "&a."));
            }
            default -> sender.sendMessage(Msg.c("&cUsage: /eco <give|take|set|sellprice|sellmultiplier> ..."));
        }
    }

    // ---------------------------------------------------------- auction house

    private void ah(CommandSender sender, String[] args) {
        if (!(sender instanceof Player p)) { playersOnly(sender); return; }
        if (args.length == 0) { gui.openAh(p, 0, false); return; }
        if (args[0].equalsIgnoreCase("sell")) {
            sellOnAh(p, args.length < 2 ? null : args[1]);
        } else if (args[0].equalsIgnoreCase("search")) {
            gui.openAhSearch(p, String.join(" ", Arrays.copyOfRange(args, 1, args.length)));
        } else {
            p.sendMessage(Msg.c("&cUsage: /ah, /ah sell <price>, /ah search <text>"));
        }
    }

    private void sellOnAh(Player p, String priceArg) {
        if (priceArg == null) { p.sendMessage(Msg.c("&cUsage: /ah sell <price> (hold the item first)")); return; }
        ItemStack hand = p.getInventory().getItemInMainHand();
        if (hand.getType().isAir()) { p.sendMessage(Msg.c("&cHold the item you want to sell.")); return; }
        OptionalDouble price = MoneyUtil.parse(priceArg);
        double min = plugin.getConfig().getDouble("auction.min-price", 1);
        if (price.isEmpty() || price.getAsDouble() < min) {
            p.sendMessage(Msg.c("&cInvalid price (minimum " + MoneyUtil.format(min) + ")."));
            return;
        }
        int max = plugin.getConfig().getInt("auction.max-listings", 10);
        if (auction.countBy(p.getUniqueId()) >= max) {
            p.sendMessage(Msg.c("&cYou can only have " + max + " active listings."));
            return;
        }
        gui.openSellConfirm(p, hand.clone(), price.getAsDouble());
    }

    // ---------------------------------------------------------- /sell (fixed base prices)

    private void sellCmd(CommandSender sender, String[] args) {
        if (!(sender instanceof Player p)) { playersOnly(sender); return; }
        if (args.length == 0) {
            ItemStack hand = p.getInventory().getItemInMainHand();
            if (!hand.getType().isAir() && prices.isSellable(hand.getType())) {
                double each = prices.sellPrice(hand.getType()).getAsDouble();
                p.sendMessage(Msg.c("&7In hand: &f" + hand.getAmount() + "x " + ItemNames.pretty(hand.getType())
                        + " &7- worth &a" + MoneyUtil.format(each * hand.getAmount())));
            }
            p.sendMessage(Msg.c("&7Use &f/sell hand &7to sell what's in your hand, or &f/sell all &7to sell everything sellable."));
            return;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "hand" -> sellHand(p);
            case "all" -> sellAll(p);
            default -> p.sendMessage(Msg.c("&cUsage: /sell, /sell hand, /sell all"));
        }
    }

    private static boolean isPlain(ItemStack it) {
        return it != null && !it.getType().isAir() && (!it.hasItemMeta() || (!it.getItemMeta().hasDisplayName() && !it.getItemMeta().hasEnchants()));
    }

    private void sellHand(Player p) {
        ItemStack hand = p.getInventory().getItemInMainHand();
        if (!isPlain(hand)) { p.sendMessage(Msg.c("&cYou aren't holding a plain, sellable item.")); return; }
        OptionalDouble each = prices.sellPrice(hand.getType());
        if (each.isEmpty()) { p.sendMessage(Msg.c("&cThat item can't be sold here.")); return; }
        double total = MoneyUtil.round(each.getAsDouble() * hand.getAmount());
        String name = ItemNames.pretty(hand.getType());
        int amount = hand.getAmount();
        p.getInventory().setItemInMainHand(null);
        eco.deposit(p.getUniqueId(), total);
        p.sendMessage(Msg.c("&aSold &f" + amount + "x " + name + " &afor &2" + MoneyUtil.format(total) + "&a."));
    }

    private void sellAll(Player p) {
        double total = 0;
        ItemStack[] contents = p.getInventory().getStorageContents();
        for (int i = 0; i < contents.length; i++) {
            ItemStack it = contents[i];
            if (!isPlain(it)) continue;
            OptionalDouble each = prices.sellPrice(it.getType());
            if (each.isEmpty()) continue;
            total += each.getAsDouble() * it.getAmount();
            contents[i] = null;
        }
        if (total <= 0) { p.sendMessage(Msg.c("&cNothing sellable in your inventory.")); return; }
        p.getInventory().setStorageContents(contents);
        total = MoneyUtil.round(total);
        eco.deposit(p.getUniqueId(), total);
        p.sendMessage(Msg.c("&aSold everything sellable for &2" + MoneyUtil.format(total) + "&a."));
    }

    // ---------------------------------------------------------- orders

    private void ordersCmd(CommandSender sender, String[] args) {
        if (!(sender instanceof Player p)) { playersOnly(sender); return; }
        if (args.length == 0) { gui.openOrders(p, 0, false); return; }
        if (!args[0].equalsIgnoreCase("create") || args.length < 4) {
            p.sendMessage(Msg.c("&cUsage: /orders, /orders create <item> <amount> <price each>"));
            return;
        }
        Material mat = Material.matchMaterial(args[1]);
        if (mat == null || mat.isAir() || !mat.isItem()) { p.sendMessage(Msg.c("&cUnknown item.")); return; }
        int amount;
        try {
            amount = Integer.parseInt(args[2]);
        } catch (NumberFormatException e) {
            p.sendMessage(Msg.c("&cInvalid amount."));
            return;
        }
        OptionalDouble price = MoneyUtil.parse(args[3]);
        if (price.isEmpty()) { p.sendMessage(Msg.c("&cInvalid price.")); return; }
        int maxOrders = plugin.getConfig().getInt("orders.max-orders", 5);
        int maxAmount = plugin.getConfig().getInt("orders.max-amount", 2304);
        if (orders.countBy(p.getUniqueId()) >= maxOrders) { p.sendMessage(Msg.c("&cYou can only have " + maxOrders + " active orders.")); return; }
        if (amount < 1 || amount > maxAmount) { p.sendMessage(Msg.c("&cAmount must be between 1 and " + maxAmount + ".")); return; }
        if (price.getAsDouble() < 0.01) { p.sendMessage(Msg.c("&cPrice must be at least $0.01 each.")); return; }
        double cost = MoneyUtil.round(amount * price.getAsDouble());
        if (eco.get(p.getUniqueId()) + 0.001 < cost) { p.sendMessage(Msg.c("&cYou need " + MoneyUtil.format(cost) + " for that order.")); return; }
        gui.openOrderConfirm(p, mat, amount, price.getAsDouble());
    }

    // ---------------------------------------------------------- tab complete

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String label, String[] args) {
        String name = cmd.getName().toLowerCase(Locale.ROOT);
        String last = args[args.length - 1].toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        switch (name) {
            case "pay", "balance" -> {
                if (args.length == 1) Bukkit.getOnlinePlayers().forEach(p -> out.add(p.getName()));
            }
            case "eco" -> {
                if (args.length == 1) out.addAll(List.of("give", "take", "set", "sellprice", "sellmultiplier"));
                else if (args.length == 2 && List.of("give", "take", "set").contains(args[0].toLowerCase(Locale.ROOT))) {
                    Bukkit.getOnlinePlayers().forEach(p -> out.add(p.getName()));
                } else if (args.length == 2 && args[0].equalsIgnoreCase("sellprice")) {
                    return matItems(last);
                }
            }
            case "ah" -> {
                if (args.length == 1) out.addAll(List.of("sell", "search"));
            }
            case "sell" -> {
                if (args.length == 1) out.addAll(List.of("hand", "all"));
            }
            case "orders" -> {
                if (args.length == 1) out.add("create");
                else if (args.length == 2 && args[0].equalsIgnoreCase("create")) return matItems(last);
            }
            default -> {}
        }
        return out.stream().filter(s -> s.toLowerCase(Locale.ROOT).startsWith(last)).collect(Collectors.toList());
    }

    private List<String> matItems(String prefix) {
        return Arrays.stream(Material.values())
                .filter(m -> m.isItem() && !m.isAir() && !m.name().startsWith("LEGACY_"))
                .map(m -> m.name().toLowerCase(Locale.ROOT))
                .filter(s -> s.startsWith(prefix))
                .limit(50)
                .collect(Collectors.toList());
    }
}
