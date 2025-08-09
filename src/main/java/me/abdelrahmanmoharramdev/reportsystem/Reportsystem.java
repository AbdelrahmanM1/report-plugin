package me.abdelrahmanmoharramdev.reportsystem;

import org.bukkit.*;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.FileWriter;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.*;

public final class Reportsystem extends JavaPlugin implements Listener {

    private String webhookUrl;
    private final Map<UUID, Long> reportCooldowns = new HashMap<>();
    private final Map<UUID, String> reportTargets = new HashMap<>();
    private final Map<UUID, Integer> guiPageTracker = new HashMap<>();
    private final Set<UUID> searchingPlayers = new HashSet<>();

    private final long cooldownTime = 10 * 1000;
    private final int PLAYERS_PER_PAGE = 45;

    private List<String> reportReasons = new ArrayList<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadConfigValues();
        getServer().getPluginManager().registerEvents(this, this);
        getLogger().info("✅ ReportSystem Enabled");
    }

    @Override
    public void onDisable() {
        getLogger().info("❌ ReportSystem Disabled");
    }

    private void loadConfigValues() {
        FileConfiguration config = getConfig();
        webhookUrl = config.getString("webhook-url");
        reportReasons = config.getStringList("report-reasons");

        if (reportReasons == null || reportReasons.isEmpty()) {
            reportReasons = Arrays.asList("Cheating", "Abusive Language", "Griefing");
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(ChatColor.RED + "Only players can use this command.");
            return true;
        }

        switch (command.getName().toLowerCase()) {
            case "report":
                openPlayerReportGUI(player, 0);
                return true;
            case "reportreload":
                reloadConfig();
                loadConfigValues();
                player.sendMessage(ChatColor.GREEN + "✅ ReportSystem config reloaded.");
                return true;
        }

        return false;
    }

    private void openPlayerReportGUI(Player reporter, int page) {
        UUID uuid = reporter.getUniqueId();
        long now = System.currentTimeMillis();

        if (reportCooldowns.containsKey(uuid) && now - reportCooldowns.get(uuid) < cooldownTime) {
            long secondsLeft = (cooldownTime - (now - reportCooldowns.get(uuid))) / 1000;
            reporter.sendMessage(ChatColor.RED + "⏳ Please wait " + secondsLeft + " seconds before reporting again.");
            return;
        }

        List<Player> players = new ArrayList<>(Bukkit.getOnlinePlayers());
        players.remove(reporter);

        int totalPages = (int) Math.ceil((double) players.size() / PLAYERS_PER_PAGE);
        page = Math.max(0, Math.min(page, totalPages - 1));
        guiPageTracker.put(uuid, page);

        Inventory gui = Bukkit.createInventory(null, 54, ChatColor.DARK_RED + "📝 Select Player (Page " + (page + 1) + ")");

        int start = page * PLAYERS_PER_PAGE;
        int end = Math.min(start + PLAYERS_PER_PAGE, players.size());

        for (int i = start; i < end; i++) {
            Player target = players.get(i);
            ItemStack skull = new ItemStack(Material.SKULL_ITEM, 1, (short) 3);
            SkullMeta meta = (SkullMeta) skull.getItemMeta();
            meta.setDisplayName(ChatColor.YELLOW + target.getName());
            meta.setOwner(target.getName());
            skull.setItemMeta(meta);
            gui.setItem(i - start, skull);
        }

        // Previous
        if (page > 0) {
            ItemStack prev = new ItemStack(Material.ARROW);
            ItemMeta meta = prev.getItemMeta();
            meta.setDisplayName(ChatColor.GREEN + "⬅ Previous Page");
            prev.setItemMeta(meta);
            gui.setItem(45, prev);
        }

        // Search button
        ItemStack search = new ItemStack(Material.COMPASS);
        ItemMeta searchMeta = search.getItemMeta();
        searchMeta.setDisplayName(ChatColor.AQUA + "🔍 Search Player");
        search.setItemMeta(searchMeta);
        gui.setItem(49, search);

        // Next
        if (page < totalPages - 1) {
            ItemStack next = new ItemStack(Material.ARROW);
            ItemMeta meta = next.getItemMeta();
            meta.setDisplayName(ChatColor.GREEN + "➡ Next Page");
            next.setItemMeta(meta);
            gui.setItem(53, next);
        }

        reporter.openInventory(gui);
    }

    private void openReasonGUI(Player reporter, String targetName) {
        Inventory gui = Bukkit.createInventory(null, 27, ChatColor.DARK_RED + "📝 Reason for " + targetName);

        for (int i = 0; i < reportReasons.size(); i++) {
            String reason = reportReasons.get(i);
            ItemStack item = new ItemStack(Material.PAPER);
            ItemMeta meta = item.getItemMeta();
            meta.setDisplayName(ChatColor.RED + reason);
            item.setItemMeta(meta);
            gui.setItem(i, item);
        }

        reporter.openInventory(gui);
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        Player clicker = (Player) event.getWhoClicked();
        ItemStack clicked = event.getCurrentItem();
        String title = event.getView().getTitle();
        event.setCancelled(true);

        if (clicked == null || !clicked.hasItemMeta()) return;

        String display = ChatColor.stripColor(clicked.getItemMeta().getDisplayName());

        if (title.contains("📝 Select Player")) {
            if (display.equals("⬅ Previous Page")) {
                int page = guiPageTracker.getOrDefault(clicker.getUniqueId(), 0);
                openPlayerReportGUI(clicker, page - 1);
                return;
            }

            if (display.equals("➡ Next Page")) {
                int page = guiPageTracker.getOrDefault(clicker.getUniqueId(), 0);
                openPlayerReportGUI(clicker, page + 1);
                return;
            }

            if (display.equals("🔍 Search Player")) {
                clicker.closeInventory();
                searchingPlayers.add(clicker.getUniqueId());
                clicker.sendMessage(ChatColor.AQUA + "Type the player's name in chat to report them. Type 'cancel' to stop.");
                return;
            }

            if (display.equals(clicker.getName())) {
                clicker.sendMessage(ChatColor.RED + "❌ You can't report yourself.");
                clicker.closeInventory();
                return;
            }

            reportTargets.put(clicker.getUniqueId(), display);
            openReasonGUI(clicker, display);
        }

        else if (title.contains("📝 Reason for")) {
            String reason = display;
            UUID uuid = clicker.getUniqueId();

            if (!reportTargets.containsKey(uuid)) {
                clicker.sendMessage(ChatColor.RED + "❌ No target selected.");
                clicker.closeInventory();
                return;
            }

            String reported = reportTargets.remove(uuid);
            String time = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());

            reportCooldowns.put(uuid, System.currentTimeMillis());
            clicker.sendMessage(ChatColor.GREEN + "✅ Report submitted.");

            sendToWebhook(clicker.getName(), reported, reason, time);
            logToFile(time, clicker.getName(), reported, reason);
            notifyModerators(clicker.getName(), reported, reason);

            clicker.closeInventory();
        }
    }

    @EventHandler
    public void onPlayerChat(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        if (!searchingPlayers.contains(player.getUniqueId())) return;

        event.setCancelled(true);
        String msg = event.getMessage().trim();

        if (msg.equalsIgnoreCase("cancel")) {
            player.sendMessage(ChatColor.RED + "Search cancelled.");
            searchingPlayers.remove(player.getUniqueId());
            return;
        }

        Player target = Bukkit.getPlayerExact(msg);
        if (target == null) {
            player.sendMessage(ChatColor.RED + "❌ Player not found.");
            return;
        }
        if (target.getName().equalsIgnoreCase(player.getName())) {
            player.sendMessage(ChatColor.RED + "❌ You can't report yourself.");
            return;
        }

        searchingPlayers.remove(player.getUniqueId());
        reportTargets.put(player.getUniqueId(), target.getName());

        Bukkit.getScheduler().runTask(this, () -> openReasonGUI(player, target.getName()));
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        reportTargets.remove(uuid);
        reportCooldowns.remove(uuid);
        guiPageTracker.remove(uuid);
        searchingPlayers.remove(uuid);
    }

    private void sendToWebhook(String reporter, String reported, String reason, String time) {
        if (webhookUrl == null || webhookUrl.isEmpty()) return;

        String json = "{"
                + "\"embeds\": [{"
                + "\"title\": \"🚨 New Report\","
                + "\"color\": 15158332,"
                + "\"fields\": ["
                + "{\"name\": \"📅 Time\", \"value\": \"" + time + "\", \"inline\": true},"
                + "{\"name\": \"👤 Reporter\", \"value\": \"" + reporter + "\", \"inline\": true},"
                + "{\"name\": \"🔴 Reported\", \"value\": \"" + reported + "\", \"inline\": true},"
                + "{\"name\": \"📝 Reason\", \"value\": \"" + reason + "\"}"
                + "]"
                + "}]"
                + "}";

        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            try {
                URL url = new URL(webhookUrl);
                HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod("POST");
                connection.setRequestProperty("Content-Type", "application/json");
                connection.setDoOutput(true);

                try (OutputStream os = connection.getOutputStream()) {
                    os.write(json.getBytes());
                    os.flush();
                }

                connection.getResponseCode();
                connection.disconnect();
            } catch (Exception e) {
                getLogger().warning("Failed to send to Discord webhook.");
                e.printStackTrace();
            }
        });
    }

    private void logToFile(String time, String reporter, String reported, String reason) {
        try {
            FileWriter writer = new FileWriter(getDataFolder() + "/reports.log", true);
            writer.write(String.format("[%s] %s reported %s for: %s%n", time, reporter, reported, reason));
            writer.close();
        } catch (Exception e) {
            getLogger().warning("Failed to write to reports.log");
            e.printStackTrace();
        }
    }

    private void notifyModerators(String reporter, String reported, String reason) {
        String msg = ChatColor.RED + "[ReportSystem] " + ChatColor.YELLOW +
                reporter + " reported " + reported + " for: " + ChatColor.GRAY + reason;

        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.hasPermission("reportsystem.notify")) {
                player.sendMessage(msg);
            }
        }
    }
}
