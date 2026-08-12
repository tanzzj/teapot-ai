package com.teamer.teapot.ai.rbac.service;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 登录失败计数锁定（SPEC §14 第 2 条：5 次锁 10 分钟，内存计数一期即可）。
 */
@Component
public class LoginAttemptLimiter {

    private static final int MAX_ATTEMPTS = 5;
    private static final Duration LOCK_WINDOW = Duration.ofMinutes(10);

    private final Map<String, AtomicInteger> failCount = new ConcurrentHashMap<>();
    private final Map<String, Long> lockUntil = new ConcurrentHashMap<>();

    /** 锁定中返回 true */
    public boolean isLocked(String username) {
        Long until = lockUntil.get(username);
        if (until == null) {
            return false;
        }
        if (System.currentTimeMillis() >= until) {
            lockUntil.remove(username);
            failCount.remove(username);
            return false;
        }
        return true;
    }

    /** 记录一次失败；达到阈值则上锁 */
    public void recordFailure(String username) {
        int count = failCount.computeIfAbsent(username, k -> new AtomicInteger()).incrementAndGet();
        if (count >= MAX_ATTEMPTS) {
            lockUntil.put(username, System.currentTimeMillis() + LOCK_WINDOW.toMillis());
        }
    }

    public void reset(String username) {
        failCount.remove(username);
        lockUntil.remove(username);
    }
}
