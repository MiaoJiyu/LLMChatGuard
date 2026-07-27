package com.chatmoderator.detection;

/**
 * 简单令牌桶限速器：窗口为 1 分钟，容量 = rpm。
 * 每次成功 acquire 消耗 1 个令牌；令牌按时间比例匀速补充，上限为 rpm。
 * 用于约束批量检测对模型 API 的请求频率（需求：model 配置中的 rpm 速率上限）。
 */
public class RateLimiter {

    private final int capacity;
    private double tokens;
    private long lastRefill;
    private static final long WINDOW_MS = 60_000;

    public RateLimiter(int rpm) {
        this.capacity = Math.max(1, rpm);
        this.tokens = capacity;
        this.lastRefill = System.currentTimeMillis();
    }

    public synchronized boolean tryAcquire() {
        refill();
        if (tokens >= 1.0) {
            tokens -= 1.0;
            return true;
        }
        return false;
    }

    private void refill() {
        long now = System.currentTimeMillis();
        double added = (now - lastRefill) / (double) WINDOW_MS * capacity;
        if (added > 0) {
            tokens = Math.min(capacity, tokens + added);
            lastRefill = now;
        }
    }
}
