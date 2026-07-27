package com.chatmoderator.web;

import com.chatmoderator.ChatModeratorPlugin;
import com.chatmoderator.log.LogEntry;
import com.chatmoderator.punishment.PunishmentRecord;
import com.google.gson.Gson;

import java.io.File;
import java.nio.file.Files;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/**
 * 处罚与日志查询服务，支持按玩家名、时间、结果等筛选（需求 §8.3）。
 */
public class QueryService {

    private static final Gson GSON = new Gson();
    private final ChatModeratorPlugin plugin;

    public QueryService(ChatModeratorPlugin plugin) {
        this.plugin = plugin;
    }

    public List<PunishmentRecord> queryPunishments(String player, int page, int size) {
        List<PunishmentRecord> all = loadPunishments();
        if (player != null && !player.isBlank()) {
            String p = player.toLowerCase();
            all.removeIf(r -> r.playerName == null || !r.playerName.toLowerCase().contains(p));
        }
        all.sort((a, b) -> Long.compare(b.timestamp, a.timestamp));
        return paginate(all, page, size);
    }

    public int countPunishments(String player) {
        List<PunishmentRecord> all = loadPunishments();
        if (player != null && !player.isBlank()) {
            String p = player.toLowerCase();
            all.removeIf(r -> r.playerName == null || !r.playerName.toLowerCase().contains(p));
        }
        return all.size();
    }

    public List<PunishmentRecord> loadPunishments() {
        List<PunishmentRecord> list = new ArrayList<>();
        File dir = new File(plugin.getDataFolder(), "punishments");
        if (!dir.exists()) return list;
        File[] files = dir.listFiles((d, n) -> n.endsWith(".json"));
        if (files == null) return list;
        for (File f : files) {
            try {
                PunishmentRecord r = GSON.fromJson(
                        new String(Files.readAllBytes(f.toPath())), PunishmentRecord.class);
                if (r != null) list.add(r);
            } catch (Exception ignored) {
            }
        }
        return list;
    }

    public List<LogEntry> queryLogs(String type, String player, String from,
                                    String to, String result, int page, int size) {
        File dir = new File(plugin.getDataFolder(), "logs");
        List<LogEntry> all = new ArrayList<>();
        if (!dir.exists()) return all;
        File[] files = dir.listFiles((d, n) -> n.endsWith(".log"));
        if (files == null) return all;

        LocalDate fromDate = parseDate(from);
        LocalDate toDate = parseDate(to);
        Boolean wantResult = null;
        if (result != null && !result.isBlank()) {
            wantResult = Boolean.parseBoolean(result);
        }

        for (File f : files) {
            try {
                for (String line : Files.readAllLines(f.toPath())) {
                    if (line.isBlank()) continue;
                    LogEntry e = LogEntry.fromJson(line);
                    if (match(e, type, player, fromDate, toDate, wantResult)) {
                        all.add(e);
                    }
                }
            } catch (Exception ignored) {
            }
        }
        all.sort((a, b) -> Long.compare(b.time, a.time));
        return paginate(all, page, size);
    }

    private boolean match(LogEntry e, String type, String player,
                          LocalDate fromDate, LocalDate toDate, Boolean wantResult) {
        if (type != null && !type.isBlank() && !type.equals(e.type)) return false;
        if (player != null && !player.isBlank()
                && (e.player == null || !e.player.toLowerCase().contains(player.toLowerCase()))) {
            return false;
        }
        if (wantResult != null && (e.result == null || e.result != wantResult)) return false;
        if (fromDate != null || toDate != null) {
            LocalDate d = Instant.ofEpochMilli(e.time)
                    .atZone(ZoneId.systemDefault()).toLocalDate();
            if (fromDate != null && d.isBefore(fromDate)) return false;
            if (toDate != null && d.isAfter(toDate)) return false;
        }
        return true;
    }

    private LocalDate parseDate(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return LocalDate.parse(s.trim());
        } catch (Exception e) {
            return null;
        }
    }

    private <T> List<T> paginate(List<T> all, int page, int size) {
        int from = Math.max(0, (page - 1) * size);
        int to = Math.min(all.size(), from + size);
        return new ArrayList<>(all.subList(from, to));
    }
}
