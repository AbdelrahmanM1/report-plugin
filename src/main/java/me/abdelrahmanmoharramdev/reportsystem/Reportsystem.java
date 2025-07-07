package me.abdelrahmanmoharramdev.reportsystem;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.*;

public final class Reportsystem extends JavaPlugin {

    private String webhookUrl;

    // Anti-spam cooldown
    private final Map<UUID, Long> reportCooldowns = new HashMap<>();
    private final long cooldownTime = 10 * 1000; // 10 seconds (in milliseconds)

    @Override
    public void onEnable() {
        saveDefaultConfig();
        this.webhookUrl = getConfig().getString("webhook-url");

        if (webhookUrl == null || webhookUrl.isEmpty()) {
            getLogger().warning("Webhook URL is not set in config.yml!");
        }

        // Registering Player Quit event to clear cooldowns
        getServer().getPluginManager().registerEvents(new org.bukkit.event.Listener() {
            @org.bukkit.event.EventHandler
            public void onPlayerQuit(org.bukkit.event.player.PlayerQuitEvent event) {
                reportCooldowns.remove(event.getPlayer().getUniqueId());
            }
        }, this);

        getLogger().info("ReportSystem Enabled");
    }

    @Override
    public void onDisable() {
        getLogger().info("ReportSystem Disabled");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("report")) {
            return handleReportCommand(sender, args);
        }

        if (command.getName().equalsIgnoreCase("reportlist")) {
            return handleReportListCommand(sender);
        }

        return false;
    }

    private boolean handleReportCommand(CommandSender sender, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(ChatColor.RED + "Only players can use this command.");
            return true;
        }

        Player reporter = (Player) sender;

        if (!reporter.hasPermission("reportsystem.report")) {
            reporter.sendMessage(ChatColor.RED + "You don't have permission to use this command.");
            return true;
        }

        // Anti-spam: Check cooldown
        UUID uuid = reporter.getUniqueId();
        long now = System.currentTimeMillis();
        if (reportCooldowns.containsKey(uuid)) {
            long lastUsed = reportCooldowns.get(uuid);
            if (now - lastUsed < cooldownTime) {
                long secondsLeft = (cooldownTime - (now - lastUsed)) / 1000;
                reporter.sendMessage(ChatColor.RED + "⏳ Please wait " + secondsLeft + " more second(s) before using /report again.");
                return true;
            }
        }
        reportCooldowns.put(uuid, now);

        if (args.length < 2) {
            reporter.sendMessage(ChatColor.RED + "Usage: /report <player> <reason>");
            return true;
        }

        String reportedName = args[0];
        if (reportedName.equalsIgnoreCase(reporter.getName())) {
            reporter.sendMessage(ChatColor.RED + "You can't report yourself.");
            return true;
        }

        Player reportedPlayer = Bukkit.getPlayerExact(reportedName);
        if (reportedPlayer == null) {
            reporter.sendMessage(ChatColor.RED + "The player '" + reportedName + "' is not online.");
            return true;
        }

        String reason = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
        String time = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());

        reporter.sendMessage(ChatColor.GREEN + "✅ Report submitted.");

        sendToWebhook(reporter.getName(), reportedName, reason, time);
        logToFile(time, reporter.getName(), reportedName, reason);
        notifyModerators(reporter.getName(), reportedName, reason);

        return true;
    }

    private boolean handleReportListCommand(CommandSender sender) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(ChatColor.RED + "Only players can use this command.");
            return true;
        }

        Player player = (Player) sender;
        if (!player.isOp() && !player.hasPermission("reportsystem.view")) {
            player.sendMessage(ChatColor.RED + "You don't have permission to use this command.");
            return true;
        }

        File logFile = new File(getDataFolder(), "reports.log");
        if (!logFile.exists()) {
            player.sendMessage(ChatColor.YELLOW + "No reports have been logged yet.");
            return true;
        }

        try {
            List<String> lines = new ArrayList<>();
            BufferedReader reader = new BufferedReader(new FileReader(logFile));
            String line;
            while ((line = reader.readLine()) != null) {
                lines.add(line);
            }
            reader.close();

            int count = 0;
            player.sendMessage(ChatColor.AQUA + "Recent Reports:");
            for (int i = lines.size() - 1; i >= 0 && count < 5; i--, count++) {
                player.sendMessage(ChatColor.GRAY + "- " + lines.get(i));
            }

        } catch (IOException e) {
            player.sendMessage(ChatColor.RED + "Failed to read the report log.");
            e.printStackTrace();
        }

        return true;
    }

    private void sendToWebhook(String reporter, String reported, String reason, String time) {
        if (webhookUrl == null || webhookUrl.isEmpty()) return;

        String json = "{"
                + "\"embeds\": [{"
                + "\"title\": \"🚨 New Player Report\","
                + "\"color\": 15158332,"
                + "\"fields\": ["
                + "  {\"name\": \"📅 Time\", \"value\": \"" + time + "\", \"inline\": true},"
                + "  {\"name\": \"👤 Reporter\", \"value\": \"" + reporter + "\", \"inline\": true},"
                + "  {\"name\": \"🔴 Reported\", \"value\": \"" + reported + "\", \"inline\": true},"
                + "  {\"name\": \"📝 Reason\", \"value\": \"" + reason + "\"}"
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
                getLogger().warning("Failed to send embed to Discord webhook.");
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
