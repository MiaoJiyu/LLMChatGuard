package com.chatmoderator.web;

import com.chatmoderator.ChatModeratorPlugin;
import com.chatmoderator.config.ConfigManager;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 基于 Cookie 的会话管理，管理员密码 BCrypt 校验（需求 §8.2）。
 */
public class SessionManager {

    private final ChatModeratorPlugin plugin;
    private final Map<String, Long> sessions = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();

    public SessionManager(ChatModeratorPlugin plugin) {
        this.plugin = plugin;
    }

    /** 校验管理员凭据。 */
    public boolean login(String user, String pass) {
        ConfigManager cm = plugin.getConfigManager();
        if (!cm.getWebAdminUsername().equals(user)) return false;
        String stored = cm.getWebAdminPassword();
        if (cm.isPasswordHashed()) {
            return at.favre.lib.crypto.bcrypt.BCrypt.verifyer()
                    .verify(pass.toCharArray(), stored).verified;
        }
        return stored.equals(pass);
    }

    public String createSession() {
        byte[] b = new byte[24];
        random.nextBytes(b);
        String id = Base64.getUrlEncoder().withoutPadding().encodeToString(b);
        long ttl = (long) plugin.getConfigManager().getWebAdminSessionTimeoutMinutes() * 60_000;
        sessions.put(id, System.currentTimeMillis() + ttl);
        return id;
    }

    public boolean isValid(String cookie) {
        if (cookie == null) return false;
        Long exp = sessions.get(cookie);
        if (exp == null) return false;
        if (System.currentTimeMillis() > exp) {
            sessions.remove(cookie);
            return false;
        }
        return true;
    }

    public void destroy(String cookie) {
        if (cookie != null) sessions.remove(cookie);
    }

    /** 周期性清理过期会话（由 WebServer 定时调用）。 */
    public void purge() {
        long now = System.currentTimeMillis();
        sessions.entrySet().removeIf(e -> e.getValue() < now);
    }
}
