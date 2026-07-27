package com.chatmoderator.log;

import com.google.gson.Gson;

/**
 * 单条日志条目，以 JSON 行格式写入日志文件（需求 §7）。
 */
public class LogEntry {

    private static final Gson GSON = new Gson();

    public final long time;
    public final String type;
    public final String player;
    public final Boolean result;
    public final String content;

    public LogEntry(String type, String player, Boolean result, String content) {
        this.time = System.currentTimeMillis();
        this.type = type;
        this.player = player;
        this.result = result;
        this.content = content;
    }

    public String toJson() {
        return GSON.toJson(this);
    }

    public static LogEntry fromJson(String line) {
        return GSON.fromJson(line, LogEntry.class);
    }
}
