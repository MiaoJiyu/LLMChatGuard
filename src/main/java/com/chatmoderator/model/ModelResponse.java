package com.chatmoderator.model;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * 大模型响应解析结果（需求 §5.4）。
 * 优先解析 JSON 对象（response_format=json_object 时模型输出 {"results":[...]} 或单个对象），
 * 并兼容旧版 3 行文本格式与包裹了 Markdown 代码块/说明文字的输出。
 */
public class ModelResponse {

    private static final Logger LOG = Logger.getLogger(ModelResponse.class.getName());

    public boolean isBanned = false;
    public List<String> bannedWords = new ArrayList<>();
    public boolean parsed = false;

    public static ModelResponse parse(String text) {
        ModelResponse r = new ModelResponse();
        if (text == null) {
            logRaw(text);
            return r;
        }
        String t = stripFences(text);
        JsonElement el = tryParseJson(t);
        if (el != null && el.isJsonObject()) {
            JsonObject o = el.getAsJsonObject();
            JsonArray results = o.getAsJsonArray("results");
            if (results != null) {
                ModelResponse fr = fromResults(results);
                if (!fr.parsed) logRaw(text);
                return fr;
            }
            // 单对象形式 {"triggered":bool,"banned_words":[...]}（兼容）
            if (o.has("triggered") || o.has("banned")) {
                r.parsed = true;
                JsonElement b = o.has("triggered") ? o.get("triggered") : o.get("banned");
                if (b != null && b.isJsonPrimitive()) r.isBanned = b.getAsBoolean();
                JsonArray bw = o.getAsJsonArray("banned_words");
                if (bw != null) {
                    for (int i = 0; i < bw.size(); i++) r.bannedWords.add(bw.get(i).getAsString());
                }
                return r;
            }
        }
        // 回退：逐行解析（兼容旧版/自定义 3 行格式：布尔行 + {"banned_words":[...]}）
        String[] lines = t.split("\\r?\\n");
        boolean foundBool = false;
        boolean foundJson = false;
        for (String raw : lines) {
            String ln = raw.trim();
            if (!foundBool && (ln.equalsIgnoreCase("true") || ln.equalsIgnoreCase("false"))) {
                r.isBanned = ln.equalsIgnoreCase("true");
                foundBool = true;
                continue;
            }
            if (ln.startsWith("{")) {
                try {
                    JsonObject o = JsonParser.parseString(ln).getAsJsonObject();
                    JsonArray arr = o.getAsJsonArray("banned_words");
                    if (arr != null) {
                        for (int i = 0; i < arr.size(); i++) {
                            r.bannedWords.add(arr.get(i).getAsString());
                        }
                    }
                    foundJson = true;
                } catch (Exception ignored) {
                    // 非 JSON 行，忽略
                }
            }
        }
        // 命中状态：明确 True/False 优先；若模型省略布尔行，则由是否含违禁词推断
        if (foundJson && !foundBool) {
            r.isBanned = !r.bannedWords.isEmpty();
        }
        r.parsed = foundBool || foundJson;
        if (!r.parsed) logRaw(text);
        return r;
    }

    /** 合并 results 数组（多行/批量）为单条判定：任一触发即命中，违禁词取并集。 */
    private static ModelResponse fromResults(JsonArray results) {
        ModelResponse r = new ModelResponse();
        if (results == null || results.size() == 0) return r;
        r.parsed = true;
        for (int i = 0; i < results.size(); i++) {
            JsonElement el = results.get(i);
            if (!el.isJsonObject()) continue;
            JsonObject o = el.getAsJsonObject();
            JsonElement b = o.has("triggered") ? o.get("triggered") : o.get("banned");
            if (b != null && b.isJsonPrimitive() && b.getAsBoolean()) r.isBanned = true;
            JsonArray bw = o.getAsJsonArray("banned_words");
            if (bw != null) {
                for (int j = 0; j < bw.size(); j++) r.bannedWords.add(bw.get(j).getAsString());
            }
        }
        return r;
    }

