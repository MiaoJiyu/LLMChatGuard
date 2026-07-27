package com.chatmoderator;

import com.chatmoderator.afk.AFKManager;
import com.chatmoderator.command.CommandHandler;
import com.chatmoderator.config.ConfigManager;
import com.chatmoderator.config.ModelConfig;
import com.chatmoderator.listener.ChatListener;
import com.chatmoderator.log.LogManager;
import com.chatmoderator.model.ModelClient;
import com.chatmoderator.mode.ModeDecider;
import com.chatmoderator.prompt.PromptBuilder;
import com.chatmoderator.punishment.PunishmentExecutor;
import com.chatmoderator.web.QueryService;
import com.chatmoderator.web.WebServer;
import com.chatmoderator.words.WordFilter;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * ChatModerator 主类：装配所有管理器、注册监听与命令、启动 Web 面板与日志调度（需求 §11）。
 */
public class ChatModeratorPlugin extends JavaPlugin {

    private ConfigManager configManager;
    private Map<String, ModelConfig> models;
    private WordFilter wordFilter;
    private AFKManager afkManager;
    private ModeDecider modeDecider;
    private ModelClient modelClient;
    private PunishmentExecutor punishmentExecutor;
    private LogManager logManager;
    private QueryService queryService;
    private WebServer webServer;
    private ChatListener chatListener;
    private CommandHandler commandHandler;

    @Override
    public void onEnable() {
        configManager = new ConfigManager(this);
        configManager.ensureDefaultResources();
        configManager.load();

        wordFilter = new WordFilter(this);
        wordFilter.load();

        afkManager = new AFKManager(this);
        afkManager.setAfkTimeoutMinutes(configManager.getAfkTimeoutMinutes());
        afkManager.register();

        modeDecider = new ModeDecider(this);

        models = ModelConfig.loadAll(new File(getDataFolder(), "models"));

        modelClient = new ModelClient(this);
        modelClient.init();

        punishmentExecutor = new PunishmentExecutor(this);

        logManager = new LogManager(this);
        logManager.start();

        queryService = new QueryService(this);

        chatListener = new ChatListener(this);
        chatListener.register();

        commandHandler = new CommandHandler(this);
        if (getCommand("chatmod") != null) {
            getCommand("chatmod").setExecutor(commandHandler);
        }

        // 启动 Web 面板（端口为 0 时禁用）
        if (configManager.getWebPort() != 0) {
            try {
                webServer = new WebServer(this, configManager.getWebBindAddress(), configManager.getWebPort());
                webServer.start();
                getLogger().info("Web 管理面板已启动: http://" + configManager.getWebBindAddress()
                        + ":" + configManager.getWebPort());
            } catch (IOException e) {
                getLogger().warning("Web 管理面板启动失败: " + e.getMessage());
            }
        }

        getLogger().info("ChatModerator 已启用（模型: " + configManager.getModel() + "）");
    }

    @Override
    public void onDisable() {
        if (modelClient != null) modelClient.shutdown();
        if (logManager != null) logManager.stop();
        if (webServer != null) {
            try {
                webServer.stop();
            } catch (Exception ignored) {
            }
        }
        getLogger().info("ChatModerator 已禁用");
    }

    /** 运行时热重载配置、词库、提示词与模型信息。 */
    public void reloadAll() {
        configManager.load();
        wordFilter.load();
        models = ModelConfig.loadAll(new File(getDataFolder(), "models"));
        afkManager.setAfkTimeoutMinutes(configManager.getAfkTimeoutMinutes());
        logManager.logReload("配置热重载完成");
        getLogger().info("热重载完成");
    }

    /** 返回当前选中的模型配置；不存在时返回 null。 */
    public ModelConfig getActiveModel() {
        return models.get(configManager.getModel());
    }

    /** 输出当前状态到命令发送者。 */
    public void printStatus(CommandSender sender) {
        ModeDecider.DetectMode m = modeDecider.decide();
        sender.sendMessage("§b== ChatModerator 状态 ==");
        sender.sendMessage("检测模式: " + modeDeciderName(m));
        sender.sendMessage("在线玩家: " + modeDecider.getCachedOnline());
        sender.sendMessage("活跃 OP: " + modeDecider.getCachedActiveOp());
        sender.sendMessage("词库大小: " + wordFilter.size());
        sender.sendMessage("队列等待: " + modelClient.getQueueWaiting());
        sender.sendMessage("当前模型: " + configManager.getModel());
    }

    /** 模拟检测某玩家消息并返回结果。 */
    public void runTest(CommandSender sender, String playerName, String message) {
        ModelConfig cfg = getActiveModel();
        if (cfg == null) {
            sender.sendMessage("§c未找到模型配置: " + configManager.getModel());
            return;
        }
        List<String> words = wordFilter.getWords();
        String sp = PromptBuilder.resolve(this, cfg.systemPromptTemplate,
                configManager.getCustomPromptTemplate(), words);
        sender.sendMessage("§7正在检测玩家 " + playerName + " 的消息...");
        modelClient.analyze(message, sp, cfg).whenComplete((resp, err) -> {
            if (err != null) {
                sender.sendMessage("§c检测异常: " + err.getMessage());
                return;
            }
            sender.sendMessage("§a解析成功: " + resp.parsed
                    + " | 命中: " + resp.isBanned
                    + " | 违禁词: " + resp.bannedWords);
        });
    }

    private String modeDeciderName(ModeDecider.DetectMode m) {
        switch (m) {
            case STOP: return "关闭检测";
            case SAMPLE: return "抽样检测";
            case FULL: return "全量检测";
            default: return m.name();
        }
    }

    // ---- getters ----
    public ConfigManager getConfigManager() { return configManager; }
    public Map<String, ModelConfig> getModels() { return models; }
    public WordFilter getWordFilter() { return wordFilter; }
    public AFKManager getAfkManager() { return afkManager; }
    public ModeDecider getModeDecider() { return modeDecider; }
    public ModelClient getModelClient() { return modelClient; }
    public PunishmentExecutor getPunishmentExecutor() { return punishmentExecutor; }
    public LogManager getLogManager() { return logManager; }
    public QueryService getQueryService() { return queryService; }
}
