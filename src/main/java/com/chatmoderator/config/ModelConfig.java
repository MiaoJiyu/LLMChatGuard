package com.chatmoderator.config;

import com.google.gson.Gson;
import com.google.gson.annotations.SerializedName;

import java.io.File;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;

/**
 * 单个模型实例配置，对应 models/*.json（需求 §5.2）。
 */
public class ModelConfig {

    @SerializedName("model_id")
    public String modelId;

    @SerializedName("api_url")
    public String apiUrl;

    @SerializedName("api_key")
    public String apiKey;

    @SerializedName("model_name")
    public String modelName;

    @SerializedName("max_tokens")
    public int maxTokens = 1024;

    @SerializedName("temperature")
    public double temperature = 0.2;

    @SerializedName("top_p")
    public double topP = 1.0;

    @SerializedName("top_k")
    public int topK = 20;

    @SerializedName("thinking")
    public boolean thinking = false;

    /** 思考参数名：不同提供方不同（OpenAI/DeepSeek 用 "thinking"，SiliconFlow 用 "enable_thinking"）。 */
    @SerializedName("thinking_param")
    public String thinkingParam = "thinking";

    @SerializedName("rpm")
    public int rpm = 20;

    @SerializedName("timeout_seconds")
    public int timeoutSeconds = 120;

    @SerializedName("stream")
    public boolean stream = false;

    @SerializedName("system_prompt_template")
    public String systemPromptTemplate = "";

    private static final Gson GSON = new Gson();

    /** 加载 models/ 目录下全部 JSON 模型配置，键名为文件名（不含扩展名）。 */
    public static Map<String, ModelConfig> loadAll(File modelsDir) {
        Map<String, ModelConfig> map = new HashMap<>();
        if (!modelsDir.exists()) {
            modelsDir.mkdirs();
        }
        File[] files = modelsDir.listFiles((dir, name) -> name.endsWith(".json"));
        if (files == null) return map;
        for (File f : files) {
            try {
                String name = f.getName().replace(".json", "");
                ModelConfig mc = GSON.fromJson(new String(Files.readAllBytes(f.toPath())), ModelConfig.class);
                if (mc.modelId == null || mc.modelId.isBlank()) {
                    mc.modelId = name;
                }
                map.put(name, mc);
            } catch (Exception e) {
                System.err.println("加载模型配置失败 " + f.getName() + ": " + e.getMessage());
            }
        }
        return map;
    }

    /**
     * 最终生效的提示词模板路径（三级优先级）：
     * 模型配置 > 全局配置 > 内置默认（调用方在 PrompteBuilder 中回退内置）。
     */
    public String getEffectivePromptTemplate(String globalTemplate) {
        if (systemPromptTemplate != null && !systemPromptTemplate.isBlank()) {
            return systemPromptTemplate;
        }
        return globalTemplate;
    }
}
