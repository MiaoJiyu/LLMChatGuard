package com.chatmoderator.prompt;

import com.chatmoderator.ChatModeratorPlugin;
import com.google.gson.Gson;

import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

/**
 * 自定义系统提示词处理：三级优先级解析模板，替换 {{BANNED_WORDS_JSON}} 占位符（需求 §5.3）。
 */
public final class PromptBuilder {

    public static final String PLACEHOLDER = "{{BANNED_WORDS_JSON}}";
    private static final Gson GSON = new Gson();

    /** 屏蔽构造。 */
    private PromptBuilder() {
    }

    /**
     * 解析最终系统提示词。
     * 优先级：模型配置模板 > 全局配置模板 > 内置默认。
     * 若模板缺少占位符，则在末尾追加违禁词 JSON 以保证兼容。
     */
    public static String resolve(ChatModeratorPlugin plugin, String modelTemplate,
                                 String globalTemplate, List<String> bannedWords) {
        String template = null;
        if (modelTemplate != null && !modelTemplate.isBlank()) {
            File f = new File(plugin.getDataFolder(), modelTemplate);
            if (f.isFile()) {
                template = readFile(f);
            }
        }
        if (template == null && globalTemplate != null && !globalTemplate.isBlank()) {
            File f = new File(plugin.getDataFolder(), globalTemplate);
            if (f.isFile()) {
                template = readFile(f);
            }
        }
        if (template == null) {
            template = loadBuiltin();
        }
        return replacePlaceholder(template, bannedWords);
    }

    private static String replacePlaceholder(String template, List<String> bannedWords) {
        String json = GSON.toJson(bannedWords);
        if (template.contains(PLACEHOLDER)) {
            return template.replace(PLACEHOLDER, json);
        }
        return template + "\n\n当前违禁词库（JSON 数组）：\n" + json;
    }

    private static String loadBuiltin() {
        try (InputStream in = PromptBuilder.class.getClassLoader()
                .getResourceAsStream("prompts/default_prompt.txt")) {
            if (in != null) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        } catch (Exception ignored) {
            // 回退到最简提示
        }
        return "你是聊天内容审查助手，请判断玩家消息是否包含违禁词。" + PLACEHOLDER;
    }

    private static String readFile(File f) {
        try {
            return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }
}
