package com.chatmoderator.model;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.List;

/**
 * 大模型响应解析结果（需求 §5.4）。
 * 响应文本按行分割：第1行行号、第2行 True/False、第3行 JSON {"banned_words":[...]}。
 */
public class ModelResponse {

    public boolean isBanned = false;
    public List<String> bannedWords = new ArrayList<>();
    public boolean parsed = false;

    public static ModelResponse parse(String text) {
        ModelResponse r = new ModelResponse();
        if (text == null) return r;
        String[] lines = text.split("\\r?\\n");
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
        return r;
    }

    /**
     * 批量解析：从模型返回文本中提取 JSON 数组，逐元素生成判定结果。
     * 与入队的消息顺序一一对应；解析失败或数量不足时补空结果（parsed=false）。
     */
    public static List<ModelResponse> parseBatch(String text, int expected) {
        List<ModelResponse> out = new ArrayList<>();
        if (text == null) {
            for (int i = 0; i < expected; i++) out.add(new ModelResponse());
            return out;
        }
        int s = text.indexOf('[');
        int e = text.lastIndexOf(']');
        if (s < 0 || e < 0 || e < s) {
            for (int i = 0; i < expected; i++) out.add(new ModelResponse());
            return out;
        }
        String arrStr = text.substring(s, e + 1);
        try {
            JsonArray arr = JsonParser.parseString(arrStr).getAsJsonArray();
            for (int i = 0; i < arr.size(); i++) {
                JsonElement el = arr.get(i);
                ModelResponse r = new ModelResponse();
                if (el.isJsonObject()) {
                    JsonObject o = el.getAsJsonObject();
                    r.parsed = true;
                    JsonElement b = o.get("banned");
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
        return out;
    }
}
