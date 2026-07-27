package com.chatmoderator.words;

import com.chatmoderator.ChatModeratorPlugin;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 违禁词加载：扫描 banned_words/ 下所有 .txt，合并去重（需求 §5.1）。
 */
public class WordFilter {

    private final ChatModeratorPlugin plugin;
    private final Set<String> words = new HashSet<>();

    public WordFilter(ChatModeratorPlugin plugin) {
        this.plugin = plugin;
    }

    public void load() {
        words.clear();
        File dir = new File(plugin.getDataFolder(), "banned_words");
        if (!dir.exists()) {
            dir.mkdirs();
        }
        File[] files = dir.listFiles((d, name) -> name.endsWith(".txt"));
        if (files == null) return;
        for (File f : files) {
            try {
                for (String line : Files.readAllLines(f.toPath())) {
                    String w = line.trim();
                    if (!w.isEmpty() && !w.startsWith("#")) {
                        words.add(w.toLowerCase());
                    }
                }
            } catch (Exception e) {
                plugin.getLogger().warning("读取词库失败 " + f.getName() + ": " + e.getMessage());
            }
        }
        plugin.getLogger().info("已加载 " + words.size() + " 个违禁词");
    }

    public List<String> getWords() {
        return new ArrayList<>(words);
    }

    public int size() {
        return words.size();
    }

    /** 本地兜底检测：消息标准化（小写）后是否命中任意词（用于 API 失败策略 local）。 */
    public boolean localContains(String message) {
        if (message == null || message.isEmpty()) return false;
        String norm = message.toLowerCase().trim();
        for (String w : words) {
            if (norm.contains(w)) return true;
        }
        return false;
    }
}
