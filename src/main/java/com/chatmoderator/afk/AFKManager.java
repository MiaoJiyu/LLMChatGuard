package com.chatmoderator.afk;

import com.chatmoderator.ChatModeratorPlugin;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * AFK 检测与状态维护（需求 §4.1）。
 * 监听移动/命令/退出维护最后活跃时间；聊天活跃由 ChatListener 调用 update。
 */
public class AFKManager implements Listener {

    private final ChatModeratorPlugin plugin;
    private final Map<UUID, Long> lastActive = new ConcurrentHashMap<>();
    private long afkTimeoutMs = 5L * 60_000;

    public AFKManager(ChatModeratorPlugin plugin) {
        this.plugin = plugin;
    }

    public void setAfkTimeoutMinutes(int minutes) {
        this.afkTimeoutMs = Math.max(1, minutes) * 60_000L;
    }

    public void register() {
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    public void update(Player p) {
        if (p != null) {
            lastActive.put(p.getUniqueId(), System.currentTimeMillis());
        }
    }

    public boolean isAfk(Player p) {
        Long t = lastActive.get(p.getUniqueId());
        if (t == null) return false; // 刚上线视为活跃
        return (System.currentTimeMillis() - t) > afkTimeoutMs;
    }

    public void remove(UUID id) {
        lastActive.remove(id);
    }

    @EventHandler
    public void onMove(PlayerMoveEvent e) {
        if (e.getFrom().getBlockX() == e.getTo().getBlockX()
                && e.getFrom().getBlockY() == e.getTo().getBlockY()
                && e.getFrom().getBlockZ() == e.getTo().getBlockZ()) {
            return; // 仅视角转动不算活动
        }
        update(e.getPlayer());
    }

    @EventHandler
    public void onCommand(PlayerCommandPreprocessEvent e) {
        update(e.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        remove(e.getPlayer().getUniqueId());
    }
}
