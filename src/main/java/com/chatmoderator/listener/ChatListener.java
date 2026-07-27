package com.chatmoderator.listener;

import com.chatmoderator.ChatModeratorPlugin;
import com.chatmoderator.config.ModelConfig;
import com.chatmoderator.model.ModelResponse;
import com.chatmoderator.mode.ModeDecider;
import com.chatmoderator.prompt.PromptBuilder;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;

import java.util.List;
import java.util.Random;

/**
 * 聊天事件监听，串联：模式决策 -> 抽样/全量 -> 大模型检测 -> 处罚/日志（需求 §5）。
 */
public class ChatListener implements Listener {

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
        detect(p, msg);
    }

    private void detect(Player p, String msg) {
        ModelConfig cfg = plugin.getActiveModel();
        if (cfg == null) {
            plugin.getLogger().warning("未找到可用模型配置，跳过检测");
            return;
        }
        List<String> words = plugin.getWordFilter().getWords();
        String systemPrompt = PromptBuilder.resolve(plugin,
                cfg.systemPromptTemplate,
                plugin.getConfigManager().getCustomPromptTemplate(),
                words);

        plugin.getModelClient().analyze(msg, systemPrompt, cfg)
                .whenComplete((resp, err) -> {
                    if (err != null) {
                        plugin.getLogger().warning("检测异常: " + err.getMessage());
                        handleFailure(p, msg);
                        return;
                    }
                    plugin.getLogManager().logDetection(p.getName(), msg, resp.isBanned, resp.bannedWords);
                    if (resp.parsed && resp.isBanned) {
                        plugin.getPunishmentExecutor().execute(p, resp.bannedWords, msg);
                    } else if (!resp.parsed) {
                        handleFailure(p, msg);
                    }
                });
    }

    private void handleFailure(Player p, String msg) {
        String policy = plugin.getConfigManager().getFailurePolicyChecked();
        if ("local".equalsIgnoreCase(policy)) {
            List<String> matched = plugin.getWordFilter().localMatches(msg);
            if (!matched.isEmpty()) {
                plugin.getPunishmentExecutor().execute(p, matched, msg);
            }
        }
        // pass：放行
    }
}
