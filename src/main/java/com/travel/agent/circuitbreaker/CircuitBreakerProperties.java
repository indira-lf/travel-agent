package com.travel.agent.circuitbreaker;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * 工具（Tool）维度熔断降级配置。
 *
 * <p>通过 application.yml 的 {@code app.circuit-breaker} 节点配置，
 * 控制被监控 tool 连续失败后的降级与恢复策略（含启用开关、白黑名单、阈值、冷却、
 * Redis 键空间等全部参数）。</p>
 *
 * @author Hollis
 */
@Component
@ConfigurationProperties(prefix = "app.circuit-breaker")
public class CircuitBreakerProperties {

    /**
     * 是否启用熔断。关闭后 Hook 直接放行所有工具调用结果。
     */
    private boolean enabled = false;

    /**
     * 受监控的 tool 名称白名单（逗号分隔）。<b>只有出现在白名单中的 tool 才会被熔断</b>，
     * 避免误伤基础设施类工具（如 DB / 缓存读写）。
     */
    private String monitoredToolNames = "";

    /**
     * 不参与熔断的 tool 名称黑名单（逗号分隔）。优先级高于白名单，
     * 用于"全局白名单 + 临时屏蔽个别 tool"的场景。
     */
    private String excludeToolNames = "";

    /**
     * 触发降级的连续失败次数阈值（达到后从 toolkit 中卸载该 tool）。
     */
    private int failureThreshold = 3;

    /**
     * 初始冷却时间（秒），第一次触发降级后的等待时长。
     */
    private long initialCooldownSeconds = 30;

    /**
     * 指数退避倍数，每次重新触发降级时冷却时间乘以此值（向上取整）。
     */
    private double backoffMultiplier = 2.0;

    /**
     * 冷却时间上限（秒），避免指数退避无限增长。
     */
    private long maxCooldownSeconds = 600;

    /**
     * Redis Key 前缀，区分不同环境的熔断状态。
     */
    private String redisKeyPrefix = "tool:cb:";

    /**
     * 故障计数 / 冷却时间在 Redis 中的默认过期时间（秒），
     * 仅用于清理长期未触发的脏数据，不影响核心逻辑。
     */
    private long stateTtlSeconds = 86400;

    public int getFailureThreshold() {
        return failureThreshold;
    }

    public void setFailureThreshold(int failureThreshold) {
        this.failureThreshold = failureThreshold;
    }

    public long getInitialCooldownSeconds() {
        return initialCooldownSeconds;
    }

    public void setInitialCooldownSeconds(long initialCooldownSeconds) {
        this.initialCooldownSeconds = initialCooldownSeconds;
    }

    public double getBackoffMultiplier() {
        return backoffMultiplier;
    }

    public void setBackoffMultiplier(double backoffMultiplier) {
        this.backoffMultiplier = backoffMultiplier;
    }

    public long getMaxCooldownSeconds() {
        return maxCooldownSeconds;
    }

    public void setMaxCooldownSeconds(long maxCooldownSeconds) {
        this.maxCooldownSeconds = maxCooldownSeconds;
    }

    public String getRedisKeyPrefix() {
        return redisKeyPrefix;
    }

    public void setRedisKeyPrefix(String redisKeyPrefix) {
        this.redisKeyPrefix = redisKeyPrefix;
    }

    public long getStateTtlSeconds() {
        return stateTtlSeconds;
    }

    public void setStateTtlSeconds(long stateTtlSeconds) {
        this.stateTtlSeconds = stateTtlSeconds;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getMonitoredToolNames() {
        return monitoredToolNames;
    }

    public void setMonitoredToolNames(String monitoredToolNames) {
        this.monitoredToolNames = monitoredToolNames;
    }

    public String getExcludeToolNames() {
        return excludeToolNames;
    }

    public void setExcludeToolNames(String excludeToolNames) {
        this.excludeToolNames = excludeToolNames;
    }

    /**
     * 解析白名单字符串为不可变 Set。空值返回空集合。
     */
    public Set<String> parseMonitoredToolNames() {
        if (monitoredToolNames == null || monitoredToolNames.isBlank()) {
            return Collections.emptySet();
        }
        return Collections.unmodifiableSet(new HashSet<>(Arrays.asList(monitoredToolNames.split("\\s*,\\s*"))));
    }

    /**
     * 解析黑名单字符串为不可变 Set。空值返回空集合。
     */
    public Set<String> parseExcludedToolNames() {
        if (excludeToolNames == null || excludeToolNames.isBlank()) {
            return Collections.emptySet();
        }
        return Collections.unmodifiableSet(new HashSet<>(Arrays.asList(excludeToolNames.split("\\s*,\\s*"))));
    }
}
