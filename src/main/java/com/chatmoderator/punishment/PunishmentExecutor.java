package com.chatmoderator.punishment;

import com.chatmoderator.ChatModeratorPlugin;
import com.google.gson.Gson;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 处罚执行：变量安全替换、以控制台身份执行命令、生成结构化处罚记录（需求 §6）。
 */
public class PunishmentExecutor {

    private static final Gson GSON = new Gson();
    private final ChatModeratorPlugin plugin;

    public PunishmentExecutor(ChatModeratorPlugin plugin) {
        this.plugin = plugin;
    }

    public void execute(Player player, List<String> bannedWords, String message) {
        String command = buildCommand(player.getName(), bannedWords, message);

        // 命令必须以控制台身份在主线程执行
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            boolean ok = plugin.getServer().dispatchCommand(
                    plugin.getServer().getConsoleSender(), command);
            if (!ok) {
                plugin.getLogger().warning("处罚命令执行失败（命令可能不存在或权限不足）: " + command);
            }
        });

        PunishmentRecord rec = new PunishmentRecord();
        rec.timestamp = System.currentTimeMillis();
        rec.playerUuid = player.getUniqueId().toString();
        rec.playerName = player.getName();
        rec.serverName = plugin.getConfigManager().getServerName();
        rec.bannedWords = bannedWords;
        rec.message = message;
        rec.command = command;
        rec.reason = "触发违禁词自动检测";
        saveRecord(rec);

        plugin.getLogManager().logPunishment(player.getName(), command);
    }

    /** 以玩家名执行处罚（玩家可能已离线，无法取得 Player 对象时使用）。 */
    public void executeByName(String playerName, List<String> bannedWords, String message) {
        String command = buildCommand(playerName, bannedWords, message);
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            boolean ok = plugin.getServer().dispatchCommand(
                    plugin.getServer().getConsoleSender(), command);
            if (!ok) {
                plugin.getLogger().warning("处罚命令执行失败（命令可能不存在或权限不足）: " + command);
            }
        });
        PunishmentRecord rec = new PunishmentRecord();
        rec.timestamp = System.currentTimeMillis();
        rec.playerUuid = "";
        rec.playerName = playerName;
        rec.serverName = plugin.getConfigManager().getServerName();
        rec.bannedWords = bannedWords;
        rec.message = message;
        rec.command = command;
        rec.reason = "触发违禁词自动检测";
        saveRecord(rec);
        plugin.getLogManager().logPunishment(playerName, command);
    }

    private String buildCommand(String playerName, List<String> bannedWords, String message) {
        String safe = sanitize(playerName);
        String template = plugin.getConfigManager().getPunishmentCommand();
        String words = bannedWords == null ? "" : String.join(",", bannedWords);
        return template
                .replace("{playerName}", safe)
                .replace("{serverName}", plugin.getConfigManager().getServerName())
                .replace("{bannedWords}", words)
                .replace("{reason}", "触发违禁词自动检测");
    }

    /** 防止玩家名注入命令分隔符（; | &）或换行截断。 */
    private String sanitize(String name) {
        if (name == null) return "unknown";
        return name.replace(";", "")
                .replace("|", "")
                .replace("&", "")
                .replace("\n", "")
                .replace("\r", "")
                .trim();
    }

    private void saveRecord(PunishmentRecord rec) {
        File dir = new File(plugin.getDataFolder(), "punishments");
        dir.mkdirs();
        String ts = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").format(LocalDateTime.now());
        File f = new File(dir, "punish_" + rec.playerUuid + "_" + ts + ".json");
        try (FileWriter fw = new FileWriter(f)) {
            fw.write(GSON.toJson(rec));
        } catch (IOException e) {
            plugin.getLogger().warning("处罚记录写入失败: " + e.getMessage());
        }
    }
}
