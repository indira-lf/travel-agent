package com.gogo.travel.agent.hook;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.AgentBase;
import io.agentscope.core.hook.Hook;
import io.agentscope.core.hook.HookEvent;
import io.agentscope.core.hook.PostCallEvent;
import io.agentscope.core.hook.PreCallEvent;
import io.agentscope.core.memory.Memory;
import io.agentscope.core.message.Msg;
import io.agentscope.core.session.Session;
import io.agentscope.core.state.SessionKey;
import io.agentscope.core.state.SimpleSessionKey;
import io.agentscope.core.state.StateModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 利用 AgentScope 框架原生 Session/Memory 机制实现多轮对话记忆持久化的 Hook。
 *
 * <p><b>工作原理：</b>
 * <ul>
 *   <li>{@link PreCallEvent}：每次 {@code agent.call()} 开始时触发。此时 Agent 内存为空（全新
 *       prototype 实例），调用 {@link StateModule#loadIfExists} 从 {@link Session} 中还原
 *       上一轮的对话历史，使 Agent 具备多轮记忆。</li>
 *   <li>{@link PostCallEvent}：{@code agent.call()} 完成后触发。此时 Agent 内存包含完整的本轮
 *       对话（历史 + 新输入 + 本轮回复），<b>仅将对话消息（memory_messages）写入 Session</b>，
 *       供下一轮加载。</li>
 * </ul>
 *
 * <p><b>持久化范围说明：</b>
 * <ul>
 *   <li><b>保留</b> {@code memory_messages}：多轮对话历史，是跨请求记忆的唯一有效载体，必须持久化。</li>
 *   <li><b>跳过</b> {@code agent_meta}（name/description/systemPrompt）：Agent 配置在 Spring Bean
 *       构建时已从 YAML/代码中确定，每次 prototype 实例化即还原，写入数据库无意义。</li>
 *   <li><b>跳过</b> {@code toolkit_activeGroups}：工具组在 Toolkit 构建时静态注册，项目中不存在
 *       运行时动态开关工具的场景，无需持久化。</li>
 * </ul>
 *
 * <p><b>加载侧兼容性说明：</b>
 * {@link PreCallEvent} 时仍使用 {@link StateModule#loadIfExists}，该方法对不存在的 key
 * 会返回 {@code Optional.empty()} 并跳过，与只保存 memory_messages 完全兼容。
 *
 * <p><b>Session Key 规则：</b>使用 {@code sessionId + ":" + agentName} 作为复合 Key，
 * 保证不同 Agent 和不同用户会话的状态相互隔离。
 *
 * <p><b>SessionId 获取优先级：</b>
 * <ol>
 *   <li>Reactor Context（由 ChatController / AgentPipelineService 通过 {@code contextWrite} 写入）</li>
 *   <li>Agent Memory 消息的 metadata（兜底，用于直接调用场景）</li>
 * </ol>
 *
 * <p><b>非 ReActAgent 的 AgentBase 兼容（QueryRewritingAgent / IntentRecognitionAgent）：</b>
 * 这类单次调用型分析 Agent 不持有 {@link Memory}，{@link StateModule#saveTo}/{@link StateModule#loadIfExists}
 * 默认是 no-op，因此改为直接操作 {@link Session} 的原始 {@code List<Msg>} 存取接口，自行维护一份历史消息：
 * PreCall 时把此前累计的历史拼接进本轮输入，PostCall 时把「拼接后的输入 + 本轮回复」写回 Session，
 * 供下一轮加载，并做长度裁剪防止无限增长。
 *
 * @author Hollis
 */
public class SessionPersistenceHook implements Hook {

    private static final Logger logger = LoggerFactory.getLogger(SessionPersistenceHook.class);

    /**
     * 无状态 AgentBase 历史消息在 Session 中的存储 key，与 ReActAgent Memory 的约定保持一致。
     */
    private static final String MEMORY_MESSAGES_KEY = "memory_messages";

    /**
     * 无状态 AgentBase 保存的历史消息条数上限，超过后从头部裁剪，避免无限增长。
     */
    private static final int MAX_HISTORY_MESSAGES = 20;

    private final Session session;

    /**
     * 暂存无状态 AgentBase 在 PreCall 阶段拼接好的「历史 + 本轮输入」消息列表，供 PostCall 阶段
     * 追加本轮回复后一并写回 Session；PostCall 消费后立即移除。Key 为 sessionKey（sessionId:agentName）。
     *
     * <p>注意：若调用被中断（InterruptedException），PostCall 不会触发，对应条目会在下一次
     * 该 sessionKey 成功完成的 PreCall 时被覆盖，不会无限增长。
     */
    private final Map<String, List<Msg>> pendingStatelessInput = new ConcurrentHashMap<>();

    public SessionPersistenceHook(Session session) {
        this.session = session;
    }

    @Override
    public int priority() {
        return 10;
    }

    @Override
    public <T extends HookEvent> Mono<T> onEvent(T event) {
        if (!(event instanceof PreCallEvent) && !(event instanceof PostCallEvent)) {
            return Mono.just(event);
        }

        return Mono.deferContextual(ctx -> {

            String sessionId = ctx.getOrDefault("sessionId", null);

            if (sessionId == null) {
                logger.debug("[SessionPersistence]  未从 ReactorContext 中提取到 sessionId，跳过对话记忆持久化");
                return Mono.just(event);
            }

            String agentName = event.getAgent() != null ? event.getAgent().getName() : null;
            if (agentName == null) {
                return Mono.just(event);
            }

            // Session Key = "sessionId:agentName"，保证不同会话、不同 Agent 状态隔离
            String sessionKey = sessionId + ":" + agentName;
            // 恢复被 ToolSuspendException 暂停的 Agent 时，必须保留内存中已有的 pending tool 状态，
            // 因此跳过从 Session 加载，避免覆盖当前执行上下文。
            boolean resuming = Boolean.TRUE.equals(ctx.getOrDefault("resuming", Boolean.FALSE));

            if (event.getAgent() instanceof ReActAgent reActAgent) {
                persistReActAgent(event, reActAgent, sessionKey, sessionId, agentName, resuming);
            } else if (event.getAgent() instanceof AgentBase) {
                // 非 ReActAgent 的普通 AgentBase（QueryRewritingAgent / IntentRecognitionAgent）：
                // 本身不持有 Memory，StateModule#saveTo/loadIfExists 默认是 no-op，
                // 改为直接操作 Session 原始 List<Msg> 接口，自行维护一份历史消息。
                persistStatelessAgent(event, sessionKey, sessionId, agentName, resuming);
            } else {
                logger.debug("[SessionPersistence] Agent {} 非 AgentBase 形态，跳过 Session 持久化: sessionId={}", agentName, sessionId);
            }

            return Mono.just(event);
        });
    }

    /**
     * ReActAgent（持有 {@link Memory}）的 Session 持久化：沿用 StateModule 原生 saveTo/loadIfExists。
     */
    private void persistReActAgent(HookEvent event, ReActAgent reActAgent, String sessionKey,
                                   String sessionId, String agentName, boolean resuming) {
        StateModule stateModule = reActAgent;

        if (event instanceof PreCallEvent) {
            if (resuming) {
                logger.debug("[SessionPersistence] 恢复暂停 Agent，跳过历史记忆加载: sessionId={}, agent={}", sessionId, agentName);
            } else {
                // 加载上一轮历史记忆（首次请求时 Session 不存在，loadIfExists 直接跳过）
                boolean loaded = stateModule.loadIfExists(session, sessionKey);
                if (loaded) {
                    logger.debug("[SessionPersistence] 已加载历史记忆: sessionId={}, agent={}", sessionId, agentName);
                }
            }
        } else {
            // 仅持久化对话消息（memory_messages），跳过 agent_meta 和 toolkit_activeGroups。
            // 原因见类注释：agent 配置由 Spring 构建时固定，工具组静态注册，二者不需要数据库存储。
            Memory memory = reActAgent.getMemory();
            memory.saveTo(session, sessionKey);
            logger.debug("[SessionPersistence] 已保存对话记忆: sessionId={}, agent={}", sessionId, agentName);
        }
    }

    /**
     * 非 ReActAgent 的 AgentBase（单次调用型分析 Agent，如 QueryRewritingAgent、IntentRecognitionAgent）
     * 的 Session 持久化。
     *
     * <p>这类 Agent 每次 {@code doCall} 都是「系统提示词 + 本轮输入」的一次性调用，自身不维护 Memory，
     * 因此这里绕开 StateModule（默认 no-op），直接读写 {@link Session} 的原始消息列表：
     * <ul>
     *   <li>{@link PreCallEvent}：读取此前累计的历史消息，与本轮输入拼接后写回
     *       {@link PreCallEvent#setInputMessages}，使 {@code doCall} 能感知历史上下文；</li>
     *   <li>{@link PostCallEvent}：取出 PreCall 阶段暂存的「历史 + 本轮输入」，追加本轮回复后
     *       整体写回 Session，供下一轮加载；超过 {@link #MAX_HISTORY_MESSAGES} 时从头部裁剪。</li>
     * </ul>
     */
    private void persistStatelessAgent(HookEvent event, String sessionKey, String sessionId,
                                       String agentName, boolean resuming) {
        SessionKey key = SimpleSessionKey.of(sessionKey);

        if (event instanceof PreCallEvent preCallEvent) {
            if (resuming) {
                logger.debug("[SessionPersistence] 恢复暂停 Agent，跳过历史记忆加载: sessionId={}, agent={}", sessionId, agentName);
                return;
            }
            List<Msg> history = session.exists(key)
                    ? session.getList(key, MEMORY_MESSAGES_KEY, Msg.class)
                    : List.<Msg>of();
            List<Msg> merged = new ArrayList<>(history);
            merged.addAll(preCallEvent.getInputMessages());
            if (!history.isEmpty()) {
                preCallEvent.setInputMessages(merged);
                logger.debug("[SessionPersistence] 已加载历史记忆（无状态 Agent）: sessionId={}, agent={}, historySize={}",
                        sessionId, agentName, history.size());
            }
            // 暂存本轮「历史 + 输入」，供 PostCall 追加回复后一并写回 Session
            pendingStatelessInput.put(sessionKey, merged);
        } else {
            PostCallEvent postCallEvent = (PostCallEvent) event;
            List<Msg> merged = pendingStatelessInput.remove(sessionKey);
            List<Msg> newHistory = merged != null ? new ArrayList<>(merged) : new ArrayList<>();
            newHistory.add(postCallEvent.getFinalMessage());
            if (newHistory.size() > MAX_HISTORY_MESSAGES) {
                newHistory = new ArrayList<>(
                        newHistory.subList(newHistory.size() - MAX_HISTORY_MESSAGES, newHistory.size()));
            }
            session.save(key, MEMORY_MESSAGES_KEY, newHistory);
            logger.debug("[SessionPersistence] 已保存对话记忆（无状态 Agent）: sessionId={}, agent={}, size={}",
                    sessionId, agentName, newHistory.size());
        }
    }

    /**
     * 对外暴露的「无状态 Agent 影子历史追加」接口。
     *
     * <p>适用于快路径（例如 L1/L2 意图命中）绕过了某个无状态 Agent 的实际 {@code call()}，
     * 却又希望下一轮该 Agent 依然能读到本轮上下文的场景：调用方可以合成一对
     * {@code USER → ASSISTANT} 消息，通过本方法写入对应 Agent 的 Session 历史，
     * 效果等价于该 Agent 真正被调用过一次。
     *
     * <p>存储契约与 {@link #persistStatelessAgent} 完全一致（同 key、同 MEMORY_MESSAGES_KEY、
     * 同 MAX_HISTORY_MESSAGES 裁剪），因此下一轮 {@link PreCallEvent} 能被无缝加载。
     *
     * @param sessionId  对话会话 ID
     * @param agentName  目标无状态 Agent 名（如 {@code QueryRewritingAgent}）
     * @param userMsg    合成的用户消息（不能为 null）
     * @param assistantMsg 合成的 Agent 回复消息（不能为 null）
     */
    public void appendStatelessHistory(String sessionId, String agentName, Msg userMsg, Msg assistantMsg) {
        if (sessionId == null || agentName == null || userMsg == null || assistantMsg == null) {
            return;
        }
        String sessionKey = sessionId + ":" + agentName;
        SessionKey key = SimpleSessionKey.of(sessionKey);
        try {
            List<Msg> history = session.exists(key)
                    ? session.getList(key, MEMORY_MESSAGES_KEY, Msg.class)
                    : List.<Msg>of();
            List<Msg> newHistory = new ArrayList<>(history);
            newHistory.add(userMsg);
            newHistory.add(assistantMsg);
            if (newHistory.size() > MAX_HISTORY_MESSAGES) {
                newHistory = new ArrayList<>(
                        newHistory.subList(newHistory.size() - MAX_HISTORY_MESSAGES, newHistory.size()));
            }
            session.save(key, MEMORY_MESSAGES_KEY, newHistory);
            logger.debug("[SessionPersistence] 已追加影子历史（无状态 Agent）: sessionId={}, agent={}, size={}",
                    sessionId, agentName, newHistory.size());
        } catch (Exception e) {
            logger.warn("[SessionPersistence] 追加影子历史失败 sessionId={}, agent={}: {}",
                    sessionId, agentName, e.getMessage());
        }
    }
}
