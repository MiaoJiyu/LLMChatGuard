package com.chatmoderator.model;

import com.chatmoderator.ChatModeratorPlugin;
import com.chatmoderator.config.ModelConfig;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;

/**
 * 大模型 HTTP 异步调用封装（需求 §5.5）。
 * 使用 JDK 内置 HttpClient，配合信号量控制并发、超时与失败重试。
 */
public class ModelClient {

    private final ChatModeratorPlugin plugin;
    private HttpClient http;
    private Semaphore semaphore;
    private ExecutorService pool;

    public ModelClient(ChatModeratorPlugin plugin) {
        this.plugin = plugin;
    }

    public void init() {
        int max = Math.max(1, plugin.getConfigManager().getMaxConcurrentRequests());
        http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        semaphore = new Semaphore(max);
        pool = Executors.newFixedThreadPool(max + 2);
    }

    public void shutdown() {
        if (pool != null) {
            pool.shutdownNow();
        }
    }

    /** 当前等待中的请求数。 */
    public int getQueueWaiting() {
        return semaphore == null ? 0 : semaphore.getQueueLength();
    }

    public CompletableFuture<ModelResponse> analyze(String message, String systemPrompt, ModelConfig cfg) {
        return CompletableFuture.supplyAsync(() -> doAnalyze(message, systemPrompt, cfg), pool);
    }

    private ModelResponse doAnalyze(String message, String systemPrompt, ModelConfig cfg) {
        int attempts = Math.max(1, plugin.getConfigManager().getRetryCount() + 1);
        Exception lastErr = null;
        for (int i = 0; i < attempts; i++) {
            try {
                semaphore.acquire();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return new ModelResponse(); // parsed=false
            }
            try {
                String body = buildRequest(message, systemPrompt, cfg);
                HttpRequest req = HttpRequest.newBuilder()
                        .uri(URI.create(cfg.apiUrl))
                        .timeout(Duration.ofSeconds(cfg.timeoutSeconds))
                        .header("Content-Type", "application/json")
                        .header("Authorization", "Bearer " + cfg.apiKey)
                        .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                        .build();
                HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() >= 400) {
                    throw new RuntimeException("HTTP " + resp.statusCode());
                }
                return ModelResponse.parse(extractContent(resp.body()));
            } catch (Exception e) {
                lastErr = e;
                // 进入下一次重试
            } finally {
                semaphore.release();
            }
        }
        plugin.getLogger().warning("模型调用失败（已重试 " + (attempts - 1) + " 次）: "
                + (lastErr != null ? lastErr.getMessage() : "unknown"));
        return new ModelResponse(); // parsed=false
    }

    private String buildRequest(String message, String systemPrompt, ModelConfig cfg) {
        JsonObject req = new JsonObject();
        req.addProperty("model", cfg.modelName);
        JsonArray msgs = new JsonArray();
        JsonObject sys = new JsonObject();
        sys.addProperty("role", "system");
        sys.addProperty("content", systemPrompt);
        JsonObject usr = new JsonObject();
        usr.addProperty("role", "user");
        usr.addProperty("content", message);
        msgs.add(sys);
        msgs.add(usr);
        req.add("messages", msgs);
        req.addProperty("max_tokens", cfg.maxTokens);
        req.addProperty("temperature", cfg.temperature);
        req.addProperty("top_p", cfg.topP);
        // 仅当显式启用时附带 provider 专有参数，避免向不兼容的 API（如 OpenAI）
        // 发送未知字段导致 HTTP 400，从而使所有检测失效。
        if (cfg.topK > 0) {
            req.addProperty("top_k", cfg.topK);
        }
        if (cfg.thinking) {
            req.addProperty("thinking", true);
        }
        return req.toString();
    }

    private String extractContent(String body) {
        try {
            JsonObject root = JsonParser.parseString(body).getAsJsonObject();
            JsonArray choices = root.getAsJsonArray("choices");
            if (choices != null && choices.size() > 0) {
                JsonObject msg = choices.get(0).getAsJsonObject()
                        .getAsJsonObject("message");
                return msg.get("content").getAsString();
            }
        } catch (Exception ignored) {
            // 返回空串，交由解析逻辑处理
        }
        return "";
    }
}
