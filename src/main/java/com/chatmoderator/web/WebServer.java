package com.chatmoderator.web;

import com.chatmoderator.ChatModeratorPlugin;
import com.chatmoderator.log.LogEntry;
import com.chatmoderator.mode.ModeDecider;
import com.chatmoderator.punishment.PunishmentRecord;
import fi.iki.elonen.NanoHTTPD;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.logging.Level;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 内嵌 Web 管理面板（NanoHTTPD）。路由：/ 、/punishments 、/logs 、/login 、/logout 、/static（需求 §8）。
 */
public class WebServer extends NanoHTTPD {

    private final ChatModeratorPlugin plugin;
    private final SessionManager sessions;

    public WebServer(ChatModeratorPlugin plugin, String host, int port) throws IOException {
        super(host, port);
        this.plugin = plugin;
        this.sessions = new SessionManager(plugin);
        if (host == null || host.isEmpty() || "0.0.0.0".equals(host)) {
            plugin.getLogger().warning("Web 面板绑定在所有网络接口 (0.0.0.0)，存在公网暴露风险！"
                    + " 建议改用 127.0.0.1 并经反向代理 + HTTPS 暴露，且务必配置强 BCrypt 密码。");
        }
    }

    public SessionManager getSessions() {
        return sessions;
    }

