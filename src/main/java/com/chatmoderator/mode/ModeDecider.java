package com.chatmoderator.mode;

import com.chatmoderator.ChatModeratorPlugin;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/**
 * 识别模式决策引擎（需求 §4.2）。
 * 每次 AsyncPlayerChatEvent 即时计算，并缓存状态供状态查询使用。
 */
public class ModeDecider {

    public enum DetectMode {
        STOP, SAMPLE, FULL
    }

    private final ChatModeratorPlugin plugin;
    private DetectMode cachedMode = DetectMode.FULL;
    private int cachedOnline = 0;
    private int cachedActiveOp = 0;

    public ModeDecider(ChatModeratorPlugin plugin) {
        this.plugin = plugin;
    }

    /** 依据在线玩家数与活跃 OP 数决策检测模式。 */
    public DetectMode decide() {
        int online = 0;
        int activeOp = 0;
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (isNpc(p)) continue;
            online++;
            if (p.isOp() && !plugin.getAfkManager().isAfk(p)) {
                activeOp++;
            }
        }

        DetectMode old = cachedMode;
        DetectMode mode;
        if (activeOp >= 2 || online <= 1) {
            mode = DetectMode.STOP;
        } else if (activeOp == 1 && online <= 3) {
            mode = DetectMode.SAMPLE;
        } else if (activeOp == 0 && online > 3) {
            mode = DetectMode.FULL;
        } else {
            // 需求未明确覆盖的组合：没有活跃 OP 时偏向全量保护
            mode = (activeOp == 0) ? DetectMode.FULL : DetectMode.SAMPLE;
        }

        // 仅在检测模式实际切换时记录事件（需求 §7.1：检测模式切换事件）
        if (old != mode) {
            plugin.getLogManager().logMode(mode.name(), online, activeOp);
        }

        cachedMode = mode;
        cachedOnline = online;
        cachedActiveOp = activeOp;
        return mode;
    }

    public DetectMode getCachedMode() {
        return cachedMode;
    }

    public int getCachedOnline() {
        return cachedOnline;
    }

    public int getCachedActiveOp() {
        return cachedActiveOp;
    }

    private boolean isNpc(Player p) {
        return p.hasMetadata("NPC") || p.hasMetadata("npc");
    }
}
