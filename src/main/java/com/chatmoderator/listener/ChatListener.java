package com.chatmoderator.listener;

import com.chatmoderator.ChatModeratorPlugin;
import com.chatmoderator.mode.ModeDecider;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;

import java.util.Random;

/**
 * 聊天事件监听：串联模式决策与检测触发（需求 §5）。
 * 非调试模式下消息入队，由 DetectionManager 按固定间隔批量检测；
 * 调试模式（config.debug=true）下无视不检测条件，逐条立即检测。
 */
public class ChatListener implements Listener {

    /** 豁免权限：拥有该权限的玩家对话不被检测，但仍记录到日志。 */
    public static final String BYPASS_PERMISSION = "chatmod.bypass";

    private final ChatModeratorPlugin plugin;
    private final Random random = new Random();

    public ChatListener(ChatModeratorPlugin plugin) {
        this.plugin = plugin;
    }

    public void register() {
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    @EventHandler
    public void onChat(AsyncPlayerChatEvent e) {
        Player p = e.getPlayer();
        String msg = e.getMessage();

        // 聊天即视为活跃
        plugin.getAfkManager().update(p);
        // 记录原始消息（无论是否检测）
        plugin.getLogManager().logChat(p.getName(), msg);

        // 豁免权限：拥有 chatmod.bypass 的玩家不被检测（即便调试模式也豁免），
        // 但聊天已记录到日志，此处再补一条 bypass 标记便于审计区分"豁免"与"漏检"。
        if (p.hasPermission(BYPASS_PERMISSION)) {
            plugin.getLogManager().logBypass(p.getName(), msg);
            return;
        }

        // 调试模式：无视不检测条件，逐条立即检测
        if (plugin.getConfigManager().isDebug()) {
            plugin.getDetectionManager().detectNow(p, msg);
            return;
        }

        ModeDecider.DetectMode mode = plugin.getModeDecider().decide();

        if (mode == ModeDecider.DetectMode.STOP) {
            return;
        }
        if (mode == ModeDecider.DetectMode.SAMPLE) {
            double rate = plugin.getConfigManager().getSampleRate();
            if (random.nextDouble() > rate) {
                return; // 本次抽样未选中
            }
        }
        // 入队，交由 DetectionManager 按固定间隔批量检测
        plugin.getDetectionManager().submit(p.getName(), msg);
    }
}
