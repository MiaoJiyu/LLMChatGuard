package com.chatmoderator.model;

import com.chatmoderator.ChatModeratorPlugin;
import com.chatmoderator.config.ModelConfig;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.SocketTimeoutException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.logging.Level;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.ArrayList;
import java.util.List;
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

    /** 批量检测：将一批消息合并为一次请求，返回与消息顺序对应的判定列表。 */
    public CompletableFuture<List<ModelResponse>> analyzeBatch(String systemPrompt,
                                                               List<String> messageLines,
                                                               ModelConfig cfg) {
        return CompletableFuture.supplyAsync(() -> doAnalyzeBatch(systemPrompt, messageLines, cfg), pool);
    }

    private ModelResponse doAnalyze(String message, String systemPrompt, ModelConfig cfg) {
        int attempts = Math.max(1, plugin.getConfigManager().getRetryCount() + 1);
        Exception lastErr = null;
        boolean allowThinking = cfg.thinking;
        boolean allowStream = cfg.stream;
        boolean allowJson = cfg.jsonMode;
        for (int i = 0; i < attempts; i++) {
            try {
                semaphore.acquire();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return new ModelResponse(); // parsed=false
            }
            try {
                String body = buildRequest(message, systemPrompt, cfg, allowThinking, allowStream, allowJson);
                String text = sendRequest(body, cfg, allowStream);
                return ModelResponse.parse(text);
            } catch (ModelHttpException e) {
                // 4xx：服务端拒绝某可选参数，逐级降级后重试（不消耗重试次数）
                if (allowThinking) {
                    plugin.getLogger().warning("thinking 被服务端拒绝（HTTP " + e.getMessage()
                            + "），已退回不 thinking 重试");
                    allowThinking = false; i--; continue;
                }
                if (allowStream) { allowStream = false; i--; continue; }
                if (allowJson) { allowJson = false; i--; continue; }
                lastErr = e;
            } catch (Exception e) {
                // 任意失败（超时/网络/空响应等）：若本次带了 thinking，先退回不 thinking 重试一次
                if (e instanceof EmptyResponseException) {
                    plugin.getLogger().warning("模型返回空响应，正在重试（剩余 "
                            + (attempts - i - 1) + " 次）");
                    lastErr = e;
                    // 不降级 thinking，仅走普通重试（注意：i 自增进入下一轮）
                    continue;
                }
                if (allowThinking) {
                    plugin.getLogger().warning("thinking 请求失败（" + e.getClass().getSimpleName()
                            + "），已退回不 thinking 重试");
                    allowThinking = false; i--; continue;
                }
                lastErr = e;
                // 进入下一次重试
            } finally {
                semaphore.release();
            }
        }
        logFailure("模型调用失败（已重试 " + (attempts - 1) + " 次）", lastErr);
        return new ModelResponse(); // parsed=false
    }

    private List<ModelResponse> doAnalyzeBatch(String systemPrompt,
                                               List<String> messageLines,
                                               ModelConfig cfg) {
        int attempts = Math.max(1, plugin.getConfigManager().getRetryCount() + 1);
        Exception lastErr = null;
        boolean allowThinking = cfg.thinking;
        boolean allowStream = cfg.stream;
        boolean allowJson = cfg.jsonMode;
        for (int i = 0; i < attempts; i++) {
            try {
                semaphore.acquire();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return emptyResults(messageLines.size());
            }
            try {
                String body = buildBatchRequest(systemPrompt, messageLines, cfg, allowThinking, allowStream, allowJson);
                String text = sendRequest(body, cfg, allowStream);
                return ModelResponse.parseBatch(text, messageLines.size());
            } catch (ModelHttpException e) {
                if (allowThinking) {
                    plugin.getLogger().warning("thinking 被服务端拒绝（HTTP " + e.getMessage()
                            + "），已退回不 thinking 重试");
                    allowThinking = false; i--; continue;
                }
                if (allowStream) { allowStream = false; i--; continue; }
                if (allowJson) { allowJson = false; i--; continue; }
                lastErr = e;
            } catch (Exception e) {
                if (e instanceof EmptyResponseException) {
                    plugin.getLogger().warning("批量模型返回空响应，正在重试（剩余 "
                            + (attempts - i - 1) + " 次）");
                    lastErr = e;
                    continue;
                }
                if (allowThinking) {
                    plugin.getLogger().warning("thinking 请求失败（" + e.getClass().getSimpleName()
                            + "），已退回不 thinking 重试");
                    allowThinking = false; i--; continue;
                }
                lastErr = e;
            } finally {
                semaphore.release();
            }
        }
        logFailure("批量模型调用失败（已重试 " + (attempts - 1) + " 次）", lastErr);
        return emptyResults(messageLines.size());
    }

    /** 模型返回 HTTP 4xx（参数/鉴权类错误）时抛出，用于触发可选参数的自动降级。 */
    private static final class ModelHttpException extends RuntimeException {
        ModelHttpException(String msg) { super(msg); }
    }

    /** 响应体为空/结构无法识别（多因服务端限流或负载高返回空体），视为可重试瞬时失败。 */
    private static final class EmptyResponseException extends RuntimeException {
        EmptyResponseException(String msg) { super(msg); }
    }

    private static List<ModelResponse> emptyResults(int n) {
        List<ModelResponse> list = new ArrayList<>();
        for (int i = 0; i < n; i++) list.add(new ModelResponse()); // parsed=false
        return list;
    }

    /** 输出模型调用的详细失败信息：异常类名 + 消息 + 完整堆栈，便于排查超时/鉴权/网络问题。 */
    private void logFailure(String summary, Exception lastErr) {
        if (lastErr == null) {
            plugin.getLogger().warning(summary + ": 未知错误（无异常信息）");
            return;
        }
        String detail = lastErr.getClass().getName() + ": " + lastErr.getMessage();
        if (lastErr.getCause() != null) {
            detail += "；原因: " + lastErr.getCause().getClass().getName()
                    + ": " + lastErr.getCause().getMessage();
        }
        plugin.getLogger().log(Level.WARNING, summary + ": " + detail, lastErr);
    }

    /** 发送请求并返回模型文本（非流式走 JSON，流式走 SSE 并剥离思考内容）。 */
    private String sendRequest(String body, ModelConfig cfg, boolean useStream) throws Exception {
        if (useStream) {
            return sendStream(body, cfg);
        }
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(cfg.apiUrl))
                .timeout(Duration.ofSeconds(cfg.timeoutSeconds))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + cfg.apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() >= 400) {
            String respBody = resp.body();
            plugin.getLogger().warning("模型返回 HTTP " + resp.statusCode()
                    + "；请求体(截断)=" + truncate(body, 1500)
                    + "；响应=" + truncate(respBody, 800));
            throw new ModelHttpException("HTTP " + resp.statusCode() + " " + respBody);
        }
        String raw = resp.body();
        String content = extractContent(raw);
        if (content == null || content.isEmpty()) {
            // 响应体为空或结构无法识别——明确打印原始 body 与状态码，便于区分
            // "服务端返回空体（限流/负载）" 与 "返回结构非 OpenAI 标准"。
            plugin.getLogger().warning("模型返回内容为空（HTTP " + resp.statusCode()
                    + "，body 长度=" + (raw == null ? 0 : raw.length())
                    + "）；原始响应(截断)=" + truncate(raw, 1500));
            // 视为可重试的瞬时失败（NIM 限流/负载高时常返回空体），交由调用方重试。
            throw new EmptyResponseException("empty body / unrecognized structure (HTTP " + resp.statusCode() + ")");
        }
        return content;
    }

    /**
     * 流式（SSE）读取：使用 sendAsync 让请求超时仅约束"连接 + 首字节"，
     * 主体则按"空闲超时"手动逐行读取——只要令牌持续到达就不会超时，
     * 从而避免慢模型（思考过程/长生成）因整体超时失败。
     * 跳过 reasoning_content 思考内容，最终剥离 <think:6124c78e>…</think:6124c78e>。
     */
    private String sendStream(String body, ModelConfig cfg) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(cfg.apiUrl))
                .timeout(Duration.ofSeconds(Math.max(cfg.timeoutSeconds, 30)))
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .header("Authorization", "Bearer " + cfg.apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        HttpResponse<InputStream> resp;
        try {
            resp = http.sendAsync(req, HttpResponse.BodyHandlers.ofInputStream()).join();
        } catch (Exception e) {
            Throwable cause = e instanceof java.util.concurrent.CompletionException ? e.getCause() : e;
            if (cause instanceof RuntimeException) throw (RuntimeException) cause;
            throw new RuntimeException(cause);
        }
        if (resp.statusCode() >= 400) {
            String err = new String(resp.body().readAllBytes(), StandardCharsets.UTF_8);
            plugin.getLogger().warning("流式模型返回 HTTP " + resp.statusCode()
                    + "；请求体(截断)=" + truncate(body, 1500) + "；响应=" + truncate(err, 800));
            throw new ModelHttpException("HTTP " + resp.statusCode() + " " + err);
        }
        long idleMs = Math.max(cfg.timeoutSeconds, 30) * 1000L;
        long last = System.currentTimeMillis();
        InputStream in = resp.body();
        StringBuilder sb = new StringBuilder();
        ByteArrayOutputStream lineBuf = new ByteArrayOutputStream();
        while (true) {
            int avail = in.available();
            if (avail <= 0) {
                if (System.currentTimeMillis() - last > idleMs) {
                    throw new SocketTimeoutException("SSE 读取空闲超时（已超过 " + idleMs + "ms 无新数据）");
                }
                Thread.sleep(20);
                continue;
            }
            int r = in.read();
            last = System.currentTimeMillis();
            if (r < 0) break; // 流结束
            if (r == '\n') {
                String line = lineBuf.toString("UTF-8").trim();
                lineBuf.reset();
                if (line.isEmpty() || !line.startsWith("data:")) continue;
                String data = line.substring(5).trim();
                if ("[DONE]".equals(data)) break;
                try {
                    JsonObject o = JsonParser.parseString(data).getAsJsonObject();
                    JsonArray choices = o.getAsJsonArray("choices");
                    if (choices != null && !choices.isEmpty()) {
                        JsonObject delta = choices.get(0).getAsJsonObject().getAsJsonObject("delta");
                        if (delta != null) {
                            // 跳过思考内容（如 DeepSeek 的 reasoning_content），仅累积正文 content
                            if (delta.has("reasoning_content")) continue;
                            JsonElement c = delta.get("content");
                            if (c != null && c.isJsonPrimitive()) {
                                sb.append(c.getAsString());
                            }
                        }
                    }
                } catch (Exception ignored) {
                    // 忽略非 JSON 的心跳/注释行
                }
            } else if (r != '\r') {
                lineBuf.write(r);
            }
        }
        return stripThinking(sb.toString());
    }

    /** 剥离模型正文中可能存在的 <think:6124c78e>…</think:6124c78e> 思考段落。 */
    private static String stripThinking(String text) {
        if (text == null) return "";
        return text.replaceAll("(?s)<think>.*?</think>", "").trim();
    }

    /** 截断超长字符串用于日志，避免刷屏。 */
    private static String truncate(String s, int max) {
        if (s == null) return "null";
        return s.length() <= max ? s : s.substring(0, max) + "…(截断，共 " + s.length() + " 字符)";
    }

    /** 思考参数名：未配置时回退为通用 "thinking"。 */
    private static String thinkingParamName(ModelConfig cfg) {
        return (cfg.thinkingParam == null || cfg.thinkingParam.isEmpty()) ? "thinking" : cfg.thinkingParam;
    }

    /** 附加结构化 JSON 输出要求：response_format = {"type":"json_object"}。 */
    private static void addJsonResponseFormat(JsonObject req) {
        JsonObject rf = new JsonObject();
        rf.addProperty("type", "json_object");
        req.add("response_format", rf);
    }

    private String buildRequest(String message, String systemPrompt, ModelConfig cfg,
                                 boolean allowThinking, boolean allowStream, boolean allowJson) {
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
        // 仅当显式启用且服务端支持时附带 provider 专有参数，避免向不兼容的 API
        // 发送未知字段导致 HTTP 400，从而使所有检测失效（被拒时由调用方自动降级）。
        if (cfg.topK > 0) {
            req.addProperty("top_k", cfg.topK);
        }
        if (allowThinking) {
            req.addProperty(thinkingParamName(cfg), true);
        }
        if (allowStream) {
            req.addProperty("stream", true);
        }
        if (allowJson) {
            addJsonResponseFormat(req);
        }
        return req.toString();
    }

    private String buildBatchRequest(String systemPrompt, List<String> messageLines, ModelConfig cfg,
                                     boolean allowThinking, boolean allowStream, boolean allowJson) {
        JsonObject req = new JsonObject();
        req.addProperty("model", cfg.modelName);
        JsonArray msgs = new JsonArray();
        JsonObject sys = new JsonObject();
        sys.addProperty("role", "system");
        sys.addProperty("content", systemPrompt);
        JsonObject usr = new JsonObject();
        usr.addProperty("role", "user");
        StringBuilder sb = new StringBuilder();
        sb.append("请逐条审查以下 ").append(messageLines.size())
                .append(" 条玩家聊天消息是否包含违禁词。\n");
        sb.append("必须仅返回一个合法的 JSON 对象，结构为 {\"results\": [ ... ]}，不要任何额外文字、Markdown 或代码块标记。\n");
        sb.append("results 数组长度与消息数一致、顺序一一对应，每个元素格式：{\"line_number\": 行号, \"triggered\": true/false, \"banned_words\": [\"命中的原始违禁词\"]}。\n\n");
        sb.append("消息列表：\n");
        for (int i = 0; i < messageLines.size(); i++) {
            sb.append((i + 1)).append(") ").append(messageLines.get(i)).append("\n");
        }
        usr.addProperty("content", sb.toString());
        msgs.add(sys);
        msgs.add(usr);
        req.add("messages", msgs);
        req.addProperty("max_tokens", cfg.maxTokens);
        req.addProperty("temperature", cfg.temperature);
        req.addProperty("top_p", cfg.topP);
        if (cfg.topK > 0) {
            req.addProperty("top_k", cfg.topK);
        }
        if (allowThinking) {
            req.addProperty(thinkingParamName(cfg), true);
        }
        if (allowStream) {
            req.addProperty("stream", true);
        }
        if (allowJson) {
            addJsonResponseFormat(req);
        }
        return req.toString();
    }

    private String extractContent(String body) {
        if (body == null || body.isEmpty()) return "";
        try {
            JsonObject root = JsonParser.parseString(body).getAsJsonObject();
            // 1) 标准 OpenAI：choices[0].message.content
            JsonArray choices = root.getAsJsonArray("choices");
            if (choices != null && choices.size() > 0) {
                JsonObject c0 = choices.get(0).getAsJsonObject();
                JsonObject msg = c0.has("message") ? c0.getAsJsonObject("message") : null;
                if (msg != null && msg.has("content")) {
                    JsonElement c = msg.get("content");
                    if (c != null && c.isJsonPrimitive()) return c.getAsString();
                }
                // 兼容部分实现把 content 直接放在 choice 上
                if (c0.has("content")) {
                    JsonElement c = c0.get("content");
                    if (c != null && c.isJsonPrimitive()) return c.getAsString();
                }
            }
            // 2) 兼容 NVIDIA NIM / 某些实现的 output 字段
            if (root.has("output")) {
                JsonElement out = root.get("output");
                if (out.isJsonPrimitive()) return out.getAsString();
                if (out.isJsonObject() && out.getAsJsonObject().has("content")) {
                    JsonElement c = out.getAsJsonObject().get("content");
                    if (c != null && c.isJsonPrimitive()) return c.getAsString();
                }
            }
            // 3) 兼容顶层直接带 content 字段
            if (root.has("content")) {
                JsonElement c = root.get("content");
                if (c != null && c.isJsonPrimitive()) return c.getAsString();
            }
        } catch (Exception ignored) {
            // 返回空串，交由解析逻辑处理
        }
        return "";
    }
}
