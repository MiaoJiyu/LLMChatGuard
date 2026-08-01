package com.chatmoderator.log;

import com.chatmoderator.ChatModeratorPlugin;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.bukkit.scheduler.BukkitTask;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * 日志系统：异步队列落盘、按小时滚动、每日 tar.gz 归档、按保留期清理（需求 §7）。
 */
public class LogManager {

    private final ChatModeratorPlugin plugin;
    private final BlockingQueue<LogEntry> queue = new LinkedBlockingQueue<>();
    private final DateTimeFormatter hourFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd-HH");
    private final DateTimeFormatter tsFmt = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private volatile boolean running = true;
    private Thread writer;
    private BukkitTask archiveTask;
    private LocalDate lastArchived;

    public LogManager(ChatModeratorPlugin plugin) {
        this.plugin = plugin;
    }

    public void start() {
        writer = new Thread(this::run, "ChatModerator-LogWriter");
        writer.start();
        scheduleArchive();
    }

    public void stop() {
        running = false;
        if (writer != null) writer.interrupt();
        if (archiveTask != null) archiveTask.cancel();
    }

    // ---- 便捷日志方法 ----
    public void logChat(String player, String message) {
        enqueue("chat", player, null, message);
    }

    /** 记录被 chatmod.bypass 豁免检测的聊天（仍保留消息内容以便审计）。 */
    public void logBypass(String player, String message) {
        enqueue("bypass", player, null, message);
    }

    public void logDetection(String player, String message, boolean banned, List<String> words) {
        enqueue("detection", player, banned,
                "banned=" + banned + " words=" + words + " message=" + message);
    }

    public void logMode(String mode, int online, int activeOp) {
        enqueue("mode", null, null,
                "mode=" + mode + " online=" + online + " activeOp=" + activeOp);
    }

    public void logPunishment(String player, String command) {
        enqueue("punishment", player, true, "command=" + command);
    }

    public void logError(String message) {
        enqueue("error", null, null, message);
    }

    public void logReload(String message) {
        enqueue("reload", null, null, message);
    }

    private void enqueue(String type, String player, Boolean result, String content) {
        queue.offer(new LogEntry(type, player, result, content));
    }

    // ---- 写入线程 ----
    private void run() {
        while (running) {
            try {
                LogEntry e = queue.poll(1, TimeUnit.SECONDS);
                if (e == null) continue;
                writeLine(e);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception ex) {
                ex.printStackTrace();
            }
        }
        // 退出前flush
        LogEntry e;
        while ((e = queue.poll()) != null) {
            try {
                writeLine(e);
            } catch (Exception ignored) {
            }
        }
    }

    private void writeLine(LogEntry e) throws IOException {
        File dir = new File(plugin.getDataFolder(), "logs");
        dir.mkdirs();
        File f = new File(dir, "chatmod-" + LocalDateTime.now().format(hourFmt) + ".log");
        try (FileWriter fw = new FileWriter(f, true);
             BufferedWriter bw = new BufferedWriter(fw)) {
            bw.write(e.toJson());
            bw.newLine();
        }
    }

    // ---- 归档调度 ----
    private void scheduleArchive() {
        // 每游戏小时检查一次（20 ticks * 60 * 60）
        long period = 20L * 60 * 60;
        archiveTask = plugin.getServer().getScheduler()
                .runTaskTimerAsynchronously(plugin, this::archiveTick, period, period);
    }

    private void archiveTick() {
        int hour = plugin.getConfigManager().getLogArchiveHour();
        if (LocalTime.now().getHour() != hour) return;
        LocalDate yesterday = LocalDate.now().minusDays(1);
        if (yesterday.equals(lastArchived)) return;
        archiveDay(yesterday);
        cleanupOld(plugin.getConfigManager().getLogRetentionDays());
        lastArchived = yesterday;
    }

    private void archiveDay(LocalDate day) {
        File logsDir = new File(plugin.getDataFolder(), "logs");
        File archiveDir = new File(logsDir, "archive");
        archiveDir.mkdirs();
        String prefix = "chatmod-" + day + "-";
        File[] files = logsDir.listFiles((d, n) -> n.startsWith(prefix) && n.endsWith(".log"));
        if (files == null || files.length == 0) return;

        File out = new File(archiveDir, day + ".tar.gz");
        try (FileOutputStream fos = new FileOutputStream(out);
             java.util.zip.GZIPOutputStream gz = new java.util.zip.GZIPOutputStream(fos);
             TarArchiveOutputStream tar = new TarArchiveOutputStream(gz)) {
            tar.setBigNumberMode(TarArchiveOutputStream.BIGNUMBER_POSIX);
            for (File f : files) {
                TarArchiveEntry entry = new TarArchiveEntry(f, f.getName());
                tar.putArchiveEntry(entry);
                Files.copy(f.toPath(), tar);
                tar.closeArchiveEntry();
            }
        } catch (IOException e) {
            plugin.getLogger().warning("日志归档失败: " + e.getMessage());
            return;
        }
        for (File f : files) f.delete();
        plugin.getLogger().info("已归档 " + files.length + " 个日志文件 -> " + out.getName());
    }

    private void cleanupOld(int retentionDays) {
        File archiveDir = new File(new File(plugin.getDataFolder(), "logs"), "archive");
        if (!archiveDir.exists()) return;
        LocalDate cutoff = LocalDate.now().minusDays(retentionDays);
        File[] files = archiveDir.listFiles((d, n) -> n.endsWith(".tar.gz"));
        if (files == null) return;
        for (File f : files) {
            String name = f.getName().replace(".tar.gz", "");
            try {
                LocalDate d = LocalDate.parse(name);
                if (d.isBefore(cutoff)) {
                    f.delete();
                }
            } catch (Exception ignored) {
            }
        }
    }
}
