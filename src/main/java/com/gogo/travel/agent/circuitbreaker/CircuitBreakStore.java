package com.gogo.travel.agent.circuitbreaker;

import com.gogo.travel.agent.hook.ToolCircuitBreakerHook;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.Collections;

/**
 * Tool 维度熔断状态存储。
 *
 * <p>基于 Redis 持久化每个 {@code toolName} 的连续失败次数、是否处于开启（OPEN）状态、
 * 开启时间戳以及降级代数（用于指数退避）。供 {@link ToolCircuitBreakerHook} 使用。</p>
 *
 * <p><b>Redis Key 设计（每个 tool 仅 2 把 key）：</b>
 * <ul>
 *   <li>{@code {prefix}fail:{toolName}} —— 连续失败计数（INCR 原子操作）</li>
 *   <li>{@code {prefix}gen:{toolName}} —— 降级代数与开启时间 Hash：
 *     <ul>
 *       <li>{@code n}  —— 降级代数（每次 trip 自增，用于退避）</li>
 *       <li>{@code at} —— 开启时间戳（毫秒）。<b>存在即代表处于 OPEN 状态</b>，
 *                       不再单独存 "open" 标记位</li>
 *     </ul>
 *   </li>
 * </ul>
 *
 * <p>目标冷却时长（cooldown）由 {@code n} 与 {@link CircuitBreakerProperties}
 * 派生（{@code min(initial * multiplier^(n-1), max)}），无需单独持久化。</p>
 *
 * <p>使用 Redis 而非进程内 Map 的原因：</p>
 * <ol>
 *   <li>分布式环境下多实例共享同一份熔断视图，避免单实例重启导致状态丢失</li>
 *   <li>INCR / HINCRBY 等原子操作天然支持并发安全</li>
 *   <li>TTL 自动清理长期未触发的脏数据</li>
 * </ol>
 *
 * @author Hollis
 */
public class CircuitBreakStore {

    private static final String FIELD_GENERATION = "n";
    private static final String FIELD_OPENED_AT = "at";

    /**
     * 失败计数原子脚本：INCR 自增连续失败计数后统一续期，返回自增后的值。
     * INCR 与 EXPIRE 在单次脚本内原子完成，避免两次往返之间失败留下无 TTL 的脏 key。
     *
     * <p>KEYS[1] = fail key；ARGV[1] = TTL 秒数。</p>
     */
    private static final RedisScript<Long> INCR_FAILURE_SCRIPT = new DefaultRedisScript<>(
            "local v = redis.call('INCR', KEYS[1]) "
                    + "redis.call('EXPIRE', KEYS[1], ARGV[1]) "
                    + "return v",
            Long.class);

    /**
     * 进入 OPEN 状态原子脚本：HINCRBY 递增降级代数 + HSET 记录开启时间戳 + EXPIRE 续期，
     * 返回自增后的降级代数。三步在单次脚本内原子完成，避免中途失败导致
     * “代数已增但未记开启时间” 或 “无 TTL 脏 key” 等状态不一致。
     *
     * <p>KEYS[1] = gen key；ARGV[1] = 代数字段名(n)；ARGV[2] = 开启时间字段名(at)；
     * ARGV[3] = 开启时间戳(毫秒)；ARGV[4] = TTL 秒数。</p>
     */
    private static final RedisScript<Long> OPEN_NEXT_GENERATION_SCRIPT = new DefaultRedisScript<>(
            "local n = redis.call('HINCRBY', KEYS[1], ARGV[1], 1) "
                    + "redis.call('HSET', KEYS[1], ARGV[2], ARGV[3]) "
                    + "redis.call('EXPIRE', KEYS[1], ARGV[4]) "
                    + "return n",
            Long.class);

    private final StringRedisTemplate redisTemplate;
    private final CircuitBreakerProperties properties;

    public CircuitBreakStore(StringRedisTemplate redisTemplate,
                             CircuitBreakerProperties properties) {
        this.redisTemplate = redisTemplate;
        this.properties = properties;
    }