    @Override
    public Response serve(IHTTPSession session) {
        try {
            return doServe(session);
        } catch (Exception e) {
            // 任何单请求处理异常都记录并返回 500，避免整个 Web 面板被拖垮（端口保持可访问）
            plugin.getLogger().log(Level.SEVERE,
                    "Web 请求处理异常: " + session.getUri(), e);
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR,
                    "text/plain; charset=utf-8", "Internal Server Error");
        }
    }

    private Response doServe(IHTTPSession session) {
        sessions.purge();
        String uri = session.getUri();
        AuthInfo auth = resolveAuth(session);
        boolean authed = auth.valid;

        // 受保护路由：未认证则重定向到登录页，并打印诊断信息
        if ((uri.equals("/logs") || uri.equals("/punishments")) && !authed) {
            plugin.getLogger().warning("[" + uri + "] 未认证，重定向至 /login。"
                    + " rawCookie=" + mask(auth.rawCookie) + " 解析到的 cm_sid=" + auth.ids
                    + " 有效会话=" + auth.valid);
            return redirect("/login");
        }

        if (uri.equals("/") || uri.equals("/index.html")) {
            return dashboard(authed);
        }
        if (uri.equals("/punishments")) {
            return punishments(session, authed);
        }
        if (uri.equals("/logs")) {
            return logs(session);
        }
        if (uri.equals("/login")) {
            if (session.getMethod() == Method.POST) {
                return doLogin(session);
            }
            return loginPage();
        }
        if (uri.equals("/logout")) {
            sessions.destroy(auth.validId);
            return redirect("/");
        }
        if (uri.equals("/static/style.css")) {
            return styleCss();
        }
        return notFound();
    }

    private Response dashboard(boolean authed) {
        ModeDecider.DetectMode mode = plugin.getModeDecider().getCachedMode();
        int[] stats = plugin.getModeDecider().computeLiveStats();
        Map<String, String> p = new HashMap<>();
        p.put("SERVER_NAME", plugin.getConfigManager().getServerName());
        p.put("MODE_BADGE", modeBadge(mode));
        p.put("ONLINE", String.valueOf(stats[0]));
        p.put("ACTIVE_OP", String.valueOf(stats[1]));
        p.put("QUEUE", String.valueOf(plugin.getDetectionManager().getQueueSize()));
        p.put("DATA_DIR", plugin.getDataFolder().getAbsolutePath());
        p.put("LOGIN_STATUS", loginStatus(authed));

        List<PunishmentRecord> recent = plugin.getQueryService().queryPunishments("", 1, 10);
        StringBuilder rows = new StringBuilder();
        if (recent.isEmpty()) {
            rows.append("<tr><td colspan=\"3\" class=\"empty\">暂无处罚记录</td></tr>");
        } else {
            for (PunishmentRecord r : recent) {
                rows.append("<tr><td>").append(esc(r.playerName)).append("</td><td class=\"kw\">")
                        .append(esc(String.join(",", r.bannedWords))).append("</td><td class=\"muted\">")
                        .append(esc(formatTime(r.timestamp))).append("</td></tr>");
            }
        }
        p.put("RECENT_PUNISHMENTS", rows.toString());
        return html(render("dashboard.html", p));
    }

    private Response punishments(IHTTPSession session, boolean authed) {
        Map<String, String> parms = session.getParms();
        String player = parms.getOrDefault("player", "");
        int page = parseInt(parms.get("page"), 1);
        int size = 15;
        QueryService qs = plugin.getQueryService();
        List<PunishmentRecord> list = qs.queryPunishments(player, page, size);
        int total = qs.countPunishments(player);
        int pages = Math.max(1, (int) Math.ceil((double) total / size));

        Map<String, String> p = new HashMap<>();
        p.put("SERVER_NAME", plugin.getConfigManager().getServerName());
        p.put("PLAYER", esc(player));
        p.put("LOGIN_STATUS", loginStatus(authed));

        StringBuilder rows = new StringBuilder();
        if (list.isEmpty()) {
            rows.append("<tr><td colspan=\"4\" class=\"empty\">暂无记录</td></tr>");
        } else {
            for (PunishmentRecord r : list) {
                rows.append("<tr><td>").append(esc(r.playerName)).append("</td><td class=\"kw\">")
                        .append(esc(String.join(",", r.bannedWords))).append("</td><td class=\"code\">")
                        .append(esc(r.command)).append("</td><td class=\"muted\">")
                        .append(esc(formatTime(r.timestamp))).append("</td></tr>");
            }
        }
        p.put("ROWS", rows.toString());
        p.put("PAGINATION", pagination("/punishments?player=" + enc(player), page, pages));
        return html(render("punishments.html", p));
    }

    private Response logs(IHTTPSession session) {
        Map<String, String> parms = session.getParms();
        String type = parms.getOrDefault("type", "");
        String player = parms.getOrDefault("player", "");
        String from = parms.getOrDefault("from", "");
        String to = parms.getOrDefault("to", "");
        String result = parms.getOrDefault("result", "");
        int page = parseInt(parms.get("page"), 1);
        int size = 30;
        List<LogEntry> list = plugin.getQueryService().queryLogs(type, player, from, to, result, page, size);

        Map<String, String> p = new HashMap<>();
        p.put("SERVER_NAME", plugin.getConfigManager().getServerName());
        p.put("PLAYER", esc(player));
        p.put("FROM", esc(from));
        p.put("TO", esc(to));
        p.put("LOGIN_STATUS", loginStatus(true));
        p.put("TYPE_ALL", type.isEmpty() ? "selected" : "");
        p.put("TYPE_CHAT", "chat".equals(type) ? "selected" : "");
        p.put("TYPE_BYPASS", "bypass".equals(type) ? "selected" : "");
        p.put("TYPE_DETECTION", "detection".equals(type) ? "selected" : "");
        p.put("TYPE_MODE", "mode".equals(type) ? "selected" : "");
        p.put("TYPE_PUNISHMENT", "punishment".equals(type) ? "selected" : "");
        p.put("TYPE_ERROR", "error".equals(type) ? "selected" : "");
        p.put("RES_ALL", result.isEmpty() ? "selected" : "");
        p.put("RES_TRUE", "true".equals(result) ? "selected" : "");
        p.put("RES_FALSE", "false".equals(result) ? "selected" : "");

        StringBuilder rows = new StringBuilder();
        if (list.isEmpty()) {
            rows.append("<tr><td colspan=\"5\" class=\"empty\">暂无日志</td></tr>");
        } else {
            for (LogEntry e : list) {
                rows.append("<tr><td class=\"muted\">").append(esc(formatTime(e.time))).append("</td><td>")
                        .append(esc(e.type)).append("</td><td>").append(esc(e.player)).append("</td><td>")
                        .append(resultBadge(e.result)).append("</td><td class=\"code\">")
                        .append(esc(e.content)).append("</td></tr>");
            }
        }
        p.put("ROWS", rows.toString());
        int pg = (list.size() == size) ? page + 1 : page;
        p.put("PAGINATION", pagination("/logs?type=" + enc(type) + "&player=" + enc(player)
                + "&from=" + enc(from) + "&to=" + enc(to) + "&result=" + enc(result), page, pg));
        return html(render("logs.html", p));
    }

    private Response doLogin(IHTTPSession session) {
        // NanoHTTPD 不会自动解析 POST 表单体，仅对登录接口按需解析并限制体积，避免吞掉异常掩盖故障
        try {
            Map<String, String> files = new HashMap<>();
            session.parseBody(files);
        } catch (Exception e) {
            plugin.getLogger().warning("解析登录请求体失败: " + e.getMessage());
        }
        Map<String, String> parms = session.getParms();
        String user = parms.getOrDefault("username", "");
        String pass = parms.getOrDefault("password", "");
        if (sessions.login(user, pass)) {
            String id = sessions.createSession();
            Response r = redirect("/");
            // Max-Age（浏览器通用）与 Expires（兼容旧客户端）双写；Expires 时区必须固定为 GMT，
            // 否则浏览器会拒绝该 Cookie（之前用 JVM 默认时区导致登录后依然未认证）。
            long maxAgeSec = (long) plugin.getConfigManager().getWebAdminSessionTimeoutMinutes() * 60;
            long expireMs = System.currentTimeMillis() + maxAgeSec * 1000;
            String expires = new java.text.SimpleDateFormat(
                    "EEE, dd MMM yyyy HH:mm:ss 'GMT'", java.util.Locale.US).format(new Date(expireMs));
            r.addHeader("Set-Cookie", "cm_sid=" + id
                    + "; Path=/; HttpOnly; SameSite=Lax; Max-Age=" + maxAgeSec + "; Expires=" + expires);
            return r;
        }
        Map<String, String> p = new HashMap<>();
        p.put("SERVER_NAME", plugin.getConfigManager().getServerName());
        p.put("ERROR", "<div class=\"alert\">用户名或密码错误</div>");
        return html(render("login.html", p));
    }

    private Response loginPage() {
        Map<String, String> p = new HashMap<>();
        p.put("SERVER_NAME", plugin.getConfigManager().getServerName());
        p.put("ERROR", "");
        return html(render("login.html", p));
    }

    // ---- 渲染辅助 ----
    private String modeBadge(ModeDecider.DetectMode mode) {
        switch (mode) {
            case STOP: return "<span class=\"badge stop\">关闭检测</span>";
            case SAMPLE: return "<span class=\"badge sample\">抽样检测</span>";
            case FULL: return "<span class=\"badge full\">全量检测</span>";
            default: return mode.name();
        }
    }

    private String resultBadge(Boolean r) {
        if (r == null) return "<span class=\"badge\">-</span>";
        return r ? "<span class=\"badge danger\">命中</span>" : "<span class=\"badge ok\">未命中</span>";
    }

    private String loginStatus(boolean authed) {
        if (authed) return "<a href=\"/logout\">注销</a>";
        return "<a href=\"/login\">登录</a>";
    }

    private String pagination(String base, int page, int pages) {
        StringBuilder sb = new StringBuilder();
        sb.append("<div class=\"pager\">");
        if (page > 1) sb.append("<a href=\"").append(base).append("&page=").append(page - 1).append("\">上一页</a>");
        else sb.append("<span class=\"muted\">上一页</span>");
        sb.append("<span class=\"muted\"> 第 ").append(page).append(" / ").append(pages).append(" 页 </span>");
        if (page < pages) sb.append("<a href=\"").append(base).append("&page=").append(page + 1).append("\">下一页</a>");
        else sb.append("<span class=\"muted\">下一页</span>");
        sb.append("</div>");
        return sb.toString();
    }

    private Response styleCss() {
        return newFixedLengthResponse(Response.Status.OK, "text/css", readResource("style.css"));
    }

    private Response html(String content) {
        return newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", content);
    }

    private Response redirect(String to) {
        Response r = newFixedLengthResponse(Response.Status.REDIRECT, "text/html; charset=utf-8", "");
        r.addHeader("Location", to);
        return r;
    }

    private Response notFound() {
        return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/html; charset=utf-8", "<h1>404</h1>");
    }

    private String render(String templateName, Map<String, String> params) {
        String tpl = readResource(templateName);
        for (Map.Entry<String, String> e : params.entrySet()) {
            tpl = tpl.replace("{{" + e.getKey() + "}}", e.getValue() == null ? "" : e.getValue());
        }
        return tpl;
    }

    /** 认证解析结果：原始 Cookie 头、解析到的全部 cm_sid 值、是否存在有效会话。 */
    private static final class AuthInfo {
        final String rawCookie;
        final List<String> ids;
        final boolean valid;
        final String validId;
        AuthInfo(String rawCookie, List<String> ids, boolean valid, String validId) {
            this.rawCookie = rawCookie;
            this.ids = ids;
            this.valid = valid;
            this.validId = validId;
        }
    }

    /**
     * 解析认证状态：从 Cookie 头取出全部 cm_sid（可能同时存在新旧两个，
     * 例如重启/重新登录后浏览器保留了过期会话 id），逐一校验，命中任一有效会话即视为已认证。
     * 若手动解析为空，回退到 NanoHTTPD 的 CookieHandler，提升兼容性。
     */
    private AuthInfo resolveAuth(IHTTPSession session) {
        String raw = session.getHeaders().get("cookie");
        List<String> ids = parseCookieValues(raw, "cm_sid");
        if (ids.isEmpty()) {
            String legacy = session.getCookies().read("cm_sid");
            if (legacy != null && !legacy.isEmpty()) ids.add(legacy);
        }
        for (String id : ids) {
            if (sessions.isValid(id)) {
                return new AuthInfo(raw, ids, true, id);
            }
        }
        return new AuthInfo(raw, ids, false, null);
    }

    /** 解析 Cookie 头，返回指定名称的全部值（按出现顺序，后者为较新设置）。 */
    private static List<String> parseCookieValues(String header, String name) {
        List<String> out = new ArrayList<>();
        if (header == null) return out;
        for (String part : header.split(";")) {
            String trimmed = part.trim();
            int eq = trimmed.indexOf('=');
            if (eq <= 0) continue;
            if (!name.equals(trimmed.substring(0, eq).trim())) continue;
            String v = trimmed.substring(eq + 1).trim();
            if (v.length() >= 2 && v.startsWith("\"") && v.endsWith("\"")) {
                v = v.substring(1, v.length() - 1);
            }
            out.add(v);
        }
        return out;
    }

    /** 仅保留前缀用于日志，避免泄露完整会话 id。 */
    private static String mask(String s) {
        if (s == null) return "null";
        return s.length() <= 12 ? s : s.substring(0, 12) + "…(len=" + s.length() + ")";
    }

    private String readResource(String name) {        try (InputStream in = getClass().getClassLoader().getResourceAsStream("web/" + name)) {
            if (in == null) return "";
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }

    private static String enc(String s) {
        try {
            return java.net.URLEncoder.encode(s == null ? "" : s, "UTF-8");
        } catch (Exception e) {
            return "";
        }
    }

    private static String formatTime(long ms) {
        return new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date(ms));
    }

    private static int parseInt(String s, int def) {
        try {
            return Integer.parseInt(s);
        } catch (Exception e) {
            return def;
        }
    }
}