    /**
     * 批量解析：从模型返回文本中提取 results 对象（或兼容顶层数组），逐元素生成判定结果。
     * 与入队的消息顺序一一对应；解析失败或数量不足时补空结果（parsed=false）。
     */
    public static List<ModelResponse> parseBatch(String text, int expected) {
        List<ModelResponse> out = new ArrayList<>();
        if (text == null) {
            for (int i = 0; i < expected; i++) out.add(new ModelResponse());
            logRaw(text);
            return out;
        }
        String t = stripFences(text);
        // 1) 优先解析：{"results":[...]} 对象，或兼容旧版顶层数组 [...]
        JsonArray arr = null;
        JsonElement el = tryParseJson(t);
        if (el != null) {
            if (el.isJsonObject()) {
                JsonArray res = el.getAsJsonObject().getAsJsonArray("results");
                if (res != null) arr = res;
            } else if (el.isJsonArray()) {
                arr = el.getAsJsonArray();
            }
        }
        if (arr != null) {
            for (int i = 0; i < arr.size(); i++) {
                JsonElement e = arr.get(i);
                ModelResponse r = new ModelResponse();
                if (e.isJsonObject()) {
                    JsonObject o = e.getAsJsonObject();
                    r.parsed = true;
                    JsonElement b = o.has("triggered") ? o.get("triggered") : o.get("banned");
                    if (b != null && b.isJsonPrimitive()) {
                        r.isBanned = b.getAsBoolean();
                    }
                    JsonArray bw = o.getAsJsonArray("banned_words");
                    if (bw != null) {
                        for (int j = 0; j < bw.size(); j++) {
                            r.bannedWords.add(bw.get(j).getAsString());
                        }
                    }
                }
                out.add(r);
            }
            while (out.size() < expected) out.add(new ModelResponse());
        } else {
            // 2) 回退：从文本中提取顶层数组子串（兼容包裹了 Markdown 代码块等的旧输出）
            int s = t.indexOf('[');
            int e = t.lastIndexOf(']');
            if (s >= 0 && e >= 0 && e > s) {
                String arrStr = t.substring(s, e + 1);
                try {
                    JsonArray legacy = JsonParser.parseString(arrStr).getAsJsonArray();
                    for (int i = 0; i < legacy.size(); i++) {
                        JsonElement le = legacy.get(i);
                        ModelResponse r = new ModelResponse();
                        if (le.isJsonObject()) {
                            JsonObject o = le.getAsJsonObject();
                            r.parsed = true;
                            JsonElement b = o.has("triggered") ? o.get("triggered") : o.get("banned");
                            if (b != null && b.isJsonPrimitive()) {
                                r.isBanned = b.getAsBoolean();
                            }
                            JsonArray bw = o.getAsJsonArray("banned_words");
                            if (bw != null) {
                                for (int j = 0; j < bw.size(); j++) {
                                    r.bannedWords.add(bw.get(j).getAsString());
                                }
                            }
                        }
                        out.add(r);
                    }
                    while (out.size() < expected) out.add(new ModelResponse());
                } catch (Exception ignored) {
                    for (int i = 0; i < expected; i++) out.add(new ModelResponse());
                }
            } else {
                for (int i = 0; i < expected; i++) out.add(new ModelResponse());
            }
        }
        if (out.stream().noneMatch(x -> x.parsed)) logRaw(text);
        return out;
    }

    /** 去除 ```json ... ``` 或 ``` ... ``` 包裹，并去除首尾空白。 */
    private static String stripFences(String text) {
        if (text == null) return null;
        String t = text.trim();
        if (t.startsWith("```")) {
            int nl = t.indexOf('\n');
            if (nl >= 0) t = t.substring(nl + 1);
            if (t.endsWith("```")) t = t.substring(0, t.length() - 3);
            t = t.trim();
        }
        return t;
    }

    /** 解析 JSON：先整体解析，失败再尝试截取首个 {...} 对象（兼容前后带说明文字的输出）。 */
    private static JsonElement tryParseJson(String t) {
        if (t == null) return null;
        try {
            return JsonParser.parseString(t);
        } catch (Exception ignored) {
            // 尝试截取首个完整 JSON 对象
        }
        int s = t.indexOf('{');
        int e = t.lastIndexOf('}');
        if (s >= 0 && e > s) {
            try {
                return JsonParser.parseString(t.substring(s, e + 1));
            } catch (Exception ignored) {
                // 仍失败
            }
        }
        return null;
    }

    private static void logRaw(String text) {
        LOG.warning("模型返回解析失败，原始内容(截断)=" + truncate(text, 600));
    }

    private static String truncate(String s, int max) {
        if (s == null) return "null";
        return s.length() <= max ? s : s.substring(0, max) + "…(截断，共 " + s.length() + " 字符)";
    }
}