    // ──────────────────────────────────────────────────────────────────────────
    // 失败计数
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * 原子地增加指定 tool 的连续失败计数，并返回增加后的值。
     * 仅在非 OPEN 状态下增长；OPEN 状态下的失败由 {@link #openWithNextGeneration} 单独处理。
     */
    public long incrementFailureCount(String toolName) {
        Long val = redisTemplate.execute(
                INCR_FAILURE_SCRIPT,
                Collections.singletonList(failKey(toolName)),
                String.valueOf(properties.getStateTtlSeconds()));
        return val != null ? val : 1L;
    }

    /** 重置连续失败计数（恢复成功后调用）。 */
    public void resetFailureCount(String toolName) {
        redisTemplate.delete(failKey(toolName));
    }

    /** 获取当前连续失败计数（不修改）。 */
    public long getFailureCount(String toolName) {
        return parseLong(redisTemplate.opsForValue().get(failKey(toolName)));
    }

    // ──────────────────────────────────────────────────────────────────────────
    // OPEN 状态机（合并 openedAt + generation + cooldown 派生）
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * 进入 OPEN 状态：原子地递增降级代数、记录当前时间为开启时间戳，
     * 返回按指数退避计算出的冷却时长（秒）。
     *
     * <p>OPEN 状态判定：{@code gen:{toolName}} Hash 中存在 {@code at} 字段即为 OPEN。</p>
     */
    public long openWithNextGeneration(String toolName) {
        // 开启时间戳仍由应用侧生成，保持与 isCooldownExpired() 中 System.currentTimeMillis() 一致的时钟基准。
        Long newGen = redisTemplate.execute(
                OPEN_NEXT_GENERATION_SCRIPT,
                Collections.singletonList(genKey(toolName)),
                FIELD_GENERATION,
                FIELD_OPENED_AT,
                String.valueOf(System.currentTimeMillis()),
                String.valueOf(properties.getStateTtlSeconds()));
        long gen = newGen != null ? newGen : 1L;
        return computeCooldown(gen);
    }

    /** 判断指定 tool 是否处于 OPEN 状态。 */
    public boolean isOpen(String toolName) {
        return Boolean.TRUE.equals(redisTemplate.opsForHash().hasKey(genKey(toolName), FIELD_OPENED_AT));
    }

    /** 清除 OPEN 状态（含降级代数与开启时间戳）。 */
    public void clearOpen(String toolName) {
        redisTemplate.delete(genKey(toolName));
    }

    /** 获取当前降级代数（不修改）。0 表示从未降级过。 */
    public long getGeneration(String toolName) {
        Object val = redisTemplate.opsForHash().get(genKey(toolName), FIELD_GENERATION);
        return parseLong(val == null ? null : val.toString());
    }

    /**
     * 派生方法：当前 OPEN 周期的目标冷却时长（秒）。
     * {@code min(initial * multiplier^(generation-1), max)}。
     */
    public long getCooldownSeconds(String toolName) {
        long gen = getGeneration(toolName);
        if (gen <= 0) {
            return 0L;
        }
        return computeCooldown(gen);
    }

    /**
     * 判断指定 tool 是否已过冷却期。
     * 不在 OPEN 状态视为已过冷却（防御性处理）。
     */
    public boolean isCooldownExpired(String toolName) {
        Object atObj = redisTemplate.opsForHash().get(genKey(toolName), FIELD_OPENED_AT);
        if (atObj == null) {
            return true;
        }
        long openedAt;
        try {
            openedAt = Long.parseLong(atObj.toString());
        } catch (NumberFormatException e) {
            return true;
        }
        long cooldown = getCooldownSeconds(toolName);
        if (cooldown <= 0) {
            return true;
        }
        return System.currentTimeMillis() >= openedAt + cooldown * 1000L;
    }

    // ──────────────────────────────────────────────────────────────────────────
    // 内部工具
    // ──────────────────────────────────────────────────────────────────────────

    private long computeCooldown(long generation) {
        double raw = properties.getInitialCooldownSeconds()
                * Math.pow(properties.getBackoffMultiplier(), Math.max(0, generation - 1));
        long cooldown = (long) Math.ceil(raw);
        return Math.min(cooldown, properties.getMaxCooldownSeconds());
    }

    private static long parseLong(String val) {
        if (val == null) {
            return 0L;
        }
        try {
            return Long.parseLong(val);
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private String failKey(String toolName) {
        return properties.getRedisKeyPrefix() + "fail:" + toolName;
    }

    private String genKey(String toolName) {
        return properties.getRedisKeyPrefix() + "gen:" + toolName;
    }
}
