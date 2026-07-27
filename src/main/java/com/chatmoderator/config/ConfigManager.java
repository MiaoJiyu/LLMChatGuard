package com.chatmoderator.config;

import com.chatmoderator.ChatModeratorPlugin;
import org.yaml.snakeyaml.Yaml;

import java.io.File;
import java.nio.file.Files;
import java.util.Collections;
import java.util.Map;

/**
 * 主配置文件 (config.yaml) 加载与热重载。
 * 字段对应需求 §9，并补充 failure_policy 失败策略。
 */
public class ConfigManager {

    private final ChatModeratorPlugin plugin;
    private Map<String, Object> root = Collections.emptyMap();

    private String serverName = "My Server";
    private String model = "default";
    private String customPromptTemplate = "prompts/default_prompt.txt";
    private int afkTimeoutMinutes = 5;
    private double sampleRate = 0.5;
    private String samplingStrategy = "probability";
    private String punishmentCommand = "/ban {playerName} 您触发了{serverName}违禁词自动检测系统，被处以封禁惩罚。【此处罚由系统自动处理】";
    private int maxConcurrentRequests = 5;
    private int retryCount = 0;
    private String failurePolicy = "pass";
    private int logRetentionDays = 7;
    private int logArchiveHour = 3;
    private int webPort = 8080;
    private String webBindAddress = "127.0.0.1";
    private String webAdminUsername = "admin";
    // 注意：不再提供弱口令默认值。首次启动若未配置 web_admin.password，
    // Web 面板会在 onEnable 阶段被禁用并要求管理员手动设置（见 ChatModeratorPlugin）。
    private String webAdminPassword = "";
    private int webAdminSessionTimeoutMinutes = 30;
    private int batchIntervalSeconds = 5;
    private int maxBatchSize = 20;
    private boolean debug = false;

    public ConfigManager(ChatModeratorPlugin plugin) {
        this.plugin = plugin;
    }

    /** 首次启用写入默认资源（config.yaml、models/default.json、提示词、词库）。 */
    public void ensureDefaultResources() {
        plugin.saveResource("config.yaml", false);
        plugin.saveResource("models/default.json", false);
        plugin.saveResource("prompts/default_prompt.txt", false);
        plugin.saveResource("banned_words/base.txt", false);
    }

    /** 重新读取 config.yaml。 */
    public void load() {
        File f = new File(plugin.getDataFolder(), "config.yaml");
        if (!f.exists()) {
            ensureDefaultResources();
        }
        try {
            Yaml yaml = new Yaml();
            Map<String, Object> data = yaml.load(Files.readString(f.toPath()));
            if (data == null) data = Collections.emptyMap();
            root = data;

            serverName = get("server_name", serverName);
            model = get("model", model);
            customPromptTemplate = get("custom_prompt_template", customPromptTemplate);
            afkTimeoutMinutes = getInt("afk_timeout_minutes", afkTimeoutMinutes);
            sampleRate = getDouble("sample_rate", sampleRate);
            samplingStrategy = get("sampling_strategy", samplingStrategy);
            punishmentCommand = get("punishment_command", punishmentCommand);
            maxConcurrentRequests = getInt("max_concurrent_requests", maxConcurrentRequests);
            retryCount = getInt("retry_count", retryCount);
            failurePolicy = get("failure_policy", failurePolicy);
            logRetentionDays = getInt("log_retention_days", logRetentionDays);
            logArchiveHour = getInt("log_archive_hour", logArchiveHour);
            webPort = getInt("web_port", webPort);
            webBindAddress = get("web_bind_address", webBindAddress);
            batchIntervalSeconds = getInt("batch_interval_seconds", batchIntervalSeconds);
            maxBatchSize = getInt("max_batch_size", maxBatchSize);
            debug = getBoolean("debug", debug);

            Object adminObj = root.get("web_admin");
            if (adminObj instanceof Map) {
                Map<String, Object> admin = (Map<String, Object>) adminObj;
                webAdminUsername = get(admin, "username", webAdminUsername);
                webAdminPassword = get(admin, "password", webAdminPassword);
                webAdminSessionTimeoutMinutes = getInt(admin, "session_timeout_minutes", webAdminSessionTimeoutMinutes);
            }
        } catch (Exception e) {
            plugin.getLogger().severe("无法加载 config.yaml: " + e.getMessage());
        }
    }

    // ---- helpers ----
    private String get(String key, String def) {
        Object v = root.get(key);
        return v == null ? def : String.valueOf(v);
    }

    private String get(Map<String, Object> m, String key, String def) {
        Object v = m.get(key);
        return v == null ? def : String.valueOf(v);
    }

    private int getInt(String key, int def) {
        return parseInt(root.get(key), def);
    }

    private int getInt(Map<String, Object> m, String key, int def) {
        return parseInt(m.get(key), def);
    }

    private double getDouble(String key, double def) {
        Object v = root.get(key);
        return v == null ? def : parseDouble(v, def);
    }

    private int parseInt(Object o, int def) {
        if (o == null) return def;
        try {
            return Integer.parseInt(String.valueOf(o));
        } catch (Exception e) {
            return def;
        }
    }

    private double parseDouble(Object o, double def) {
        if (o == null) return def;
        try {
            return Double.parseDouble(String.valueOf(o));
        } catch (Exception e) {
            return def;
        }
    }

    private boolean getBoolean(String key, boolean def) {
        Object v = root.get(key);
        if (v == null) return def;
        if (v instanceof Boolean) return (Boolean) v;
        return Boolean.parseBoolean(String.valueOf(v));
    }

    // ---- getters ----
    public String getServerName() { return serverName; }
    public String getModel() { return model; }
    public String getCustomPromptTemplate() { return customPromptTemplate; }
    public int getAfkTimeoutMinutes() { return afkTimeoutMinutes; }
    public double getSampleRate() { return sampleRate; }
    public String getSamplingStrategy() { return samplingStrategy; }
    public String getPunishmentCommand() { return punishmentCommand; }
    public int getMaxConcurrentRequests() { return maxConcurrentRequests; }
    public int getRetryCount() { return retryCount; }
    public String getFailurePolicy() { return failurePolicy; }

    /** 仅允许 pass / local，否则回退为 pass 并记录告警（防止配置被篡改为无效值绕过检测）。 */
    public String getFailurePolicyChecked() {
        if ("pass".equalsIgnoreCase(failurePolicy) || "local".equalsIgnoreCase(failurePolicy)) {
            return failurePolicy.toLowerCase();
        }
        plugin.getLogger().warning("无效的 failure_policy: '" + failurePolicy
                + "'，已回退为 pass（解析失败/超时将放行）");
        return "pass";
    }
    public int getLogRetentionDays() { return logRetentionDays; }
    public int getLogArchiveHour() { return logArchiveHour; }
    public int getWebPort() { return webPort; }
    public String getWebBindAddress() { return webBindAddress; }
    public String getWebAdminUsername() { return webAdminUsername; }
    public String getWebAdminPassword() { return webAdminPassword; }
    public int getWebAdminSessionTimeoutMinutes() { return webAdminSessionTimeoutMinutes; }
    public int getBatchIntervalSeconds() { return batchIntervalSeconds; }
    public int getMaxBatchSize() { return maxBatchSize; }
    public boolean isDebug() { return debug; }

    /** 密码是否为 BCrypt 哈希（以 $2a$/$2b$/$2y$ 开头）。 */
    public boolean isPasswordHashed() {
        return webAdminPassword != null && webAdminPassword.startsWith("$2");
    }
}
