package com.chatmoderator.punishment;

import java.util.List;

/**
 * 处罚记录数据结构，序列化为 punishments/punish_<UUID>_<ts>.json（需求 §6）。
 */
public class PunishmentRecord {

    public long timestamp;
    public String playerUuid;
    public String playerName;
    public String serverName;
    public List<String> bannedWords;
    public String message;
    public String command;
    public String reason;
}
