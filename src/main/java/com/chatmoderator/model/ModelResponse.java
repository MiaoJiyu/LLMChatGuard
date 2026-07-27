package com.chatmoderator.model;

import com.google.gson.JsonArray;
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
        if (lines.length < 2) {
            return r; // 行数不足，parsed 保持 false
        }
        r.isBanned = lines[1].trim().equalsIgnoreCase("true");
        if (lines.length >= 3) {
            try {
                JsonObject o = JsonParser.parseString(lines[2].trim()).getAsJsonObject();
                JsonArray arr = o.getAsJsonArray("banned_words");
                if (arr != null) {
                    for (int i = 0; i < arr.size(); i++) {
                        r.bannedWords.add(arr.get(i).getAsString());
                    }
                }
            } catch (Exception ignored) {
                // JSON 解析失败，保留 parsed=false
            }
        }
        r.parsed = true;
        return r;
    }
}
