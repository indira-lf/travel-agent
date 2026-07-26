package com.gogo.travel.agent.context;

import com.alibaba.ttl.TransmittableThreadLocal;

/**
 * 线程本地上下文持有者，用于在 Spring prototype bean 构建时传递 {@link AgentSessionContext}。
 *
 * <p>使用 {@link TransmittableThreadLocal} 而非普通 {@code ThreadLocal}，
 * 能够自动将父线程的值传播到线程池线程（如 Reactor 的 boundedElastic），
 * 无需在每个 SubAgentProvider lambda 中手动重新 set。
 *
 * <p>使用方式：
 * <ol>
 *   <li>在调用 {@code applicationContext.getBean("xxxAgent")} 之前调用 {@link #set}</li>
 *   <li>prototype bean 的 {@code build()} 方法中调用 {@link #get} 读取上下文</li>
 *   <li>在请求完成后调用 {@link #clear} 清理，防止线程池污染</li>
 * </ol>
 *
 * @author Hollis
 */
public final class AgentSessionContextHolder {

    private static final TransmittableThreadLocal<AgentSessionContext> HOLDER =
            new TransmittableThreadLocal<>();

    private AgentSessionContextHolder() {}

    public static void set(AgentSessionContext context) {
        HOLDER.set(context);
    }

    public static AgentSessionContext get() {
        return HOLDER.get();
    }

    public static void clear() {
        HOLDER.remove();
    }
}
