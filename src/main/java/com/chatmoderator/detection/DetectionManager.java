package com.chatmoderator.detection;

import com.chatmoderator.ChatModeratorPlugin;
import com.chatmoderator.config.ModelConfig;
import com.chatmoderator.model.ModelClient;
import com.chatmoderator.model.ModelResponse;
import com.chatmoderator.prompt.PromptBuilder;
import com.chatmoderator.words.WordFilter;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 批量检测调度器（需求：按固定间隔批量发送、rpm 速率上限）。
 * 聊天事件把消息入队；调度线程按 batch_interval_seconds 周期从队列取出一批，
 * 在 rpm 限速允许时合并为一次模型请求，结果逐条回写日志/处罚/失败兜底。
 */
public class DetectionManager {

    private final ChatModeratorPlugin plugin;
    private final BlockingQueue<QueuedMessage> queue = new LinkedBlockingQueue<>();
    private ScheduledExecutorService scheduler;
    private RateLimiter rateLimiter;
    private int rateLimiterRpm = -1;

    public DetectionManager(ChatModeratorPlugin plugin) {
        this.plugin = plugin;
    }

    public void start() {
        int interval = Math.max(1, plugin.getConfigManager().getBatchIntervalSeconds());
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "ChatModerator-Batch");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleAtFixedRate(this::flush, interval, interval, TimeUnit.SECONDS);
        plugin.getLogger().info("批量检测已启动：间隔 " + interval + "s，最大批大小 "
                + plugin.getConfigManager().getMaxBatchSize()
                + "，模型 rpm=" + activeRpm());
    }

    /** 停止调度（onDisable / reload 前调用）。 */
    public void stop() {
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
    }

    /** reload 后按最新配置重启调度周期。 */
    public void restart() {
        stop();
        start();
    }

    /** 聊天事件入队（可在异步线程调用）。 */
    public void submit(String playerName, String message) {
        queue.offer(new QueuedMessage(playerName, message));
    }

    public int getQueueSize() {
        return queue.size();
    }

    /** 调试模式：无视排队与模式条件，逐条立即检测。 */
    public void detectNow(Player p, String msg) {
        ModelConfig cfg = plugin.getActiveModel();
        if (cfg == null) {
            plugin.getLogger().warning("未找到可用模型配置，跳过检测");
            return;
        }
        String sp = buildSystemPrompt(cfg);
        plugin.getModelClient().analyze(msg, sp, cfg).whenComplete((resp, err) -> {
            // 回调运行在异步线程，处罚需在主线程，统一切回主线程处理
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (err != null) {
                    plugin.getLogger().warning("检测异常: " + err.getMessage());
                    handleFailure(p.getName(), msg);
                    return;
                }
                process(p.getName(), msg, resp);
            });
        });
    }

    /** 周期触发：取出一批消息，受 rpm 限速后批量发送。 */
    private void flush() {
        int max = Math.max(1, plugin.getConfigManager().getMaxBatchSize());
        List<QueuedMessage> batch = new ArrayList<>();
        queue.drainTo(batch, max);
        if (batch.isEmpty()) {
            return;
        }

        // 速率上限：rpm 限流。受限时把消息退回队列，下个周期再试。
        int rpm = activeRpm();
        if (rpm > 0) {
            if (rateLimiter == null || rateLimiterRpm != rpm) {
                rateLimiter = new RateLimiter(rpm);
                rateLimiterRpm = rpm;
            }
            if (!rateLimiter.tryAcquire()) {
                for (QueuedMessage m : batch) queue.offer(m);
                return;
            }
        }

        ModelConfig cfg = plugin.getActiveModel();
        if (cfg == null) {
            for (QueuedMessage m : batch) handleFailure(m.playerName, m.message);
            return;
        }
        String sp = buildSystemPrompt(cfg);
        List<String> lines = new ArrayList<>();
        for (QueuedMessage m : batch) {
            lines.add("玩家 " + m.playerName + ": " + m.message);
        }
        plugin.getModelClient().analyzeBatch(sp, lines, cfg).whenComplete((responses, err) -> {
            if (err != null) {
                plugin.getLogger().warning("批量检测异常: " + err.getMessage());
                for (QueuedMessage m : batch) handleFailure(m.playerName, m.message);
                return;
            }
            for (int i = 0; i < batch.size(); i++) {
                ModelResponse r = (i < responses.size()) ? responses.get(i) : new ModelResponse();
                QueuedMessage m = batch.get(i);
                final ModelResponse fr = r;
                // 处罚涉及指令执行，切回主线程
                plugin.getServer().getScheduler().runTask(plugin,
                        () -> process(m.playerName, m.message, fr));
            }
        });
    }

    private int activeRpm() {
        ModelConfig cfg = plugin.getActiveModel();
        return cfg != null ? cfg.rpm : 0;
    }

    private String buildSystemPrompt(ModelConfig cfg) {
        return PromptBuilder.resolve(plugin, cfg.systemPromptTemplate,
                plugin.getConfigManager().getCustomPromptTemplate(),
                plugin.getWordFilter().getWords());
    }

    /** 对单条结果做日志/处罚/失败兜底（运行在主线程）。 */
    private void process(String playerName, String message, ModelResponse r) {
        plugin.getLogManager().logDetection(playerName, message, r.isBanned, r.bannedWords);
        if (r.parsed && r.isBanned) {
            punish(playerName, r.bannedWords, message);
        } else if (!r.parsed) {
            handleFailure(playerName, message);
        }
    }

    private void punish(String playerName, List<String> bannedWords, String message) {
        Player p = Bukkit.getPlayerExact(playerName);
        if (p != null) {
            plugin.getPunishmentExecutor().execute(p, bannedWords, message);
        } else {
            // 批处理时玩家可能已离线：仍以玩家名执行封禁命令并记录
            plugin.getPunishmentExecutor().executeByName(playerName, bannedWords, message);
        }
    }

    private void handleFailure(String playerName, String message) {
        String policy = plugin.getConfigManager().getFailurePolicyChecked();
        if ("local".equalsIgnoreCase(policy)) {
            List<String> matched = plugin.getWordFilter().localMatches(message);
            if (!matched.isEmpty()) {
                punish(playerName, matched, message);
            }
        }
        // pass：放行
    }

    /** 队列中的一条待检测消息（仅保留玩家名与内容，玩家对象可能已离线下线）。 */
    static final class QueuedMessage {
        final String playerName;
        final String message;

        QueuedMessage(String playerName, String message) {
            this.playerName = playerName;
            this.message = message;
        }
    }
}
