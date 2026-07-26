package com.travel.agent.registry;

import com.travel.agent.IntentRecognitionAgent;
import com.travel.agent.QueryRewritingAgent;
import io.agentscope.core.ReActAgent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

/**
 * Agent 注册表。
 *
 * <p>由于所有 ReActAgent Bean 都是 prototype scope，每次 {@link #getAgent} 调用都通过
 * {@link ApplicationContext#getBean} 获取全新实例，保证不同 session 之间内存完全隔离。</p>
 *
 * <p><b>集群注意：</b>不再维护运行时白名单。prototype bean 的 build 方法在首次被调用时
 * 才会执行 register，若某节点从未实例化过目标 Agent，依赖白名单会导致跨节点恢复失败。
 * 这里直接按 PascalCase agentName 转首字母小写得到 Spring beanName，从容器获取实例。</p>
 *
 * @author Hollis
 */
@Component
public class AgentRegistry {

    private static final Logger logger = LoggerFactory.getLogger(AgentRegistry.class);

    @Autowired
    private ApplicationContext context;

    /**
     * 获取一个全新的 agent 实例（prototype scope）。
     *
     * <p>agentName 命名规则：首字母小写即为 Spring bean 名，例如 "ItineraryManageAgent" → "itineraryManageAgent"。</p>
     *
     * <p><b>集群注意：</b>直接按约定 beanName 从 Spring 容器获取，由 bean definition 本身保证合法性，
     * 避免某节点未实例化过该 Agent 时白名单校验导致恢复失败。</p>
     */
    public ReActAgent getAgent(String name) {
        String beanName = Character.toLowerCase(name.charAt(0)) + name.substring(1);
        try {
            return context.getBean(beanName, ReActAgent.class);
        } catch (Exception e) {
            logger.error("[AgentRegistry] 获取 Agent bean 失败: name={}, beanName={}", name, beanName, e);
            return null;
        }
    }

    public IntentRecognitionAgent getIntentRecognizer() {
        try {
            return context.getBean("intentRecognitionAgent", IntentRecognitionAgent.class);
        } catch (Exception e) {
            logger.error("[AgentRegistry] 获取 Agent bean 失败: name={}, beanName={}", "intentRecognitionAgent", "intentRecognitionAgent", e);
            return null;
        }
    }

    public QueryRewritingAgent getQueryRewriter() {
        try {
            return context.getBean("queryRewritingAgent", QueryRewritingAgent.class);
        } catch (Exception e) {
            logger.error("[AgentRegistry] 获取 Agent bean 失败: name={}, beanName={}", "queryRewritingAgent", "queryRewritingAgent", e);
            return null;
        }
    }
}
