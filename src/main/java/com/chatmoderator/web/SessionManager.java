package com.chatmoderator.web;

import com.chatmoderator.ChatModeratorPlugin;
import com.chatmoderator.config.ConfigManager;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 基于 Cookie 的会话管理，管理员密码 BCrypt 校验（需求 §8.2）。
 * 安全性：会话 id 由 24 字节密码学随机数生成（base64url），带过期时间，定时清理。
 */
public class SessionManager {

    private final ChatModeratorPlugin plugin;
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();

    private static final class Session {
        final boolean admin;
        final long expiresAt; // 毫秒时间戳

        Session(boolean admin, long expiresAt) {
            this.admin = admin;
            this.expiresAt = expiresAt;
        }
    }

    public SessionManager(ChatModeratorPlugin plugin) {
        this.plugin = plugin;
    }

    /** 校验管理员凭据。 */
    public boolean login(String user, String pass) {
        ConfigManager cm = plugin.getConfigManager();
        if (!cm.getWebAdminUsername().equals(user)) return false;
        String stored = cm.getWebAdminPassword();
        if (stored == null || stored.isEmpty()) return false;
        if (cm.isPasswordHashed()) {
            return at.favre.lib.crypto.bcrypt.BCrypt.verifyer()
                    .verify(pass.toCharArray(), stored).verified;
        }
        // 明文分支使用定长比较，降低时序侧信道
        return constantTimeEquals(stored, pass);
    }

    /** 生成密码学安全的会话 id（24 字节随机），并登记过期时间。 */
    public String createSession() {
        byte[] b = new byte[24];
        random.nextBytes(b);
        String id = Base64.getUrlEncoder().withoutPadding().encodeToString(b);
        int ttlMin = Math.max(1, plugin.getConfigManager().getWebAdminSessionTimeoutMinutes());
        sessions.put(id, new Session(true, System.currentTimeMillis() + (long) ttlMin * 60_000));
        return id;
    }

    public boolean isValid(String cookie) {
        if (cookie == null) return false;
        Session s = sessions.get(cookie);
        if (s == null) return false;
        if (System.currentTimeMillis() > s.expiresAt) {
            sessions.remove(cookie);
            return false;
        }
        return true;
    }

    public boolean isAdmin(String cookie) {
        if (cookie == null) return false;
        Session s = sessions.get(cookie);
        return s != null && s.admin && System.currentTimeMillis() <= s.expiresAt;
    }

    public void destroy(String cookie) {
        if (cookie != null) sessions.remove(cookie);
    }

    /** 周期性清理过期会话（由 WebServer 定时调用）。 */
    public void purge() {
        long now = System.currentTimeMillis();
        sessions.entrySet().removeIf(e -> e.getValue().expiresAt < now);
    }

    private static boolean constantTimeEquals(String a, String b) {
        if (a.length() != b.length()) return false;
        int result = 0;
        for (int i = 0; i < a.length(); i++) {
            result |= a.charAt(i) ^ b.charAt(i);
        }
        return result == 0;
    }
}
